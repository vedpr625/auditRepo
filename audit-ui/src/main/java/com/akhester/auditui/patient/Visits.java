package com.akhester.auditui.patient;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.people.Who;
import com.akhester.auditui.registry.RegistryClient;

/** Groups a patient's records into visits and works out who looked and which flags apply. Plain functions, no data access. */
final class Visits {

    private Visits() {}

    /** kind: outpatient | inpatient | emergency | other. ward is set only when a registry stay matched. */
    record Visit(String key, String kind, String ward, String facility, String facilityName, Instant start, Instant end, String encounter,
                 int events, int people, int systems, int ok, int refused, boolean emergency, List<PatientController.Line> lines, List<PatientController.Line> breakGlass, boolean recorded) {}

    record Looker(String name, String role, String facilityName, int count) {}

    record Flags(int breakGlass, int breakGlassDecided, int breakGlassWaiting, int refused, int noLink, int unknownLink) {}

    /**
     * Visits, newest first. Records are first grouped by encounter (or by facility and day when there is none); groups that fall inside the same registry
     * stay are then merged, so a lab record or a pharmacy record sent during the stay joins it. {@code recorded} is true for a visit that matches a stay.
     */
    static List<Visit> group(List<PatientController.Line> lines, RegistryClient.PatientInfo info, Function<String, String> facilityName, ZoneId zone) {
        // 1. a record that falls inside a registry stay (same facility, dates a day either side) belongs to that stay; the encounter it carries then
        //    also pulls in records of other systems (lab, pharmacy) that carry the same encounter but sit at another facility
        Map<PatientController.Line, RegistryClient.Stay> own = new java.util.IdentityHashMap<>();
        Map<String, RegistryClient.Stay> encStay = new LinkedHashMap<>();
        for (PatientController.Line l : lines) {
            Instant t = l.row().getRecordedInstant();
            RegistryClient.Stay s = info == null || t == null ? null : match(info, l.row().getFacility(), t, t);
            if (s != null) { own.put(l, s); if (l.row().getEncounterRef() != null) encStay.putIfAbsent(l.row().getEncounterRef(), s); }
        }
        Map<String, List<PatientController.Line>> merged = new LinkedHashMap<>();
        Map<String, RegistryClient.Stay> stayOf = new LinkedHashMap<>();
        for (PatientController.Line l : lines) {
            AuditRow r = l.row();
            RegistryClient.Stay s = own.get(l);
            if (s == null && r.getEncounterRef() != null && (r.getFacility() == null || !r.getFacility().startsWith("Device/hhims"))) s = encStay.get(r.getEncounterRef());   // lab, pharmacy and similar systems join the stay; another hospital does not
            String key = s != null ? "stay:" + s.facilityRef() + "@" + s.from()
                : r.getEncounterRef() != null ? r.getEncounterRef() + "@" + r.getFacility()
                : r.getFacility() + "@" + (r.getRecordedInstant() == null ? "?" : r.getRecordedInstant().atZone(zone).toLocalDate());
            merged.computeIfAbsent(key, k -> new ArrayList<>()).add(l);
            if (s != null) stayOf.put(key, s);
        }
        List<Visit> out = new ArrayList<>();
        for (Map.Entry<String, List<PatientController.Line>> g : merged.entrySet()) {
            List<PatientController.Line> ls = new ArrayList<>(g.getValue());
            ls.sort(Comparator.comparing((PatientController.Line l) -> l.row().getRecordedInstant(), Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
            Instant start = null, end = null;
            Map<String, Integer> fac = new LinkedHashMap<>();
            Set<String> who = new LinkedHashSet<>(), sys = new LinkedHashSet<>();
            int ok = 0, refused = 0;
            boolean emergency = false;
            List<PatientController.Line> btg = new ArrayList<>();
            String encounter = null;
            for (PatientController.Line l : ls) {
                AuditRow r = l.row();
                Instant t = r.getRecordedInstant();
                if (t != null) { if (start == null || t.isBefore(start)) start = t; if (end == null || t.isAfter(end)) end = t; }
                fac.merge(String.valueOf(r.getFacility()), 1, Integer::sum);
                who.add(r.getAgentRef() != null ? r.getAgentRef() : String.valueOf(r.getAgentName()));
                if (r.getSubmittedBy() != null) sys.add(r.getSubmittedBy());
                if (r.getOutcomeCode() == null || "0".equals(r.getOutcomeCode())) ok++; else refused++;
                if (r.isEtreatOrBtg()) emergency = true;
                if (r.isBreakGlass()) btg.add(l);
                if (encounter == null && r.getEncounterRef() != null) encounter = r.getEncounterRef();
            }
            RegistryClient.Stay stay = stayOf.get(g.getKey());
            String facility = stay != null ? stay.facilityRef() : fac.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            String kind = emergency ? "emergency" : "other";
            String ward = null;
            if (stay != null) {
                ward = stay.ward();
                String w = ward == null ? "" : ward.toLowerCase();
                kind = w.startsWith("opd") ? "outpatient" : (w.contains("casualty") || w.contains("emergency")) ? "emergency" : "inpatient";
            }
            out.add(new Visit(g.getKey(), kind, ward, facility, facilityName.apply(facility), start, end, encounter,
                ls.size(), who.size(), sys.size(), ok, refused, emergency, ls, btg, stay != null));
        }
        out.sort(Comparator.comparing(Visit::end, Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
        return out;
    }

    /** A registry stay at the same facility whose dates (a day either side) cover the visit. */
    static RegistryClient.Stay match(RegistryClient.PatientInfo info, String facility, Instant start, Instant end) {
        if (facility == null) return null;
        for (RegistryClient.Stay s : info.stays()) {
            if (!facility.equals(s.facilityRef())) continue;
            // a stay lasting under a day (an outpatient visit) gets half a day either side, a longer one a day
            Duration slack = s.to() != null && s.from() != null && Duration.between(s.from(), s.to()).compareTo(Duration.ofDays(1)) < 0 ? Duration.ofHours(12) : Duration.ofDays(1);
            Instant from = s.from() == null ? Instant.MIN : s.from().minus(slack);
            Instant to = s.to() == null ? Instant.now().plus(slack) : s.to().plus(slack);
            if (!start.isBefore(from) && !(end == null ? start : end).isAfter(to)) return s;
        }
        return null;
    }

    /** Who looked, most records first. */
    static List<Looker> lookers(List<PatientController.Line> lines, Function<String, String> facilityName) {
        record Acc(String name, String role, String fac, int[] n) {}
        Map<String, Acc> m = new LinkedHashMap<>();
        for (PatientController.Line l : lines) {
            AuditRow r = l.row();
            String k = r.getAgentRef() != null ? r.getAgentRef() : "name:" + r.getAgentName();
            m.computeIfAbsent(k, x -> new Acc(r.getAgentName(), Who.role(r.getAgentRef(), r.getAgentName()), facilityName.apply(r.getFacility()), new int[1])).n()[0]++;
        }
        return m.values().stream().map(a -> new Looker(a.name(), a.role(), a.fac(), a.n()[0])).sorted(Comparator.comparingInt(Looker::count).reversed().thenComparing(Looker::name)).toList();
    }

    static Flags flags(List<PatientController.Line> lines) {
        int btg = 0, decided = 0, waiting = 0, refused = 0, none = 0, unknown = 0;
        for (PatientController.Line l : lines) {
            AuditRow r = l.row();
            if (r.isBreakGlass()) { btg++; if ("reviewed".equals(r.getReviewStatus()) || "flagged".equals(r.getReviewStatus())) decided++; else waiting++; }
            if (r.getOutcomeCode() != null && !"0".equals(r.getOutcomeCode())) refused++;
            if ("none".equals(l.link())) none++;
            if ("unknown".equals(l.link())) unknown++;
        }
        return new Flags(btg, decided, waiting, refused, none, unknown);
    }
}

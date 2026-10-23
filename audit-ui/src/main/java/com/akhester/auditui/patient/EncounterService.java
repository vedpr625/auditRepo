package com.akhester.auditui.patient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.akhester.auditui.consent.ConsentClient;
import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.registry.Registry;
import com.akhester.auditui.registry.RegistryClient;

/**
 * Builds what the Encounters tab and the Encounter page show: which registry encounter an audit record belongs to, the header facts of an encounter
 * (class, period, facility, ward, attending, provider), and counts. Nothing clinical is read: only the registry Encounter header.
 * The order of work stays with the controllers: audit records first, then the consent check, and only on a permit does anything here read the registry.
 */
@Service
public class EncounterService {

    private final RegistryClient registry;

    public EncounterService(RegistryClient registry) { this.registry = registry; }

    /** One line of the Encounters tab. */
    public record EncRow(Registry.Encounter e, int access, int people, int btg, String link) {}

    /** The encounter header as the registry gave it: header is the section status (null when the consent does not cover encounters), enc the encounter or null. */
    public record Header(Registry.Section<Registry.Encounter> header, Registry.Encounter enc) {}

    /** How many records of one kind were opened: action letter and sending system pick the words (as in the access log), count is how many. Values are never shown. */
    public record KindCount(String action, String system, int count) {}

    /** The rows of the Encounters tab, newest first, each with the number of audit records, people and break-glass accesses of that visit. */
    public static List<EncRow> rows(String patientId, List<Registry.Encounter> encs, List<PatientController.Line> lines) {
        List<EncRow> rows = new ArrayList<>();
        for (Registry.Encounter e : encs.stream().sorted(Comparator.comparing(Registry.Encounter::start, Comparator.nullsFirst(Comparator.naturalOrder())).reversed()).toList()) {
            List<PatientController.Line> ls = linesOf(e, encs, lines);
            rows.add(new EncRow(e, ls.size(), (int) ls.stream().map(l -> who(l.row())).distinct().count(), (int) ls.stream().filter(l -> l.row().isBreakGlass()).count(),
                "/encounter/" + e.id() + "?patient=" + patientId));
        }
        return rows;
    }

    private static String who(AuditRow r) { return r.getAgentRef() != null ? r.getAgentRef() : String.valueOf(r.getAgentName()); }

    /** Counts per kind of record opened, from the action and sending system of the audit records. Most frequent first. */
    public static List<KindCount> kinds(List<PatientController.Line> lines) {
        Map<String, Integer> n = new LinkedHashMap<>();
        Map<String, String[]> key = new LinkedHashMap<>();
        for (PatientController.Line l : lines) {
            String a = l.row().getAction() == null ? "R" : l.row().getAction();
            String s = l.row().getSubmittedBy() == null ? "" : l.row().getSubmittedBy();
            String k = a + "|" + s;
            n.merge(k, 1, Integer::sum);
            key.putIfAbsent(k, new String[]{a, s});
        }
        return n.entrySet().stream().map(en -> new KindCount(key.get(en.getKey())[0], key.get(en.getKey())[1], en.getValue()))
            .sorted(Comparator.comparingInt(KindCount::count).reversed()).toList();
    }

    /**
     * The registry encounter with this id (or, when the registry does not know that number, the patient's stay at the same facility and time as the audit records).
     * patientId must already have passed the consent check; only the encounter header is read.
     */
    public Header header(String patientId, String encounterId, ConsentClient.Decision consent, List<PatientController.Line> lines, Registry.Section<Registry.Encounter> patientEncounters) {
        if (!consent.allows("encounters")) return new Header(null, null);
        Registry.Section<Registry.Encounter> all = patientEncounters != null ? patientEncounters : registry.encounters(patientId);
        Registry.Section<Registry.Encounter> hdr = all;
        Registry.Encounter e = null;
        if (all.isOk()) {
            e = all.items().stream().filter(x -> encounterId.equals(x.id())).findFirst().orElse(null);
            if (e == null) e = byTime(all.items(), lines);
            if (e == null) {
                Registry.Section<Registry.Encounter> one = registry.encounter(encounterId);   // a registry that lists by another rule may still have it
                if (one.isOk() && !one.items().isEmpty() && (one.items().get(0).patientId() == null || patientId.equals(one.items().get(0).patientId()))) e = one.items().get(0);
                else hdr = Registry.Section.ok(List.of());
            }
        }
        return new Header(hdr, e);
    }

    /** Lines of this encounter: those that carry its id, and those from the same facility during its time (the way the visits are grouped). Only one registry encounter claims a line. */
    static List<PatientController.Line> linesOf(Registry.Encounter e, List<Registry.Encounter> all, List<PatientController.Line> lines) {
        List<PatientController.Line> out = new ArrayList<>();
        for (PatientController.Line l : lines) {
            AuditRow r = l.row();
            if (r.getEncounterRef() != null && r.getEncounterRef().equals("Encounter/" + e.id())) { out.add(l); continue; }
            if (r.getEncounterRef() != null && all.stream().anyMatch(x -> r.getEncounterRef().equals("Encounter/" + x.id()))) continue;   // it names another registry encounter
            Instant t = r.getRecordedInstant();
            if (t == null || r.getFacility() == null || e.facilityRef() == null || !r.getFacility().equals(e.facilityRef())) continue;
            if (RegistryClient.stay(e) == null) continue;
            RegistryClient.Stay hit = Visits.match(new RegistryClient.PatientInfo(null, null, null, null, null, all.stream().map(RegistryClient::stay).filter(x -> x != null).toList()), r.getFacility(), t, t);
            if (hit != null && e.id() != null && e.id().equals(hit.id())) out.add(l);
        }
        return out;
    }

    /** The patient's registry encounter at the same facility whose time covers the audit records (a day either side). */
    static Registry.Encounter byTime(List<Registry.Encounter> encs, List<PatientController.Line> lines) {
        Instant first = null, last = null;
        String fac = null;
        for (PatientController.Line l : lines) {
            Instant t = l.row().getRecordedInstant();
            if (t == null) continue;
            if (first == null || t.isBefore(first)) first = t;
            if (last == null || t.isAfter(last)) last = t;
            if (fac == null) fac = l.row().getFacility();
        }
        if (first == null) return null;
        RegistryClient.Stay hit = Visits.match(new RegistryClient.PatientInfo(null, null, null, null, null, encs.stream().map(RegistryClient::stay).filter(x -> x != null).toList()), fac, first, last);
        return hit == null ? null : encs.stream().filter(x -> x.id() != null && x.id().equals(hit.id())).findFirst().orElse(null);
    }

    /** Care-link counts over audit lines (ok, none, unknown). Lines the rule does not apply to are not counted. */
    public static int[] careLinkCounts(List<PatientController.Line> lines) {
        int ok = 0, none = 0, unknown = 0;
        for (PatientController.Line l : lines) {
            switch (l.link()) { case "ok" -> ok++; case "none" -> none++; case "unknown" -> unknown++; default -> { } }
        }
        return new int[]{ok, none, unknown};
    }

}

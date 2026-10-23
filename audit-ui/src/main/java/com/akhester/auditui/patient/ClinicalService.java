package com.akhester.auditui.patient;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.akhester.auditui.consent.ConsentClient;
import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.registry.Clinical;
import com.akhester.auditui.registry.RegistryClient;

/**
 * Builds what the Clinical 360 and Encounter screens show: reads only the registry sections the consent covers, then arranges them (latest value and trend
 * per result, active against past conditions and medicines, one timeline) and ties each item to the audit records of the same encounter or day.
 * The order of work stays with the controllers: audit records first, then the consent check, and only on a permit does anything here run.
 */
@Service
public class ClinicalService {

    private final RegistryClient registry;

    public ClinicalService(RegistryClient registry) { this.registry = registry; }

    /** A line of a sparkline: points in a 84 by 24 box, the last point, and the reference band (top and height) when the range is known. */
    public record Spark(String points, double lastX, double lastY, Double bandTop, Double bandHeight) {}

    /** One lab test or vital sign: the latest value, the one before, and the trend. */
    public record Result(String key, String text, String unit, String latest, String previous, String flag, String range, Instant at, int count, boolean vital, String trend, Spark spark) {}

    /** kind: encounter | procedure | result | btg. link goes to the encounter page (linkKind "encounter") or to the access log of that day ("day"). */
    public record Item(String kind, Instant at, String title, String sub, String flag, String encounterKind, int access, int btg, String link, String linkKind, String reviewState,
                       int labs, int vitals, int outside) {
        public Item(String kind, Instant at, String title, String sub, String flag, String encounterKind, int access, int btg, String link, String linkKind, String reviewState) {
            this(kind, at, title, sub, flag, encounterKind, access, btg, link, linkKind, reviewState, 0, 0, 0);
        }
        Item withResults(int labs, int vitals, int outside) { return new Item(kind, at, title, sub, flag, encounterKind, access, btg, link, linkKind, reviewState, labs, vitals, outside); }
    }

    public record EncRow(Clinical.Encounter e, int access, int people, int btg, String link) {}

    /** Null sections were not read: the consent does not cover them. */
    public record View(Clinical.Section<Clinical.Allergy> allergies, Clinical.Section<Clinical.Condition> conditions, Clinical.Section<Clinical.Medication> medications,
                       Clinical.Section<Clinical.Observation> observations, Clinical.Section<Clinical.Procedure> procedures, Clinical.Section<Clinical.Encounter> encounters,
                       List<Clinical.Allergy> allergyList, boolean noKnownAllergy, List<Clinical.Condition> activeConditions, List<Clinical.Condition> pastConditions,
                       List<Clinical.Medication> currentMeds, List<Clinical.Medication> pastMeds, List<Result> vitals, List<Result> labs, int abnormalLabs,
                       List<EncRow> encounterRows, List<Item> timeline, Clinical.Encounter current) {}

    // ---------- Clinical 360 ----------

    /** Reads the sections the consent covers. encounters may already have been read by the caller (null: read here when the consent covers it). */
    public View load(String patientId, ConsentClient.Decision consent, Clinical.Section<Clinical.Encounter> encounters, List<PatientController.Line> lines, ZoneId zone, boolean wantClinical) {
        Clinical.Section<Clinical.Encounter> enc = encounters != null ? encounters : consent.allows("encounters") ? registry.encounters(patientId) : null;
        Clinical.Section<Clinical.Condition> cond = wantClinical && consent.allows("conditions") ? registry.conditions(patientId, null) : null;
        Clinical.Section<Clinical.Medication> med = wantClinical && consent.allows("medications") ? registry.medications(patientId, null) : null;
        Clinical.Section<Clinical.Observation> obs = wantClinical && consent.allows("labs") ? registry.observations(patientId, null) : null;
        Clinical.Section<Clinical.Procedure> proc = wantClinical && consent.allows("procedures") ? registry.procedures(patientId, null) : null;
        Clinical.Section<Clinical.Allergy> alg = wantClinical && consent.allows("allergies") ? registry.allergies(patientId) : null;
        return arrange(patientId, enc, cond, med, obs, proc, alg, lines, zone);
    }

    /** Pure arrangement (no registry calls): used by load and by tests. */
    View arrange(String patientId, Clinical.Section<Clinical.Encounter> enc, Clinical.Section<Clinical.Condition> cond, Clinical.Section<Clinical.Medication> med,
                 Clinical.Section<Clinical.Observation> obs, Clinical.Section<Clinical.Procedure> proc, Clinical.Section<Clinical.Allergy> alg, List<PatientController.Line> lines, ZoneId zone) {
        List<Clinical.Encounter> encs = enc != null && enc.isOk() ? enc.items() : List.of();
        // which audit lines belong to which registry encounter
        Map<String, List<PatientController.Line>> byEnc = new LinkedHashMap<>();
        Map<String, String> linkId = new LinkedHashMap<>();
        for (Clinical.Encounter e : encs) {
            List<PatientController.Line> ls = linesOf(e, encs, lines);
            byEnc.put(e.id(), ls);
            linkId.put(e.id(), e.id());   // the encounter page resolves the registry's own number
        }
        List<EncRow> rows = new ArrayList<>();
        for (Clinical.Encounter e : encs.stream().sorted(Comparator.comparing(Clinical.Encounter::start, Comparator.nullsFirst(Comparator.naturalOrder())).reversed()).toList()) {
            List<PatientController.Line> ls = byEnc.get(e.id());
            rows.add(new EncRow(e, ls.size(), (int) ls.stream().map(l -> who(l.row())).distinct().count(), (int) ls.stream().filter(l -> l.row().isBreakGlass()).count(),
                "/encounter/" + linkId.get(e.id()) + "?patient=" + patientId));
        }
        Clinical.Encounter current = encs.stream().filter(Clinical.Encounter::current).max(Comparator.comparing(Clinical.Encounter::start)).orElse(null);

        List<Clinical.Allergy> algs = alg != null && alg.isOk() ? alg.items() : List.of();
        boolean none = !algs.isEmpty() && algs.stream().allMatch(Clinical.Allergy::noKnown);
        List<Clinical.Allergy> real = algs.stream().filter(a -> !a.noKnown()).sorted(Comparator.comparing((Clinical.Allergy a) -> !a.high())).toList();

        List<Clinical.Condition> conds = cond != null && cond.isOk() ? cond.items() : List.of();
        List<Clinical.Condition> active = conds.stream().filter(Clinical.Condition::active).sorted(byTimeDesc(Clinical.Condition::onset)).toList();
        List<Clinical.Condition> past = conds.stream().filter(c -> !c.active()).sorted(byTimeDesc(Clinical.Condition::onset)).toList();
        List<Clinical.Medication> meds = med != null && med.isOk() ? med.items() : List.of();
        List<Clinical.Medication> cur = meds.stream().filter(Clinical.Medication::active).sorted(byTimeDesc(Clinical.Medication::authoredOn)).toList();
        List<Clinical.Medication> old = meds.stream().filter(m -> !m.active()).sorted(byTimeDesc(Clinical.Medication::authoredOn)).toList();

        List<Result> results = results(obs != null && obs.isOk() ? obs.items() : List.of());
        List<Result> vitals = results.stream().filter(Result::vital).sorted(Comparator.comparingInt(r -> vitalOrder(r.key()))).toList();
        List<Result> labs = results.stream().filter(r -> !r.vital()).sorted(Comparator.comparing((Result r) -> !abnormal(r.flag())).thenComparing(Result::at, Comparator.nullsLast(Comparator.reverseOrder()))).toList();
        int abnormalLabs = (int) labs.stream().filter(r -> abnormal(r.flag())).count();

        List<Item> timeline = timeline(patientId, encs, byEnc, linkId, proc != null && proc.isOk() ? proc.items() : List.of(), obs != null && obs.isOk() ? obs.items() : List.of(), lines, zone);
        return new View(alg, cond, med, obs, proc, enc, real, none, active, past, cur, old, vitals, labs, abnormalLabs, rows, timeline, current);
    }

    static boolean abnormal(String f) { return f != null && !"N".equals(f); }

    private static <T> Comparator<T> byTimeDesc(java.util.function.Function<T, Instant> f) { return Comparator.comparing(f, Comparator.nullsLast(Comparator.reverseOrder())); }

    private static final List<String> VITAL_ORDER = List.of("85354-9", "8867-4", "9279-1", "8310-5", "59408-5", "2708-6", "29463-7");

    private static int vitalOrder(String key) { int i = VITAL_ORDER.indexOf(key); return i < 0 ? 99 : i; }

    private static String who(AuditRow r) { return r.getAgentRef() != null ? r.getAgentRef() : String.valueOf(r.getAgentName()); }

    // ---------- results and sparklines ----------

    /** Latest value, previous value and trend for each test, from all observations. */
    static List<Result> results(List<Clinical.Observation> all) {
        Map<String, List<Clinical.Observation>> by = new LinkedHashMap<>();
        for (Clinical.Observation o : all) by.computeIfAbsent(o.code() != null ? o.code() : o.text(), k -> new ArrayList<>()).add(o);
        List<Result> out = new ArrayList<>();
        for (Map.Entry<String, List<Clinical.Observation>> g : by.entrySet()) {
            List<Clinical.Observation> ls = new ArrayList<>(g.getValue());
            ls.sort(Comparator.comparing(Clinical.Observation::at, Comparator.nullsFirst(Comparator.naturalOrder())));
            Clinical.Observation last = ls.get(ls.size() - 1);
            Clinical.Observation prev = ls.size() > 1 ? ls.get(ls.size() - 2) : null;
            List<Double> vals = ls.stream().filter(o -> o.value() != null).map(Clinical.Observation::value).toList();
            List<Instant> times = ls.stream().filter(o -> o.value() != null).map(Clinical.Observation::at).toList();
            out.add(new Result(g.getKey(), last.text(), last.unitLabel(), last.display(), prev == null ? null : prev.display(), last.flag(), last.range(), last.at(), ls.size(), last.vital(),
                trend(vals), spark(vals, times, last.low(), last.high())));
        }
        return out;
    }

    /** rising | falling | steady | null (fewer than two numbers). A change under 3 percent of the earlier value counts as steady. */
    static String trend(List<Double> v) {
        if (v == null || v.size() < 2) return null;
        double a = v.get(v.size() - 2), b = v.get(v.size() - 1);
        double tol = Math.max(Math.abs(a) * 0.03, 1e-9);
        return b - a > tol ? "rising" : a - b > tol ? "falling" : "steady";
    }

    static final double W = 84, H = 24, PAD = 3;

    /** Sparkline points for two or more numbers (null otherwise). x follows the time when the times differ, else the order; y includes the reference range so the band shows. */
    static Spark spark(List<Double> vals, List<Instant> times, Double low, Double high) {
        if (vals == null || vals.size() < 2) return null;
        double min = vals.stream().min(Double::compare).get(), max = vals.stream().max(Double::compare).get();
        if (low != null) min = Math.min(min, low);
        if (high != null) max = Math.max(max, high);
        if (max - min < 1e-9) { min -= 1; max += 1; }
        boolean timed = times != null && times.size() == vals.size() && times.stream().allMatch(t -> t != null)
            && Duration.between(times.get(0), times.get(times.size() - 1)).toSeconds() > 0;
        long t0 = timed ? times.get(0).getEpochSecond() : 0, span = timed ? Math.max(1, times.get(times.size() - 1).getEpochSecond() - t0) : 0;
        StringBuilder sb = new StringBuilder();
        double lx = 0, ly = 0;
        for (int i = 0; i < vals.size(); i++) {
            double x = PAD + (W - 2 * PAD) * (timed ? (times.get(i).getEpochSecond() - t0) / (double) span : i / (double) (vals.size() - 1));
            double y = PAD + (H - 2 * PAD) * (1 - (vals.get(i) - min) / (max - min));
            if (i > 0) sb.append(' ');
            sb.append(r1(x)).append(',').append(r1(y));
            lx = x; ly = y;
        }
        Double bt = null, bh = null;
        if (low != null && high != null) {
            double yTop = PAD + (H - 2 * PAD) * (1 - (high - min) / (max - min)), yBot = PAD + (H - 2 * PAD) * (1 - (low - min) / (max - min));
            bt = r1(yTop); bh = r1(Math.max(1, yBot - yTop));
        }
        return new Spark(sb.toString(), r1(lx), r1(ly), bt, bh);
    }

    private static double r1(double d) { return Math.round(d * 10) / 10.0; }

    // ---------- tying the registry to the audit records ----------

    /** Lines of this encounter: those that carry its id, and those from the same facility during its time (the way the visits are grouped). Only one registry encounter claims a line. */
    static List<PatientController.Line> linesOf(Clinical.Encounter e, List<Clinical.Encounter> all, List<PatientController.Line> lines) {
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

    /** The encounter number the audit records use for these lines (so the encounter page finds them), else the registry's own id. */
    static String auditEncounterId(List<PatientController.Line> ls, String fallback) {
        Map<String, Integer> n = new LinkedHashMap<>();
        for (PatientController.Line l : ls) if (l.row().getEncounterRef() != null && l.row().getEncounterRef().startsWith("Encounter/")) n.merge(l.row().getEncounterRef().substring(10), 1, Integer::sum);
        return n.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(fallback);
    }

    // ---------- timeline ----------

    List<Item> timeline(String patientId, List<Clinical.Encounter> encs, Map<String, List<PatientController.Line>> byEnc, Map<String, String> linkId,
                        List<Clinical.Procedure> procs, List<Clinical.Observation> obs, List<PatientController.Line> lines, ZoneId zone) {
        List<Item> out = new ArrayList<>();
        Map<String, Clinical.Encounter> encById = new LinkedHashMap<>();
        for (Clinical.Encounter e : encs) {
            encById.put(e.id(), e);
            List<PatientController.Line> ls = byEnc.get(e.id());
            String title = (e.type() != null ? e.type() : e.classDisplay() != null ? e.classDisplay() : "Encounter");
            out.add(new Item("encounter", e.start(), title, join(" · ", e.ward(), e.facilityName(), e.reason()), null, e.kind(), ls.size(),
                (int) ls.stream().filter(l -> l.row().isBreakGlass()).count(), "/encounter/" + linkId.get(e.id()) + "?patient=" + patientId, "encounter", null));
        }
        for (Clinical.Procedure p : procs) {
            Clinical.Encounter e = p.encounterId() == null ? null : encById.get(p.encounterId());
            out.add(annotate("procedure", p.at(), p.text(), join(" · ", p.performer(), e == null ? null : e.ward()), null, e, patientId, byEnc, linkId, lines, zone));
        }
        // results: one entry per encounter (or per day when there is none), counting how many were outside the range
        Map<String, List<Clinical.Observation>> groups = new LinkedHashMap<>();
        for (Clinical.Observation o : obs) {
            if (o.at() == null) continue;
            String k = o.encounterId() != null && encById.containsKey(o.encounterId()) ? "e:" + o.encounterId() : "d:" + o.at().atZone(zone).toLocalDate();
            groups.computeIfAbsent(k, x -> new ArrayList<>()).add(o);
        }
        for (Map.Entry<String, List<Clinical.Observation>> g : groups.entrySet()) {
            List<Clinical.Observation> os = g.getValue();
            Instant at = os.stream().map(Clinical.Observation::at).max(Comparator.naturalOrder()).get();
            long labs = os.stream().filter(Clinical.Observation::lab).count(), vitals = os.size() - labs;
            long abn = os.stream().filter(Clinical.Observation::abnormal).count();
            String names = os.stream().filter(Clinical.Observation::abnormal).map(Clinical.Observation::text).distinct().limit(3).reduce((a, b) -> a + ", " + b).orElse(null);
            Clinical.Encounter e = g.getKey().startsWith("e:") ? encById.get(g.getKey().substring(2)) : null;
            out.add(annotate("result", at, null, names, abn == 0 ? "N" : "A", e, patientId, byEnc, linkId, lines, zone).withResults((int) labs, (int) vitals, (int) abn));
        }
        for (PatientController.Line l : lines) {
            AuditRow r = l.row();
            if (!r.isBreakGlass() || r.getRecordedInstant() == null) continue;
            String enc = r.getEncounterRef() != null && r.getEncounterRef().startsWith("Encounter/") ? r.getEncounterRef().substring(10) : null;
            String day = r.getRecordedInstant().atZone(zone).toLocalDate().toString();
            out.add(new Item("btg", r.getRecordedInstant(), String.valueOf(r.getAgentName()), r.getBreakGlassReason(), null, null, 1, 1,
                enc != null ? "/encounter/" + enc + "?patient=" + patientId : "/patient/" + patientId + "?tab=access&day=" + day, enc != null ? "encounter" : "day",
                r.getReviewStatus() == null ? "pending" : r.getReviewStatus()));
        }
        out.sort(Comparator.comparing(Item::at, Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /** Item tied to its encounter's access (when it has an encounter) or to the access of its calendar day. */
    private Item annotate(String kind, Instant at, String title, String sub, String flag, Clinical.Encounter e, String patientId, Map<String, List<PatientController.Line>> byEnc,
                          Map<String, String> linkId, List<PatientController.Line> lines, ZoneId zone) {
        if (e != null) {
            List<PatientController.Line> ls = byEnc.getOrDefault(e.id(), List.of());
            return new Item(kind, at, title, sub, flag, null, ls.size(), (int) ls.stream().filter(l -> l.row().isBreakGlass()).count(),
                "/encounter/" + linkId.get(e.id()) + "?patient=" + patientId, "encounter", null);
        }
        if (at == null) return new Item(kind, null, title, sub, flag, null, 0, 0, null, null, null);
        java.time.LocalDate day = at.atZone(zone).toLocalDate();
        List<PatientController.Line> ls = lines.stream().filter(l -> l.row().getRecordedInstant() != null && l.row().getRecordedInstant().atZone(zone).toLocalDate().equals(day)).toList();
        return new Item(kind, at, title, sub, flag, null, ls.size(), (int) ls.stream().filter(l -> l.row().isBreakGlass()).count(),
            "/patient/" + patientId + "?tab=access&day=" + day, "day", null);
    }

    private static String join(String sep, String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) if (p != null && !p.isBlank()) { if (b.length() > 0) b.append(sep); b.append(p); }
        return b.length() == 0 ? null : b.toString();
    }

    // ---------- Encounter page ----------

    /** What the registry holds about one encounter. Null sections: not covered by the consent. */
    public record EncounterView(Clinical.Section<Clinical.Encounter> header, Clinical.Encounter enc, Clinical.Section<Clinical.Condition> conditions,
                                Clinical.Section<Clinical.Procedure> procedures, Clinical.Section<Clinical.Medication> medications, List<Clinical.Observation> vitals,
                                List<Clinical.Observation> labs, Clinical.Section<Clinical.Observation> observations, List<Clinical.Diagnosis> diagnoses) {
        /** Laboratory results first, then vital signs. */
        public List<Clinical.Observation> all() { List<Clinical.Observation> l = new ArrayList<>(labs); l.addAll(vitals); return l; }
    }

    /**
     * The encounter with this id (or, when the registry does not know that number, the patient's stay at the same facility and time as the audit records)
     * and its recorded content for the sections the consent covers. patientId must already have passed the consent check.
     */
    public EncounterView loadEncounter(String patientId, String encounterId, ConsentClient.Decision consent, List<PatientController.Line> lines, Clinical.Section<Clinical.Encounter> patientEncounters) {
        Clinical.Section<Clinical.Encounter> all = patientEncounters;
        Clinical.Encounter e = null;
        Clinical.Section<Clinical.Encounter> hdr = all;
        if (consent.allows("encounters")) {
            if (all == null) all = registry.encounters(patientId);
            hdr = all;
            if (all.isOk()) {
                e = all.items().stream().filter(x -> encounterId.equals(x.id())).findFirst().orElse(null);
                if (e == null) e = byTime(all.items(), lines);
            }
            if (e == null && !all.isOk()) { /* keep the section status */ }
            else if (e == null) {
                Clinical.Section<Clinical.Encounter> one = registry.encounter(encounterId);   // a registry that lists by another rule may still have it
                if (one.isOk() && !one.items().isEmpty() && (one.items().get(0).patientId() == null || patientId.equals(one.items().get(0).patientId()))) e = one.items().get(0);
                else hdr = Clinical.Section.ok(List.of());
            }
        } else {
            hdr = null;
        }
        String regId = e != null && e.id() != null ? e.id() : encounterId;
        Clinical.Section<Clinical.Condition> cond = consent.allows("conditions") ? registry.conditions(patientId, regId) : null;
        Clinical.Section<Clinical.Procedure> proc = consent.allows("procedures") ? registry.procedures(patientId, regId) : null;
        Clinical.Section<Clinical.Medication> med = consent.allows("medications") ? registry.medications(patientId, regId) : null;
        Clinical.Section<Clinical.Observation> obs = consent.allows("labs") ? registry.observations(patientId, regId) : null;
        List<Clinical.Observation> vitals = obs != null && obs.isOk() ? obs.items().stream().filter(o -> !o.lab()).sorted(Comparator.comparing(Clinical.Observation::at, Comparator.nullsLast(Comparator.naturalOrder()))).toList() : List.of();
        List<Clinical.Observation> labs = obs != null && obs.isOk() ? obs.items().stream().filter(Clinical.Observation::lab).sorted(Comparator.comparing(Clinical.Observation::at, Comparator.nullsLast(Comparator.naturalOrder()))).toList() : List.of();
        return new EncounterView(hdr, e, cond, proc, med, vitals, labs, obs, e == null ? List.of() : e.diagnoses());
    }

    /** The patient's registry encounter at the same facility whose time covers the audit records (a day either side). */
    static Clinical.Encounter byTime(List<Clinical.Encounter> encs, List<PatientController.Line> lines) {
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

    static final Set<String> TREATMENT = Set.of("TREAT", "ETREAT", "BTG");

    /** Care-link counts over audit lines (ok, none, unknown). Lines the rule does not apply to are not counted. */
    public static int[] careLinkCounts(List<PatientController.Line> lines) {
        int ok = 0, none = 0, unknown = 0;
        for (PatientController.Line l : lines) {
            switch (l.link()) { case "ok" -> ok++; case "none" -> none++; case "unknown" -> unknown++; default -> { } }
        }
        return new int[]{ok, none, unknown};
    }

    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}

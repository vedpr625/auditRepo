package com.akhester.auditui.demo;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DEMONSTRATION ONLY. Made-up Encounter resources for the stand-in registry, as plain FHIR R4 JSON: class, type, period, facility, ward, attending, provider.
 * Nothing about health is held here (no reasons, diagnoses, outcomes), because the audit console never shows it.
 * Dates are relative to start-up, like the demo audit records, so the stays line up.
 * P-10407 (Kamal Mendis) uses the same encounter ids as the demo audit records; about ten other patients have one visit.
 */
final class DemoEncounterData {

    static final String COL = "Device/hhims-colombo-01", KAN = "Device/hhims-kandy-01", GAL = "Device/hhims-galle-01", JAF = "Device/hhims-jaffna-01";
    static final Map<String, String> FACILITY = Map.of(COL, "National Hospital Colombo (demo)", KAN, "Teaching Hospital Kandy (demo)",
        GAL, "Teaching Hospital Galle (demo)", JAF, "Teaching Hospital Jaffna (demo)");

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final Map<String, Pt> patients = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> encountersById = new LinkedHashMap<>();

    DemoEncounterData() { build(); }

    // ---------- what the registry serves ----------

    /** Encounters of a patient, or null when this class has none (the registry then lists plain stays). */
    List<Map<String, Object>> encounters(String pid) { Pt p = patients.get(pid); return p == null || p.enc.isEmpty() ? null : p.enc; }

    Map<String, Object> encounter(String id) { return encountersById.get(id); }

    // ---------- builders ----------

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> o = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) if (kv[i + 1] != null) o.put((String) kv[i], kv[i + 1]);
        return o;
    }

    private static Map<String, Object> coding(String system, String code, String display) { return m("system", system, "code", code, "display", display); }

    private static Map<String, Object> cc(String text, String system, String code) {
        return code == null ? m("text", text) : m("coding", List.of(coding(system, code, text)), "text", text);
    }

    private static Map<String, Object> cc(String text) { return m("text", text); }

    private static Map<String, Object> ref(String reference, String display) { return m("reference", reference, "display", display); }

    private static String ts(Instant i) { return i.truncatedTo(ChronoUnit.SECONDS).toString(); }

    private Instant ago(long days) { return now.minus(days, ChronoUnit.DAYS); }

    /** One patient's encounters. */
    private final class Pt {
        final String id;
        final List<Map<String, Object>> enc = new ArrayList<>();

        Pt(String id) { this.id = id; patients.put(id, this); }

        private Map<String, Object> subject() { return m("reference", "Patient/" + id); }

        /** An encounter. cls: AMB | IMP | EMER. attending: {reference id, display}; others: more pairs. end null means still in progress. */
        Pt enc(String eid, String cls, String type, Instant start, Instant end, String fac, String ward, String[] attending, String[][] others) {
            String clsDisplay = switch (cls) { case "AMB" -> "ambulatory"; case "IMP" -> "inpatient encounter"; default -> "emergency"; };
            List<Object> parts = new ArrayList<>();
            parts.add(m("type", List.of(cc("attender", "http://terminology.hl7.org/CodeSystem/v3-ParticipationType", "ATND")), "individual", ref("Practitioner/" + attending[0], attending[1])));
            for (String[] o : others) parts.add(m("type", List.of(cc("consultant", "http://terminology.hl7.org/CodeSystem/v3-ParticipationType", "CON")), "individual", ref("Practitioner/" + o[0], o[1])));
            Map<String, Object> period = m("start", ts(start), "end", end == null ? null : ts(end));
            Map<String, Object> e = m("resourceType", "Encounter", "id", eid, "status", end == null ? "in-progress" : "finished",
                "class", coding("http://terminology.hl7.org/CodeSystem/v3-ActCode", cls, clsDisplay),
                "type", List.of(cc(type)), "serviceType", cc(ward), "subject", subject(),
                "participant", parts, "period", period,
                "location", List.of(
                    m("location", ref(fac, FACILITY.get(fac)), "status", "completed"),
                    m("location", m("reference", "Location/" + fac.replace("Device/", "") + "-" + ward.toLowerCase().replaceAll("[^a-z0-9]+", "-"), "display", ward),
                        "physicalType", cc("Ward", "http://terminology.hl7.org/CodeSystem/location-physical-type", "wa"), "status", "completed")),
                "serviceProvider", ref("Organization/" + fac.replace("Device/", ""), FACILITY.get(fac)));
            enc.add(e);
            encountersById.put(eid, e);
            return this;
        }
    }

    // ---------- the data ----------

    private static final String[] PERERA = {"dr-n-perera", "Dr. N. Perera"}, RANASINGHE = {"dr-ranasinghe", "Dr. H. Ranasinghe"}, SILVA = {"dr-a-silva", "Dr. A. Silva"},
        FERNANDO = {"dr-r-fernando", "Dr. R. Fernando"};

    private void build() {
        kamal();
        // one visit for other patients (stay times agree with DemoRegistry's stays)
        new Pt("P-00101").enc("E-71011", "IMP", "Inpatient admission", ago(20), null, COL, "Ward 12 Medical", new String[]{"dr-m-gunasekara", "Dr. M. Gunasekara"}, new String[][]{});
        new Pt("P-00102").enc("E-71021", "IMP", "Inpatient admission", ago(9), ago(6), COL, "Ward 3 Surgical", new String[]{"dr-k-jayawardena", "Dr. K. Jayawardena"}, new String[][]{});
        new Pt("P-00105").enc("E-71051", "IMP", "Inpatient admission", ago(30), ago(25), COL, "Ward 5 Cardiology", new String[]{"dr-s-wickrama", "Dr. S. Wickramasinghe"}, new String[][]{});
        new Pt("P-00107").enc("E-71071", "IMP", "Inpatient admission", ago(4), null, COL, "Ward 12 Medical", new String[]{"dr-m-gunasekara", "Dr. M. Gunasekara"}, new String[][]{});
        new Pt("P-00110").enc("E-71101", "IMP", "Inpatient admission", ago(14), ago(8), KAN, "Ward 4 Medical", new String[]{"dr-r-fernando", "Dr. R. Fernando"}, new String[][]{});
        new Pt("P-00113").enc("E-71131", "AMB", "Outpatient consultation", ago(18), ago(18).plus(1, ChronoUnit.HOURS), COL, "OPD", new String[]{"dr-n-perera", "Dr. N. Perera"}, new String[][]{});
        new Pt("P-00112").enc("E-71121", "IMP", "Inpatient admission", ago(6), null, COL, "Ward 9 Psychiatry", new String[]{"dr-t-abeysekara", "Dr. T. Abeysekara"}, new String[][]{});
        new Pt("P-00104").enc("E-71041", "IMP", "Inpatient admission", ago(5), null, KAN, "Ward 7 Obstetrics", new String[]{"dr-r-fernando", "Dr. R. Fernando"}, new String[][]{});
        new Pt("P-00116").enc("E-71161", "EMER", "Emergency visit", ago(1), ago(1).plus(4, ChronoUnit.HOURS), COL, "Emergency Treatment Unit", new String[]{"dr-n-perera", "Dr. N. Perera"}, new String[][]{});
        new Pt("P-40119").enc("E-71191", "IMP", "Intensive care admission", ago(2), null, GAL, "ICU", new String[]{"dr-l-pathirana", "Dr. L. Pathirana"}, new String[][]{});
        new Pt("P-10418").enc("E-71181", "IMP", "Inpatient admission", ago(12), ago(9), COL, "Ward 5 Cardiology", new String[]{"dr-s-wickrama", "Dr. S. Wickramasinghe"}, new String[][]{});
        new Pt("P-20884").enc("E-71201", "IMP", "Inpatient admission", ago(6), ago(3), KAN, "Obstetrics", new String[]{"dr-r-fernando", "Dr. R. Fernando"}, new String[][]{});
    }

    /** P-10407 Kamal Mendis: the same three visits as the demo audit records (E-88213 OPD-3, E-87702 Ward 7, E-86110 casualty at Kandy). */
    private void kamal() {
        Pt p = new Pt("P-10407");
        // times that agree with DemoAuditData.patientStory()
        Instant opd = now.minus(26, ChronoUnit.HOURS);
        Instant adm = now.minus(5, ChronoUnit.DAYS);
        ZoneId colombo = ZoneId.of("Asia/Colombo");
        Instant btg = now.minus(21, ChronoUnit.DAYS).atZone(colombo).toLocalDate().atStartOfDay(colombo).toInstant().plus(2, ChronoUnit.HOURS).plus(14, ChronoUnit.MINUTES);
        String OPD = "E-88213", WARD = "E-87702", CAS = "E-86110";
        Instant opdStart = opd.minus(60, ChronoUnit.MINUTES), wardEnd = now.minus(56, ChronoUnit.HOURS), casStart = btg.minus(25, ChronoUnit.MINUTES);

        p.enc(OPD, "AMB", "Outpatient consultation", opdStart, opd.plus(30, ChronoUnit.MINUTES), COL, "OPD-3", PERERA, new String[][]{RANASINGHE})
         .enc(WARD, "IMP", "Inpatient admission", adm.minus(40, ChronoUnit.MINUTES), wardEnd, COL, "Ward 7", SILVA, new String[][]{PERERA})
         .enc(CAS, "EMER", "Emergency visit", casStart, btg.plus(3, ChronoUnit.HOURS), KAN, "Casualty", FERNANDO, new String[][]{});
    }
}

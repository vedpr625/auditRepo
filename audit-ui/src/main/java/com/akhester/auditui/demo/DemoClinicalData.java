package com.akhester.auditui.demo;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DEMONSTRATION ONLY. Made-up clinical records for the stand-in registry, as plain FHIR R4 JSON (Condition, MedicationRequest, Observation,
 * AllergyIntolerance, Procedure, and richer Encounters). Dates are relative to start-up, like the demo audit records, so the stays line up.
 * P-10407 (Kamal Mendis) is the rich one and uses the same encounter ids as the demo audit records; about ten other patients have a smaller set.
 * All names, doses and numbers are invented and not medical advice.
 */
final class DemoClinicalData {

    static final String COL = "Device/hhims-colombo-01", KAN = "Device/hhims-kandy-01", GAL = "Device/hhims-galle-01", JAF = "Device/hhims-jaffna-01";
    static final Map<String, String> FACILITY = Map.of(COL, "National Hospital Colombo (demo)", KAN, "Teaching Hospital Kandy (demo)",
        GAL, "Teaching Hospital Galle (demo)", JAF, "Teaching Hospital Jaffna (demo)");

    private static final String LOINC = "http://loinc.org", UCUM = "http://unitsofmeasure.org", SNOMED = "http://snomed.info/sct", ICD = "http://hl7.org/fhir/sid/icd-10";
    private static final String INTERP = "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation";
    private static final String OBS_CAT = "http://terminology.hl7.org/CodeSystem/observation-category";

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final Map<String, Pt> patients = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> encountersById = new LinkedHashMap<>();

    DemoClinicalData() { build(); }

    // ---------- what the registry serves ----------

    boolean has(String pid) { return patients.containsKey(pid); }

    /** Enriched encounters of a patient, or null when this class has none (the registry then lists plain stays). */
    List<Map<String, Object>> encounters(String pid) { Pt p = patients.get(pid); return p == null || p.enc.isEmpty() ? null : p.enc; }

    Map<String, Object> encounter(String id) { return encountersById.get(id); }

    /** All resources of one type for a patient (empty when none). */
    List<Map<String, Object>> list(String type, String pid) {
        Pt p = patients.get(pid);
        if (p == null) return List.of();
        return switch (type) {
            case "Condition" -> p.cond;
            case "MedicationRequest" -> p.med;
            case "Observation" -> p.obs;
            case "AllergyIntolerance" -> p.alg;
            case "Procedure" -> p.proc;
            default -> List.of();
        };
    }

    /** A registry that does not serve everything: the allergy list of P-00107 is refused (403) and Procedure is not supported (404). Shows the "not provided" screens. */
    int refusal(String type, String pid) {
        if (!"P-00107".equals(pid)) return 0;
        return "AllergyIntolerance".equals(type) ? 403 : "Procedure".equals(type) ? 404 : 0;
    }

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
    private Instant agoH(long hours) { return now.minus(hours, ChronoUnit.HOURS); }
    private Instant agoM(long minutes) { return now.minus(minutes, ChronoUnit.MINUTES); }

    /** One patient's resources. */
    private final class Pt {
        final String id;
        final List<Map<String, Object>> cond = new ArrayList<>(), med = new ArrayList<>(), obs = new ArrayList<>(), alg = new ArrayList<>(), proc = new ArrayList<>(), enc = new ArrayList<>();
        int n;

        Pt(String id) { this.id = id; patients.put(id, this); }

        private String rid(String kind) { return kind + "-" + id.substring(2) + "-" + (++n); }
        private Map<String, Object> subject() { return m("reference", "Patient/" + id); }
        private Map<String, Object> encRef(String enc) { return enc == null ? null : m("reference", "Encounter/" + enc); }

        /** status: active | resolved | inactive. snomed may be null. */
        Pt cond(String text, String icd, String snomed, String status, Instant onset, Instant abated, String enc) {
            List<Object> codings = new ArrayList<>();
            codings.add(coding(ICD, icd, text));
            if (snomed != null) codings.add(coding(SNOMED, snomed, text));
            cond.add(m("resourceType", "Condition", "id", rid("c"),
                "clinicalStatus", m("coding", List.of(coding("http://terminology.hl7.org/CodeSystem/condition-clinical", status, status))),
                "verificationStatus", m("coding", List.of(coding("http://terminology.hl7.org/CodeSystem/condition-ver-status", "confirmed", "Confirmed"))),
                "code", m("coding", codings, "text", text), "subject", subject(), "encounter", encRef(enc),
                "onsetDateTime", ts(onset), "abatementDateTime", abated == null ? null : ts(abated), "recordedDate", ts(onset)));
            return this;
        }

        Pt med(String text, String dose, String status, Instant at, String by, String reason, String enc) {
            med.add(m("resourceType", "MedicationRequest", "id", rid("m"), "status", status, "intent", "order",
                "medicationCodeableConcept", cc(text), "subject", subject(), "encounter", encRef(enc), "authoredOn", ts(at),
                "requester", by == null ? null : ref("Practitioner/" + by.toLowerCase().replaceAll("[^a-z]+", "-"), by),
                "reasonCode", reason == null ? null : List.of(cc(reason)),
                "dosageInstruction", List.of(m("text", dose))));
            return this;
        }

        Pt alg(String text, String criticality, String reaction, String severity, Instant recorded) {
            alg.add(m("resourceType", "AllergyIntolerance", "id", rid("a"),
                "clinicalStatus", m("coding", List.of(coding("http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical", "active", "Active"))),
                "criticality", criticality, "code", cc(text), "patient", subject(), "recordedDate", ts(recorded),
                "reaction", List.of(m("manifestation", List.of(cc(reaction)), "severity", severity))));
            return this;
        }

        Pt noAllergy() {
            alg.add(m("resourceType", "AllergyIntolerance", "id", rid("a"),
                "clinicalStatus", m("coding", List.of(coding("http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical", "active", "Active"))),
                "code", cc("No known allergies", SNOMED, "716186003"), "patient", subject(), "recordedDate", ts(ago(400))));
            return this;
        }

        Pt proc(String text, String snomed, Instant at, String by, String enc) {
            proc.add(m("resourceType", "Procedure", "id", rid("p"), "status", "completed", "code", cc(text, SNOMED, snomed), "subject", subject(),
                "encounter", encRef(enc), "performedDateTime", ts(at), "performer", by == null ? null : List.of(m("actor", ref("Practitioner/" + by.toLowerCase().replaceAll("[^a-z]+", "-"), by)))));
            return this;
        }

        /** A laboratory result (cat "laboratory") or vital sign ("vital-signs") with unit, reference range and H/L/N interpretation. lo or hi may be null. */
        Pt obs(String enc, String loinc, String name, String cat, String unit, Double lo, Double hi, double value, Instant at) {
            Map<String, Object> o = m("resourceType", "Observation", "id", rid("o"), "status", "final",
                "category", List.of(m("coding", List.of(coding(OBS_CAT, cat, cat)))), "code", cc(name, LOINC, loinc), "subject", subject(), "encounter", encRef(enc),
                "effectiveDateTime", ts(at), "valueQuantity", m("value", value, "unit", unit, "system", UCUM, "code", unit));
            addRange(o, unit, lo, hi, value);
            obs.add(o);
            return this;
        }

        Pt bp(String enc, double sys, double dia, Instant at) {
            Map<String, Object> o = m("resourceType", "Observation", "id", rid("o"), "status", "final",
                "category", List.of(m("coding", List.of(coding(OBS_CAT, "vital-signs", "vital-signs")))), "code", cc("Blood pressure", LOINC, "85354-9"),
                "subject", subject(), "encounter", encRef(enc), "effectiveDateTime", ts(at));
            List<Object> comps = new ArrayList<>();
            comps.add(component("Systolic blood pressure", "8480-6", sys, 90.0, 140.0));
            comps.add(component("Diastolic blood pressure", "8462-4", dia, 60.0, 90.0));
            o.put("component", comps);
            obs.add(o);
            return this;
        }

        private Map<String, Object> component(String name, String loinc, double v, double lo, double hi) {
            Map<String, Object> c = m("code", cc(name, LOINC, loinc), "valueQuantity", m("value", v, "unit", "mm[Hg]", "system", UCUM, "code", "mm[Hg]"));
            addRange(c, "mm[Hg]", lo, hi, v);
            return c;
        }

        private void addRange(Map<String, Object> holder, String unit, Double lo, Double hi, double v) {
            if (lo == null && hi == null) return;
            holder.put("referenceRange", List.of(m("low", lo == null ? null : m("value", lo, "unit", unit), "high", hi == null ? null : m("value", hi, "unit", unit))));
            String f = hi != null && v > hi ? "H" : lo != null && v < lo ? "L" : "N";
            holder.put("interpretation", List.of(m("coding", List.of(coding(INTERP, f, f.equals("H") ? "High" : f.equals("L") ? "Low" : "Normal")))));
        }

        /**
         * An encounter. cls: AMB | IMP | EMER. who: {reference id, display} of the attending, then more pairs. dx: {text, ICD-10 code, role code (CC, AD, DD, CM)}.
         * end null means still in progress.
         */
        Pt enc(String eid, String cls, String type, String reason, Instant start, Instant end, String fac, String ward, String[] attending, String[][] others, String[][] dx, String discharge) {
            String clsDisplay = switch (cls) { case "AMB" -> "ambulatory"; case "IMP" -> "inpatient encounter"; default -> "emergency"; };
            List<Object> parts = new ArrayList<>();
            parts.add(m("type", List.of(cc("attender", "http://terminology.hl7.org/CodeSystem/v3-ParticipationType", "ATND")), "individual", ref("Practitioner/" + attending[0], attending[1])));
            for (String[] o : others) parts.add(m("type", List.of(cc("consultant", "http://terminology.hl7.org/CodeSystem/v3-ParticipationType", "CON")), "individual", ref("Practitioner/" + o[0], o[1])));
            List<Object> dxs = new ArrayList<>();
            int rank = 0;
            for (String[] d : dx) {
                String cid = "Condition/c-" + id.substring(2) + "-" + d[1].toLowerCase().replaceAll("[^a-z0-9]", "");
                dxs.add(m("condition", ref(cid, d[0]), "use", m("coding", List.of(coding("http://terminology.hl7.org/CodeSystem/diagnosis-role", d[2], d[2]))), "rank", ++rank));
            }
            Map<String, Object> period = m("start", ts(start), "end", end == null ? null : ts(end));
            Map<String, Object> e = m("resourceType", "Encounter", "id", eid, "status", end == null ? "in-progress" : "finished",
                "class", coding("http://terminology.hl7.org/CodeSystem/v3-ActCode", cls, clsDisplay),
                "type", List.of(cc(type)), "serviceType", cc(ward), "subject", subject(),
                "participant", parts, "period", period, "reasonCode", reason == null ? null : List.of(cc(reason)),
                "diagnosis", dxs,
                "location", List.of(
                    m("location", ref(fac, FACILITY.get(fac)), "status", "completed"),
                    m("location", m("reference", "Location/" + fac.replace("Device/", "") + "-" + ward.toLowerCase().replaceAll("[^a-z0-9]+", "-"), "display", ward),
                        "physicalType", cc("Ward", "http://terminology.hl7.org/CodeSystem/location-physical-type", "wa"), "status", "completed")),
                "hospitalization", discharge == null ? null : m("dischargeDisposition", cc(discharge)),
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
        // smaller sets for other patients (stay times agree with DemoRegistry's stays)
        new Pt("P-00101")
            .enc("E-71011", "IMP", "Inpatient admission", "Fever, loin pain and vomiting", ago(20), null, COL, "Ward 12 Medical", new String[]{"dr-m-gunasekara", "Dr. M. Gunasekara"}, new String[][]{},
                new String[][]{{"Acute pyelonephritis", "N10", "AD"}}, null)
            .cond("Acute pyelonephritis", "N10", null, "active", ago(20), null, "E-71011")
            .cond("Essential hypertension", "I10", "59621000", "active", ago(900), null, null)
            .med("Ceftriaxone", "1 g IV once daily for 7 days", "active", ago(20), "Dr. M. Gunasekara", "Acute pyelonephritis", "E-71011")
            .med("Paracetamol", "1 g every 6 hours if needed", "active", ago(20), "Dr. M. Gunasekara", "Fever", "E-71011")
            .obs("E-71011", "6690-2", "White blood cell count", "laboratory", "10*3/uL", 4.0, 11.0, 18.4, ago(20))
            .obs("E-71011", "6690-2", "White blood cell count", "laboratory", "10*3/uL", 4.0, 11.0, 14.2, ago(17))
            .obs("E-71011", "6690-2", "White blood cell count", "laboratory", "10*3/uL", 4.0, 11.0, 10.1, ago(13))
            .obs("E-71011", "2160-0", "Creatinine", "laboratory", "mg/dL", 0.6, 1.1, 1.6, ago(20))
            .obs("E-71011", "2160-0", "Creatinine", "laboratory", "mg/dL", 0.6, 1.1, 1.2, ago(16))
            .obs("E-71011", "2160-0", "Creatinine", "laboratory", "mg/dL", 0.6, 1.1, 0.9, ago(12))
            .obs("E-71011", "8310-5", "Body temperature", "vital-signs", "Cel", 36.1, 37.5, 39.1, ago(20))
            .obs("E-71011", "8310-5", "Body temperature", "vital-signs", "Cel", 36.1, 37.5, 38.2, ago(18))
            .obs("E-71011", "8310-5", "Body temperature", "vital-signs", "Cel", 36.1, 37.5, 37.1, ago(14))
            .bp("E-71011", 132, 84, ago(20)).bp("E-71011", 126, 80, ago(14))
            .alg("Sulfonamide antibiotics", "low", "Skin rash", "mild", ago(1500))
            .proc("Renal ultrasound", null, ago(19), "Dr. P. Wickrama", "E-71011");

        new Pt("P-00102")
            .enc("E-71021", "IMP", "Inpatient admission", "Right lower abdominal pain", ago(9), ago(6), COL, "Ward 3 Surgical", new String[]{"dr-k-jayawardena", "Dr. K. Jayawardena"}, new String[][]{},
                new String[][]{{"Acute appendicitis", "K35.8", "AD"}}, "Home")
            .cond("Acute appendicitis", "K35.8", "74400008", "resolved", ago(9), ago(6), "E-71021")
            .med("Cefuroxime", "750 mg IV every 8 hours", "completed", ago(9), "Dr. K. Jayawardena", "Surgical prophylaxis", "E-71021")
            .med("Tramadol", "50 mg every 8 hours if needed", "completed", ago(8), "Dr. K. Jayawardena", "Post-operative pain", "E-71021")
            .obs("E-71021", "6690-2", "White blood cell count", "laboratory", "10*3/uL", 4.0, 11.0, 16.1, ago(9))
            .obs("E-71021", "6690-2", "White blood cell count", "laboratory", "10*3/uL", 4.0, 11.0, 9.2, ago(7))
            .obs("E-71021", "718-7", "Hemoglobin", "laboratory", "g/dL", 13.0, 17.0, 14.1, ago(9))
            .obs("E-71021", "1988-5", "C-reactive protein", "laboratory", "mg/L", 0.0, 5.0, 86.0, ago(9))
            .obs("E-71021", "1988-5", "C-reactive protein", "laboratory", "mg/L", 0.0, 5.0, 24.0, ago(7))
            .noAllergy()
            .proc("Appendicectomy", "80146002", ago(9).plus(5, ChronoUnit.HOURS), "Dr. K. Jayawardena", "E-71021");

        new Pt("P-00105")
            .enc("E-71051", "IMP", "Inpatient admission", "Chest pain at rest", ago(30), ago(25), COL, "Ward 5 Cardiology", new String[]{"dr-s-wickrama", "Dr. S. Wickramasinghe"}, new String[][]{},
                new String[][]{{"Non-ST-elevation myocardial infarction", "I21.4", "AD"}, {"Essential hypertension", "I10", "CM"}}, "Home with follow-up")
            .cond("Non-ST-elevation myocardial infarction", "I21.4", "401314000", "active", ago(30), null, "E-71051")
            .cond("Essential hypertension", "I10", "59621000", "active", ago(2400), null, null)
            .med("Aspirin", "75 mg once daily", "active", ago(30), "Dr. S. Wickramasinghe", "Myocardial infarction", "E-71051")
            .med("Clopidogrel", "75 mg once daily", "active", ago(30), "Dr. S. Wickramasinghe", "Myocardial infarction", "E-71051")
            .med("Atorvastatin", "80 mg at night", "active", ago(30), "Dr. S. Wickramasinghe", "Myocardial infarction", "E-71051")
            .med("Bisoprolol", "2.5 mg once daily", "active", ago(29), "Dr. S. Wickramasinghe", "Heart rate control", "E-71051")
            .obs("E-71051", "10839-9", "Troponin I", "laboratory", "ng/mL", 0.0, 0.04, 2.8, ago(30))
            .obs("E-71051", "10839-9", "Troponin I", "laboratory", "ng/mL", 0.0, 0.04, 1.4, ago(29))
            .obs("E-71051", "10839-9", "Troponin I", "laboratory", "ng/mL", 0.0, 0.04, 0.3, ago(27))
            .obs("E-71051", "13457-7", "LDL cholesterol", "laboratory", "mmol/L", 0.0, 3.0, 4.2, ago(30))
            .obs("E-71051", "8867-4", "Heart rate", "vital-signs", "/min", 60.0, 100.0, 104.0, ago(30))
            .obs("E-71051", "8867-4", "Heart rate", "vital-signs", "/min", 60.0, 100.0, 78.0, ago(26))
            .bp("E-71051", 158, 96, ago(30)).bp("E-71051", 132, 82, ago(26))
            .alg("Sulfonamide antibiotics", "low", "Hives", "mild", ago(2000))
            .proc("Coronary angiography", "33367005", ago(29), "Dr. S. Wickramasinghe", "E-71051")
            .proc("12-lead electrocardiogram", "29303009", ago(30), "Nurse D. Perera", "E-71051");

        // P-00107: the registry refuses the allergy list (403) and has no Procedure resource (404): the screens say "Not provided by the registry"
        new Pt("P-00107")
            .enc("E-71071", "IMP", "Inpatient admission", "Fever for four days, body aches", ago(4), null, COL, "Ward 12 Medical", new String[]{"dr-m-gunasekara", "Dr. M. Gunasekara"}, new String[][]{},
                new String[][]{{"Dengue fever", "A90", "AD"}}, null)
            .cond("Dengue fever", "A90", "38362002", "active", ago(5), null, "E-71071")
            .med("Paracetamol", "1 g every 6 hours if needed", "active", ago(4), "Dr. M. Gunasekara", "Fever", "E-71071")
            .med("Sodium chloride 0.9% infusion", "100 mL per hour IV", "active", ago(4), "Dr. M. Gunasekara", "Fluid replacement", "E-71071")
            .obs("E-71071", "777-3", "Platelet count", "laboratory", "10*3/uL", 150.0, 400.0, 98.0, ago(4))
            .obs("E-71071", "777-3", "Platelet count", "laboratory", "10*3/uL", 150.0, 400.0, 72.0, ago(3))
            .obs("E-71071", "777-3", "Platelet count", "laboratory", "10*3/uL", 150.0, 400.0, 55.0, ago(2))
            .obs("E-71071", "777-3", "Platelet count", "laboratory", "10*3/uL", 150.0, 400.0, 61.0, ago(1))
            .obs("E-71071", "20570-8", "Hematocrit", "laboratory", "%", 38.0, 50.0, 46.0, ago(4))
            .obs("E-71071", "20570-8", "Hematocrit", "laboratory", "%", 38.0, 50.0, 49.0, ago(2))
            .obs("E-71071", "1920-8", "AST", "laboratory", "U/L", 10.0, 40.0, 132.0, ago(3))
            .obs("E-71071", "8310-5", "Body temperature", "vital-signs", "Cel", 36.1, 37.5, 39.4, ago(4))
            .obs("E-71071", "8310-5", "Body temperature", "vital-signs", "Cel", 36.1, 37.5, 38.0, ago(3))
            .obs("E-71071", "8310-5", "Body temperature", "vital-signs", "Cel", 36.1, 37.5, 36.9, ago(1))
            .bp("E-71071", 112, 70, ago(4)).bp("E-71071", 104, 66, ago(2));

        new Pt("P-00110")
            .enc("E-71101", "IMP", "Inpatient admission", "Breathlessness and swollen legs", ago(14), ago(8), KAN, "Ward 4 Medical", new String[]{"dr-r-fernando", "Dr. R. Fernando"}, new String[][]{},
                new String[][]{{"Chronic kidney disease, stage 3", "N18.3", "AD"}, {"Heart failure", "I50.9", "AD"}}, "Home")
            .cond("Chronic kidney disease, stage 3", "N18.3", "433144002", "active", ago(1100), null, null)
            .cond("Heart failure", "I50.9", "84114007", "active", ago(14), null, "E-71101")
            .cond("Essential hypertension", "I10", "59621000", "active", ago(3000), null, null)
            .med("Furosemide", "40 mg once daily in the morning", "active", ago(14), "Dr. R. Fernando", "Heart failure", "E-71101")
            .med("Losartan", "50 mg once daily", "active", ago(300), "Dr. R. Fernando", "Hypertension", null)
            .obs("E-71101", "2160-0", "Creatinine", "laboratory", "mg/dL", 0.6, 1.1, 2.1, ago(14))
            .obs("E-71101", "2160-0", "Creatinine", "laboratory", "mg/dL", 0.6, 1.1, 2.0, ago(11))
            .obs("E-71101", "2160-0", "Creatinine", "laboratory", "mg/dL", 0.6, 1.1, 1.9, ago(8))
            .obs("E-71101", "2823-3", "Potassium", "laboratory", "mmol/L", 3.5, 5.1, 5.4, ago(14))
            .obs("E-71101", "718-7", "Hemoglobin", "laboratory", "g/dL", 12.0, 15.5, 10.2, ago(14))
            .obs("E-71101", "59408-5", "Oxygen saturation", "vital-signs", "%", 95.0, 100.0, 92.0, ago(14))
            .obs("E-71101", "59408-5", "Oxygen saturation", "vital-signs", "%", 95.0, 100.0, 96.0, ago(9))
            .alg("Penicillin", "high", "Anaphylaxis", "severe", ago(5000))
            .proc("Echocardiography", "40701008", ago(13), "Dr. S. Mahendran", "E-71101");

        new Pt("P-00113")
            .enc("E-71131", "AMB", "Outpatient consultation", "Wheeze and night cough", ago(18), ago(18).plus(1, ChronoUnit.HOURS), COL, "OPD", new String[]{"dr-n-perera", "Dr. N. Perera"}, new String[][]{},
                new String[][]{{"Asthma", "J45.9", "CC"}}, null)
            .cond("Asthma", "J45.9", "195967001", "active", ago(1800), null, null)
            .med("Salbutamol inhaler", "2 puffs when needed", "active", ago(18), "Dr. N. Perera", "Asthma", "E-71131")
            .med("Beclometasone inhaler", "200 micrograms twice daily", "active", ago(18), "Dr. N. Perera", "Asthma", "E-71131")
            .obs("E-71131", "59408-5", "Oxygen saturation", "vital-signs", "%", 95.0, 100.0, 97.0, ago(18))
            .obs("E-71131", "9279-1", "Respiratory rate", "vital-signs", "/min", 12.0, 20.0, 22.0, ago(18))
            .noAllergy();

        new Pt("P-00112")
            .enc("E-71121", "IMP", "Inpatient admission", "Low mood and poor sleep for six weeks", ago(6), null, COL, "Ward 9 Psychiatry", new String[]{"dr-t-abeysekara", "Dr. T. Abeysekara"}, new String[][]{},
                new String[][]{{"Major depressive disorder, moderate", "F32.1", "AD"}}, null)
            .cond("Major depressive disorder, moderate", "F32.1", "832007", "active", ago(40), null, "E-71121")
            .med("Sertraline", "50 mg once daily in the morning", "active", ago(6), "Dr. T. Abeysekara", "Depression", "E-71121")
            .obs("E-71121", "3016-3", "Thyroid stimulating hormone", "laboratory", "mIU/L", 0.4, 4.0, 2.1, ago(5))
            .obs("E-71121", "29463-7", "Body weight", "vital-signs", "kg", null, null, 58.0, ago(6))
            .noAllergy();

        new Pt("P-00104")
            .enc("E-71041", "IMP", "Inpatient admission", "Raised blood pressure in pregnancy", ago(5), null, KAN, "Ward 7 Obstetrics", new String[]{"dr-r-fernando", "Dr. R. Fernando"}, new String[][]{},
                new String[][]{{"Gestational hypertension", "O13", "AD"}}, null)
            .cond("Gestational hypertension", "O13", null, "active", ago(5), null, "E-71041")
            .med("Labetalol", "100 mg twice daily", "active", ago(5), "Dr. R. Fernando", "Gestational hypertension", "E-71041")
            .bp("E-71041", 152, 98, ago(5)).bp("E-71041", 138, 88, ago(3));

        new Pt("P-00116")
            .enc("E-71161", "EMER", "Emergency visit", "Wheeze and difficulty breathing", ago(1), ago(1).plus(4, ChronoUnit.HOURS), COL, "Emergency Treatment Unit", new String[]{"dr-n-perera", "Dr. N. Perera"}, new String[][]{},
                new String[][]{{"Acute severe asthma", "J46", "CC"}}, "Home")
            .cond("Acute severe asthma", "J46", null, "resolved", ago(1), ago(1).plus(4, ChronoUnit.HOURS), "E-71161")
            .obs("E-71161", "59408-5", "Oxygen saturation", "vital-signs", "%", 95.0, 100.0, 90.0, ago(1))
            .obs("E-71161", "59408-5", "Oxygen saturation", "vital-signs", "%", 95.0, 100.0, 97.0, ago(1).plus(3, ChronoUnit.HOURS));

        new Pt("P-40119")
            .enc("E-71191", "IMP", "Intensive care admission", "Septic shock from a chest infection", ago(2), null, GAL, "ICU", new String[]{"dr-l-pathirana", "Dr. L. Pathirana"}, new String[][]{},
                new String[][]{{"Septic shock", "A41.9", "AD"}}, null)
            .cond("Septic shock", "A41.9", "76571007", "active", ago(2), null, "E-71191")
            .cond("Type 2 diabetes mellitus", "E11.9", "44054006", "active", ago(3500), null, null)
            .med("Noradrenaline infusion", "0.1 micrograms/kg/min IV, titrated", "active", ago(2), "Dr. L. Pathirana", "Septic shock", "E-71191")
            .med("Meropenem", "1 g IV every 8 hours", "active", ago(2), "Dr. L. Pathirana", "Sepsis", "E-71191")
            .obs("E-71191", "2524-7", "Lactate", "laboratory", "mmol/L", 0.5, 2.0, 4.2, ago(2))
            .obs("E-71191", "2524-7", "Lactate", "laboratory", "mmol/L", 0.5, 2.0, 2.8, ago(1))
            .obs("E-71191", "2160-0", "Creatinine", "laboratory", "mg/dL", 0.6, 1.1, 1.9, ago(2))
            .obs("E-71191", "2160-0", "Creatinine", "laboratory", "mg/dL", 0.6, 1.1, 1.5, ago(1))
            .obs("E-71191", "6690-2", "White blood cell count", "laboratory", "10*3/uL", 4.0, 11.0, 21.3, ago(2))
            .bp("E-71191", 84, 48, ago(2)).bp("E-71191", 102, 60, ago(1))
            .noAllergy()
            .proc("Central venous catheter insertion", "392230005", ago(2).plus(1, ChronoUnit.HOURS), "Dr. L. Pathirana", "E-71191");

        new Pt("P-10418")
            .enc("E-71181", "IMP", "Inpatient admission", "Palpitations and breathlessness", ago(12), ago(9), COL, "Ward 5 Cardiology", new String[]{"dr-s-wickrama", "Dr. S. Wickramasinghe"}, new String[][]{},
                new String[][]{{"Atrial fibrillation", "I48.91", "AD"}}, "Home with follow-up")
            .cond("Atrial fibrillation", "I48.91", "49436004", "active", ago(12), null, "E-71181")
            .med("Apixaban", "5 mg twice daily", "active", ago(12), "Dr. S. Wickramasinghe", "Stroke prevention", "E-71181")
            .med("Metoprolol", "50 mg twice daily", "active", ago(12), "Dr. S. Wickramasinghe", "Rate control", "E-71181")
            .obs("E-71181", "8867-4", "Heart rate", "vital-signs", "/min", 60.0, 100.0, 132.0, ago(12))
            .obs("E-71181", "8867-4", "Heart rate", "vital-signs", "/min", 60.0, 100.0, 88.0, ago(11))
            .obs("E-71181", "8867-4", "Heart rate", "vital-signs", "/min", 60.0, 100.0, 76.0, ago(9))
            .obs("E-71181", "3016-3", "Thyroid stimulating hormone", "laboratory", "mIU/L", 0.4, 4.0, 1.8, ago(12))
            .alg("Latex", "low", "Contact rash", "mild", ago(3000))
            .proc("12-lead electrocardiogram", "29303009", ago(12), "Nurse D. Perera", "E-71181")
            .proc("Echocardiography", "40701008", ago(11), "Dr. S. Mahendran", "E-71181");

        new Pt("P-20884")
            .enc("E-71201", "IMP", "Inpatient admission", "Headache and raised blood pressure at 36 weeks", ago(6), ago(3), KAN, "Obstetrics", new String[]{"dr-r-fernando", "Dr. R. Fernando"}, new String[][]{},
                new String[][]{{"Pre-eclampsia", "O14.9", "AD"}}, "Home")
            .cond("Pre-eclampsia", "O14.9", "398254007", "resolved", ago(6), ago(3), "E-71201")
            .med("Labetalol", "100 mg twice daily", "completed", ago(6), "Dr. R. Fernando", "Pre-eclampsia", "E-71201")
            .med("Magnesium sulfate", "4 g IV loading dose, then 1 g per hour", "completed", ago(6), "Dr. R. Fernando", "Seizure prevention", "E-71201")
            .bp("E-71201", 162, 104, ago(6)).bp("E-71201", 146, 94, ago(5)).bp("E-71201", 132, 84, ago(3))
            .obs("E-71201", "718-7", "Hemoglobin", "laboratory", "g/dL", 12.0, 15.5, 10.8, ago(6))
            .obs("E-71201", "777-3", "Platelet count", "laboratory", "10*3/uL", 150.0, 400.0, 142.0, ago(6))
            .noAllergy()
            .proc("Caesarean section", "11466000", ago(5), "Dr. R. Fernando", "E-71201");
    }

    /** P-10407 Kamal Mendis, 41: the same three visits as the demo audit records (E-88213 OPD-3, E-87702 Ward 7, E-86110 casualty at Kandy). */
    private void kamal() {
        Pt p = new Pt("P-10407");
        // times that agree with DemoAuditData.patientStory()
        Instant opd = now.minus(26, ChronoUnit.HOURS);
        Instant adm = now.minus(5, ChronoUnit.DAYS);
        ZoneId colombo = ZoneId.of("Asia/Colombo");
        Instant btg = now.minus(21, ChronoUnit.DAYS).atZone(colombo).toLocalDate().atStartOfDay(colombo).toInstant().plus(2, ChronoUnit.HOURS).plus(14, ChronoUnit.MINUTES);
        String OPD = "E-88213", WARD = "E-87702", CAS = "E-86110";
        Instant opdStart = opd.minus(60, ChronoUnit.MINUTES), wardEnd = now.minus(56, ChronoUnit.HOURS), casStart = btg.minus(25, ChronoUnit.MINUTES);

        p.enc(OPD, "AMB", "Outpatient consultation", "Diabetes and blood pressure review, tired and thirsty", opdStart, opd.plus(30, ChronoUnit.MINUTES), COL, "OPD-3",
                PERERA, new String[][]{RANASINGHE}, new String[][]{{"Type 2 diabetes mellitus", "E11.9", "CC"}, {"Essential hypertension", "I10", "CM"}}, "Home")
         .enc(WARD, "IMP", "Inpatient admission", "Fever, cough and shortness of breath for three days", adm.minus(40, ChronoUnit.MINUTES), wardEnd, COL, "Ward 7",
                SILVA, new String[][]{PERERA}, new String[][]{{"Community-acquired pneumonia", "J18.9", "AD"}, {"Type 2 diabetes mellitus", "E11.9", "CM"}}, "Home with follow-up")
         .enc(CAS, "EMER", "Emergency visit", "Found unconscious, history not known", casStart, btg.plus(3, ChronoUnit.HOURS), KAN, "Casualty",
                FERNANDO, new String[][]{}, new String[][]{{"Hypoglycaemia", "E16.2", "CC"}, {"Syncope", "R55", "DD"}}, "Home");
        // the Encounter diagnoses point at these Conditions
        p.cond("Type 2 diabetes mellitus", "E11.9", "44054006", "active", ago(2500), null, null)
         .cond("Essential hypertension", "I10", "59621000", "active", ago(1500), null, null)
         .cond("Hyperlipidaemia", "E78.5", "55822004", "active", ago(1100), null, null)
         .cond("Community-acquired pneumonia", "J18.9", "233604007", "resolved", adm.minus(3, ChronoUnit.DAYS), wardEnd, WARD)
         .cond("Hypoglycaemia", "E16.2", null, "resolved", btg.minus(25, ChronoUnit.MINUTES), btg.plus(2, ChronoUnit.HOURS), CAS)
         .cond("Syncope", "R55", "271594007", "resolved", btg.minus(25, ChronoUnit.MINUTES), btg.plus(3, ChronoUnit.HOURS), CAS);

        p.alg("Penicillin", "high", "Anaphylaxis", "severe", ago(3800))
         .alg("Shellfish", "low", "Hives", "mild", ago(2600));

        p.med("Metformin", "850 mg twice daily with meals (dose raised from 500 mg)", "active", opd.minus(20, ChronoUnit.MINUTES), "Dr. N. Perera", "Type 2 diabetes mellitus", OPD)
         .med("Metformin", "500 mg twice daily with meals", "stopped", ago(200), "Dr. N. Perera", "Type 2 diabetes mellitus", null)
         .med("Amlodipine", "5 mg once daily", "active", ago(400), "Dr. N. Perera", "Hypertension", null)
         .med("Atorvastatin", "20 mg at night", "active", ago(300), "Dr. N. Perera", "Hyperlipidaemia", null)
         .med("Azithromycin", "500 mg once daily for 3 days (penicillin allergy noted)", "completed", adm, "Dr. A. Silva", "Community-acquired pneumonia", WARD)
         .med("Oxygen", "2 litres per minute by nasal prongs", "completed", adm, "Dr. A. Silva", "Low oxygen level", WARD)
         .med("Paracetamol", "1 g every 6 hours if needed", "completed", adm, "Dr. A. Silva", "Fever", WARD)
         .med("Dextrose 50%", "50 mL IV once, then 10% infusion", "completed", btg.plus(10, ChronoUnit.MINUTES), "Dr. R. Fernando", "Hypoglycaemia", CAS);

        p.proc("Diabetic foot examination", "401191002", opd.minus(25, ChronoUnit.MINUTES), "Dr. H. Ranasinghe", OPD)
         .proc("Chest X-ray", "399208008", adm.plus(40, ChronoUnit.MINUTES), "Radiology, Colombo NH", WARD)
         .proc("12-lead electrocardiogram", "29303009", btg.plus(15, ChronoUnit.MINUTES), "Nurse K. Perera", CAS)
         .proc("Capillary blood glucose test", null, btg.minus(20, ChronoUnit.MINUTES), "Nurse K. Perera", CAS);

        // casualty: low sugar, fast heart, treated with glucose
        Instant c0 = btg.minus(20, ChronoUnit.MINUTES);
        p.obs(CAS, "2345-7", "Glucose", "laboratory", "mg/dL", 70.0, 140.0, 38.0, c0)
         .obs(CAS, "2345-7", "Glucose", "laboratory", "mg/dL", 70.0, 140.0, 92.0, btg.plus(45, ChronoUnit.MINUTES))
         .obs(CAS, "2345-7", "Glucose", "laboratory", "mg/dL", 70.0, 140.0, 118.0, btg.plus(120, ChronoUnit.MINUTES))
         .bp(CAS, 92, 54, c0).bp(CAS, 118, 74, btg.plus(90, ChronoUnit.MINUTES))
         .obs(CAS, "8867-4", "Heart rate", "vital-signs", "/min", 60.0, 100.0, 112.0, c0)
         .obs(CAS, "8867-4", "Heart rate", "vital-signs", "/min", 60.0, 100.0, 84.0, btg.plus(90, ChronoUnit.MINUTES))
         .obs(CAS, "8310-5", "Body temperature", "vital-signs", "Cel", 36.1, 37.5, 36.2, c0)
         .obs(CAS, "59408-5", "Oxygen saturation", "vital-signs", "%", 95.0, 100.0, 97.0, c0)
         .obs(CAS, "10839-9", "Troponin I", "laboratory", "ng/mL", 0.0, 0.04, 0.01, btg.plus(30, ChronoUnit.MINUTES))
         .obs(CAS, "2951-2", "Sodium", "laboratory", "mmol/L", 135.0, 145.0, 140.0, btg.plus(30, ChronoUnit.MINUTES))
         .obs(CAS, "2823-3", "Potassium", "laboratory", "mmol/L", 3.5, 5.1, 3.9, btg.plus(30, ChronoUnit.MINUTES));

        // inpatient stay: pneumonia, getting better
        p.obs(WARD, "6690-2", "White blood cell count", "laboratory", "10*3/uL", 4.0, 11.0, 15.2, adm.plus(2, ChronoUnit.HOURS))
         .obs(WARD, "6690-2", "White blood cell count", "laboratory", "10*3/uL", 4.0, 11.0, 11.8, adm.plus(30, ChronoUnit.HOURS))
         .obs(WARD, "6690-2", "White blood cell count", "laboratory", "10*3/uL", 4.0, 11.0, 8.9, adm.plus(52, ChronoUnit.HOURS))
         .obs(WARD, "1988-5", "C-reactive protein", "laboratory", "mg/L", 0.0, 5.0, 148.0, adm.plus(2, ChronoUnit.HOURS))
         .obs(WARD, "1988-5", "C-reactive protein", "laboratory", "mg/L", 0.0, 5.0, 74.0, adm.plus(30, ChronoUnit.HOURS))
         .obs(WARD, "1988-5", "C-reactive protein", "laboratory", "mg/L", 0.0, 5.0, 22.0, adm.plus(52, ChronoUnit.HOURS))
         .obs(WARD, "718-7", "Hemoglobin", "laboratory", "g/dL", 13.0, 17.0, 11.9, adm.plus(2, ChronoUnit.HOURS))
         .obs(WARD, "718-7", "Hemoglobin", "laboratory", "g/dL", 13.0, 17.0, 12.6, adm.plus(52, ChronoUnit.HOURS))
         .obs(WARD, "2951-2", "Sodium", "laboratory", "mmol/L", 135.0, 145.0, 133.0, adm.plus(2, ChronoUnit.HOURS))
         .obs(WARD, "2951-2", "Sodium", "laboratory", "mmol/L", 135.0, 145.0, 139.0, adm.plus(52, ChronoUnit.HOURS))
         .obs(WARD, "2160-0", "Creatinine", "laboratory", "mg/dL", 0.7, 1.3, 1.0, adm.plus(2, ChronoUnit.HOURS))
         .obs(WARD, "2160-0", "Creatinine", "laboratory", "mg/dL", 0.7, 1.3, 0.9, adm.plus(52, ChronoUnit.HOURS))
         .obs(WARD, "2345-7", "Glucose", "laboratory", "mg/dL", 70.0, 140.0, 218.0, adm.plus(2, ChronoUnit.HOURS))
         .obs(WARD, "2345-7", "Glucose", "laboratory", "mg/dL", 70.0, 140.0, 176.0, adm.plus(30, ChronoUnit.HOURS))
         .obs(WARD, "2345-7", "Glucose", "laboratory", "mg/dL", 70.0, 140.0, 142.0, adm.plus(52, ChronoUnit.HOURS))
         .obs(WARD, "59408-5", "Oxygen saturation", "vital-signs", "%", 95.0, 100.0, 90.0, adm)
         .obs(WARD, "59408-5", "Oxygen saturation", "vital-signs", "%", 95.0, 100.0, 93.0, adm.plus(24, ChronoUnit.HOURS))
         .obs(WARD, "59408-5", "Oxygen saturation", "vital-signs", "%", 95.0, 100.0, 96.0, adm.plus(50, ChronoUnit.HOURS))
         .obs(WARD, "8310-5", "Body temperature", "vital-signs", "Cel", 36.1, 37.5, 38.6, adm)
         .obs(WARD, "8310-5", "Body temperature", "vital-signs", "Cel", 36.1, 37.5, 37.9, adm.plus(24, ChronoUnit.HOURS))
         .obs(WARD, "8310-5", "Body temperature", "vital-signs", "Cel", 36.1, 37.5, 36.9, adm.plus(50, ChronoUnit.HOURS))
         .obs(WARD, "9279-1", "Respiratory rate", "vital-signs", "/min", 12.0, 20.0, 26.0, adm)
         .obs(WARD, "9279-1", "Respiratory rate", "vital-signs", "/min", 12.0, 20.0, 22.0, adm.plus(24, ChronoUnit.HOURS))
         .obs(WARD, "9279-1", "Respiratory rate", "vital-signs", "/min", 12.0, 20.0, 18.0, adm.plus(50, ChronoUnit.HOURS))
         .obs(WARD, "8867-4", "Heart rate", "vital-signs", "/min", 60.0, 100.0, 108.0, adm)
         .obs(WARD, "8867-4", "Heart rate", "vital-signs", "/min", 60.0, 100.0, 94.0, adm.plus(24, ChronoUnit.HOURS))
         .obs(WARD, "8867-4", "Heart rate", "vital-signs", "/min", 60.0, 100.0, 82.0, adm.plus(50, ChronoUnit.HOURS))
         .bp(WARD, 118, 72, adm).bp(WARD, 122, 76, adm.plus(24, ChronoUnit.HOURS)).bp(WARD, 126, 78, adm.plus(50, ChronoUnit.HOURS));

        // outpatient review: sugar control
        Instant o0 = opd.minus(45, ChronoUnit.MINUTES);
        p.obs(null, "4548-4", "Hemoglobin A1c", "laboratory", "%", 4.0, 5.6, 7.4, ago(270))
         .obs(null, "4548-4", "Hemoglobin A1c", "laboratory", "%", 4.0, 5.6, 7.9, ago(92))
         .obs(OPD, "4548-4", "Hemoglobin A1c", "laboratory", "%", 4.0, 5.6, 8.4, opd.minus(30, ChronoUnit.MINUTES))
         .obs(OPD, "2345-7", "Glucose", "laboratory", "mg/dL", 70.0, 140.0, 164.0, opd.minus(30, ChronoUnit.MINUTES))
         .obs(OPD, "2160-0", "Creatinine", "laboratory", "mg/dL", 0.7, 1.3, 1.1, opd.minus(30, ChronoUnit.MINUTES))
         .obs(OPD, "13457-7", "LDL cholesterol", "laboratory", "mmol/L", 0.0, 3.0, 3.9, opd.minus(30, ChronoUnit.MINUTES))
         .bp(null, 142, 90, ago(270)).bp(null, 138, 88, ago(92)).bp(OPD, 148, 92, o0)
         .obs(OPD, "8867-4", "Heart rate", "vital-signs", "/min", 60.0, 100.0, 78.0, o0)
         .obs(OPD, "29463-7", "Body weight", "vital-signs", "kg", null, null, 84.5, o0)
         .obs(OPD, "8310-5", "Body temperature", "vital-signs", "Cel", 36.1, 37.5, 36.7, o0);
    }
}

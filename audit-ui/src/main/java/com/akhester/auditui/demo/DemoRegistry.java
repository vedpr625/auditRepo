package com.akhester.auditui.demo;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEMONSTRATION ONLY (audit.ui.demo=true). A tiny stand-in for the Bundle 2a registry: plain FHIR R4 Patient and Encounter,
 * all names and numbers made up. It is reached over HTTP like a real registry, so switching to the real one is an address change.
 */
@RestController
@RequestMapping("/demo/registry")
@ConditionalOnProperty(name = "audit.ui.demo", havingValue = "true")
public class DemoRegistry {

    private static final String COL = "Device/hhims-colombo-01", KAN = "Device/hhims-kandy-01", GAL = "Device/hhims-galle-01", JAF = "Device/hhims-jaffna-01";
    private static final Map<String, String> FACILITY = Map.of(COL, "National Hospital Colombo (demo)", KAN, "Teaching Hospital Kandy (demo)",
        GAL, "Teaching Hospital Galle (demo)", JAF, "Teaching Hospital Jaffna (demo)");

    /** id, name, gender, birthDate, masked id, then stays as {facility, ward, days ago started, days ago ended or -1 for still admitted}. */
    private record P(String id, String name, String gender, String born, String nic, Object[][] stays) {}

    private static Object[] s(String fac, String ward, int from, int to) { return new Object[]{fac, ward, from, to}; }

    private static final List<P> PATIENTS = List.of(
        new P("P-00101", "Nimali Perera (demo)", "female", "1984-03-12", "••••••8412V", new Object[][]{s(COL, "Ward 12 Medical", 20, -1)}),
        new P("P-00102", "Kasun Rajapaksha (demo)", "male", "1971-11-02", "••••••7123V", new Object[][]{s(COL, "Ward 3 Surgical", 9, 6)}),
        new P("P-00103", "Dinesh Kumar (demo)", "male", "1990-07-21", "••••••9020V", new Object[][]{s(COL, "OPD", 3, 3)}),
        new P("P-00104", "Sithara Weerasinghe (demo)", "female", "1996-01-30", "••••••9630V", new Object[][]{s(KAN, "Ward 7 Obstetrics", 5, -1)}),
        new P("P-00105", "Mahesh Gunawardena (demo)", "male", "1958-05-05", "••••••5805V", new Object[][]{s(COL, "Ward 5 Cardiology", 30, 25)}),
        new P("P-00106", "Anjali Fernando (demo)", "female", "2001-09-17", "••••••0109V", new Object[][]{s(KAN, "OPD", 12, 12)}),
        new P("P-00107", "Ruwan Jayasinghe (demo)", "male", "1979-12-25", "••••••7925V", new Object[][]{s(COL, "Ward 12 Medical", 4, -1)}),
        new P("P-00108", "Priyanka Silva (demo)", "female", "1965-04-09", "••••••6509V", new Object[][]{s(COL, "OPD", 40, 40)}),
        new P("P-00109", "Tharindu Bandara (demo)", "male", "2012-06-14", "(child, no id)", new Object[][]{s(KAN, "Paediatric Ward 2", 2, -1)}),
        new P("P-00110", "Lakshmi Nadarajah (demo)", "female", "1949-02-28", "••••••4902V", new Object[][]{s(KAN, "Ward 4 Medical", 14, 8)}),
        new P("P-00111", "Chamara Dissanayake (demo)", "male", "1987-08-08", "••••••8708V", new Object[][]{s(KAN, "OPD", 22, 22)}),
        new P("P-00112", "Dilani Herath (demo)", "female", "1992-10-10", "••••••9210V", new Object[][]{s(COL, "Ward 9 Psychiatry", 6, -1)}),
        new P("P-00113", "Sanjeewa Wickramasinghe (demo)", "male", "1975-03-03", "••••••7503V", new Object[][]{s(COL, "OPD", 18, 18)}),
        new P("P-00114", "Kavitha Selvam (demo)", "female", "1983-06-06", "••••••8306V", new Object[][]{s(KAN, "Ward 4 Medical", 3, 1)}),
        new P("P-00115", "Pradeep Amarasinghe (demo)", "male", "1968-01-19", "••••••6801V", new Object[][]{s(COL, "OPD", 50, 50)}),
        new P("P-00116", "Ishara Madushani (demo)", "female", "2003-12-01", "••••••0312V", new Object[][]{s(COL, "Emergency Treatment Unit", 1, 1)}),
        new P("P-00117", "Gamini Ratnayake (demo)", "male", "1955-07-07", "••••••5507V", new Object[][]{s(COL, "Ward 5 Cardiology", 7, 2)}),
        // patients that the Reviews, Break-glass and Reports screens talk about
        new P("P-10407", "Kamal Mendis (demo)", "male", "1985-03-14", "••••••4821V", new Object[][]{s(COL, "OPD-3", 1, 1), s(COL, "Ward 7", 5, 3), s(KAN, "Casualty", 22, 21)}),
        new P("P-20881", "Sunethra Karunaratne (demo)", "female", "1978-09-02", "••••••7809V", new Object[][]{s(KAN, "Casualty", 1, 1)}),
        new P("P-30177", "Ranjith Abeysekara (demo)", "male", "1990-12-12", "••••••9012V", new Object[][]{s(COL, "Casualty", 1, 1)}),
        new P("P-40119", "Malini Gamage (demo)", "female", "1969-05-30", "••••••6905V", new Object[][]{s(GAL, "ICU", 2, -1)}),
        new P("P-50290", "Thevarajah Kumar (demo)", "male", "1962-11-21", "••••••6211V", new Object[][]{s(JAF, "OPD", 1, 1)}),
        new P("P-10412", "Hasini Wijesundara (demo)", "female", "1999-02-17", "••••••9902V", new Object[][]{s(COL, "Ward 12 Medical", 8, 4)}),
        new P("P-10418", "Sampath Liyanage (demo)", "male", "1957-08-08", "••••••5708V", new Object[][]{s(COL, "Ward 5 Cardiology", 12, 9)}),
        new P("P-20884", "Nadeesha Rathnayake (demo)", "female", "1993-04-25", "••••••9304V", new Object[][]{s(KAN, "Obstetrics", 6, 3)}),
        new P("P-20890", "Vimal Senanayake (demo)", "male", "1981-01-09", "••••••8101V", new Object[][]{s(KAN, "Ward 4 Medical", 9, 7)}),
        new P("P-30181", "Shanika Jayawardena (demo)", "female", "2005-06-06", "••••••0506V", new Object[][]{s(GAL, "Paediatric Ward", 3, 1)}),
        new P("P-30190", "Upul Tennakoon (demo)", "male", "1953-10-19", "••••••5310V", new Object[][]{s(GAL, "Ward 2 Surgical", 15, 11)}),
        new P("P-40125", "Chathurika Silva (demo)", "female", "1988-07-27", "••••••8807V", new Object[][]{s(GAL, "OPD", 2, 2)}),
        new P("P-50301", "Arulpragasam Selvan (demo)", "male", "1974-03-03", "••••••7403V", new Object[][]{s(JAF, "Ward 3 Medical", 4, -1)}),
        new P("P-50310", "Sivakumari Rajan (demo)", "female", "1967-12-30", "••••••6712V", new Object[][]{s(JAF, "OPD", 10, 10)}),
        new P("P-10430", "Buddhika Fonseka (demo)", "male", "1995-09-09", "••••••9509V", new Object[][]{s(COL, "Emergency Treatment Unit", 0, 0)}),
        new P("P-10431", "Yamuna Dissanayake (demo)", "female", "1983-11-11", "••••••8311V", new Object[][]{s(COL, "OPD-3", 6, 6)}),
        new P("P-10432", "Chaminda Bandara (demo)", "male", "1972-02-02", "••••••7202V", new Object[][]{s(COL, "Ward 9 Psychiatry", 10, 7)}),
        new P("P-20895", "Pushpa Kumari (demo)", "female", "1960-06-16", "••••••6006V", new Object[][]{s(KAN, "Ward 4 Medical", 18, 14)}),
        new P("P-30200", "Dammika Herath (demo)", "male", "1980-08-24", "••••••8008V", new Object[][]{s(GAL, "OPD", 5, 5)}),
        new P("P-50320", "Ganeshan Murugesu (demo)", "male", "1958-01-28", "••••••5801V", new Object[][]{s(JAF, "Ward 3 Medical", 20, 16)}));

    /** Facility of the patient's first listed stay (used to make the demo audit records agree with the registry). */
    static String homeFacility(String id) { P p = find(id); return p == null ? COL : (String) p.stays[0][0]; }
    static String homeWard(String id) { P p = find(id); return p == null ? "OPD" : (String) p.stays[0][1]; }
    static String patientName(String id) { P p = find(id); return p == null ? id : p.name.replace(" (demo)", ""); }
    static List<String> patientIds() { return PATIENTS.stream().map(x -> x.id).toList(); }

    private static P find(String id) { return PATIENTS.stream().filter(p -> p.id.equals(id)).findFirst().orElse(null); }

    @GetMapping(value = "/metadata", produces = "application/fhir+json")
    public Map<String, Object> metadata() {
        return Map.of("resourceType", "CapabilityStatement", "status", "active", "kind", "instance", "fhirVersion", "4.0.1", "format", List.of("json"),
            "software", Map.of("name", "Demo registry (made-up data)"));
    }

    @GetMapping(value = "/Patient/{id}", produces = "application/fhir+json")
    public ResponseEntity<Map<String, Object>> patient(@PathVariable String id) {
        P p = find(id);
        if (p == null) return ResponseEntity.status(404).contentType(MediaType.valueOf("application/fhir+json"))
            .body(Map.of("resourceType", "OperationOutcome", "issue", List.of(Map.of("severity", "error", "code", "not-found"))));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("resourceType", "Patient");
        r.put("id", p.id);
        r.put("identifier", List.of(Map.of("system", "urn:demo:national-id", "value", p.nic)));
        r.put("name", List.of(Map.of("text", p.name)));
        r.put("gender", p.gender);
        r.put("birthDate", p.born);
        return ResponseEntity.ok().contentType(MediaType.valueOf("application/fhir+json")).body(r);
    }

    @GetMapping(value = "/Encounter", produces = "application/fhir+json")
    public Map<String, Object> encounters(@RequestParam String patient) {
        P p = find(patient.replace("Patient/", ""));
        List<Object> entries = new ArrayList<>();
        if (p != null) {
            int n = 0;
            for (Object[] st : p.stays) {
                String fac = (String) st[0]; int from = (Integer) st[2], to = (Integer) st[3];
                Map<String, Object> period = new LinkedHashMap<>();
                period.put("start", Instant.now().minus(from, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString());
                if (to >= 0) period.put("end", Instant.now().minus(to, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString());
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("resourceType", "Encounter"); e.put("id", p.id + "-e" + (++n)); e.put("status", to >= 0 ? "finished" : "in-progress");
                e.put("subject", Map.of("reference", "Patient/" + p.id));
                e.put("serviceType", Map.of("text", st[1]));
                e.put("period", period);
                e.put("location", List.of(Map.of("location", Map.of("reference", fac, "display", FACILITY.get(fac)))));
                entries.add(Map.of("resource", e));
            }
        }
        return Map.of("resourceType", "Bundle", "type", "searchset", "total", entries.size(), "entry", entries);
    }
}

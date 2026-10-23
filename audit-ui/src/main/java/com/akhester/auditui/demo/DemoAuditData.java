package com.akhester.auditui.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * DEMONSTRATION ONLY. About 150 made-up audit records spread over the last 14 days, built once at start-up relative to "now" so that
 * review ages look right. Also a real SHA-256 hash chain per UTC day, so the integrity check and the record drawer have something true to show.
 */
@Component
@ConditionalOnProperty(name = "audit.ui.demo", havingValue = "true")
public class DemoAuditData {

    public record Chain(String partition, int position, String previousHash, String currentHash, Instant writtenAt) {}

    private static final String TAGS = "https://akhester.com/chester";
    private static final String COL = "Device/hhims-colombo-01", KAN = "Device/hhims-kandy-01", GAL = "Device/hhims-galle-01", JAF = "Device/hhims-jaffna-01",
        ETL = "Device/moh-etl-01", RES = "Device/research-portal-01", LAB = "Device/lablink-gw-01", PHA = "Device/pharma-galle-01", ECH = "Device/echannel-svc-01", PAC = "Device/pacs-01";
    public static final String EXT_REASON = TAGS + "/StructureDefinition/break-glass-reason", EXT_REASON_AT = TAGS + "/StructureDefinition/reason-given-at";
    private static final String GENESIS = "0".repeat(64);

    private final List<AuditEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<String, Chain> chains = new java.util.concurrent.ConcurrentHashMap<>();
    private int next = 2001;

    /** A person or system that sends records. */
    private record Sys(String client, String observer, String name, int share) {}

    private static final Sys[] SYSTEMS = {
        new Sys("hhims-audit", null, "HHIMS", 55), new Sys("lablink-gw", LAB, "LabLink", 15), new Sys("pharma-galle", PHA, "PharmaLink", 10),
        new Sys("echannel-svc", ECH, "e-Channel", 6), new Sys("pacs-audit", PAC, "Radiology PACS", 5), new Sys("moh-etl", ETL, "MoH analytics", 5), new Sys("research-portal", RES, "Research portal", 4)};
    private static final String[] HOSPITALS = {COL, KAN, GAL, JAF};
    private static final int[] HOSP_SHARE = {40, 25, 20, 15};
    private static final String[][] CLINICIANS = {
        {"Dr. A. Silva", "Practitioner/dr-silva", COL}, {"Dr. N. Perera", "Practitioner/dr-n-perera", COL}, {"Dr. A. de Silva", "Practitioner/dr-a-de-silva", COL}, {"Dr. S. Perera", "Practitioner/dr-perera", COL},
        {"Dr. R. Fernando", "Practitioner/dr-fernando", KAN}, {"Dr. M. Nadarajah", "Practitioner/dr-nadarajah", JAF}, {"Nurse S. Silva", "Practitioner/nurse-silva", KAN}, {"Dr. K. Jayawardena", "Practitioner/dr-jayawardena", KAN},
        {"Dr. K. Wickrama", "Practitioner/dr-wickrama", GAL}, {"Dr. P. Gunasekara", "Practitioner/dr-gunasekara", GAL}, {"Dr. S. Kumar", "Practitioner/dr-kumar", JAF}, {"Dr. T. Selvarajah", "Practitioner/dr-selvarajah", JAF},
        {"Dr. H. Ranasinghe", "Practitioner/dr-ranasinghe", COL}, {"Nurse D. Jayasinghe", "Practitioner/nurse-jayasinghe", GAL}};
    private static final int[] DIURNAL = {1, 1, 1, 1, 1, 2, 4, 8, 14, 18, 20, 18, 14, 12, 13, 14, 12, 9, 6, 4, 3, 2, 2, 1};   // by hour of day in Sri Lanka
    private static final String[][] FAILS = {{"Not allowed (403)", "8"}, {"Not allowed (403)", "8"}, {"Not allowed (403)", "8"}, {"Token expired (401)", "4"}, {"Token expired (401)", "4"}, {"Record not valid (422)", "4"}, {"Server error (500)", "12"}};
    private static final String[] REASONS = {"Road accident, unconscious, no history available", "Patient collapsed in clinic", "Transfer from another ward, urgent drug history",
        "Cardiac arrest, need allergy list", "Child brought in unresponsive", "Overdose, need medication history", "Seizure, unknown patient", "Stroke alert, need prior imaging"};

    private final Random rnd = new Random(2026);
    private final Instant now = Instant.now();

    public DemoAuditData() {
        List<String> pats = DemoRegistry.patientIds();
        java.time.ZoneId lk = java.time.ZoneId.of("Asia/Colombo");
        // ordinary traffic: a Poisson number of records per hour for 28 days, busy at 10:00, quiet at weekends
        for (int back = 28 * 24; back >= 1; back--) {
            Instant hourStart = now.truncatedTo(ChronoUnit.HOURS).minus(back - 1, ChronoUnit.HOURS);
            var local = hourStart.atZone(lk);
            double weekend = local.getDayOfWeek().getValue() >= 6 ? 0.3 : 1.0;
            int n = poisson(DIURNAL[local.getHour()] * 0.55 * weekend);
            for (int i = 0; i < n; i++) {
                Instant at = hourStart.plusSeconds(rnd.nextInt(3600));
                if (at.isAfter(now)) continue;
                ordinary(at, pats);
            }
        }
        // a burst of failures from one system on one day (the Admin screen shows "14 failures Tue")
        for (int i = 0; i < 14; i++) ordinaryFor(SYSTEMS[2], now.minus(3, ChronoUnit.DAYS).minusSeconds(rnd.nextInt(20000)), pats, true);
        stories(pats);
        patientStory();
        events.sort(Comparator.comparing((AuditEvent e) -> e.getRecorded()));
        buildChain();
    }

    private int poisson(double mean) {
        double l = Math.exp(-mean), p = 1; int k = 0;
        do { k++; p *= rnd.nextDouble(); } while (p > l);
        return k - 1;
    }

    private String pick(String[] a, int[] w) {
        int t = 0; for (int x : w) t += x;
        int r = rnd.nextInt(t);
        for (int i = 0; i < a.length; i++) { r -= w[i]; if (r < 0) return a[i]; }
        return a[0];
    }

    private void ordinary(Instant at, List<String> pats) {
        int r = rnd.nextInt(100), acc = 0;
        for (Sys s : SYSTEMS) {
            acc += s.share();
            if (r < acc) {
                if (s.client().equals("pacs-audit") && at.isAfter(now.minus(3, ChronoUnit.HOURS))) return;   // the PACS has gone quiet
                ordinaryFor(s, at, pats, false); return;
            }
        }
    }

    private void ordinaryFor(Sys s, Instant at, List<String> pats, boolean failure) {
        String pid = pats.get(rnd.nextInt(pats.size()));
        double failRate = s.client().equals("pharma-galle") ? 0.02 : s.client().equals("hhims-audit") ? 0.009 : 0.004;
        String[] f = failure || rnd.nextDouble() < failRate ? FAILS[rnd.nextInt(FAILS.length)] : null;
        int oc = f == null ? 0 : Integer.parseInt(f[1]);
        String desc = f == null ? null : f[0];
        switch (s.client()) {
            case "hhims-audit" -> {
                String home = DemoRegistry.homeFacility(pid);
                boolean away = rnd.nextInt(100) < 5;
                String fac = away ? HOSPITALS[rnd.nextInt(4)] : home;
                String[] c = clinicianAt(fac);
                String purpose = rnd.nextInt(100) < 7 ? "ETREAT" : "TREAT";
                add(at, pid, fac, c[0], c[1], purpose, oc, desc, rnd.nextInt(10) < 8 ? "R" : (rnd.nextBoolean() ? "U" : "C"), false, false, away ? "Emergency" : DemoRegistry.homeWard(pid), s.client(), null, null);
            }
            case "lablink-gw" -> add(at, pid, LAB, "lab-gateway", "Device/lab-gateway", "TREAT", oc, desc, rnd.nextInt(10) < 6 ? "C" : "R", false, false, "Central laboratory", s.client(), null, null);
            case "pharma-galle" -> add(at, pid, PHA, "A. Jayasuriya", "Practitioner/pharm-jayasuriya", "TREAT", oc, desc, rnd.nextBoolean() ? "U" : "R", false, false, "Pharmacy", s.client(), null, null);
            case "echannel-svc" -> add(at, pid, ECH, "echannel-svc", "Device/echannel-svc", rnd.nextInt(100) < 25 ? "HPAYMT" : "HOPERAT", oc, desc, "R", false, false, "Booking", s.client(), null, null);
            case "pacs-audit" -> add(at, pid, PAC, "pacs-gw", "Device/pacs-gw", "TREAT", oc, desc, "R", false, false, "Radiology", s.client(), null, null);
            case "moh-etl" -> add(at, pid, ETL, "MOH Analytics Platform", "Organization/moh-analytics", "PUBHLTH", oc, desc, "R", false, false, null, s.client(), null, null);
            default -> add(at, pid, RES, "Clinical Research Portal", "Organization/research-portal", "HRESCH", oc, desc, "R", false, false, null, s.client(), null, null);
        }
    }

    private String[] clinicianAt(String fac) {
        List<String[]> l = new ArrayList<>();
        for (String[] c : CLINICIANS) if (c[2].equals(fac)) l.add(c);
        return l.get(rnd.nextInt(l.size()));
    }

    private String[] clinician(String name) { for (String[] c : CLINICIANS) if (c[0].equals(name)) return c; throw new IllegalArgumentException(name); }

    private void btg(String who, long minutesAgo, String pid, String reason, Integer reasonAfterMinutes) {
        String[] c = clinician(who);
        Instant at = now.minus(minutesAgo, ChronoUnit.MINUTES);
        add(at, pid, c[2], c[0], c[1], "BTG", 0, null, "R", true, false, "Casualty", "hhims-audit", reason,
            reasonAfterMinutes == null ? null : at.plus(reasonAfterMinutes, ChronoUnit.MINUTES));
    }

    /** The stories the Overview, Reviews and Break-glass screens tell. */
    private void stories(List<String> pats) {
        // repeated access: 14 views of one patient in two hours, ~30 h ago, by someone from another hospital
        for (int i = 0; i < 14; i++) {
            String[] c = clinician("Dr. K. Wickrama");
            add(now.minus(30, ChronoUnit.HOURS).minus(i * 8L, ChronoUnit.MINUTES), "P-10407", GAL, c[0], c[1], "TREAT", 0, null, i % 5 == 4 ? "U" : "R", false, false, "Ward 4", "hhims-audit", null, null);
        }
        // high volume: 62 records in one hour by one person ~4 h ago
        for (int i = 0; i < 62; i++) {
            String[] c = clinician("Dr. R. Fernando");
            add(now.minus(4, ChronoUnit.HOURS).minus(i * 55L, ChronoUnit.SECONDS), pats.get(i % pats.size()), KAN, c[0], c[1], "TREAT", 0, null, "R", false, false, "Ward 4 Medical", "hhims-audit", null, null);
        }
        // break-glass, oldest and most urgent first
        btg("Nurse S. Silva", 27 * 60, "P-20881", null, null);                                // 27 h, no reason: overdue
        btg("Dr. A. de Silva", 28 * 60, "P-30177", REASONS[0], 38);
        btg("Dr. K. Wickrama", 30 * 60 + 5, "P-40119", REASONS[2], 120);
        btg("Dr. S. Kumar", 35 * 60, "P-50290", null, null);                                  // 35 h, no reason
        btg("Dr. N. Perera", 50 * 60, "P-10407", REASONS[1], 12);
        btg("Nurse S. Silva", 3 * 24 * 60, "P-20884", REASONS[3], 95);
        btg("Nurse S. Silva", 5 * 24 * 60, "P-20890", REASONS[5], 40);
        btg("Dr. R. Fernando", 8 * 60, "P-20895", null, null);                                // 8 h, waiting
        btg("Dr. M. Nadarajah", 3 * 60, "P-50301", null, null);                               // 3 h, waiting
        for (int i = 0; i < 14; i++) {                                                         // older ones, mostly with reasons
            String[] c = CLINICIANS[rnd.nextInt(CLINICIANS.length)];
            long mins = (long) (6 * 24 * 60 + rnd.nextInt(21 * 24 * 60));
            btg(c[0], mins, pats.get(rnd.nextInt(pats.size())), REASONS[rnd.nextInt(REASONS.length)], 5 + rnd.nextInt(180));
        }
        // incomplete records: no purpose of use sent
        int[] incHours = {40, 10, 2, 100, 150, 200, 260, 330};
        for (int i = 0; i < incHours.length; i++) {
            String pid = pats.get(3 + i * 3);
            String[] c = clinicianAt(DemoRegistry.homeFacility(pid));
            add(now.minus(incHours[i], ChronoUnit.HOURS), pid, DemoRegistry.homeFacility(pid), c[0], c[1], null, 0, null, "R", false, true, DemoRegistry.homeWard(pid), "hhims-audit", null, null);
        }
    }

    /** Gives patient P-10407 three registry visits that the patient page tells (an outpatient visit, an inpatient stay, a casualty visit with break-glass). */
    private void patientStory() {
        final String pid = "P-10407";
        Random r = new Random(10407);
        // outpatient visit OPD-3 at Colombo, yesterday: 7 events, 4 people, 3 systems
        Instant opd = now.minus(26, ChronoUnit.HOURS);
        String e1 = "Encounter/E-88213";
        String[][] opdEv = {
            {"Dr. N. Perera", "Practitioner/dr-n-perera", COL, "hhims-audit", "R", "OPD-3", "0"}, {"lab-gateway", "Device/lab-gateway", LAB, "lablink-gw", "C", "Central laboratory", "0"},
            {"A. Jayasuriya", "Practitioner/pharm-jayasuriya", PHA, "pharma-galle", "U", "Pharmacy", "0"}, {"Dr. N. Perera", "Practitioner/dr-n-perera", COL, "hhims-audit", "R", "OPD-3", "0"},
            {"Dr. N. Perera", "Practitioner/dr-n-perera", COL, "hhims-audit", "R", "OPD-3", "0"}, {"lab-gateway", "Device/lab-gateway", LAB, "lablink-gw", "R", "Central laboratory", "0"},
            {"Dr. H. Ranasinghe", "Practitioner/dr-ranasinghe", COL, "hhims-audit", "R", "OPD-3", "0"}};
        for (int i = 0; i < opdEv.length; i++) {
            String[] x = opdEv[i];
            add(opd.minus(i * 4L + 2, ChronoUnit.MINUTES), pid, x[2], x[0], x[1], "TREAT", 0, null, x[4], false, false, x[5], x[3], null, null);
            withEncounter(e1);
        }
        // inpatient stay Ward 7 at Colombo, 5 to 3 days ago: 29 events, two refused, 9 people, 2 systems
        String e2 = "Encounter/E-87702";
        String[] ward = {"Dr. A. Silva", "Dr. N. Perera", "Dr. A. de Silva", "Dr. S. Perera", "Dr. H. Ranasinghe"};
        for (int i = 0; i < 29; i++) {
            String[] c;
            if (i == 3 || i == 17) {   // the laboratory system also sends records during the stay
                add(now.minus(5, ChronoUnit.DAYS).plus(i * 130L, ChronoUnit.MINUTES), pid, LAB, "lab-gateway", "Device/lab-gateway", "TREAT", 0, null, "C", false, false, "Central laboratory", "lablink-gw", null, null);
                withEncounter(e2);
                continue;
            }
            if (i % 6 == 5) c = new String[]{"Nurse " + (char) ('A' + (i / 6) % 3) + ". Fonseka", "Practitioner/nurse-fonseka-" + (i / 6) % 3, COL};
            else c = clinician(ward[i % ward.length]);
            boolean refused = i == 11 || i == 23;
            Instant at = now.minus(5, ChronoUnit.DAYS).plus(i * 130L + r.nextInt(40), ChronoUnit.MINUTES);
            add(at, pid, COL, c[0], c[1], "TREAT", refused ? 8 : 0, refused ? "Not allowed (403)" : null, i % 4 == 0 ? "U" : "R", false, false, "Ward 7", "hhims-audit", null, null);
            withEncounter(e2);
        }
        // casualty at Kandy, about three weeks ago: break-glass by a nurse, reason 26 minutes later, then a doctor treats
        String e3 = "Encounter/E-86110";
        Instant day = now.minus(21, ChronoUnit.DAYS).atZone(java.time.ZoneId.of("Asia/Colombo")).toLocalDate().atStartOfDay(java.time.ZoneId.of("Asia/Colombo")).toInstant();
        Instant btgAt = day.plus(2, ChronoUnit.HOURS).plus(14, ChronoUnit.MINUTES);
        String[] nurse = {"Nurse K. Perera", "Practitioner/nurse-k-perera", KAN}, dr = clinician("Dr. R. Fernando");
        add(btgAt, pid, KAN, nurse[0], nurse[1], "BTG", 0, null, "R", true, false, "Casualty", "hhims-audit", "unconscious patient, history needed", btgAt.plus(26, ChronoUnit.MINUTES));
        withEncounter(e3);
        add(btgAt.plus(40, ChronoUnit.MINUTES), pid, KAN, dr[0], dr[1], "ETREAT", 0, null, "R", false, false, "Casualty", "hhims-audit", null, null);
        withEncounter(e3);
    }

    /** Puts the last event added into the given encounter (so systems that do not send one still join the visit). */
    private void withEncounter(String ref) {
        AuditEvent e = events.get(events.size() - 1);
        e.getEntity().removeIf(x -> x.hasWhat() && x.getWhat().hasReference() && x.getWhat().getReference().startsWith("Encounter/"));
        e.addEntity().setWhat(new Reference(ref)).setType(new Coding("http://terminology.hl7.org/CodeSystem/audit-entity-type", "2", "System Object"));
    }

    private void add(Instant at, String pid, String facility, String agentName, String agentRef, String purpose, int outcome, String outcomeDesc, String action,
                     boolean btg, boolean incomplete, String site, String client, String reason, Instant reasonAt) {
        AuditEvent e = new AuditEvent();
        e.setId(String.valueOf(next++));
        e.setRecorded(Date.from(at.truncatedTo(ChronoUnit.SECONDS)));
        e.getType().setSystem("http://terminology.hl7.org/CodeSystem/audit-event-type").setCode("rest").setDisplay("RESTful Operation");
        e.getMeta().addProfile("https://profiles.ihe.net/ITI/BALP/StructureDefinition/IHE.BasicAudit.Patient" + (action.equals("R") ? "Read" : action.equals("C") ? "Create" : "Update"));
        e.addSubtype().setSystem("http://hl7.org/fhir/restful-interaction").setCode(action.equals("R") ? "read" : action.equals("C") ? "create" : "update").setDisplay(action.equals("R") ? "read" : action.equals("C") ? "create" : "update");
        e.setAction(AuditEvent.AuditEventAction.fromCode(action));
        e.setOutcome(AuditEvent.AuditEventOutcome.fromCode(String.valueOf(outcome)));
        if (outcome != 0) e.setOutcomeDesc(outcomeDesc == null ? "Demo: the request failed" : outcomeDesc);
        if (purpose != null) e.addPurposeOfEvent(new CodeableConcept().addCoding(new Coding("http://terminology.hl7.org/CodeSystem/v3-ActReason", purpose, null)));
        if (btg) e.getMeta().addSecurity(new Coding("http://terminology.hl7.org/CodeSystem/v3-ActCode", "BTG", "break glass"));
        e.getMeta().addTag(new Coding(TAGS + "/submitting-client", client, null));
        if (incomplete) e.getMeta().addTag(new Coding(TAGS + "/tags", "policy-incomplete", "Required purpose of use missing"));
        if (reason != null) e.addExtension(EXT_REASON, new StringType(reason));
        if (reasonAt != null) e.addExtension(EXT_REASON_AT, new org.hl7.fhir.r4.model.DateTimeType(Date.from(reasonAt)));
        AuditEvent.AuditEventAgentComponent a = e.addAgent();
        a.setName(agentName).setRequestor(true).setWho(new Reference(agentRef));
        a.getNetwork().setAddress("10.20." + (Math.abs(agentRef.hashCode()) % 200) + ".15").setType(AuditEvent.AuditEventAgentNetworkType._2);
        e.getSource().setSite(site == null ? "Integration gateway" : site).setObserver(new Reference(facility));
        e.addEntity().setWhat(new Reference("Patient/" + pid)).setType(new Coding("http://terminology.hl7.org/CodeSystem/audit-entity-type", "1", "Person"))
            .setRole(new Coding("http://terminology.hl7.org/CodeSystem/object-role", "1", "Patient"));
        if (client.equals("hhims-audit") && facility.startsWith("Device/hhims")) {
            int enc = 80000 + Math.abs((pid + at.truncatedTo(ChronoUnit.DAYS)).hashCode()) % 9000;
            e.addEntity().setWhat(new Reference("Encounter/E-" + enc)).setType(new Coding("http://terminology.hl7.org/CodeSystem/audit-entity-type", "2", "System Object"));
        }
        events.add(e);
    }

    private void buildChain() {
        Map<String, String> last = new HashMap<>();
        Map<String, Integer> pos = new HashMap<>();
        for (AuditEvent e : events) {
            String day = e.getRecorded().toInstant().toString().substring(0, 10);
            String prev = last.getOrDefault(day, GENESIS);
            String cur = sha(prev + "|" + e.getIdElement().getIdPart() + "|" + e.getRecorded().toInstant());
            int p = pos.merge(day, 1, Integer::sum);
            chains.put(e.getIdElement().getIdPart(), new Chain(day, p, prev, cur, e.getRecorded().toInstant().plusSeconds(1)));
            last.put(day, cur);
        }
    }

    static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    /**
     * The console writing its own activity to the stand-in Chester (POST /AuditEvent): the event gets the next id and joins the hash chain of
     * its day, so it shows in Explore and passes the chain check like any other record. Returns the stored event.
     */
    public synchronized AuditEvent append(AuditEvent e) {
        e.setId(String.valueOf(next++));
        if (e.getRecorded() == null) e.setRecorded(new Date());
        Instant at = e.getRecorded().toInstant();
        String day = at.toString().substring(0, 10);
        String prev = GENESIS;
        int pos = 0;
        for (AuditEvent o : events) {
            Chain c = chains.get(o.getIdElement().getIdPart());
            if (c != null && c.partition().equals(day) && c.position() >= pos) { pos = c.position(); prev = c.currentHash(); }
        }
        String cur = sha(prev + "|" + e.getIdElement().getIdPart() + "|" + at);
        chains.put(e.getIdElement().getIdPart(), new Chain(day, pos + 1, prev, cur, at.plusSeconds(1)));
        events.add(e);
        return e;
    }

    public List<AuditEvent> events() { return events; }
    public Chain chain(String id) { return chains.get(id); }
    public String genesis() { return GENESIS; }
    public int partitions() { return (int) chains.values().stream().map(Chain::partition).distinct().count(); }
}

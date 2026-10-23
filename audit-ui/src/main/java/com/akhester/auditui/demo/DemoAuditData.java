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
    private static final String COL = "Device/hhims-colombo-01", KAN = "Device/hhims-kandy-01", ETL = "Device/moh-etl-01", RES = "Device/research-portal-01";
    private static final String GENESIS = "0".repeat(64);

    private final List<AuditEvent> events = new ArrayList<>();
    private final Map<String, Chain> chains = new HashMap<>();
    private int next = 2001;

    public DemoAuditData() {
        Instant now = Instant.now();
        Random rnd = new Random(2026);
        String[][] clinicians = {{"Dr. A. Silva", "Practitioner/dr-silva"}, {"Dr. S. Perera", "Practitioner/dr-perera"}, {"Dr. K. Jayawardena", "Practitioner/dr-jayawardena"},
            {"Dr. R. Fernando", "Practitioner/dr-fernando"}, {"Dr. M. Nadarajah", "Practitioner/dr-nadarajah"}};
        List<String> pats = DemoRegistry.patientIds();

        // ordinary treatment reads: the clinician's system is the patient's own hospital
        for (int i = 0; i < 95; i++) {
            String pid = pats.get(rnd.nextInt(pats.size()));
            String[] c = clinicians[rnd.nextInt(clinicians.length)];
            double hoursAgo = Math.pow(rnd.nextDouble(), 1.7) * 14 * 24;
            int oc = rnd.nextInt(100) < 8 ? new int[]{4, 8, 12}[rnd.nextInt(3)] : 0;
            add(now.minusSeconds((long) (hoursAgo * 3600)), pid, DemoRegistry.homeFacility(pid), c[0], c[1], "TREAT", oc, rnd.nextInt(10) < 8 ? "R" : (rnd.nextBoolean() ? "U" : "C"), false, false, DemoRegistry.homeWard(pid));
        }
        // public health and research use, by systems rather than clinicians
        for (int i = 0; i < 22; i++) {
            String pid = pats.get(rnd.nextInt(pats.size()));
            boolean ph = i % 2 == 0;
            add(now.minusSeconds((long) (rnd.nextDouble() * 12 * 24 * 3600)), pid, ph ? ETL : RES, ph ? "MOH Analytics Platform" : "Clinical Research Portal",
                ph ? "Organization/moh-analytics" : "Organization/research-portal", ph ? "PUBHLTH" : "HRESCH", 0, "R", false, false, null);
        }
        // emergency treatment: some at the patient's own hospital, some at a different one (care-link demo)
        for (int i = 0; i < 12; i++) {
            String pid = pats.get(rnd.nextInt(pats.size()));
            String fac = i % 3 == 0 ? (DemoRegistry.homeFacility(pid).equals(COL) ? KAN : COL) : DemoRegistry.homeFacility(pid);
            String[] c = clinicians[rnd.nextInt(clinicians.length)];
            add(now.minusSeconds((long) (rnd.nextDouble() * 9 * 24 * 3600)), pid, fac, c[0], c[1], "ETREAT", 0, "R", false, false, "Emergency");
        }
        // break-glass: ages chosen so the review clock shows overdue, due soon and fine
        int[] btgHours = {72, 36, 20, 8, 3};
        String[] btgPat = {"P-00116", "P-00112", "P-00109", "P-00104", "P-00101"};
        for (int i = 0; i < btgHours.length; i++) {
            String[] c = clinicians[(i + 1) % clinicians.length];
            add(now.minusSeconds(btgHours[i] * 3600L), btgPat[i], i % 2 == 0 ? COL : KAN, c[0], c[1], "BTG", 0, "R", true, false, "Emergency");
        }
        // incomplete records (no purpose supplied)
        int[] incHours = {40, 10, 2};
        for (int i = 0; i < incHours.length; i++) {
            String pid = pats.get(3 + i * 4);
            String[] c = clinicians[i];
            add(now.minusSeconds(incHours[i] * 3600L), pid, DemoRegistry.homeFacility(pid), c[0], c[1], null, 0, "R", false, true, DemoRegistry.homeWard(pid));
        }
        events.sort(Comparator.comparing((AuditEvent e) -> e.getRecorded()));
        buildChain();
    }

    private void add(Instant at, String pid, String facility, String agentName, String agentRef, String purpose, int outcome, String action,
                     boolean btg, boolean incomplete, String site) {
        AuditEvent e = new AuditEvent();
        e.setId(String.valueOf(next++));
        e.setRecorded(Date.from(at.truncatedTo(ChronoUnit.SECONDS)));
        e.getType().setSystem("http://terminology.hl7.org/CodeSystem/audit-event-type").setCode("rest").setDisplay("RESTful Operation");
        e.getMeta().addProfile("https://profiles.ihe.net/ITI/BALP/StructureDefinition/IHE.BasicAudit.PatientRead");
        e.addSubtype().setSystem("http://hl7.org/fhir/restful-interaction").setCode("read").setDisplay("read");
        e.setAction(AuditEvent.AuditEventAction.fromCode(action));
        e.setOutcome(AuditEvent.AuditEventOutcome.fromCode(String.valueOf(outcome)));
        if (outcome != 0) e.setOutcomeDesc("Demo: the request failed");
        if (purpose != null) e.addPurposeOfEvent(new CodeableConcept().addCoding(new Coding("http://terminology.hl7.org/CodeSystem/v3-ActReason", purpose, null)));
        if (btg) e.getMeta().addSecurity(new Coding("http://terminology.hl7.org/CodeSystem/v3-ActCode", "BTG", "break glass"));
        String client = facility.contains("colombo") ? "hhims-colombo" : facility.contains("kandy") ? "hhims-kandy" : facility.contains("moh") ? "moh-etl" : "research-portal";
        e.getMeta().addTag(new Coding(TAGS + "/submitting-client", client, null));
        if (incomplete) e.getMeta().addTag(new Coding(TAGS + "/tags", "policy-incomplete", "Required purpose of use missing"));
        AuditEvent.AuditEventAgentComponent a = e.addAgent();
        a.setName(agentName).setRequestor(true).setWho(new Reference(agentRef));
        a.getNetwork().setAddress("10.20." + (Math.abs(agentRef.hashCode()) % 200) + ".15").setType(AuditEvent.AuditEventAgentNetworkType._2);
        e.getSource().setSite(site == null ? "Integration gateway" : site).setObserver(new Reference(facility));
        e.addEntity().setWhat(new Reference("Patient/" + pid)).setType(new Coding("http://terminology.hl7.org/CodeSystem/audit-entity-type", "1", "Person"))
            .setRole(new Coding("http://terminology.hl7.org/CodeSystem/object-role", "1", "Patient"));
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

    public List<AuditEvent> events() { return events; }
    public Chain chain(String id) { return chains.get(id); }
    public String genesis() { return GENESIS; }
    public int partitions() { return (int) chains.values().stream().map(Chain::partition).distinct().count(); }
}

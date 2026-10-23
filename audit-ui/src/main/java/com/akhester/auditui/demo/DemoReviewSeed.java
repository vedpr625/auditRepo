package com.akhester.auditui.demo;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.hl7.fhir.r4.model.AuditEvent;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.akhester.auditui.reviews.CaseRepository;
import com.akhester.auditui.reviews.CaseRepository.Case;
import com.akhester.auditui.reviews.CaseService;

/**
 * DEMONSTRATION ONLY. Sixteen made-up review cases (nine closed in the last 14 days, the rest at every stage) tied to real records in
 * {@link DemoAuditData}. Replaced on every start so the ages stay right; cases people opened themselves are left alone.
 */
@Component
@Order(20)
@ConditionalOnProperty(name = "audit.ui.demo", havingValue = "true")
public class DemoReviewSeed implements ApplicationRunner {

    private static final String PRIYA = "priya.admin", NIMAL = "nimal.ministry", SAMANTHA = "samantha.privacy";

    private final DemoAuditData data;
    private final CaseService service;
    private final CaseRepository repo;
    private final JdbcTemplate jdbc;
    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

    public DemoReviewSeed(DemoAuditData data, CaseService service, CaseRepository repo, JdbcTemplate jdbc) {
        this.data = data;
        this.service = service;
        this.repo = repo;
        this.jdbc = jdbc;
    }

    private static String agent(AuditEvent e) { return e.getAgentFirstRep().getName(); }
    private static String agentRef(AuditEvent e) { return e.getAgentFirstRep().getWho().getReference(); }
    private static Instant at(AuditEvent e) { return e.getRecorded().toInstant(); }
    private static String patient(AuditEvent e) {
        return e.getEntity().stream().filter(x -> x.hasWhat() && x.getWhat().getReference().startsWith("Patient/")).map(x -> x.getWhat().getReference()).findFirst().orElse(null);
    }
    private static boolean btg(AuditEvent e) { return e.getMeta().getSecurity().stream().anyMatch(c -> "BTG".equals(c.getCode())); }

    /** The latest {@code limit} matching records that happened before {@code before}, oldest first. */
    private List<AuditEvent> find(Predicate<AuditEvent> p, int limit, Instant before) {
        List<AuditEvent> all = data.events().stream().filter(p).filter(e -> at(e).isBefore(before)).sorted(Comparator.comparing(AuditEvent::getRecorded)).collect(Collectors.toList());
        return all.subList(Math.max(0, all.size() - limit), all.size());
    }
    private List<AuditEvent> find(Predicate<AuditEvent> p, int limit) { return find(p, limit, now.plusSeconds(1)); }
    private List<AuditEvent> byAgent(String name, int limit) { return find(e -> name.equals(agent(e)) && !btg(e), limit); }
    private List<AuditEvent> byAgent(String name, int limit, Instant before) { return find(e -> name.equals(agent(e)) && !btg(e), limit, before); }
    private static String client(AuditEvent e) {
        return e.getMeta().getTag().stream().filter(t -> t.getSystem() != null && t.getSystem().endsWith("/submitting-client")).map(t -> t.getCode()).findFirst().orElse(null);
    }
    private AuditEvent btgOf(String name, String patient) {
        return find(e -> btg(e) && name.equals(agent(e)) && ("Patient/" + patient).equals(patient(e)), 1).get(0);
    }
    private static List<String> ids(List<AuditEvent> l) { return l.stream().map(e -> e.getIdElement().getIdPart()).toList(); }
    private Instant ago(long hours) { return now.minus(hours, ChronoUnit.HOURS); }

    @Override
    public void run(ApplicationArguments args) {
        // the earlier run's demo cases, their event decisions and their history go; a person's own cases stay
        jdbc.update("DELETE FROM audit_review WHERE note LIKE '[case C-%'");
        jdbc.update("DELETE FROM audit_review_log WHERE note LIKE '[case C-%'");
        repo.deleteDemoSeed();

        // nine closed cases
        closed("night", "Night access", "Dr. H. Ranasinghe", null, byAgent("Dr. H. Ranasinghe", 9, ago(46)), ago(46), ago(19), PRIYA, NIMAL,
            "appropriate", "Shift cover, confirmed by the ward.", "appropriate", "Agreed. Duty roster shows cover.");
        closed("volume", "High-volume access", "lab-gateway", null, find(e -> "lab-gateway".equals(agent(e)), 12, ago(100)), ago(100), ago(70), NIMAL, PRIYA,
            "appropriate", "The gateway sends results in batches every morning.", "appropriate", "Known batch job.");
        AuditEvent deSilva = btgOf("Dr. A. de Silva", "P-30177");
        closed("btg", "Break-glass access to review", agent(deSilva), "Patient/P-30177", List.of(deSilva), at(deSilva), ago(18), PRIYA, NIMAL,
            "appropriate", "Road accident. Reason given in 38 minutes.", "appropriate", "Matches the emergency record.");
        closed("failures", "Repeated failures", "pharma-galle", null, find(e -> "pharma-galle".equals(client(e)) && e.getOutcome() != AuditEvent.AuditEventOutcome._0, 14, ago(78)),
            ago(78), ago(60), NIMAL, PRIYA, "follow_up", "Ask the pharmacy vendor why the token keeps expiring.", "follow_up", "Agreed. Vendor contacted.");
        AuditEvent perera = btgOf("Dr. N. Perera", "P-10407");
        closed("btg", "Break-glass access to review", agent(perera), "Patient/P-10407", List.of(perera), at(perera), ago(40), NIMAL, PRIYA,
            "appropriate", "Collapse in clinic, reason in 12 minutes.", "appropriate", "Fine.");
        closed("repeatbtg", "Repeat break-glass use", "Nurse S. Silva", null, find(e -> btg(e) && "Nurse S. Silva".equals(agent(e)), 3, ago(110)),
            ago(110), ago(96), PRIYA, NIMAL, "follow_up", "Three uses in five days. Talk to the ward manager.", "follow_up", "Agreed.");
        closedOfficer("night", "Night access", "Dr. S. Kumar", null, byAgent("Dr. S. Kumar", 5, ago(150)), ago(150), ago(120), NIMAL, "violation",
            "Records opened for a family member.", SAMANTHA, "Confirmed. Disciplinary process started.");
        AuditEvent rep = byAgent("Dr. P. Gunasekara", 40).get(0);   // only for the agent reference
        closed("repeat", "Repeated access", "Dr. P. Gunasekara", null, byAgent("Dr. P. Gunasekara", 11, ago(220)), ago(220), ago(190), PRIYA, NIMAL,
            "appropriate", "Ward round, same patient reviewed through the day.", "appropriate", "Agreed.");
        AuditEvent silva2 = find(e -> btg(e) && "Nurse S. Silva".equals(agent(e)) && "Patient/P-20884".equals(patient(e)), 1).get(0);
        closed("btg", "Break-glass access to review", agent(silva2), "Patient/P-20884", List.of(silva2), at(silva2), ago(50), NIMAL, PRIYA,
            "appropriate", "Cardiac arrest, reason given in 95 minutes.", "appropriate", "Late, but genuine.");

        // the two reviewers differ, so it waits for the privacy officer
        escalated();
        AuditEvent silvaRepeat = find(e -> btg(e) && "Nurse S. Silva".equals(agent(e)) && at(e).isAfter(ago(24 * 5 + 1)), 3).get(0);
        List<AuditEvent> silvaAll = find(e -> btg(e) && "Nurse S. Silva".equals(agent(e)) && at(e).isAfter(ago(24 * 7)), 5);
        Case c42 = service.open("repeatbtg", "Repeat break-glass use", "Nurse S. Silva", null, "Device/hhims-kandy-01", "agent=" + agentRef(silvaRepeat), ids(silvaAll),
            "Repeat break-glass rule", ago(20), SAMANTHA, null, true);
        draft(c42, "follow_up", "Three uses this week. Waiting for the ward manager’s view.");

        List<AuditEvent> wick = find(e -> "Dr. K. Wickrama".equals(agent(e)) && "Patient/P-10407".equals(patient(e)) && !btg(e), 14);
        Case c43 = service.open("repeat", "Repeated access", "Dr. K. Wickrama", "Patient/P-10407", "Device/hhims-galle-01", "q=P-10407", ids(wick),
            "Repeated-access rule", at(wick.get(0)).plus(2, ChronoUnit.HOURS), PRIYA, null, true);
        draft(c43, "follow_up", "The patient was not on Dr. Wickrama’s ward. Waiting on Galle TH.");
        repo.addLog(c43.id(), c43.openedAt().plus(55, ChronoUnit.MINUTES), PRIYA, "note", "Asked Galle TH for an explanation. No reply yet.");

        AuditEvent kumar = btgOf("Dr. S. Kumar", "P-50290");
        Case c44 = service.open("btgreason", "Break-glass without a reason", agent(kumar), "Patient/P-50290", "Device/hhims-jaffna-01", "q=P-50290", List.of(kumar.getIdElement().getIdPart()),
            "Break-glass reason rule", at(kumar), NIMAL, null, true);
        repo.addLog(c44.id(), at(kumar).plus(14, ChronoUnit.HOURS), NIMAL, "note", "Reminder sent to the clinician. No reason yet.");

        List<AuditEvent> night = find(e -> "Dr. P. Gunasekara".equals(agent(e)) && at(e).isAfter(ago(30)), 6);
        if (night.isEmpty()) night = byAgent("Dr. P. Gunasekara", 6);
        service.open("night", "Night access", "Dr. P. Gunasekara", null, "Device/hhims-galle-01", "agent=" + agentRef(rep), ids(night), "Night-access rule", ago(6), null, null, true);

        AuditEvent silva = btgOf("Nurse S. Silva", "P-20881");
        service.open("btgreason", "Break-glass without a reason", agent(silva), "Patient/P-20881", "Device/hhims-kandy-01", "q=P-20881", List.of(silva.getIdElement().getIdPart()),
            "Break-glass reason rule", at(silva), null, null, true);

        secondOpinion();
    }

    // ── builders ─────────────────────────────────────────────────────────────

    private String facilityOf(List<AuditEvent> l) { return l.isEmpty() ? null : l.get(0).getSource().getObserver().getReference(); }

    private String exploreFor(String patientRef, List<AuditEvent> l) {
        if (patientRef != null) return "q=" + patientRef.replace("Patient/", "");
        return l.isEmpty() ? null : "agent=" + agentRef(l.get(0));
    }

    /** A case closed because two reviewers agreed. */
    private void closed(String kind, String title, String subject, String patientRef, List<AuditEvent> evs, Instant opened, Instant closedAt,
                        String first, String second, String d1, String n1, String d2, String n2) {
        Case c = service.open(kind, title, subject, patientRef, facilityOf(evs), exploreFor(patientRef, evs), ids(evs), kind.startsWith("btg") ? "Break-glass reason rule" : "Rule engine (demo)", opened, first, null, true);
        Instant t1 = opened.plus(Math.max(1, ChronoUnit.MINUTES.between(opened, closedAt) / 2), ChronoUnit.MINUTES);
        repo.addDecision(c.id(), "first", first, d1, n1, t1);
        repo.addLog(c.id(), t1, first, "decided", d1);
        repo.addLog(c.id(), t1.plusMillis(1), first, "second", second);
        repo.addDecision(c.id(), "second", second, d2, n2, closedAt);
        repo.addLog(c.id(), closedAt, second, "decided", d2);
        repo.addLog(c.id(), closedAt.plusMillis(1), second, "closed", d2);
        repo.update(c.id(), "review", "second", first, second, null, null);
        repo.close(c.id(), "second", d2, closedAt, "pending");
        bridge(c.id(), d2, second);
        service.seal(c.id());
    }

    /** A case where the first reviewer ruled a violation, so the privacy officer decided. */
    private void closedOfficer(String kind, String title, String subject, String patientRef, List<AuditEvent> evs, Instant opened, Instant closedAt,
                               String first, String d1, String n1, String officer, String n2) {
        Case c = service.open(kind, title, subject, patientRef, facilityOf(evs), exploreFor(patientRef, evs), ids(evs), "Rule engine (demo)", opened, first, null, true);
        Instant t1 = opened.plus(2, ChronoUnit.HOURS);
        repo.addDecision(c.id(), "first", first, d1, n1, t1);
        repo.addLog(c.id(), t1, first, "decided", d1);
        repo.addLog(c.id(), t1.plusMillis(1), first, "escalated", "violation");
        repo.addDecision(c.id(), "officer", officer, "violation", n2, closedAt);
        repo.addLog(c.id(), closedAt, officer, "decided", "violation");
        repo.addLog(c.id(), closedAt.plusMillis(1), officer, "closed", "violation");
        repo.update(c.id(), "review", "escalated", first, null, null, null);
        repo.close(c.id(), "escalated", "violation", closedAt, "pending");
        bridge(c.id(), "violation", officer);
        service.seal(c.id());
    }

    private void bridge(String caseId, String outcome, String user) {
        String status = "appropriate".equals(outcome) ? "reviewed" : "flagged";
        for (String ev : repo.eventIds(caseId)) {
            jdbc.update("DELETE FROM audit_review WHERE event_id=?", ev);
            jdbc.update("INSERT INTO audit_review (event_id, status, reviewer, reviewed_at, note) VALUES (?,?,?,?,?)", ev, status, user, java.sql.Timestamp.from(now), "[case " + caseId + "] " + outcome);
            jdbc.update("INSERT INTO audit_review_log (event_id, status, reviewer, reviewed_at, note) VALUES (?,?,?,?,?)", ev, status, user, java.sql.Timestamp.from(now), "[case " + caseId + "] " + outcome);
        }
    }

    private void draft(Case c, String decision, String note) {
        repo.update(c.id(), "review", "review", c.assignee(), null, decision, note);
    }

    /** The two reviewers chose different decisions: the case waits for the privacy officer. */
    private void escalated() {
        List<AuditEvent> evs = byAgent("Dr. M. Nadarajah", 7, ago(52));
        Case c = service.open("night", "Night access", "Dr. M. Nadarajah", null, facilityOf(evs), exploreFor(null, evs), ids(evs), "Night-access rule", ago(52), PRIYA, null, true);
        Instant t1 = ago(40), t2 = ago(30);
        repo.addDecision(c.id(), "first", PRIYA, "follow_up", "Seven records overnight with no admission recorded.", t1);
        repo.addLog(c.id(), t1, PRIYA, "decided", "follow_up");
        repo.addLog(c.id(), t1.plusMillis(1), PRIYA, "second", NIMAL);
        repo.addDecision(c.id(), "second", NIMAL, "appropriate", "He was on call that night.", t2);
        repo.addLog(c.id(), t2, NIMAL, "decided", "appropriate");
        repo.addLog(c.id(), t2.plusMillis(1), NIMAL, "escalated", "differ");
        repo.update(c.id(), "review", "escalated", PRIYA, NIMAL, null, null);
    }

    /** The first reviewer has decided; the signed-in demo admin is asked for the second opinion. */
    private void secondOpinion() {
        List<AuditEvent> evs = byAgent("Dr. R. Fernando", 62);
        Case c = service.open("volume", "High-volume access", "Dr. R. Fernando", null, "Device/hhims-kandy-01", exploreFor(null, evs), ids(evs), "High-volume rule", ago(4), NIMAL, null, true);
        Instant t1 = ago(3);
        repo.addDecision(c.id(), "first", NIMAL, "follow_up", "62 records in one hour is far above his usual 10. Ask Kandy TH.", t1);
        repo.addLog(c.id(), t1, NIMAL, "decided", "follow_up");
        repo.addLog(c.id(), t1.plusMillis(1), NIMAL, "second", PRIYA);
        repo.update(c.id(), "review", "second", NIMAL, PRIYA, null, null);
    }
}

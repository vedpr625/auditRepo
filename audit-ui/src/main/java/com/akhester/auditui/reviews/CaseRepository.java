package com.akhester.auditui.reviews;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Cases in the UI's own database (review_case and its event, decision and history tables). */
@Repository
public class CaseRepository {

    public record Case(String id, long seq, String kind, String title, String subject, String patientRef, String facility, String exploreQs,
                       String status, Instant openedAt, String openedBy, Instant dueAt, String assignee, String secondReviewer,
                       String draftDecision, String draftNote, String outcome, Instant closedAt, String fingerprint, String reopenOf, boolean demoSeed) {
        public boolean isClosed() { return "done".equals(status); }
        public boolean isOpen() { return !isClosed(); }
        /** Patient id without the "Patient/" prefix, or null. */
        public String patientId() { return patientRef == null ? null : (patientRef.startsWith("Patient/") ? patientRef.substring(8) : patientRef); }
    }

    public record Decision(String role, String reviewer, String decision, String note, Instant decidedAt) {}
    public record LogEntry(Instant at, String actor, String code, String arg) {}

    private static final String COLS = "id, seq, kind, title, subject, patient_ref, facility, explore_qs, status, opened_at, opened_by, due_at, assignee, second_reviewer, "
        + "draft_decision, draft_note, outcome, closed_at, fingerprint, reopen_of, demo_seed";

    private static Instant ts(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static final RowMapper<Case> CASE = (rs, i) -> new Case(rs.getString("id"), rs.getLong("seq"), rs.getString("kind"), rs.getString("title"),
        rs.getString("subject"), rs.getString("patient_ref"), rs.getString("facility"), rs.getString("explore_qs"), rs.getString("status"),
        ts(rs, "opened_at"), rs.getString("opened_by"), ts(rs, "due_at"), rs.getString("assignee"), rs.getString("second_reviewer"),
        rs.getString("draft_decision"), rs.getString("draft_note"), rs.getString("outcome"), ts(rs, "closed_at"), rs.getString("fingerprint"),
        rs.getString("reopen_of"), rs.getBoolean("demo_seed"));

    private final JdbcTemplate jdbc;

    public CaseRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static Timestamp t(Instant i) { return i == null ? null : Timestamp.from(i); }

    public long nextSeq() {
        Long m = jdbc.queryForObject("SELECT MAX(seq) FROM review_case", Long.class);
        return m == null ? 1031 : Math.max(1031, m + 1);
    }

    public void insert(Case c) {
        jdbc.update("INSERT INTO review_case (" + COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            c.id(), c.seq(), c.kind(), c.title(), c.subject(), c.patientRef(), c.facility(), c.exploreQs(), c.status(), t(c.openedAt()), c.openedBy(), t(c.dueAt()),
            c.assignee(), c.secondReviewer(), c.draftDecision(), c.draftNote(), c.outcome(), t(c.closedAt()), c.fingerprint(), c.reopenOf(), c.demoSeed());
    }

    public Optional<Case> find(String id) {
        return jdbc.query("SELECT " + COLS + " FROM review_case WHERE id=?", CASE, id).stream().findFirst();
    }

    public List<Case> all() {
        return jdbc.query("SELECT " + COLS + " FROM review_case ORDER BY opened_at", CASE);
    }

    public void addEvents(String caseId, Collection<String> eventIds) {
        for (String e : eventIds) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM review_case_event WHERE case_id=? AND event_id=?", Integer.class, caseId, e);
            if (n == null || n == 0) jdbc.update("INSERT INTO review_case_event (case_id, event_id) VALUES (?,?)", caseId, e);
        }
    }

    public List<String> eventIds(String caseId) {
        return jdbc.query("SELECT event_id FROM review_case_event WHERE case_id=? ORDER BY event_id", (rs, i) -> rs.getString(1), caseId);
    }

    public Map<String, Integer> eventCounts() {
        return jdbc.query("SELECT case_id, COUNT(*) FROM review_case_event GROUP BY case_id", (rs, i) -> Map.entry(rs.getString(1), rs.getInt(2)))
            .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** The newest case each of the given events belongs to (event id to case id). */
    public Map<String, String> caseOfEvents(Collection<String> eventIds) {
        if (eventIds.isEmpty()) return Map.of();
        String in = eventIds.stream().map(x -> "?").collect(Collectors.joining(","));
        return jdbc.query("SELECT e.event_id, e.case_id FROM review_case_event e JOIN review_case c ON c.id=e.case_id WHERE e.event_id IN (" + in + ") ORDER BY c.seq",
            (rs, i) -> Map.entry(rs.getString(1), rs.getString(2)), eventIds.toArray()).stream()
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b));
    }

    public void addDecision(String caseId, String role, String reviewer, String decision, String note, Instant at) {
        jdbc.update("INSERT INTO review_case_decision (case_id, role, reviewer, decision, note, decided_at) VALUES (?,?,?,?,?,?)",
            caseId, role, reviewer, decision, note, t(at));
    }

    public List<Decision> decisions(String caseId) {
        return jdbc.query("SELECT role, reviewer, decision, note, decided_at FROM review_case_decision WHERE case_id=? ORDER BY id",
            (rs, i) -> new Decision(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), ts(rs, "decided_at")), caseId);
    }

    public void addLog(String caseId, Instant at, String actor, String code, String arg) {
        jdbc.update("INSERT INTO review_case_log (case_id, at, actor, code, arg) VALUES (?,?,?,?,?)", caseId, t(at), actor, code, arg);
    }

    public List<LogEntry> log(String caseId) {
        return jdbc.query("SELECT at, actor, code, arg FROM review_case_log WHERE case_id=? ORDER BY at, id",
            (rs, i) -> new LogEntry(ts(rs, "at"), rs.getString(2), rs.getString(3), rs.getString(4)), caseId);
    }

    /** Changes the case only if it is still in the status the caller saw (two people cannot both win). Returns whether it changed. */
    public boolean update(String id, String expectStatus, String status, String assignee, String second, String draftDecision, String draftNote) {
        return jdbc.update("UPDATE review_case SET status=?, assignee=?, second_reviewer=?, draft_decision=?, draft_note=? WHERE id=? AND status=?",
            status, assignee, second, draftDecision, draftNote, id, expectStatus) == 1;
    }

    public boolean close(String id, String expectStatus, String outcome, Instant at, String fingerprint) {
        return jdbc.update("UPDATE review_case SET status='done', outcome=?, closed_at=?, fingerprint=?, draft_decision=NULL, draft_note=NULL WHERE id=? AND status=?",
            outcome, t(at), fingerprint, id, expectStatus) == 1;
    }

    public void setFingerprint(String id, String fp) { jdbc.update("UPDATE review_case SET fingerprint=? WHERE id=?", fp, id); }

    /** Reviewer names seen in cases and decisions (used when the console has no user directory). */
    public List<String> knownReviewers() {
        return jdbc.query("SELECT DISTINCT n FROM (SELECT assignee n FROM review_case UNION SELECT second_reviewer FROM review_case UNION SELECT reviewer FROM review_case_decision) x WHERE n IS NOT NULL ORDER BY n",
            (rs, i) -> rs.getString(1));
    }

    /** Cases that wait for this person: assigned to them, waiting for their second opinion, with the privacy officer if they are one, or unassigned (reviewers can take them). */
    public int countOpenFor(String user, boolean reviewer, boolean privacyOfficer) {
        Integer n = jdbc.queryForObject(
            "SELECT COUNT(*) FROM review_case WHERE (status='review' AND assignee=?) OR (status='second' AND second_reviewer=?) OR (status='escalated' AND ?) OR (status='new' AND ?)",
            Integer.class, user, user, privacyOfficer, reviewer);
        return n == null ? 0 : n;
    }

    public void deleteDemoSeed() {
        List<String> ids = jdbc.query("SELECT id FROM review_case WHERE demo_seed=TRUE", (rs, i) -> rs.getString(1));
        for (String id : ids) {
            jdbc.update("DELETE FROM review_case_event WHERE case_id=?", id);
            jdbc.update("DELETE FROM review_case_decision WHERE case_id=?", id);
            jdbc.update("DELETE FROM review_case_log WHERE case_id=?", id);
            jdbc.update("DELETE FROM review_case WHERE id=?", id);
        }
    }
}

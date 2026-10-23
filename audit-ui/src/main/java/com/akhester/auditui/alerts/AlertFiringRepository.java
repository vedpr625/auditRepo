package com.akhester.auditui.alerts;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Rows in alert_firing: each time a rule fired. */
@Repository
public class AlertFiringRepository {

    public record Firing(long id, String ruleId, Instant firedAt, String severity, String subject, String facility, Integer amount, String caseId, boolean markedNormal, String markedBy) {}

    private final JdbcTemplate jdbc;

    public AlertFiringRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static Firing map(java.sql.ResultSet rs) throws java.sql.SQLException {
        int a = rs.getInt("amount");
        return new Firing(rs.getLong("id"), rs.getString("rule_id"), rs.getTimestamp("fired_at").toInstant(), rs.getString("severity"), rs.getString("subject"),
            rs.getString("facility"), rs.wasNull() ? null : a, rs.getString("case_id"), rs.getBoolean("marked_normal"), rs.getString("marked_by"));
    }

    public void insert(String ruleId, Instant at, String severity, String subject, String facility, Integer amount, String caseId, boolean demo) {
        jdbc.update("INSERT INTO alert_firing (rule_id, fired_at, severity, subject, facility, amount, case_id, demo_seed) VALUES (?,?,?,?,?,?,?,?)",
            ruleId, Timestamp.from(at), severity, subject, facility, amount, caseId, demo);
    }

    public List<Firing> since(Instant from, int limit) {
        return jdbc.query("SELECT id, rule_id, fired_at, severity, subject, facility, amount, case_id, marked_normal, marked_by FROM alert_firing WHERE fired_at >= ? ORDER BY fired_at DESC LIMIT ?",
            (rs, i) -> map(rs), Timestamp.from(from), limit);
    }

    public Map<String, Integer> countsSince(Instant from) {
        return jdbc.query("SELECT rule_id, COUNT(*) FROM alert_firing WHERE fired_at >= ? GROUP BY rule_id", (rs, i) -> Map.entry(rs.getString(1), rs.getInt(2)), Timestamp.from(from))
            .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    public int countSince(Instant from) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM alert_firing WHERE fired_at >= ?", Integer.class, Timestamp.from(from));
        return n == null ? 0 : n;
    }

    public boolean markNormal(long id, String user) {
        return jdbc.update("UPDATE alert_firing SET marked_normal=TRUE, marked_by=? WHERE id=?", user, id) == 1;
    }

    public void deleteDemoSeed() { jdbc.update("DELETE FROM alert_firing WHERE demo_seed=TRUE"); }
}

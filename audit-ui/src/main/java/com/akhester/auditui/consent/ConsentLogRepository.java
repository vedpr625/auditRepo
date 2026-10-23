package com.akhester.auditui.consent;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConsentLogRepository {

    public record Entry(Instant at, String username, String patientRef, String role, String purpose, String decision, String reason) {}

    private final JdbcTemplate jdbc;

    public ConsentLogRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void add(String username, String patientRef, String role, String purpose, ConsentClient.Decision d) {
        jdbc.update("INSERT INTO consent_check_log(checked_at, username, patient_ref, actor_role, purpose, decision, reason, consent_id) VALUES (?,?,?,?,?,?,?,?)",
            Instant.now().atOffset(ZoneOffset.UTC), username, patientRef, role, purpose, d.result().name().toLowerCase(), d.reason(), d.consentId());
    }

    public List<Entry> recent(int limit) {
        return jdbc.query("SELECT checked_at, username, patient_ref, actor_role, purpose, decision, reason FROM consent_check_log ORDER BY id DESC LIMIT ?",
            (rs, i) -> new Entry(rs.getObject(1, OffsetDateTime.class).toInstant(), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7)), limit);
    }
}

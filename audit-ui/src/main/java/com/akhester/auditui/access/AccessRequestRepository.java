package com.akhester.auditui.access;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Requests to open a restricted patient record. */
@Repository
public class AccessRequestRepository {

    public record Request(long id, Instant at, String user, String patientRef, String note, String status) {}

    private final JdbcTemplate jdbc;

    public AccessRequestRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void add(String user, String patientRef, String note) {
        jdbc.update("INSERT INTO access_request (requested_at, username, patient_ref, note, status) VALUES (?,?,?,?,'pending')", Timestamp.from(Instant.now()), user, patientRef,
            note == null ? null : (note.length() > 500 ? note.substring(0, 500) : note));
    }

    public boolean hasPending(String user, String patientRef) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM access_request WHERE username=? AND patient_ref=? AND status='pending'", Integer.class, user, patientRef);
        return n != null && n > 0;
    }

    public List<Request> recent(int limit) {
        return jdbc.query("SELECT id, requested_at, username, patient_ref, note, status FROM access_request ORDER BY requested_at DESC, id DESC LIMIT ?",
            (rs, i) -> new Request(rs.getLong(1), rs.getTimestamp(2).toInstant(), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)), limit);
    }
}

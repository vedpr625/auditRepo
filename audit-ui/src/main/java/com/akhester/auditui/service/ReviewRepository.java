package com.akhester.auditui.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Review decisions in the UI's own database. Every change is also appended to audit_review_log. */
@Repository
public class ReviewRepository {

    public record Review(String eventId, String status, String reviewer, Instant reviewedAt, String note) {}

    private final JdbcTemplate jdbc;

    public ReviewRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public void save(String eventId, String status, String reviewer, String note) {
        Timestamp now = Timestamp.from(Instant.now());
        String n = (note == null || note.isBlank()) ? null : (note.length() > 2000 ? note.substring(0, 2000) : note);
        int updated = jdbc.update("UPDATE audit_review SET status=?, reviewer=?, reviewed_at=?, note=? WHERE event_id=?",
                status, reviewer, now, n, eventId);
        if (updated == 0) {
            jdbc.update("INSERT INTO audit_review (event_id, status, reviewer, reviewed_at, note) VALUES (?,?,?,?,?)",
                    eventId, status, reviewer, now, n);
        }
        jdbc.update("INSERT INTO audit_review_log (event_id, status, reviewer, reviewed_at, note) VALUES (?,?,?,?,?)",
                eventId, status, reviewer, now, n);
    }

    public Optional<Review> find(String eventId) {
        List<Review> l = jdbc.query("SELECT event_id, status, reviewer, reviewed_at, note FROM audit_review WHERE event_id=?",
                (rs, i) -> new Review(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getString(5)),
                eventId);
        return l.stream().findFirst();
    }

    /** Status per event id for the ids given (ids with no decision are absent = pending). */
    public Map<String, Review> findAll(List<String> ids) {
        if (ids.isEmpty()) return Map.of();
        String in = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        return jdbc.query("SELECT event_id, status, reviewer, reviewed_at, note FROM audit_review WHERE event_id IN (" + in + ")",
                (rs, i) -> new Review(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getString(5)),
                ids.toArray()).stream().collect(Collectors.toMap(Review::eventId, r -> r));
    }

    /** Newest decisions with the given status, at most {@code limit}. */
    public List<String> idsWithStatus(String status, int limit) {
        return jdbc.query("SELECT event_id FROM audit_review WHERE status=? ORDER BY reviewed_at DESC LIMIT ?",
                (rs, i) -> rs.getString(1), status, limit);
    }

    public List<Review> history(String eventId) {
        return jdbc.query("SELECT event_id, status, reviewer, reviewed_at, note FROM audit_review_log WHERE event_id=? ORDER BY id",
                (rs, i) -> new Review(rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getString(5)),
                eventId);
    }

    public int countWithStatus(String status) {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM audit_review WHERE status=?", Integer.class, status);
        return c == null ? 0 : c;
    }
}

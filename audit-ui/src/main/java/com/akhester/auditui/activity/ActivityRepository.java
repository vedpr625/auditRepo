package com.akhester.auditui.activity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The console's own table of what people did in it (the activity log). */
@Repository
public class ActivityRepository {

    public record Entry(long id, Instant at, String username, String role, String kind, String summary, String detail, String patientRef, String chesterId, String chesterState) {}

    private final JdbcTemplate jdbc;

    public ActivityRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String COLS = "id, at, username, role, kind, summary, detail, patient_ref, chester_id, chester_state";

    public long insert(Instant at, String user, String role, String kind, String summary, String detail, String patientRef) {
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("INSERT INTO console_activity (at, username, role, kind, summary, detail, patient_ref) VALUES (?,?,?,?,?,?,?)", new String[]{"ID"});
            ps.setTimestamp(1, Timestamp.from(at)); ps.setString(2, user); ps.setString(3, role); ps.setString(4, kind);
            ps.setString(5, clip(summary, 500)); ps.setString(6, clip(detail, 500)); ps.setString(7, patientRef);
            return ps;
        }, keys);
        return keys.getKey() == null ? -1 : keys.getKey().longValue();
    }

    public void setChester(long id, String chesterId, String state) {
        jdbc.update("UPDATE console_activity SET chester_id=?, chester_state=? WHERE id=?", chesterId, state, id);
    }

    /** Newest first. kind null = all kinds. */
    public List<Entry> recent(String kind, int limit, int offset) {
        String where = kind == null ? "" : " WHERE kind=?";
        Object[] args = kind == null ? new Object[]{limit, offset} : new Object[]{kind, limit, offset};
        return jdbc.query("SELECT " + COLS + " FROM console_activity" + where + " ORDER BY at DESC, id DESC LIMIT ? OFFSET ?", (rs, i) -> new Entry(rs.getLong("id"),
            rs.getTimestamp("at").toInstant(), rs.getString("username"), rs.getString("role"), rs.getString("kind"), rs.getString("summary"), rs.getString("detail"),
            rs.getString("patient_ref"), rs.getString("chester_id"), rs.getString("chester_state")), args);
    }

    public int count(String kind) {
        Integer n = kind == null ? jdbc.queryForObject("SELECT COUNT(*) FROM console_activity", Integer.class)
            : jdbc.queryForObject("SELECT COUNT(*) FROM console_activity WHERE kind=?", Integer.class, kind);
        return n == null ? 0 : n;
    }

    /** True when this person already did exactly this a moment ago (a search typed letter by letter is one search). */
    public boolean repeated(String user, String kind, String summary, String detail, Instant since) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM console_activity WHERE username=? AND kind=? AND summary=? AND COALESCE(detail,'')=? AND at>=?", Integer.class,
            user, kind, summary, detail == null ? "" : clip(detail, 500), Timestamp.from(since));
        return n != null && n > 0;
    }

    public boolean noticeSeen(String user) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM console_notice_ack WHERE username=?", Integer.class, user);
        return n != null && n > 0;
    }

    public void ackNotice(String user) {
        if (!noticeSeen(user)) jdbc.update("INSERT INTO console_notice_ack (username, acked_at) VALUES (?,?)", user, Timestamp.from(Instant.now()));
    }

    static String clip(String s, int n) { return s == null ? null : (s.length() <= n ? s : s.substring(0, n - 1) + "…"); }
}

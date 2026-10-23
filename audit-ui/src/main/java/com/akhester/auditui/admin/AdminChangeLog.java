package com.akhester.auditui.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** What administrators changed in the console. Real changes made here are recorded; rows marked demo are made up. */
@Repository
public class AdminChangeLog {

    public record Entry(Instant at, String who, String what, boolean demo) {}

    private final JdbcTemplate jdbc;

    public AdminChangeLog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void add(String who, String what, boolean demo, Instant at) {
        String w = what.length() > 500 ? what.substring(0, 500) : what;
        jdbc.update("INSERT INTO admin_change_log(changed_at, who, what, demo) VALUES (?,?,?,?)", Timestamp.from(at), who == null ? "?" : who, w, demo);
    }

    public List<Entry> recent(int limit) {
        return jdbc.query("SELECT changed_at, who, what, demo FROM admin_change_log ORDER BY changed_at DESC, id DESC LIMIT ?",
            (rs, i) -> new Entry(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3), rs.getBoolean(4)), limit);
    }

    public int count() {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM admin_change_log", Integer.class);
        return c == null ? 0 : c;
    }
}

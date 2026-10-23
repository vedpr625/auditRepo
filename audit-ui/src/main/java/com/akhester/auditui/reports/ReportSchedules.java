package com.akhester.auditui.reports;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Report schedules: demo state only. Nothing is sent by this build. Also the log of report downloads. */
@Repository
public class ReportSchedules {

    public record Schedule(long id, String reportKey, String cadence, String recipients, boolean demo) {}

    private final JdbcTemplate jdbc;

    public ReportSchedules(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Schedule> all() {
        return jdbc.query("SELECT id, report_key, cadence, recipients, demo FROM report_schedule ORDER BY id",
            (rs, i) -> new Schedule(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5)));
    }

    public void add(String key, String cadence, String recipients, String by, boolean demo) {
        jdbc.update("INSERT INTO report_schedule(report_key, cadence, recipients, created_by, demo) VALUES (?,?,?,?,?)", key, cadence, recipients, by, demo);
    }

    public void logDownload(String user, String key, String format, String params, int rows) {
        String p = params == null ? null : (params.length() > 1000 ? params.substring(0, 1000) : params);
        jdbc.update("INSERT INTO report_download_log(downloaded_at, username, report_key, format, params, row_count) VALUES (?,?,?,?,?,?)",
            java.sql.Timestamp.from(java.time.Instant.now()), user == null ? "?" : user, key, format, p, rows);
    }
}

package com.akhester.auditui.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Sending systems (Keycloak client ids seen on records) and the display name an admin gave each. */
@Repository
public class SendingSystemRepository {

    public record System(String clientId, String displayName, String note, Instant firstSeen, Instant lastSeen) {
        public String label() { return displayName == null || displayName.isBlank() ? clientId : displayName; }
    }

    private final JdbcTemplate jdbc;

    public SendingSystemRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<System> all() {
        return jdbc.query("SELECT client_id, display_name, note, first_seen, last_seen FROM sending_system ORDER BY last_seen DESC, client_id",
            (rs, i) -> new System(rs.getString(1), rs.getString(2), rs.getString(3),
                rs.getObject(4, OffsetDateTime.class).toInstant(), rs.getObject(5, OffsetDateTime.class).toInstant()));
    }

    /** Display names by client id, for showing on other pages. */
    public Map<String, String> names() {
        Map<String, String> m = new java.util.HashMap<>();
        for (System s : all()) m.put(s.clientId(), s.label());
        return m;
    }

    /** Records that a client id was seen at the given time. Keeps the earliest first_seen and the latest last_seen. */
    public void seen(String clientId, Instant at, String by) {
        if (clientId == null || clientId.isBlank() || clientId.length() > 255) return;
        OffsetDateTime t = at.atOffset(ZoneOffset.UTC);
        int n = jdbc.update("UPDATE sending_system SET last_seen = CASE WHEN last_seen < ? THEN ? ELSE last_seen END, "
            + "first_seen = CASE WHEN first_seen > ? THEN ? ELSE first_seen END WHERE client_id = ?", t, t, t, t, clientId);
        if (n == 0) {
            try {
                jdbc.update("INSERT INTO sending_system(client_id, first_seen, last_seen, updated_by) VALUES (?,?,?,?)", clientId, t, t, by);
            } catch (org.springframework.dao.DuplicateKeyException ignored) { /* another request inserted it first */ }
        }
    }

    public void rename(String clientId, String displayName, String note, String by) {
        jdbc.update("UPDATE sending_system SET display_name = ?, note = ?, updated_by = ? WHERE client_id = ?",
            blankToNull(displayName, 120), blankToNull(note, 255), by, clientId);
    }

    private static String blankToNull(String s, int max) {
        if (s == null) return null;
        String t = s.strip().replaceAll("\\p{Cntrl}", "");
        if (t.isEmpty()) return null;
        return t.length() > max ? t.substring(0, max) : t;
    }
}

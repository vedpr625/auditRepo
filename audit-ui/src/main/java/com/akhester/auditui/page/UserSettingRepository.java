package com.akhester.auditui.page;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Small per-person choices that do not belong in user_pref (which emails a person wants). */
@Repository
public class UserSettingRepository {

    /** The email choices on the Settings screen, with their defaults (Planned: no email is sent yet, the choice is stored). */
    public static final Map<String, Boolean> NOTIFY = new java.util.LinkedHashMap<>();
    static {
        NOTIFY.put("notify.assigned", true);
        NOTIFY.put("notify.second", true);
        NOTIFY.put("notify.closed", true);
        NOTIFY.put("notify.alert", false);
        NOTIFY.put("notify.daily", false);
    }

    private final JdbcTemplate jdbc;

    public UserSettingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** All of the person's stored choices. */
    public Map<String, String> all(String user) {
        Map<String, String> m = new HashMap<>();
        if (user == null) return m;
        jdbc.query("SELECT setting_key, setting_value FROM user_setting WHERE username=?", rs -> { m.put(rs.getString(1), rs.getString(2)); }, user);
        return m;
    }

    /** Each email choice, with the default where the person never chose. */
    public Map<String, Boolean> notifications(String user) {
        Map<String, String> stored = all(user);
        Map<String, Boolean> out = new java.util.LinkedHashMap<>();
        NOTIFY.forEach((k, d) -> out.put(k, stored.containsKey(k) ? "true".equals(stored.get(k)) : d));
        return out;
    }

    @Transactional
    public void set(String user, String key, String value) {
        Timestamp now = Timestamp.from(Instant.now());
        int n = jdbc.update("UPDATE user_setting SET setting_value=?, updated_at=? WHERE username=? AND setting_key=?", value, now, user, key);
        if (n == 0) jdbc.update("INSERT INTO user_setting (username, setting_key, setting_value, updated_at) VALUES (?,?,?,?)", user, key, value, now);
    }
}

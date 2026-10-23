package com.akhester.auditui.page;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Small administrator-editable settings (key/value). */
@Repository
public class AppSettingRepository {

    public static final String REGISTRY_BASE_URL = "registry.base-url";

    private final JdbcTemplate jdbc;

    public AppSettingRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<String> get(String key) {
        return jdbc.query("SELECT setting_value FROM app_setting WHERE setting_key=?", (rs, i) -> rs.getString(1), key)
                .stream().findFirst().filter(v -> v != null && !v.isBlank());
    }

    @Transactional
    public void set(String key, String value, String user) {
        Timestamp now = Timestamp.from(Instant.now());
        int n = jdbc.update("UPDATE app_setting SET setting_value=?, updated_by=?, updated_at=? WHERE setting_key=?", value, user, now, key);
        if (n == 0) jdbc.update("INSERT INTO app_setting (setting_key, setting_value, updated_by, updated_at) VALUES (?,?,?,?)", key, value, user, now);
    }
}

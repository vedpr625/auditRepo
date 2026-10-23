package com.akhester.auditui.prefs;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Display preferences in the UI's own database. */
@Repository
public class UserPrefsRepository {

    private final JdbcTemplate jdbc;

    public UserPrefsRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<UserPrefs> find(String username) {
        return jdbc.query("SELECT language, time_zone, time_display, clock24, show_utc, theme, density, accent FROM user_pref WHERE username=?",
                (rs, i) -> new UserPrefs(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                        rs.getBoolean(5), rs.getString(6), rs.getString(7), rs.getString(8)),
                username).stream().findFirst();
    }

    @Transactional
    public void save(String username, UserPrefs p) {
        p.checked();
        Timestamp now = Timestamp.from(Instant.now());
        int n = jdbc.update("UPDATE user_pref SET language=?, time_zone=?, time_display=?, clock24=?, show_utc=?, theme=?, density=?, accent=?, updated_at=? WHERE username=?",
                p.language(), p.timeZone(), p.timeDisplay(), p.clock24(), p.showUtc(), p.theme(), p.density(), p.accent(), now, username);
        if (n == 0) {
            jdbc.update("INSERT INTO user_pref (username, language, time_zone, time_display, clock24, show_utc, theme, density, accent, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                    username, p.language(), p.timeZone(), p.timeDisplay(), p.clock24(), p.showUtc(), p.theme(), p.density(), p.accent(), now);
        }
    }
}

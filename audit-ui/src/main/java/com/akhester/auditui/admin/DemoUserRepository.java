package com.akhester.auditui.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** DEMONSTRATION ONLY: the made-up user list behind the Admin Users tab. It records changes; it does not change who can sign in. */
@Repository
public class DemoUserRepository {

    public record User(String username, String displayName, String email, String role, boolean enabled, boolean invited, Instant createdAt, Instant pwResetAt) {}

    public static final List<String> ROLES = List.of("AUDIT_ADMIN", "MINISTRY_AUDITOR", "PRIVACY_OFFICER", "FACILITY_AUDITOR");
    private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public DemoUserRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static User map(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp reset = rs.getTimestamp(8);
        return new User(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getBoolean(6), rs.getTimestamp(7).toInstant(), reset == null ? null : reset.toInstant());
    }

    public List<User> all() {
        return jdbc.query("SELECT username, display_name, email, role, enabled, invited, created_at, pw_reset_at FROM demo_user ORDER BY enabled DESC, username", (rs, i) -> map(rs));
    }

    public User find(String username) {
        List<User> l = jdbc.query("SELECT username, display_name, email, role, enabled, invited, created_at, pw_reset_at FROM demo_user WHERE username = ?", (rs, i) -> map(rs), username);
        return l.isEmpty() ? null : l.get(0);
    }

    public int count() {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM demo_user", Integer.class);
        return c == null ? 0 : c;
    }

    public void add(String username, String name, String email, String role, boolean invited, Instant at) {
        jdbc.update("INSERT INTO demo_user(username, display_name, email, role, enabled, invited, created_at) VALUES (?,?,?,?,TRUE,?,?)", username, name, email, role, invited, Timestamp.from(at));
    }

    public boolean setRole(String username, String role) { return jdbc.update("UPDATE demo_user SET role = ? WHERE username = ?", role, username) > 0; }

    public boolean setEnabled(String username, boolean enabled) { return jdbc.update("UPDATE demo_user SET enabled = ? WHERE username = ?", enabled, username) > 0; }

    /** Makes a one-time password, stores only its SHA-256, and returns the plain text once so the screen can show it. */
    public String resetPassword(String username, Instant at) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        String plain = b.toString();
        int n = jdbc.update("UPDATE demo_user SET pw_hash = ?, pw_reset_at = ? WHERE username = ?", sha(plain), Timestamp.from(at), username);
        return n > 0 ? plain : null;
    }

    private static String sha(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

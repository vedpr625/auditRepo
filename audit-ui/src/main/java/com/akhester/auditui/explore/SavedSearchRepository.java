package com.akhester.auditui.explore;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

/** Searches a person keeps from Explore. Each is the Explore query string (only known filter names are kept), stored per person. */
@Repository
public class SavedSearchRepository {

    /** The filter names a saved search may hold (never page, size or the open panel). */
    public static final Set<String> KEYS = Set.of("tab", "q", "purpose", "outcome", "result", "agent", "facility", "encounter", "department", "client", "action",
        "dateFrom", "dateTo", "range", "sort", "groupBy", "breakGlass", "incomplete", "link");
    public static final int MAX_PER_USER = 30;

    public record Saved(long id, String name, String query, Instant createdAt) {
        /** The query as readable lines, "key=value" each, for Settings and the Try row. */
        public List<String> lines() {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, String> e : parse(query).entrySet()) {
                if ("range".equals(e.getKey()) && "24h".equals(e.getValue()) && parse(query).size() == 1) continue;
                out.add(e.getKey() + ("result".equals(e.getKey()) && "failed".equals(e.getValue()) ? "!=ok" : "=" + e.getValue()));
            }
            return out;
        }
    }

    private final JdbcTemplate jdbc;

    public SavedSearchRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static Map<String, String> parse(String q) {
        Map<String, String> m = new LinkedHashMap<>();
        if (q == null) return m;
        String s = q.startsWith("?") ? q.substring(1) : q;
        for (String part : s.split("&")) {
            if (part.isEmpty()) continue;
            int i = part.indexOf('=');
            String k = URLDecoder.decode(i < 0 ? part : part.substring(0, i), StandardCharsets.UTF_8);
            String v = i < 0 ? "" : URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8);
            if (KEYS.contains(k) && !v.isBlank() && v.length() <= 200) m.put(k, v.strip());
        }
        return m;
    }

    static String canonical(Map<String, String> m) {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<String, String> e : m.entrySet()) {
            if (b.length() > 0) b.append('&');
            b.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return b.toString();
    }

    /** Stores a search; throws IllegalArgumentException when the name or the query is not usable, or the person has too many. */
    public long add(String user, String name, String query) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 120) throw new IllegalArgumentException("name");
        for (int i = 0; i < n.length(); i++) if (Character.isISOControl(n.charAt(i))) throw new IllegalArgumentException("name");
        Map<String, String> m = parse(query);
        if (m.isEmpty()) throw new IllegalArgumentException("query");
        String q = canonical(m);
        if (q.length() > 600) throw new IllegalArgumentException("query");
        Integer have = jdbc.queryForObject("SELECT COUNT(*) FROM saved_search WHERE username=?", Integer.class, user);
        if (have != null && have >= MAX_PER_USER) throw new IllegalArgumentException("limit");
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement("INSERT INTO saved_search (username, name, query, created_at) VALUES (?,?,?,?)", new String[]{"id"});
            ps.setString(1, user); ps.setString(2, n); ps.setString(3, q); ps.setTimestamp(4, Timestamp.from(Instant.now()));
            return ps;
        }, kh);
        Number k = kh.getKey();
        return k == null ? 0 : k.longValue();
    }

    public List<Saved> forUser(String user) {
        if (user == null) return List.of();
        return jdbc.query("SELECT id, name, query, created_at FROM saved_search WHERE username=? ORDER BY created_at, id",
            (rs, i) -> new Saved(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant()), user);
    }

    /** Removes one of the person's own searches. Returns true when something was removed. */
    public boolean delete(String user, long id) {
        return jdbc.update("DELETE FROM saved_search WHERE id=? AND username=?", id, user) > 0;
    }
}

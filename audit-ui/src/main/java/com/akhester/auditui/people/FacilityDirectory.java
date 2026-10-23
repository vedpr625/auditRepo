package com.akhester.auditui.people;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Facilities (the "source" reference on records) with readable names. Names come from the facility table; unknown references show as sent. */
@Repository
public class FacilityDirectory {

    public record Facility(String ref, String name, String district, String kind, boolean demo, boolean listed) {}

    private final JdbcTemplate jdbc;

    public FacilityDirectory(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<Facility> listed() {
        return jdbc.query("SELECT ref, name, district, kind, demo FROM facility ORDER BY name",
            (rs, i) -> new Facility(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), true));
    }

    /** Listed facilities plus any other reference seen on records (shown as sent, marked not listed). */
    public List<Facility> withSeen(Collection<String> seen) {
        Map<String, Facility> m = new LinkedHashMap<>();
        for (Facility f : listed()) m.put(f.ref(), f);
        if (seen != null) for (String r : seen) if (r != null && !r.equals("—") && !m.containsKey(r)) m.put(r, new Facility(r, tail(r), null, null, false, false));
        return new ArrayList<>(m.values());
    }

    public Map<String, String> names() {
        Map<String, String> m = new java.util.HashMap<>();
        for (Facility f : listed()) m.put(f.ref(), f.name());
        return m;
    }

    public void add(String ref, String name, String district, String kind, boolean demo) {
        jdbc.update("INSERT INTO facility(ref, name, district, kind, demo) VALUES (?,?,?,?,?)", ref, name, district, kind, demo);
    }

    public static String tail(String ref) {
        if (ref == null) return "—";
        int i = ref.indexOf('/');
        return i >= 0 ? ref.substring(i + 1) : ref;
    }

    public static String nameIn(Map<String, String> names, String ref) { return names.getOrDefault(ref, tail(ref)); }
}

package com.akhester.auditui.people;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.prefs.PrefsContext;
import com.akhester.auditui.service.AuditEventService;

/**
 * Who and which patients appear in the newest records this person may see. Chester has no "list the people" call, so this reads the newest
 * {@value #SCAN_LIMIT} records (Chester applies the person's own access) and counts them. Cached for a minute per signed-in person.
 */
@Service
public class PeopleIndex {

    public static final int SCAN_LIMIT = 2000;
    private static final long TTL_MS = 60_000;

    public record Person(String ref, String name, String kind, String role, String facility, int events, int failed, int breakGlass, Instant last) {}
    public record PatientEntry(String id, int events, int people, int failed, int breakGlass, Instant last) {}
    public record Snapshot(List<Person> people, List<PatientEntry> patients, Set<String> facilities, int scanned, Instant oldest, Instant newest, boolean truncated, String error) {}

    private record Cached(long at, Snapshot s) {}

    private final AuditEventService audit;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public PeopleIndex(AuditEventService audit) { this.audit = audit; }

    public Snapshot snapshot() {
        String user = String.valueOf(PrefsContext.username());
        Cached c = cache.get(user);
        if (c != null && System.currentTimeMillis() - c.at < TTL_MS && c.s.error() == null) return c.s;
        Snapshot s = scan();
        if (cache.size() > 200) cache.clear();
        cache.put(user, new Cached(System.currentTimeMillis(), s));
        return s;
    }

    private static final class P {
        String ref, name, kind, role; int events, failed, btg; Instant last; Map<String, Integer> fac = new LinkedHashMap<>();
    }
    private static final class Pt {
        String id; int events, failed, btg; Instant last; Set<String> people = new HashSet<>();
    }

    private Snapshot scan() {
        Map<String, P> people = new LinkedHashMap<>();
        Map<String, Pt> pats = new LinkedHashMap<>();
        Set<String> facs = new TreeSet<>();
        int scanned = 0;
        Instant oldest = null, newest = null;
        boolean truncated = false;
        try {
            int offset = 0;
            while (offset < SCAN_LIMIT) {
                List<AuditRow> rows = audit.pageWhere(Map.of(), 100, offset, "-date");
                for (AuditRow r : rows) {
                    scanned++;
                    Instant t = r.getRecordedInstant();
                    if (t != null) { if (newest == null || t.isAfter(newest)) newest = t; if (oldest == null || t.isBefore(oldest)) oldest = t; }
                    boolean failed = r.getOutcomeCode() != null && !"0".equals(r.getOutcomeCode());
                    String key = r.getAgentRef() != null ? r.getAgentRef() : "name:" + r.getAgentName();
                    P p = people.computeIfAbsent(key, k -> {
                        P n = new P(); n.ref = r.getAgentRef(); n.name = r.getAgentName(); n.kind = Who.kind(r.getAgentRef()); n.role = Who.role(r.getAgentRef(), r.getAgentName()); return n; });
                    p.events++; if (failed) p.failed++; if (r.isBreakGlass()) p.btg++;
                    if (p.last == null || (t != null && t.isAfter(p.last))) p.last = t;
                    if (r.getFacility() != null && !"—".equals(r.getFacility())) { p.fac.merge(r.getFacility(), 1, Integer::sum); facs.add(r.getFacility()); }
                    String pr = r.getPatientRef();
                    if (pr != null && pr.startsWith("Patient/")) {
                        Pt x = pats.computeIfAbsent(pr.substring(8), k -> { Pt n = new Pt(); n.id = k; return n; });
                        x.events++; if (failed) x.failed++; if (r.isBreakGlass()) x.btg++;
                        if (x.last == null || (t != null && t.isAfter(x.last))) x.last = t;
                        x.people.add(key);
                    }
                }
                offset += rows.size();
                if (rows.size() < 100) break;
            }
            if (offset >= SCAN_LIMIT) truncated = audit.countWhere(Map.of()) > scanned;
        } catch (RuntimeException e) {
            return new Snapshot(List.of(), List.of(), Set.of(), 0, null, null, false, "chester");
        }
        List<Person> pl = new ArrayList<>();
        for (P p : people.values()) {
            String top = p.fac.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
            pl.add(new Person(p.ref, p.name, p.kind, p.role, top, p.events, p.failed, p.btg, p.last));
        }
        pl.sort(Comparator.comparingInt(Person::events).reversed().thenComparing(x -> String.valueOf(x.name())));
        List<PatientEntry> tl = new ArrayList<>();
        for (Pt x : pats.values()) tl.add(new PatientEntry(x.id, x.events, x.people.size(), x.failed, x.btg, x.last));
        tl.sort(Comparator.comparingInt(PatientEntry::events).reversed().thenComparing(PatientEntry::id));
        return new Snapshot(pl, tl, facs, scanned, oldest, newest, truncated, null);
    }
}

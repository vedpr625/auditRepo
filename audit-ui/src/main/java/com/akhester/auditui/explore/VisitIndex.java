package com.akhester.auditui.explore;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.prefs.PrefsContext;
import com.akhester.auditui.service.AuditEventService;

/** Visits (Encounter numbers) seen on the newest records this person may see. Read once a minute per person, like the people index. */
@Service
public class VisitIndex {

    private static final int SCAN = 1000;
    private static final long TTL_MS = 60_000;

    public record Visit(String id, String patientId, String facility, String department, int events, Instant last) {}
    private record Cached(long at, List<Visit> visits) {}

    private final AuditEventService audit;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    public VisitIndex(AuditEventService audit) { this.audit = audit; }

    public List<Visit> visits() {
        String user = String.valueOf(PrefsContext.username());
        Cached c = cache.get(user);
        if (c != null && System.currentTimeMillis() - c.at < TTL_MS) return c.visits;
        List<Visit> v = scan();
        if (cache.size() > 200) cache.clear();
        cache.put(user, new Cached(System.currentTimeMillis(), v));
        return v;
    }

    private static final class V { String id, patient, facility, dept; int events; Instant last; }

    private List<Visit> scan() {
        Map<String, V> m = new LinkedHashMap<>();
        try {
            int off = 0;
            while (off < SCAN) {
                List<AuditRow> rows = audit.pageWhere(Map.of(), 100, off, "-date");
                for (AuditRow r : rows) {
                    String e = r.getEncounterRef();
                    if (e == null || !e.startsWith("Encounter/")) continue;
                    V v = m.computeIfAbsent(e.substring(10), k -> { V n = new V(); n.id = k; return n; });
                    v.events++;
                    if (v.patient == null && r.getPatientRef() != null && r.getPatientRef().startsWith("Patient/")) v.patient = r.getPatientRef().substring(8);
                    if (v.facility == null) v.facility = r.getFacility();
                    if (v.dept == null) v.dept = r.getDepartment();
                    if (v.last == null || (r.getRecordedInstant() != null && r.getRecordedInstant().isAfter(v.last))) v.last = r.getRecordedInstant();
                }
                off += rows.size();
                if (rows.size() < 100) break;
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        List<Visit> out = new ArrayList<>();
        for (V v : m.values()) out.add(new Visit(v.id, v.patient, v.facility, v.dept, v.events, v.last));
        out.sort(Comparator.comparing((Visit x) -> x.last() == null ? Instant.EPOCH : x.last()).reversed());
        return out;
    }
}

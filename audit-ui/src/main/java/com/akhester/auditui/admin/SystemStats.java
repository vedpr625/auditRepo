package com.akhester.auditui.admin;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.service.AuditEventService;

/** Live numbers for a sending system, asked of Chester (so they follow the signed-in person's own access). */
@Service
public class SystemStats {

    public record Summary(String clientId, int events30, int failed7, Instant last, String status, long days) {}
    public record Day(LocalDate day, int ok, int failed) {}

    static final long QUIET_HOURS = 2, GONE_DAYS = 7, FAILING_AT = 10;

    private final AuditEventService audit;

    public SystemStats(AuditEventService audit) { this.audit = audit; }

    private Map<String, List<String>> tag(String clientId) { return Map.of("_tag", List.of(audit.submittingClientTag(clientId))); }

    private static Map<String, List<String>> plus(Map<String, List<String>> base, String k, String... v) {
        Map<String, List<String>> q = new java.util.LinkedHashMap<>(base);
        q.put(k, new ArrayList<>(List.of(v)));
        return q;
    }

    /** status: active | failing | quiet | gone | none | unknown (Chester did not answer). */
    public Summary summary(String clientId) {
        try {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            int e30 = audit.countWhere(plus(tag(clientId), "date", "ge" + today.minusDays(29)));
            int f7 = audit.countWhere(plus(plus(tag(clientId), "date", "ge" + today.minusDays(6)), "outcome", "4,8,12"));
            List<AuditRow> last = audit.pageWhere(tag(clientId), 1, 0, "-date");
            Instant at = last.isEmpty() ? null : last.get(0).getRecordedInstant();
            return new Summary(clientId, e30, f7, at, status(at, f7, Instant.now()), at == null ? 0 : Duration.between(at, Instant.now()).toDays());
        } catch (RuntimeException e) {
            return new Summary(clientId, 0, 0, null, "unknown", 0);
        }
    }

    static String status(Instant last, int failed7, Instant now) {
        if (last == null) return "none";
        Duration age = Duration.between(last, now);
        if (age.toDays() >= GONE_DAYS) return "gone";
        if (age.toHours() >= QUIET_HOURS) return "quiet";
        if (failed7 >= FAILING_AT) return "failing";
        return "active";
    }

    /** Records per day for the last {@code days} days (UTC days), oldest first. */
    public List<Day> perDay(String clientId, int days) {
        List<Day> out = new ArrayList<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        try {
            for (int i = days - 1; i >= 0; i--) {
                LocalDate d = today.minusDays(i);
                Map<String, List<String>> q = plus(tag(clientId), "date", "eq" + d);
                int all = audit.countWhere(q), bad = audit.countWhere(plus(q, "outcome", "4,8,12"));
                out.add(new Day(d, all - bad, bad));
            }
        } catch (RuntimeException e) { return List.of(); }
        return out;
    }

    public List<AuditRow> latest(String clientId, int n) {
        try { return audit.pageWhere(tag(clientId), n, 0, "-date"); } catch (RuntimeException e) { return List.of(); }
    }

    public Set<String> facilities(List<AuditRow> rows) {
        Set<String> s = new LinkedHashSet<>();
        for (AuditRow r : rows) if (r.getFacility() != null && !"—".equals(r.getFacility())) s.add(r.getFacility());
        return s;
    }
}

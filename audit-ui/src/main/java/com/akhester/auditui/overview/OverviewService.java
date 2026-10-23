package com.akhester.auditui.overview;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.akhester.auditui.admin.SendingSystemRepository;
import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.prefs.PrefsContext;
import com.akhester.auditui.service.AuditEventService;

/**
 * Numbers for the Overview page. Chester shows each person only what they may see, so every cache entry belongs to one
 * person and is never shared. Entries live about a minute (audit.ui.overview-cache-seconds).
 */
@Service
public class OverviewService {

    public record Kpis(int events24h, int failed24h, int breakGlass24h, long overdue, long warning, boolean slaTruncated, String error) {}
    public record Hour(Instant from, int count) {}
    public record SystemRow(String clientId, String label, int count24h) {}
    public record QueueItem(AuditRow row, String why) {}

    private record Entry<T>(Instant at, T value) {}

    private final AuditEventService audit;
    private final SendingSystemRepository systems;
    private final long ttlSeconds;
    private final Map<String, Entry<Kpis>> kpiCache = new ConcurrentHashMap<>();
    private final Map<String, Entry<List<Hour>>> hourCache = new ConcurrentHashMap<>();
    private final Map<String, Entry<List<QueueItem>>> queueCache = new ConcurrentHashMap<>();
    private final Map<String, Entry<List<SystemRow>>> systemCache = new ConcurrentHashMap<>();

    public OverviewService(AuditEventService audit, SendingSystemRepository systems,
                           @Value("${audit.ui.overview-cache-seconds:60}") long ttlSeconds) {
        this.audit = audit;
        this.systems = systems;
        this.ttlSeconds = ttlSeconds;
    }

    private <T> boolean fresh(Entry<T> e) { return e != null && Duration.between(e.at(), Instant.now()).getSeconds() < ttlSeconds; }

    private static String user() {
        String u = PrefsContext.username();
        return u == null ? "?" : u;
    }

    private void trim(Map<String, ?> cache) { if (cache.size() > 500) cache.clear(); }

    private static Map<String, List<String>> since(Instant from) {
        Map<String, List<String>> p = new LinkedHashMap<>();
        p.put("date", new ArrayList<>(List.of("ge" + from.truncatedTo(ChronoUnit.SECONDS))));
        return p;
    }

    public Kpis kpis() {
        String u = user();
        Entry<Kpis> hit = kpiCache.get(u);
        if (fresh(hit)) return hit.value();
        Kpis k;
        try {
            Instant from = Instant.now().minus(24, ChronoUnit.HOURS);
            int all = audit.countWhere(since(from));
            Map<String, List<String>> f = since(from);
            f.put("outcome", List.of("4,8,12"));
            int failed = audit.countWhere(f);
            Map<String, List<String>> bg = since(from);
            bg.put("break-glass", List.of("BTG"));
            int btg = audit.countWhere(bg);
            AuditEventService.SlaCounts sla = audit.slaCounts();
            k = new Kpis(all, failed, btg, sla.overdue(), sla.warning(), sla.truncated(), sla.error());
        } catch (RuntimeException e) {
            k = new Kpis(0, 0, 0, 0, 0, false, "unreachable");
        }
        if (k.error() == null) { trim(kpiCache); kpiCache.put(u, new Entry<>(Instant.now(), k)); }
        return k;
    }

    /** Records per hour for the last 24 hours, oldest first. A count of -1 means Chester did not answer for that hour. */
    public List<Hour> hours(java.time.ZoneId zone) {
        String u = user() + "|" + zone;
        Entry<List<Hour>> hit = hourCache.get(u);
        if (fresh(hit)) return hit.value();
        // hours start on the clock hour of the person's own zone (in +05:30 that is half past in UTC)
        Instant top = java.time.ZonedDateTime.now(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant();
        List<Hour> out = new ArrayList<>();
        boolean failed = false;
        for (int i = 23; i >= 0 && !failed; i--) {
            Instant a = top.minus(i + 1L, ChronoUnit.HOURS), b = top.minus(i, ChronoUnit.HOURS);
            try {
                Map<String, List<String>> p = new LinkedHashMap<>();
                p.put("date", new ArrayList<>(List.of("ge" + a, "lt" + b)));
                out.add(new Hour(a, audit.countWhere(p)));
            } catch (RuntimeException e) {
                failed = true;
            }
        }
        if (failed) return List.of();
        trim(hourCache);
        hourCache.put(u, new Entry<>(Instant.now(), out));
        return out;
    }

    /** Records waiting for a decision (break-glass or incomplete, not reviewed), oldest first. */
    public List<QueueItem> queue() {
        String u = user();
        Entry<List<QueueItem>> hit = queueCache.get(u);
        if (fresh(hit)) return hit.value();
        Map<String, AuditRow> found = new LinkedHashMap<>();
        Map<String, String> why = new LinkedHashMap<>();
        Map<String, List<String>> bg = new LinkedHashMap<>();
        bg.put("break-glass", List.of("BTG"));
        for (AuditRow r : audit.recentWhere(bg, 50, "date")) { found.put(r.getId(), r); why.put(r.getId(), "breakglass"); }
        Map<String, List<String>> inc = new LinkedHashMap<>();
        inc.put("_tag", List.of(audit.incompleteTag()));
        for (AuditRow r : audit.recentWhere(inc, 50, "date")) { found.putIfAbsent(r.getId(), r); why.putIfAbsent(r.getId(), "incomplete"); }
        List<QueueItem> out = found.values().stream()
            .filter(AuditRow::isSlaApplicable)
            .sorted(Comparator.comparing(AuditRow::getRecordedInstant, Comparator.nullsLast(Comparator.naturalOrder())))
            .limit(8).map(r -> new QueueItem(r, why.get(r.getId()))).toList();
        trim(queueCache);
        queueCache.put(u, new Entry<>(Instant.now(), out));
        return out;
    }

    /** Records per sending system in the last 24 hours (systems known to the Admin list). */
    public List<SystemRow> systemRows() {
        String u = user();
        Entry<List<SystemRow>> hit = systemCache.get(u);
        if (fresh(hit)) return hit.value();
        Instant from = Instant.now().minus(24, ChronoUnit.HOURS);
        List<SystemRow> out = new ArrayList<>();
        for (SendingSystemRepository.System s : systems.all()) {
            Map<String, List<String>> p = since(from);
            p.put("_tag", List.of(audit.submittingClientTag(s.clientId())));
            out.add(new SystemRow(s.clientId(), s.label(), audit.countWhere(p)));
        }
        trim(systemCache);
        systemCache.put(u, new Entry<>(Instant.now(), out));
        return out;
    }
}

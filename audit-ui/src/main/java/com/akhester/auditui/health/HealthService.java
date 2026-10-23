package com.akhester.auditui.health;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

import org.springframework.stereotype.Service;

import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.service.AuditEventService;

/** Figures for the Health screen, all read from Chester with the signed-in person's own access. */
@Service
public class HealthService {

    public record Minute(int ago, int count) {}
    public record Live(Instant at, List<Minute> minutes, int lastHour, double perMinute, Instant lastEvent, int events24, int failed24,
                       AuditEventService.ChesterInfo chester, Instant oldest, int maxPerMinute, boolean truncated) {}
    public record DayCheck(boolean valid, int problems, String hash, String error) {}
    public record LastCheck(Instant at, String by, Map<LocalDate, DayCheck> days) {}
    public record Day(LocalDate day, int events, DayCheck check) {}

    private static final int MINUTE_SCAN_MAX = 3000;

    private final AuditEventService audit;
    private volatile LastCheck lastCheck;

    public HealthService(AuditEventService audit) { this.audit = audit; }

    public Live live() {
        Instant now = Instant.now();
        Instant from = now.minus(60, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        int[] per = new int[60];
        Set<String> seen = new HashSet<>();
        Instant cursor = null;
        int got = 0;
        boolean truncated = false;
        while (true) {
            List<String> d = new ArrayList<>(List.of("ge" + from));
            if (cursor != null) d.add("le" + cursor);
            Map<String, List<String>> p = new LinkedHashMap<>();
            p.put("date", d);
            List<AuditRow> page = audit.recentWhere(p, 100, "-date");
            int added = 0;
            for (AuditRow r : page) {
                if (r.getId() == null || !seen.add(r.getId()) || r.getRecordedInstant() == null) continue;
                added++; got++;
                long ago = Duration.between(r.getRecordedInstant(), now).toMinutes();
                if (ago >= 0 && ago < 60) per[(int) ago]++;
            }
            if (page.size() < 100) break;
            if (got >= MINUTE_SCAN_MAX) { truncated = true; break; }
            Instant oldest = page.get(page.size() - 1).getRecordedInstant();
            if (oldest == null) break;
            cursor = added == 0 ? oldest.truncatedTo(ChronoUnit.SECONDS).minusSeconds(1) : oldest.truncatedTo(ChronoUnit.SECONDS);
        }
        List<Minute> mins = new ArrayList<>();
        int max = 1, sum10 = 0, total = 0;
        for (int a = 59; a >= 0; a--) { mins.add(new Minute(a, per[a])); max = Math.max(max, per[a]); total += per[a]; if (a < 10) sum10 += per[a]; }
        List<AuditRow> newest = audit.recentWhere(new LinkedHashMap<>(), 1, "-date");
        List<AuditRow> oldestRows = audit.recentWhere(new LinkedHashMap<>(), 1, "date");
        Instant since = now.minus(24, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        Map<String, List<String>> all = new LinkedHashMap<>();
        all.put("date", new ArrayList<>(List.of("ge" + since)));
        int e24 = audit.countWhere(all);
        Map<String, List<String>> f = new LinkedHashMap<>();
        f.put("date", new ArrayList<>(List.of("ge" + since)));
        f.put("outcome", List.of("4,8,12"));
        int f24 = audit.countWhere(f);
        return new Live(now, mins, total, sum10 / 10.0, newest.isEmpty() ? null : newest.get(0).getRecordedInstant(), e24, f24, audit.chesterInfo(),
            oldestRows.isEmpty() ? null : oldestRows.get(0).getRecordedInstant(), max, truncated);
    }

    /** The last seven UTC days (the day a record is stored under), newest first, with the result of the last check if there was one. */
    public List<Day> days() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<Day> out = new ArrayList<>();
        LastCheck lc = lastCheck;
        for (int i = 0; i < 7; i++) {
            LocalDate d = today.minusDays(i);
            int c;
            try { c = audit.countWhere(dayParams(d)); } catch (RuntimeException e) { c = -1; }
            out.add(new Day(d, c, lc == null ? null : lc.days().get(d)));
        }
        return out;
    }

    public LastCheck lastCheck() { return lastCheck; }

    private static Map<String, List<String>> dayParams(LocalDate d) {
        Map<String, List<String>> p = new LinkedHashMap<>();
        p.put("date", new ArrayList<>(List.of("ge" + d.atStartOfDay().toInstant(ZoneOffset.UTC), "lt" + d.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))));
        return p;
    }

    /** Asks Chester to verify each of the last seven days, one at a time, and remembers the answer (in memory, lost on restart). */
    public LastCheck verify(String by) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Map<LocalDate, DayCheck> res = new LinkedHashMap<>();
        for (int i = 0; i < 7; i++) {
            LocalDate d = today.minusDays(i);
            Map<String, Object> r = audit.verifyChain(d.toString());
            String err = r.get("error") == null ? null : String.valueOf(r.get("error"));
            boolean valid = err == null && Boolean.TRUE.equals(r.get("valid"));
            int problems = r.get("problemCount") instanceof Number n ? n.intValue() : 0;
            String hash = null;
            if (err == null) {
                try {
                    List<AuditRow> last = audit.recentWhere(dayParams(d), 1, "-date");
                    if (!last.isEmpty()) {
                        var det = audit.getDetail(last.get(0).getId());
                        if (det != null && det.getChainHash() != null) {
                            String h = det.getChainHash();
                            hash = h.length() > 12 ? h.substring(0, 4) + "…" + h.substring(h.length() - 4) : h;
                        }
                    }
                } catch (RuntimeException ignored) { /* the hash is a nicety; the verdict stands without it */ }
            }
            res.put(d, new DayCheck(valid, problems, hash, err));
        }
        lastCheck = new LastCheck(Instant.now(), by, res);
        return lastCheck;
    }
}

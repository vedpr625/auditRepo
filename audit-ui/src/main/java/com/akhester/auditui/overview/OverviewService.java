package com.akhester.auditui.overview;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import com.akhester.auditui.admin.SendingSystemRepository;
import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.page.TimeFormat;
import com.akhester.auditui.prefs.PrefsContext;
import com.akhester.auditui.prefs.UserPrefs;
import com.akhester.auditui.service.AuditEventService;
import com.akhester.auditui.service.ReviewRepository;

/**
 * Everything the Overview page shows. One scan of the newest records (up to {@value #SCAN_MAX}, at most 30 days) is kept per person
 * for about a minute (Chester shows each person only what they may see, so nothing is shared between people). All charts, the
 * risk queue and the tables are worked out from that scan, so a page costs one pass over Chester, not forty queries.
 * Where the scan was cut short the page says so, and the headline counts are asked of Chester directly.
 */
@Service
public class OverviewService {

    public static final int SCAN_MAX = 6000;
    private static final int SCAN_DAYS = 30;

    // ── what the page asks for ───────────────────────────────────────────────
    public record Params(String range, LocalDate from, LocalDate to, String facility, boolean compare) {
        public static Params defaults() { return new Params("24h", null, null, null, false); }
    }

    // ── what the page shows ──────────────────────────────────────────────────
    public record Kpi(String id, String label, String value, String suffix, String sub, String tone, String subTone, String spark, String sparkAlt, List<Boolean> dots) {}
    public record Bar(String label, String title, int ok, int failed, double okPct, double failPct) {}
    public record Chart(List<Bar> bars, List<String> yLabels, List<String> xLabels, String summary, String unit, boolean empty) {}
    public record Need(String kind, String tag, String tagCss, String title, String meta, String why, String whyCss, int risk, String href) {}
    public record SysRow(String clientId, String name, String note, String lastEvent, Instant lastInstant, int events, int failed, String rate, Integer typical, boolean quiet, String statusText, int pct) {}
    public record HeatRow(String day, List<Cell> cells) {}
    public record Cell(int level, String title) {}
    public record Share(String label, int count, String pct, int widthPct, String css) {}
    public record DayBar(String title, int given, int missing, int givenPx, int missingPx) {}
    public record Slice(String label, int count, int widthPct, String css) {}
    public record Dashboard(Instant computedAt, boolean stale, boolean truncated, int scanned, String rangeLabel, String prevLabel,
                            List<Kpi> kpis, Chart chart, List<Need> needs, int needsTotal, List<SysRow> systems, List<HeatRow> heat,
                            List<Share> refusals, int failedTotal, String refusalNote, String refusalHref, List<Share> purposes,
                            List<DayBar> btg, List<String> btgAxis, String btgAvg, int btgTotal,
                            List<Slice> reviewSlices, int reviewOpen, int decidedReviewed, int decidedFlagged,
                            List<String> facilities, Instant from, Instant to) {}

    private record Scan(Instant at, List<AuditRow> rows, boolean truncated, int total) {}

    private final AuditEventService audit;
    private final SendingSystemRepository systems;
    private final ReviewRepository reviews;
    private final MessageSource messages;
    private final long ttlSeconds;
    private final Map<String, Scan> scans = new ConcurrentHashMap<>();

    public OverviewService(AuditEventService audit, SendingSystemRepository systems, ReviewRepository reviews, MessageSource messages,
                           @Value("${audit.ui.overview-cache-seconds:60}") long ttlSeconds) {
        this.audit = audit;
        this.systems = systems;
        this.reviews = reviews;
        this.messages = messages;
        this.ttlSeconds = ttlSeconds;
    }

    private static String user() { String u = PrefsContext.username(); return u == null ? "?" : u; }

    // ── scan ─────────────────────────────────────────────────────────────────

    /** Newest records of the last 30 days, newest first. When Chester does not answer, the last good scan is returned marked stale (or the error is thrown if there is none). */
    private Scan scan(boolean[] stale) {
        String u = user();
        Scan hit = scans.get(u);
        if (hit != null && Duration.between(hit.at(), Instant.now()).getSeconds() < ttlSeconds) return hit;
        try {
            Instant from = Instant.now().minus(SCAN_DAYS, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            Map<String, List<String>> p0 = new LinkedHashMap<>();
            p0.put("date", new ArrayList<>(List.of("ge" + from)));
            int total = audit.countWhere(p0);
            List<AuditRow> rows = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Instant cursor = null;
            while (rows.size() < SCAN_MAX) {
                List<String> d = new ArrayList<>(List.of("ge" + from));
                if (cursor != null) d.add("le" + cursor);
                Map<String, List<String>> p = new LinkedHashMap<>();
                p.put("date", d);
                List<AuditRow> page = audit.recentWhere(p, 100, "-date");
                int added = 0;
                for (AuditRow r : page) if (r.getId() != null && seen.add(r.getId())) { rows.add(r); added++; }
                if (page.size() < 100) break;
                Instant oldest = page.get(page.size() - 1).getRecordedInstant();
                if (oldest == null) break;
                cursor = added == 0 ? oldest.truncatedTo(ChronoUnit.SECONDS).minusSeconds(1) : oldest.truncatedTo(ChronoUnit.SECONDS);
            }
            Scan s = new Scan(Instant.now(), rows, total > rows.size(), total);
            if (scans.size() > 200) scans.clear();
            scans.put(u, s);
            return s;
        } catch (RuntimeException e) {
            if (hit != null) { stale[0] = true; return hit; }
            throw e;
        }
    }

    /** True when Chester answers; used by the page to choose between the dashboard and the "not answering" state. */
    public Dashboard dashboard(Params p, UserPrefs prefs, Locale loc) {
        boolean[] stale = {false};
        Scan scan = scan(stale);
        Instant now = Instant.now();
        ZoneId zone = prefs.zone();
        TimeFormat tf = new TimeFormat(prefs, loc, messages, now);
        return new Builder(p == null ? Params.defaults() : p, scan, stale[0], now, zone, tf, loc, prefs).build();
    }

    // ── builder ──────────────────────────────────────────────────────────────

    private final class Builder {
        final Params p; final Scan scan; final boolean stale; final Instant now; final ZoneId zone; final TimeFormat tf; final Locale loc; final UserPrefs prefs;
        Instant from, to, prevFrom, prevTo;
        boolean hourly;
        String facility;
        List<AuditRow> inRange, all;

        Builder(Params p, Scan scan, boolean stale, Instant now, ZoneId zone, TimeFormat tf, Locale loc, UserPrefs prefs) {
            this.p = p; this.scan = scan; this.stale = stale; this.now = now; this.zone = zone; this.tf = tf; this.loc = loc; this.prefs = prefs;
        }

        String m(String key, Object... args) { return messages.getMessage(key, args, key, loc); }
        String n(long v) { return String.format(loc, "%,d", v); }

        Dashboard build() {
            // facility filter (only values that exist in the scan are accepted)
            List<String> facilities = scan.rows().stream().map(AuditRow::getFacility).filter(f -> f != null && !"—".equals(f)).distinct().sorted().toList();
            facility = p.facility() != null && facilities.contains(p.facility()) ? p.facility() : null;
            all = scan.rows().stream().filter(r -> facility == null || facility.equals(r.getFacility())).filter(r -> r.getRecordedInstant() != null).toList();

            // range
            String range = p.range() == null ? "24h" : p.range();
            LocalDate today = LocalDate.now(zone);
            String rangeLabel;
            if ("custom".equals(range) && p.from() != null) {
                LocalDate f = p.from().isBefore(today.minusDays(SCAN_DAYS - 1)) ? today.minusDays(SCAN_DAYS - 1) : p.from();
                LocalDate t = p.to() == null || p.to().isAfter(today) ? today : p.to();
                if (t.isBefore(f)) t = f;
                from = f.atStartOfDay(zone).toInstant();
                to = t.plusDays(1).atStartOfDay(zone).toInstant();
                if (to.isAfter(now)) to = now;
                hourly = ChronoUnit.DAYS.between(f, t) < 2;
                rangeLabel = f.equals(t) ? f.format(day()) : f.format(day()) + " – " + t.format(day());
            } else if ("7d".equals(range)) {
                from = today.minusDays(6).atStartOfDay(zone).toInstant(); to = now; hourly = false; rangeLabel = m("overview.range.7d");
            } else if ("30d".equals(range)) {
                from = today.minusDays(SCAN_DAYS - 1).atStartOfDay(zone).toInstant(); to = now; hourly = false; rangeLabel = m("overview.range.30d");
            } else {
                Instant top = ZonedDateTime.now(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant();
                from = top.minus(24, ChronoUnit.HOURS); to = now; hourly = true; rangeLabel = m("overview.range.24h");
            }
            Duration len = Duration.between(from, to);
            prevTo = from; prevFrom = from.minus(len);
            inRange = all.stream().filter(r -> !r.getRecordedInstant().isBefore(from) && !r.getRecordedInstant().isAfter(to)).toList();
            String prevLabel = m("overview.prev." + (("custom".equals(range) ? "custom" : range)));

            List<Kpi> kpis = kpis(len, prevLabel);
            Chart chart = chart();
            List<SysRow> sysRows = systemRows();
            List<Need> needs = needs(sysRows);
            int failedTotal = (int) inRange.stream().filter(OverviewService::failed).count();
            return new Dashboard(scan.at(), stale, scan.truncated(), scan.rows().size(), rangeLabel, prevLabel, kpis, chart,
                needs.stream().limit(5).toList(), needs.size(), sysRows, heat(), refusals(), failedTotal, refusalNote(), refusalHref(), purposes(),
                btgBars(), btgAxis(), btgAverage(), btgCount14(), reviewSlices(), openReviews().size(),
                safe(() -> reviews.countWithStatus("reviewed")), safe(() -> reviews.countWithStatus("flagged")), facilities, from, to);
        }

        DateTimeFormatter day() { return DateTimeFormatter.ofPattern("d MMM", loc).withZone(zone); }

        // ── KPI tiles ────────────────────────────────────────────────────────
        List<Kpi> kpis(Duration len, String prevLabel) {
            int events = countOrScan(inRange.size(), null);
            int failed = (int) inRange.stream().filter(OverviewService::failed).count();
            int btg = (int) inRange.stream().filter(AuditRow::isBreakGlass).count();
            if (scan.truncated() && facility == null) {
                failed = countOr("outcome", "4,8,12", failed);
                btg = countOr("break-glass", "BTG", btg);
            }
            int[] prev = p.compare() ? previous() : null;
            List<AuditRow> open = openReviews();
            long overdue = open.stream().filter(r -> r.getAgeHours() >= AuditRow.slaOverdueHours).count();
            Set<String> people = inRange.stream().map(AuditRow::getAgentName).filter(a -> a != null && !"—".equals(a)).collect(Collectors.toSet());
            Set<String> sites = inRange.stream().map(AuditRow::getFacility).filter(a -> a != null && !"—".equals(a)).collect(Collectors.toSet());
            long noReason = inRange.stream().filter(r -> r.isBreakGlass() && r.getBreakGlassReason() == null).count();
            List<SysRow> sr = systemRows();
            long reporting = sr.stream().filter(s -> !s.quiet()).count();
            List<SysRow> quiet = sr.stream().filter(SysRow::quiet).toList();

            List<Kpi> k = new ArrayList<>();
            k.add(new Kpi("events", m("overview.k.events"), n(events), null,
                prev != null ? delta(events, prev[0], prevLabel) : m("overview.k.events.rate", n(Math.round(events / Math.max(1.0, len.toMinutes() / 60.0)))),
                "", "info", spark(inRange, null), m("overview.spark.events"), null));
            k.add(new Kpi("failed", m("overview.k.failed"), n(failed), null,
                prev != null ? delta(failed, prev[1], prevLabel) : m("overview.k.failed.share", pct(failed, events)),
                failed > 0 ? "bad" : "", "bad", spark(inRange, OverviewService::failed), m("overview.spark.failed"), null));
            k.add(new Kpi("btg", m("overview.k.btg"), n(btg), null,
                prev != null ? delta(btg, prev[2], prevLabel) : (noReason > 0 ? m("overview.k.btg.noreason", noReason) : m("overview.k.btg.allgiven")),
                btg > 0 ? "warn" : "", "warn", spark(inRange, AuditRow::isBreakGlass), m("overview.spark.btg"), null));
            k.add(new Kpi("reviews", m("overview.kpi.reviews"), n(open.size()), null,
                overdue > 0 ? m("overview.kpi.reviews.late", overdue, AuditRow.slaOverdueHours) : m("overview.kpi.reviews.none", AuditRow.slaOverdueHours),
                overdue > 0 ? "bad" : "", overdue > 0 ? "bad" : "", spark(open, null), m("overview.spark.reviews"), null));
            k.add(new Kpi("people", m("overview.kpi.people"), n(people.size()), null,
                m("overview.kpi.people.sites", sites.size()), "", "", sparkPeople(), m("overview.spark.people"), null));
            k.add(new Kpi("systems", m("overview.kpi.systems"), String.valueOf(reporting), m("overview.kpi.systems.of", sr.size()),
                quiet.isEmpty() ? m("overview.kpi.systems.allok") : quiet.stream().map(SysRow::name).limit(2).collect(Collectors.joining(", ")) + " · " + m("overview.kpi.systems.quiet"),
                "", quiet.isEmpty() ? "" : "warn", null, m("overview.spark.systems", reporting, sr.size()), sr.stream().map(s -> !s.quiet()).toList()));
            return k;
        }

        int countOrScan(int fromScan, String unused) {
            if (!scan.truncated()) return fromScan;
            try { return audit.countWhere(rangeParams(null, null)); } catch (RuntimeException e) { return fromScan; }
        }

        int countOr(String key, String val, int fallback) {
            try { return audit.countWhere(rangeParams(key, val)); } catch (RuntimeException e) { return fallback; }
        }

        Map<String, List<String>> rangeParams(String key, String val) {
            Map<String, List<String>> q = new LinkedHashMap<>();
            q.put("date", new ArrayList<>(List.of("ge" + from.truncatedTo(ChronoUnit.SECONDS), "lt" + to.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1))));
            if (key != null) q.put(key, List.of(val));
            if (facility != null) q.put("source-ref", List.of(facility));
            return q;
        }

        int[] previous() {
            try {
                int[] out = new int[3];
                String[][] k = {{null, null}, {"outcome", "4,8,12"}, {"break-glass", "BTG"}};
                for (int i = 0; i < 3; i++) {
                    Map<String, List<String>> q = new LinkedHashMap<>();
                    q.put("date", new ArrayList<>(List.of("ge" + prevFrom.truncatedTo(ChronoUnit.SECONDS), "lt" + prevTo.truncatedTo(ChronoUnit.SECONDS))));
                    if (k[i][0] != null) q.put(k[i][0], List.of(k[i][1]));
                    if (facility != null) q.put("source-ref", List.of(facility));
                    out[i] = audit.countWhere(q);
                }
                return out;
            } catch (RuntimeException e) { return null; }
        }

        String delta(int now, int before, String label) {
            if (before == 0) return now == 0 ? m("overview.delta.same", label) : m("overview.delta.new", label);
            long pct = Math.round((now - before) * 100.0 / before);
            if (pct == 0) return m("overview.delta.same", label);
            return m(pct > 0 ? "overview.delta.up" : "overview.delta.down", Math.abs(pct), label);
        }

        String pct(long a, long b) { return b == 0 ? "0%" : String.format(loc, "%.2f%%", a * 100.0 / b); }

        /** Twelve equal slices of the range, as the points of an SVG line (viewBox 0 0 120 26). */
        String spark(List<AuditRow> rows, java.util.function.Predicate<AuditRow> f) {
            int slices = 12;
            long span = Math.max(1, Duration.between(from, to).toMillis());
            int[] c = new int[slices];
            for (AuditRow r : rows) {
                if (f != null && !f.test(r)) continue;
                int i = (int) Math.min(slices - 1, Math.max(0, (r.getRecordedInstant().toEpochMilli() - from.toEpochMilli()) * slices / span));
                c[i]++;
            }
            return points(c);
        }

        String sparkPeople() {
            int slices = 12;
            long span = Math.max(1, Duration.between(from, to).toMillis());
            List<Set<String>> sets = new ArrayList<>();
            for (int i = 0; i < slices; i++) sets.add(new HashSet<>());
            for (AuditRow r : inRange) {
                int i = (int) Math.min(slices - 1, Math.max(0, (r.getRecordedInstant().toEpochMilli() - from.toEpochMilli()) * slices / span));
                sets.get(i).add(String.valueOf(r.getAgentName()));
            }
            return points(sets.stream().mapToInt(Set::size).toArray());
        }

        String points(int[] c) {
            int max = Math.max(1, java.util.Arrays.stream(c).max().orElse(1));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < c.length; i++) {
                double y = 23 - (c[i] * 20.0 / max);
                sb.append(i == 0 ? "" : " ").append(Math.round(i * 120.0 / (c.length - 1))).append(',').append(String.format(Locale.ROOT, "%.1f", y));
            }
            return sb.toString();
        }

        // ── chart ────────────────────────────────────────────────────────────
        Chart chart() {
            List<Instant> starts = new ArrayList<>();
            ZonedDateTime cur;
            if (hourly) cur = from.atZone(zone).truncatedTo(ChronoUnit.HOURS); else cur = from.atZone(zone).truncatedTo(ChronoUnit.DAYS);
            while (cur.toInstant().isBefore(to)) { starts.add(cur.toInstant()); cur = hourly ? cur.plusHours(1) : cur.plusDays(1); }
            int nb = starts.size();
            int[] ok = new int[nb], bad = new int[nb];
            for (AuditRow r : inRange) {
                int i = bucket(starts, r.getRecordedInstant());
                if (i < 0) continue;
                if (failed(r)) bad[i]++; else ok[i]++;
            }
            int max = 1;
            for (int i = 0; i < nb; i++) max = Math.max(max, ok[i] + bad[i]);
            int nice = niceMax(max);
            List<Bar> bars = new ArrayList<>();
            DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", loc).withZone(zone);
            int busy = 0, peakFail = 0;
            for (int i = 0; i < nb; i++) {
                String lab = hourly ? tf.hourLabel(starts.get(i)) : dayFmt.format(starts.get(i));
                bars.add(new Bar(lab, m("overview.chart.bar", lab, n(ok[i] + bad[i]), n(bad[i])), ok[i], bad[i], ok[i] * 100.0 / nice, bad[i] == 0 ? 0 : Math.max(1.2, bad[i] * 100.0 / nice)));
                if (ok[i] + bad[i] > ok[busy] + bad[busy]) busy = i;
                if (bad[i] > bad[peakFail]) peakFail = i;
            }
            List<String> y = new ArrayList<>();
            for (int i = 4; i >= 0; i--) y.add(n(nice / 4L * i));
            List<String> x = new ArrayList<>();
            for (int i = 0; i < 5 && nb > 0; i++) {
                int idx = (int) Math.round((nb - 1) * i / 4.0);
                x.add(hourly ? tf.hourLabel(starts.get(idx)) : DateTimeFormatter.ofPattern("d MMM", loc).withZone(zone).format(starts.get(idx)));
            }
            String summary;
            if (nb == 0 || ok[busy] + bad[busy] == 0) summary = m("overview.chart.summary.none");
            else {
                String busyLabel = bars.isEmpty() ? "" : (hourly ? tf.hourLabel(starts.get(busy)) : dayFmt.format(starts.get(busy)));
                String base = m(hourly ? "overview.chart.summary.hour" : "overview.chart.summary.day", busyLabel, n(ok[busy] + bad[busy]));
                if (bad[peakFail] > 0) {
                    final int pf = peakFail;
                    Map<String, Long> byFac = inRange.stream().filter(OverviewService::failed).filter(r -> bucket(starts, r.getRecordedInstant()) == pf)
                        .collect(Collectors.groupingBy(r -> String.valueOf(r.getFacility()), Collectors.counting()));
                    var top = byFac.entrySet().stream().max(Map.Entry.comparingByValue());
                    String peakLabel = hourly ? tf.hourLabel(starts.get(pf)) : dayFmt.format(starts.get(pf));
                    String fail = top.isPresent() && top.get().getValue() * 2 > bad[pf]
                        ? m("overview.chart.summary.fail.fac", peakLabel, n(bad[pf]), shortName(top.get().getKey()))
                        : m("overview.chart.summary.fail", peakLabel, n(bad[pf]));
                    summary = base + " " + fail;
                } else summary = base + " " + m("overview.chart.summary.nofail");
            }
            return new Chart(bars, y, x, summary, m(hourly ? "overview.chart.unit.hour" : "overview.chart.unit.day"), nb == 0);
        }

        int bucket(List<Instant> starts, Instant t) {
            int lo = 0, hi = starts.size() - 1, ans = -1;
            while (lo <= hi) { int mid = (lo + hi) >>> 1; if (!starts.get(mid).isAfter(t)) { ans = mid; lo = mid + 1; } else hi = mid - 1; }
            return ans;
        }

        // ── systems ──────────────────────────────────────────────────────────
        List<SysRow> sysCache;

        List<SysRow> systemRows() {
            if (sysCache != null) return sysCache;
            Map<String, String> names = safe(systems::names, Map.<String, String>of());
            Map<String, List<AuditRow>> byClient = new LinkedHashMap<>();
            for (AuditRow r : all) byClient.computeIfAbsent(r.getSubmittedBy() == null ? "—" : r.getSubmittedBy(), k -> new ArrayList<>()).add(r);
            for (String id : names.keySet()) byClient.putIfAbsent(id, new ArrayList<>());
            Duration len = Duration.between(from, to);
            int windows;
            windows = (int) Math.min(7, Math.max(0, (Duration.ofDays(SCAN_DAYS).toMinutes() / Math.max(1, len.toMinutes())) - 1));
            List<SysRow> out = new ArrayList<>();
            int maxEvents = 1;
            for (var e : byClient.entrySet()) maxEvents = Math.max(maxEvents, (int) e.getValue().stream().filter(r -> !r.getRecordedInstant().isBefore(from)).count());
            for (var e : byClient.entrySet()) {
                List<AuditRow> rs = e.getValue();
                int ev = 0, fl = 0;
                Instant last = null;
                for (AuditRow r : rs) {
                    if (last == null || r.getRecordedInstant().isAfter(last)) last = r.getRecordedInstant();
                    if (!r.getRecordedInstant().isBefore(from) && !r.getRecordedInstant().isAfter(to)) { ev++; if (failed(r)) fl++; }
                }
                Integer typical = null;
                int prevTotal = 0;
                if (windows > 0) {
                    Instant lo = from.minus(len.multipliedBy(windows));
                    for (AuditRow r : rs) if (!r.getRecordedInstant().isBefore(lo) && r.getRecordedInstant().isBefore(from)) prevTotal++;
                    typical = (int) Math.round(prevTotal / (double) windows);
                }
                // quiet: nothing for much longer than this system's normal gap (never fewer than 2 hours)
                boolean quiet = false; String status;
                if (last == null) { status = m("overview.sys.none"); quiet = true; }
                else {
                    long gapMin = Duration.between(last, now).toMinutes();
                    int per30 = (int) rs.stream().filter(r -> r.getRecordedInstant().isAfter(now.minus(7, ChronoUnit.DAYS))).count();
                    double usualGapMin = per30 == 0 ? Double.MAX_VALUE : 7 * 24 * 60.0 / per30;
                    quiet = gapMin >= 120 && gapMin > 3 * usualGapMin && per30 >= 7;
                    status = quiet ? m("overview.sys.quiet", gapMin / 60) : m("overview.sys.reporting");
                }
                String id = e.getKey();
                String name = names.getOrDefault(id, id);
                Set<String> fac = rs.stream().filter(r -> !r.getRecordedInstant().isBefore(from)).map(AuditRow::getFacility).filter(f -> f != null && !"—".equals(f)).collect(Collectors.toSet());
                out.add(new SysRow(id, name, fac.isEmpty() ? "" : m("overview.sys.sites", fac.size()), last == null ? "—" : tf.relative(last), last, ev, fl,
                    ev == 0 ? "—" : pct(fl, ev), typical, quiet, status, (int) Math.max(ev == 0 ? 0 : 2, ev * 100L / maxEvents)));
            }
            out.sort(Comparator.comparingInt(SysRow::events).reversed());
            return sysCache = out;
        }

        // ── people who need a decision, riskiest first ───────────────────────
        List<AuditRow> openCache;

        List<AuditRow> openReviews() {
            if (openCache != null) return openCache;
            return openCache = all.stream().filter(AuditRow::isSlaApplicable).sorted(Comparator.comparing(AuditRow::getRecordedInstant)).toList();
        }

        List<Need> needs(List<SysRow> sys) {
            List<Need> out = new ArrayList<>();
            // break-glass and incomplete records with no decision yet
            Map<String, Long> btgByAgent = all.stream().filter(r -> r.isBreakGlass() && r.getRecordedInstant().isAfter(now.minus(7, ChronoUnit.DAYS)))
                .collect(Collectors.groupingBy(r -> String.valueOf(r.getAgentName()), Collectors.counting()));
            for (AuditRow r : openReviews()) {
                int risk = r.isBreakGlass() ? 50 : 30;
                List<String> why = new ArrayList<>();
                boolean late = r.getAgeHours() >= AuditRow.slaOverdueHours;
                if (late) { risk += 25; why.add(m("overview.why.late", r.getAgeHours(), AuditRow.slaOverdueHours)); }
                else if (r.getAgeHours() >= AuditRow.slaWarningHours) { risk += 10; why.add(m("overview.why.soon", r.getAgeHours())); }
                if (r.isBreakGlass() && r.getBreakGlassReason() == null) { risk += 15; why.add(m("overview.why.noreason")); }
                long times = btgByAgent.getOrDefault(String.valueOf(r.getAgentName()), 0L);
                if (r.isBreakGlass() && times >= 3) { risk += 10; why.add(m("overview.why.repeat", times)); }
                if (r.isIncomplete()) why.add(m("overview.why.incomplete"));
                if (why.isEmpty()) why.add(m(r.isBreakGlass() ? "overview.why.btg" : "overview.why.incomplete"));
                String patient = r.getPatientRef() == null ? "" : r.getPatientRef().replace("Patient/", "");
                out.add(new Need(late ? "late" : r.isBreakGlass() ? "btg" : "incomplete",
                    m(late ? "overview.tag.late" : r.isBreakGlass() ? "overview.tag.btg" : "overview.tag.incomplete"),
                    late ? "tag-bad" : "tag-warn",
                    m(r.isBreakGlass() ? (r.getBreakGlassReason() == null ? "overview.need.btg.noreason" : "overview.need.btg") : "overview.need.incomplete"),
                    m("overview.need.meta", Math.min(risk, 99), String.valueOf(r.getAgentName()), shortName(r.getFacility()), tf.relative(r.getRecordedInstant())),
                    String.join("; ", why), late ? "bad" : "warn", Math.min(risk, 99),
                    patient.isEmpty() ? "/" : "/patient/" + patient));
            }
            // an unusual burst: the same person opening many records within one hour
            Map<String, List<Instant>> times = new HashMap<>();
            for (AuditRow r : inRange) if (!failed(r)) times.computeIfAbsent(String.valueOf(r.getAgentName()), k -> new ArrayList<>()).add(r.getRecordedInstant());
            for (var e : times.entrySet()) {
                List<Instant> t = e.getValue(); t.sort(Comparator.naturalOrder());
                int best = 0, j = 0;
                for (int i = 0; i < t.size(); i++) { while (Duration.between(t.get(j), t.get(i)).toMinutes() >= 60) j++; best = Math.max(best, i - j + 1); }
                if (best >= 20 && !"—".equals(e.getKey())) {
                    double perDay = t.size() / Math.max(1.0, Duration.between(from, to).toHours() / 24.0);
                    int risk = Math.min(90, 45 + best);
                    out.add(new Need("volume", m("overview.tag.volume"), "tag-warn", m("overview.need.volume"), m("overview.need.volume.meta", risk, e.getKey(), best),
                        m("overview.why.volume", Math.round(perDay)), "warn", risk, "/?agent=" + java.net.URLEncoder.encode(e.getKey(), java.nio.charset.StandardCharsets.UTF_8)));
                }
            }
            // a system that went quiet
            for (SysRow s : sys) if (s.quiet() && s.lastInstant() != null)
                out.add(new Need("quiet", m("overview.tag.quiet"), "tag-off", m("overview.need.quiet", s.name()), m("overview.need.quiet.meta", 55, s.lastEvent()),
                    m("overview.why.quiet"), "warn", 55, "/health"));
            out.sort(Comparator.comparingInt(Need::risk).reversed());
            return out;
        }

        // ── heat map: weekday by hour, four weeks ────────────────────────────
        List<HeatRow> heat() {
            int[][] c = new int[7][24];
            Instant lo = now.minus(28, ChronoUnit.DAYS);
            for (AuditRow r : all) {
                if (r.getRecordedInstant().isBefore(lo)) continue;
                ZonedDateTime z = r.getRecordedInstant().atZone(zone);
                c[z.getDayOfWeek().getValue() - 1][z.getHour()]++;
            }
            int max = 1;
            for (int[] row : c) for (int v : row) max = Math.max(max, v);
            List<HeatRow> out = new ArrayList<>();
            for (int d = 0; d < 7; d++) {
                String dn = DayOfWeek.of(d + 1).getDisplayName(TextStyle.SHORT, loc);
                List<Cell> cells = new ArrayList<>();
                for (int h = 0; h < 24; h++) {
                    int v = c[d][h];
                    int level = v == 0 ? 0 : Math.min(4, 1 + (int) (v * 4.0 / (max + 0.001)));
                    if (v == 0) level = 0;
                    cells.add(new Cell(level, m("overview.heat.cell", dn, String.format("%02d", h), n(v))));
                }
                out.add(new HeatRow(dn, cells));
            }
            return out;
        }

        // ── refusals and purposes ────────────────────────────────────────────
        List<Share> refusals() {
            Map<String, Long> g = inRange.stream().filter(OverviewService::failed)
                .collect(Collectors.groupingBy(r -> r.getOutcomeDesc() == null || r.getOutcomeDesc().isBlank() ? m("overview.reason.unknown") : r.getOutcomeDesc().trim(), Collectors.counting()));
            long top = g.values().stream().mapToLong(Long::longValue).max().orElse(1);
            return g.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(6)
                .map(e -> new Share(e.getKey(), e.getValue().intValue(), n(e.getValue()), (int) Math.max(2, e.getValue() * 100 / top), "bad")).toList();
        }

        String refusalNote() {
            List<AuditRow> f = inRange.stream().filter(OverviewService::failed).toList();
            if (f.isEmpty()) return null;
            Map<String, Long> by = f.stream().collect(Collectors.groupingBy(r -> String.valueOf(r.getFacility()), Collectors.counting()));
            var top = by.entrySet().stream().max(Map.Entry.comparingByValue()).orElse(null);
            if (top != null && top.getValue() * 2 > f.size() && by.size() > 1) return m("overview.refused.mostly", shortName(top.getKey()), Math.round(top.getValue() * 100.0 / f.size()));
            return m("overview.refused.spread", by.size());
        }

        String refusalHref() {
            StringBuilder sb = new StringBuilder("/?outcome=4");
            return sb.toString();
        }

        List<Share> purposes() {
            Map<String, Long> g = new LinkedHashMap<>();
            for (AuditRow r : inRange) {
                String k = r.getPurposeCode() == null || r.getPurposeCode().isBlank() ? "NONE" : r.getPurposeCode().toUpperCase();
                g.merge(k, 1L, Long::sum);
            }
            long total = g.values().stream().mapToLong(Long::longValue).sum();
            long top = g.values().stream().mapToLong(Long::longValue).max().orElse(1);
            return g.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed()).map(e -> {
                String css = switch (e.getKey()) { case "ETREAT", "BTG" -> "warn"; case "NONE" -> "off"; default -> "ok"; };
                String label = messages.getMessage("overview.purpose." + e.getKey(), null, e.getKey(), loc);
                return new Share(label, e.getValue().intValue(), total == 0 ? "0%" : pctShort(e.getValue() * 100.0 / total), (int) Math.max(2, e.getValue() * 100 / top), css);
            }).toList();
        }

        String pctShort(double v) { return v < 1 ? String.format(loc, "%.1f%%", v) : Math.round(v) + "%"; }

        // ── break-glass, last 14 days ────────────────────────────────────────
        List<LocalDate> days14() {
            LocalDate today = LocalDate.now(zone);
            List<LocalDate> d = new ArrayList<>();
            for (int i = 13; i >= 0; i--) d.add(today.minusDays(i));
            return d;
        }

        List<DayBar> btgBars() {
            Map<LocalDate, int[]> m = new TreeMap<>();
            List<LocalDate> d = days14();
            for (LocalDate x : d) m.put(x, new int[2]);
            for (AuditRow r : all) if (r.isBreakGlass()) {
                int[] c = m.get(r.getRecordedInstant().atZone(zone).toLocalDate());
                if (c != null) c[r.getBreakGlassReason() == null ? 1 : 0]++;
            }
            int max = Math.max(1, m.values().stream().mapToInt(c -> c[0] + c[1]).max().orElse(1));
            DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMM", loc);
            List<DayBar> out = new ArrayList<>();
            for (var e : m.entrySet()) {
                int g = e.getValue()[0], x = e.getValue()[1];
                out.add(new DayBar(m("overview.btg.bar", f.format(e.getKey()), g + x, x), g, x, (int) Math.round(g * 120.0 / max), (int) Math.round(x * 120.0 / max)));
            }
            return out;
        }

        List<String> btgAxis() {
            List<LocalDate> d = days14();
            DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMM", loc);
            return List.of(f.format(d.get(0)), f.format(d.get(7)), f.format(d.get(13)));
        }

        int btgCount14() {
            LocalDate lo = days14().get(0);
            return (int) all.stream().filter(r -> r.isBreakGlass() && !r.getRecordedInstant().atZone(zone).toLocalDate().isBefore(lo)).count();
        }

        String btgAverage() {
            LocalDate lo = days14().get(0);
            List<Long> mins = all.stream().filter(r -> r.isBreakGlass() && r.getReasonGivenAt() != null && !r.getRecordedInstant().atZone(zone).toLocalDate().isBefore(lo))
                .map(r -> Duration.between(r.getRecordedInstant(), r.getReasonGivenAt()).toMinutes()).filter(x -> x >= 0).toList();
            if (mins.isEmpty()) return null;
            long avg = Math.round(mins.stream().mapToLong(Long::longValue).average().orElse(0));
            return avg >= 60 ? m("overview.dur.hm", avg / 60, avg % 60) : m("overview.dur.m", avg);
        }

        // ── review queue summary ─────────────────────────────────────────────
        List<Slice> reviewSlices() {
            List<AuditRow> open = openReviews();
            long fresh = open.stream().filter(r -> r.getAgeHours() < AuditRow.slaWarningHours).count();
            long soon = open.stream().filter(r -> r.getAgeHours() >= AuditRow.slaWarningHours && r.getAgeHours() < AuditRow.slaOverdueHours).count();
            long late = open.stream().filter(r -> r.getAgeHours() >= AuditRow.slaOverdueHours).count();
            long max = Math.max(1, Math.max(fresh, Math.max(soon, late)));
            return List.of(
                new Slice(m("overview.rq.fresh", AuditRow.slaWarningHours), (int) fresh, (int) (fresh == 0 ? 0 : Math.max(3, fresh * 100 / max)), "ok"),
                new Slice(m("overview.rq.soon", AuditRow.slaWarningHours, AuditRow.slaOverdueHours), (int) soon, (int) (soon == 0 ? 0 : Math.max(3, soon * 100 / max)), "warn"),
                new Slice(m("overview.rq.late", AuditRow.slaOverdueHours), (int) late, (int) (late == 0 ? 0 : Math.max(3, late * 100 / max)), "bad"));
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    static boolean failed(AuditRow r) { return r.getOutcomeCode() != null && !"0".equals(r.getOutcomeCode()); }

    static String shortName(String ref) {
        if (ref == null) return "—";
        int i = ref.indexOf('/');
        return i >= 0 ? ref.substring(i + 1) : ref;
    }

    static int niceMax(int v) {
        int pow = 1;
        while (pow * 10 <= v) pow *= 10;
        for (int mult : new int[]{1, 2, 4, 5, 8, 10}) if (mult * pow >= v && (mult * pow) % 4 == 0) return mult * pow;
        return 10 * pow;
    }

    private static int safe(java.util.function.IntSupplier s) { try { return s.getAsInt(); } catch (RuntimeException e) { return 0; } }

    private static <T> T safe(java.util.function.Supplier<T> s, T dflt) { try { return s.get(); } catch (RuntimeException e) { return dflt; } }
}

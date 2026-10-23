package com.akhester.auditui.reports;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.akhester.auditui.admin.SendingSystemRepository;
import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.people.FacilityDirectory;
import com.akhester.auditui.people.PeopleIndex;
import com.akhester.auditui.people.Who;
import com.akhester.auditui.service.AuditEventService;

/** The seven reports. Data comes from Chester through AuditEventService (so each person gets only what their access allows), plus the console's own review log. */
@Service
public class ReportService {

    public enum Key {
        PATIENT("patient"), PERSON("person"), FACILITY("facility"), BREAKGLASS("breakglass"), FAILED("failed"), REVIEWS("reviews"), HEALTH("health");
        private final String id;
        Key(String id) { this.id = id; }
        public String id() { return id; }
        public static Key of(String s) {
            for (Key k : values()) if (k.id.equals(s)) return k;
            return PATIENT;
        }
    }

    /** from/to are whole days (UTC days in Chester). include: all | reads. */
    public record Params(Key key, String patient, String person, LocalDate from, LocalDate to, String include) {}
    public record Row(Instant when, List<String> cells, boolean breakGlass) {}
    public record Stat(String label, String value, String tone) {}
    public record Bar(String label, long ok, long failed) {}
    /** columns are message-key suffixes (reports.col.*). error: chester | forbidden | null. needsInput: the report needs a patient or person first. */
    public record Result(Key key, List<String> columns, boolean hasWhen, List<Row> rows, int total, boolean truncated, List<Stat> stats, List<Bar> bars, String error, boolean needsInput) {}

    public static final int CAP = 5000;
    static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    static final Pattern REF = Pattern.compile("[A-Za-z0-9._/-]{1,80}");
    private static final long QUIET_HOURS = 2;

    private final AuditEventService audit;
    private final PeopleIndex people;
    private final FacilityDirectory facilities;
    private final SendingSystemRepository systems;
    private final JdbcTemplate jdbc;
    private final MessageSource messages;

    public ReportService(AuditEventService audit, PeopleIndex people, FacilityDirectory facilities, SendingSystemRepository systems, JdbcTemplate jdbc, MessageSource messages) {
        this.audit = audit; this.people = people; this.facilities = facilities; this.systems = systems; this.jdbc = jdbc; this.messages = messages;
    }

    /** Builds the parameters from what was typed. Throws IllegalArgumentException with a message-key suffix (reports.err.*) when something cannot be used. */
    public Params parse(Key key, String patient, String person, String from, String to, String include, ZoneId zone) {
        LocalDate today = LocalDate.now(zone);
        LocalDate t, f;
        try {
            t = blank(to) ? today : LocalDate.parse(to.strip());
            f = blank(from) ? t.minusDays(29) : LocalDate.parse(from.strip());
        } catch (java.time.format.DateTimeParseException e) { throw new IllegalArgumentException("date"); }
        if (f.isAfter(t)) throw new IllegalArgumentException("range");
        if (f.isBefore(t.minusYears(2))) throw new IllegalArgumentException("long");
        String pt = null, pe = null;
        if (key == Key.PATIENT && !blank(patient)) {
            String x = patient.strip().replaceFirst("^Patient/", "");
            if (!ID.matcher(x).matches()) throw new IllegalArgumentException("patient");
            pt = x;
        }
        if (key == Key.PERSON && !blank(person)) pe = resolvePerson(person.strip());
        return new Params(key, pt, pe, f, t, "reads".equals(include) ? "reads" : "all");
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    /** A reference (Practitioner/dr-silva), a bare id (dr-silva) or a name that appears in the people list. */
    private String resolvePerson(String t) {
        if (t.length() > 80) throw new IllegalArgumentException("person");
        if (REF.matcher(t).matches()) return t.contains("/") ? t : "Practitioner/" + t;
        for (PeopleIndex.Person p : people.snapshot().people())
            if (p.name() != null && p.name().equalsIgnoreCase(t) && p.ref() != null) return p.ref();
        throw new IllegalArgumentException("person");
    }

    public Result run(Params p, ZoneId zone, Locale locale) {
        try {
            return switch (p.key()) {
                case PATIENT -> p.patient() == null ? input(p.key()) : events(p, Map.of("entity-ref", List.of("Patient/" + p.patient())), zone, locale, true);
                case PERSON -> p.person() == null ? input(p.key()) : events(p, Map.of("agent-ref", List.of(p.person())), zone, locale, false);
                case FACILITY -> facility(p, locale);
                case BREAKGLASS -> breakGlass(p, locale);
                case FAILED -> failed(p, zone, locale);
                case REVIEWS -> Who.canReview() ? reviews(p, locale) : new Result(p.key(), List.of(), false, List.of(), 0, false, List.of(), List.of(), "forbidden", false);
                case HEALTH -> health(p, zone, locale);
            };
        } catch (RuntimeException e) {
            return new Result(p.key(), List.of(), false, List.of(), 0, false, List.of(), List.of(), "chester", false);
        }
    }

    private Result input(Key k) { return new Result(k, List.of(), false, List.of(), 0, false, List.of(), List.of(), null, true); }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String m(String key, Object... args) { return messages.getMessage(key, args, key, org.springframework.context.i18n.LocaleContextHolder.getLocale()); }

    private Map<String, List<String>> dates(Params p, Map<String, List<String>> base) {
        Map<String, List<String>> q = new LinkedHashMap<>(base);
        q.put("date", new ArrayList<>(List.of("ge" + p.from(), "le" + p.to())));
        return q;
    }

    private List<AuditRow> collect(Map<String, List<String>> q, int[] total) {
        total[0] = audit.countWhere(q);
        List<AuditRow> all = new ArrayList<>();
        int offset = 0;
        while (offset < CAP) {
            List<AuditRow> page = audit.pageWhere(q, 100, offset, "-date");
            all.addAll(page);
            offset += page.size();
            if (page.size() < 100) break;
        }
        return all;
    }

    private String system(Map<String, String> names, AuditRow r) {
        return r.getSubmittedBy() == null ? "—" : names.getOrDefault(r.getSubmittedBy(), r.getSubmittedBy());
    }

    private String facility(Map<String, String> names, AuditRow r) { return r.getFacility() == null || "—".equals(r.getFacility()) ? "—" : FacilityDirectory.nameIn(names, r.getFacility()); }

    private String what(AuditRow r) { return m("reports.action." + (r.getAction() == null ? "R" : r.getAction())); }

    private static String key(AuditRow r) { return r.getAgentRef() != null ? r.getAgentRef() : "name:" + r.getAgentName(); }

    private static String tone(long n, String bad) { return n > 0 ? bad : "ok"; }

    // ── 1 and 2: events about a patient, events by a person ──────────────────

    private Result events(Params p, Map<String, List<String>> base, ZoneId zone, Locale locale, boolean forPatient) {
        Map<String, List<String>> q = dates(p, base);
        if ("reads".equals(p.include())) q.put("action", List.of("R"));
        int[] total = new int[1];
        List<AuditRow> rows = collect(q, total);
        Map<String, String> fnames = facilities.names(), snames = systems.names();
        List<Row> out = new ArrayList<>();
        Set<String> who = new LinkedHashSet<>(), facs = new LinkedHashSet<>();
        int btg = 0, failed = 0;
        for (AuditRow r : rows) {
            who.add(key(r)); if (r.getFacility() != null && !"—".equals(r.getFacility())) facs.add(r.getFacility());
            if (r.isBreakGlass()) btg++;
            if (r.getOutcomeCode() != null && !"0".equals(r.getOutcomeCode())) failed++;
            List<String> c = new ArrayList<>();
            if (forPatient) { c.add(r.getAgentName()); c.add(m("people.role." + com.akhester.auditui.people.Who.role(r.getAgentRef(), r.getAgentName()))); }
            else { c.add(r.getPatientRef() == null ? "—" : r.getPatientRef().replaceFirst("^Patient/", "")); }
            c.add(facility(fnames, r)); c.add(what(r)); c.add(r.getPurposeLabel()); c.add(system(snames, r)); c.add(r.getOutcomeLabel()); c.add(r.getId());
            out.add(new Row(r.getRecordedInstant(), c, r.isBreakGlass()));
        }
        List<String> cols = forPatient ? List.of("person", "role", "facility", "what", "purpose", "system", "outcome", "event")
                                       : List.of("patient", "facility", "what", "purpose", "system", "outcome", "event");
        List<Stat> st = new ArrayList<>(List.of(new Stat("events", String.valueOf(total[0]), "plain"),
            new Stat(forPatient ? "people" : "patients", String.valueOf(forPatient ? who.size() : rows.stream().map(AuditRow::getPatientRef).distinct().count()), "plain"),
            new Stat("facilities", String.valueOf(facs.size()), "plain"), new Stat("breakglass", String.valueOf(btg), btg > 0 ? "warn" : "ok")));
        if (failed > 0) st.add(new Stat("refused", String.valueOf(failed), "bad"));
        return new Result(p.key(), cols, true, out, total[0], total[0] > rows.size(), st, List.of(), null, false);
    }

    // ── 3: facility activity (counts asked of Chester, nothing scanned) ──────

    private Result facility(Params p, Locale locale) {
        PeopleIndex.Snapshot snap = people.snapshot();
        List<Row> out = new ArrayList<>();
        List<Bar> bars = new ArrayList<>();
        long events = 0, failed = 0, btg = 0;
        for (FacilityDirectory.Facility f : facilities.withSeen(snap.facilities())) {
            Map<String, List<String>> base = dates(p, Map.of("source-ref", List.of(f.ref())));
            int tot = audit.countWhere(base);
            if (tot == 0) continue;
            int fail = audit.countWhere(with(base, "outcome", "4,8,12"));
            int bg = audit.countWhere(with(base, "break-glass", "BTG"));
            int inc = audit.countWhere(with(base, "_tag", audit.incompleteTag()));
            events += tot; failed += fail; btg += bg;
            out.add(new Row(null, List.of(f.name(), String.valueOf(tot), String.valueOf(fail), pct(fail, tot), String.valueOf(bg), String.valueOf(inc)), false));
            bars.add(new Bar(f.name(), tot - fail, fail));
        }
        out.sort(Comparator.comparingInt((Row r) -> -Integer.parseInt(r.cells().get(1))));
        bars.sort(Comparator.comparingLong((Bar b) -> -(b.ok() + b.failed())));
        List<Stat> st = List.of(new Stat("facilities", String.valueOf(out.size()), "plain"), new Stat("events", String.valueOf(events), "plain"),
            new Stat("failed", String.valueOf(failed), tone(failed, "bad")), new Stat("breakglass", String.valueOf(btg), tone(btg, "warn")));
        return new Result(p.key(), List.of("facility", "events", "failed", "rate", "breakglass", "incomplete"), false, out, out.size(), false, st, bars, null, false);
    }

    private static Map<String, List<String>> with(Map<String, List<String>> base, String k, String v) {
        Map<String, List<String>> q = new LinkedHashMap<>(base);
        q.put(k, new ArrayList<>(List.of(v)));
        return q;
    }

    private static String pct(long part, long whole) { return whole == 0 ? "0%" : String.format(Locale.ROOT, "%.1f%%", 100.0 * part / whole); }

    // ── 4: break-glass ───────────────────────────────────────────────────────

    private Result breakGlass(Params p, Locale locale) {
        int[] total = new int[1];
        List<AuditRow> rows = collect(dates(p, Map.of("break-glass", List.of("BTG"))), total);
        Map<String, String> fnames = facilities.names();
        List<Row> out = new ArrayList<>();
        int noReason = 0, decided = 0, pending = 0;
        for (AuditRow r : rows) {
            boolean reason = r.getBreakGlassReason() != null && !r.getBreakGlassReason().isBlank();
            if (!reason) noReason++;
            if ("reviewed".equals(r.getReviewStatus()) || "flagged".equals(r.getReviewStatus())) decided++; else pending++;
            String after = !reason ? m("reports.bg.noreason")
                : r.getReasonGivenAt() != null && r.getRecordedInstant() != null ? m("reports.bg.minutes", Duration.between(r.getRecordedInstant(), r.getReasonGivenAt()).toMinutes()) : "—";
            out.add(new Row(r.getRecordedInstant(), List.of(r.getPatientRef() == null ? "—" : r.getPatientRef().replaceFirst("^Patient/", ""), r.getAgentName(), facility(fnames, r),
                reason ? r.getBreakGlassReason() : "—", after, m("reports.review." + (r.getReviewStatus() == null ? "pending" : r.getReviewStatus())), r.getId()), true));
        }
        List<Stat> st = List.of(new Stat("events", String.valueOf(total[0]), "plain"), new Stat("noreason", String.valueOf(noReason), tone(noReason, "bad")),
            new Stat("decided", String.valueOf(decided), "ok"), new Stat("pending", String.valueOf(pending), tone(pending, "warn")));
        return new Result(p.key(), List.of("patient", "person", "facility", "reason", "reasonafter", "review", "event"), true, out, total[0], total[0] > rows.size(), st, List.of(), null, false);
    }

    // ── 5: failed access ─────────────────────────────────────────────────────

    private Result failed(Params p, ZoneId zone, Locale locale) {
        Map<String, List<String>> q = dates(p, Map.of("outcome", List.of("4,8,12")));
        int[] total = new int[1];
        List<AuditRow> rows = collect(q, total);
        Map<String, String> fnames = facilities.names(), snames = systems.names();
        Map<String, long[]> bySystem = new LinkedHashMap<>();
        List<Row> out = new ArrayList<>();
        for (AuditRow r : rows) {
            bySystem.computeIfAbsent(r.getSubmittedBy() == null ? "" : r.getSubmittedBy(), k -> new long[1])[0]++;
            out.add(new Row(r.getRecordedInstant(), List.of(system(snames, r), r.getAgentName(), facility(fnames, r), what(r),
                r.getOutcomeDesc() == null || r.getOutcomeDesc().isBlank() ? r.getOutcomeLabel() : r.getOutcomeDesc(), r.getOutcomeLabel(), r.getId()), r.isBreakGlass()));
        }
        List<Bar> bars = new ArrayList<>();
        for (Map.Entry<String, long[]> e : bySystem.entrySet()) {
            long all = e.getKey().isEmpty() ? e.getValue()[0] : audit.countWhere(dates(p, Map.of("_tag", List.of(audit.submittingClientTag(e.getKey())))));
            bars.add(new Bar(e.getKey().isEmpty() ? "—" : snames.getOrDefault(e.getKey(), e.getKey()), Math.max(0, all - e.getValue()[0]), e.getValue()[0]));
        }
        bars.sort(Comparator.comparingLong((Bar b) -> -b.failed()));
        int all = audit.countWhere(dates(p, Map.of()));
        List<Stat> st = List.of(new Stat("failed", String.valueOf(total[0]), tone(total[0], "bad")), new Stat("systems", String.valueOf(bySystem.size()), "plain"),
            new Stat("ofevents", String.valueOf(all), "plain"), new Stat("rate", pct(total[0], all), "plain"));
        return new Result(p.key(), List.of("system", "person", "facility", "what", "failure", "severity", "event"), true, out, total[0], total[0] > rows.size(), st, bars, null, false);
    }

    // ── 6: review decisions (the console's own log; only events this person can see in Chester) ──

    private Result reviews(Params p, Locale locale) {
        Instant lo = p.from().atStartOfDay().toInstant(java.time.ZoneOffset.UTC), hi = p.to().plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        List<Object[]> log = new ArrayList<>();
        jdbc.query("SELECT event_id, status, reviewer, reviewed_at, note FROM audit_review_log WHERE reviewed_at >= ? AND reviewed_at < ? ORDER BY id DESC LIMIT 1000",
            rs -> { log.add(new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getString(5)}); },
            java.sql.Timestamp.from(lo), java.sql.Timestamp.from(hi));
        Map<String, AuditRow> events = new HashMap<>();
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(log.stream().map(o -> (String) o[0]).toList()));
        for (int i = 0; i < ids.size(); i += 50)
            for (AuditRow r : audit.pageWhere(Map.of("_id", List.of(String.join(",", ids.subList(i, Math.min(i + 50, ids.size()))))), 100, 0, "-date")) events.put(r.getId(), r);
        List<Row> out = new ArrayList<>();
        List<Double> hours = new ArrayList<>();
        int reviewed = 0, flagged = 0;
        for (Object[] o : log) {
            AuditRow r = events.get((String) o[0]);
            if (r == null) continue;
            Instant at = (Instant) o[3];
            double h = r.getRecordedInstant() == null ? -1 : Duration.between(r.getRecordedInstant(), at).toMinutes() / 60.0;
            if (h >= 0) hours.add(h);
            if ("reviewed".equals(o[1])) reviewed++; else if ("flagged".equals(o[1])) flagged++;
            out.add(new Row(at, List.of((String) o[0], r.getPatientRef() == null ? "—" : r.getPatientRef().replaceFirst("^Patient/", ""), m("reports.review." + o[1]), (String) o[2],
                o[4] == null ? "—" : (String) o[4], h < 0 ? "—" : String.format(Locale.ROOT, "%.1f", h)), r.isBreakGlass()));
        }
        hours.sort(Double::compare);
        String median = hours.isEmpty() ? "—" : String.format(Locale.ROOT, "%.1f h", hours.get(hours.size() / 2));
        List<Stat> st = List.of(new Stat("decisions", String.valueOf(out.size()), "plain"), new Stat("decided", String.valueOf(reviewed), "ok"),
            new Stat("flagged", String.valueOf(flagged), tone(flagged, "warn")), new Stat("median", median, "plain"));
        return new Result(p.key(), List.of("event", "patient", "decision", "reviewer", "note", "hours"), true, out, out.size(), log.size() >= 1000, st, List.of(), null, false);
    }

    // ── 7: system health (per sending system) ────────────────────────────────

    private Result health(Params p, ZoneId zone, Locale locale) {
        DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH).withZone(zone);
        List<Row> out = new ArrayList<>();
        List<Bar> bars = new ArrayList<>();
        long events = 0, failed = 0; int quiet = 0;
        for (SendingSystemRepository.System s : systems.all()) {
            Map<String, List<String>> tag = Map.of("_tag", List.of(audit.submittingClientTag(s.clientId())));
            int tot = audit.countWhere(dates(p, tag));
            int fail = audit.countWhere(with(dates(p, tag), "outcome", "4,8,12"));
            List<AuditRow> last = audit.pageWhere(tag, 1, 0, "-date");
            Instant lastAt = last.isEmpty() ? null : last.get(0).getRecordedInstant();
            String status = lastAt == null ? "none" : Duration.between(lastAt, Instant.now()).toHours() >= 24 * 7 ? "gone"
                : Duration.between(lastAt, Instant.now()).toHours() >= QUIET_HOURS ? "quiet" : tot > 0 && fail * 20L > tot ? "failing" : "active";
            if (!status.equals("active")) quiet++;
            events += tot; failed += fail;
            out.add(new Row(null, List.of(s.label(), s.clientId(), String.valueOf(tot), String.valueOf(fail), pct(fail, tot), lastAt == null ? "—" : f.format(lastAt), m("reports.health." + status)), false));
            bars.add(new Bar(s.label(), tot - fail, fail));
        }
        bars.sort(Comparator.comparingLong((Bar b) -> -(b.ok() + b.failed())));
        List<Stat> st = List.of(new Stat("systems", String.valueOf(out.size()), "plain"), new Stat("events", String.valueOf(events), "plain"),
            new Stat("failed", String.valueOf(failed), tone(failed, "bad")), new Stat("notactive", String.valueOf(quiet), tone(quiet, "warn")));
        return new Result(p.key(), List.of("system", "clientid", "events", "failed", "rate", "lastsent", "status"), false, out, out.size(), false, st, bars, null, false);
    }

    /** Column headings in the person's language (for the file). */
    public List<String> headings(Result r, Locale locale) {
        List<String> h = new ArrayList<>();
        for (String c : r.columns()) h.add(messages.getMessage("reports.col." + c, null, c, locale));
        return h;
    }

    public String text(String key, Locale locale) { return messages.getMessage(key, null, key, locale); }
}

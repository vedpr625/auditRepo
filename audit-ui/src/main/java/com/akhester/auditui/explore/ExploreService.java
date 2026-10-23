package com.akhester.auditui.explore;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;

import com.akhester.auditui.admin.SendingSystemRepository;
import com.akhester.auditui.consent.ConsentClient;
import com.akhester.auditui.consent.ConsentLogRepository;
import com.akhester.auditui.model.AuditFilter;
import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.page.ShellAdvice;
import com.akhester.auditui.page.TimeFormat;
import com.akhester.auditui.patient.PatientController;
import com.akhester.auditui.people.FacilityDirectory;
import com.akhester.auditui.people.PeopleIndex;
import com.akhester.auditui.people.Who;
import com.akhester.auditui.prefs.PrefsContext;
import com.akhester.auditui.prefs.UserPrefs;
import com.akhester.auditui.registry.RegistryClient;
import com.akhester.auditui.service.AuditEventService;

import lombok.Builder;
import lombok.Data;

/**
 * Everything the Explore screen shows besides the raw rows: the events-over-time chart, the day headers, the "14 similar views" collapsing,
 * grouping, the filter chips and the event detail panel. All data comes from {@link AuditEventService}, so Chester applies the person's own access.
 */
@Service
public class ExploreService {

    private static final int BUCKETS = 24;
    /** Rows of one patient, one person and one place within this many minutes of each other count as "similar views". */
    private static final int SIMILAR_GAP_MIN = 30;
    private static final int SIMILAR_MIN = 3;
    private static final int GROUP_SCAN = 500;
    private static final int LINK_SCAN = 300;
    private static final int LINK_PATIENTS = 25;

    private final AuditEventService audit;
    private final SendingSystemRepository systems;
    private final FacilityDirectory facilities;
    private final MessageSource messages;
    private final PeopleIndex people;
    private final ConsentClient consent;
    private final ConsentLogRepository consentLog;
    private final RegistryClient registry;

    public ExploreService(AuditEventService audit, SendingSystemRepository systems, FacilityDirectory facilities, MessageSource messages,
                          PeopleIndex people, ConsentClient consent, ConsentLogRepository consentLog, RegistryClient registry) {
        this.audit = audit; this.systems = systems; this.facilities = facilities; this.messages = messages;
        this.people = people; this.consent = consent; this.consentLog = consentLog; this.registry = registry;
    }

    // ── view models ──────────────────────────────────────────────────────────

    /** One line of the table: a day header, a row, a collapsed run of similar rows, or a group. */
    @Data @Builder
    public static class Item {
        /** day | row | collapsed | group */
        private String type;
        private AuditRow row;
        private String id, clock, patientId, actorName, actorRef, actorRole, systemId, systemLabel, facilityRef, facilityLabel, whatKey, whatText, dept, desc, action;
        private Instant at;
        /** ok | failed | btg */
        private String result;
        private boolean noReason, careUnknown, overdue, child, selected;
        private String reviewStatus;
        // collapsed
        private int count; private int groupNo; private String spanFrom, spanTo;
        // day header
        private String dayName, zoneShort, zoneOffset;
        // group
        private String key, label, filterKey, filterValue; private int failed, btg; private Instant last;
        private int pct;
    }

    public record Bar(int ok, int failed, int pct, String from, String to, String tip) {}
    public record Axis(int pos, String label, boolean end) {}
    public record Chart(List<Bar> bars, List<Axis> axis, long total, long failed, boolean any, boolean estimated) {}
    public record Chip(String key, String label, String value) {}

    public record View(Chart chart, List<Item> items, List<Chip> chips, String rangeLabel, boolean grouped, boolean groupCapped, int groupScanned) {}

    // ── filter clean-up ──────────────────────────────────────────────────────

    /** Lets people type names: a facility, system or practitioner name is turned into the reference or id Chester stores. */
    public void normalize(AuditFilter f) {
        String fac = f.getFacility();
        if (fac != null && !fac.isBlank() && !fac.contains("/")) {
            String n = fac.strip().toLowerCase(Locale.ROOT);
            FacilityDirectory.Facility hit = facilities.listed().stream().filter(x -> x.name().equalsIgnoreCase(n)).findFirst()
                .orElseGet(() -> facilities.listed().stream().filter(x -> x.name().toLowerCase(Locale.ROOT).contains(n) || x.ref().toLowerCase(Locale.ROOT).endsWith(n)).findFirst().orElse(null));
            if (hit != null) f.setFacility(hit.ref());
        }
        String cl = f.getClient();
        if (cl != null && !cl.isBlank()) {
            String n = cl.strip();
            try {
                systems.names().forEach((id, label) -> { if (label.equalsIgnoreCase(n) && !id.equals(n)) f.setClient(id); });
            } catch (RuntimeException ignored) { /* keep the typed value */ }
        }
        String ag = f.getAgent();
        if (ag != null && !ag.isBlank() && !ag.contains("/")) {
            String n = ag.strip().toLowerCase(Locale.ROOT);
            try {
                PeopleIndex.Person hit = people.snapshot().people().stream().filter(x -> x.ref() != null && (x.ref().toLowerCase(Locale.ROOT).endsWith("/" + n)
                    || (x.name() != null && x.name().toLowerCase(Locale.ROOT).contains(n)))).findFirst().orElse(null);
                if (hit != null) f.setAgent(hit.ref());
            } catch (RuntimeException ignored) { /* keep the typed value */ }
        }
    }

    // ── table ────────────────────────────────────────────────────────────────

    private String msg(String key, Locale loc, Object... args) { return messages.getMessage(key, args, key, loc); }

    private static String tail(String ref) {
        if (ref == null) return null;
        int i = ref.indexOf('/');
        return i >= 0 ? ref.substring(i + 1) : ref;
    }

    private Item rowItem(AuditRow r, UserPrefs prefs, Map<String, String> sysNames, Map<String, String> facNames, DateTimeFormatter clock, String zoneShort) {
        boolean failed = r.getOutcomeCode() != null && !"0".equals(r.getOutcomeCode());
        String res = failed ? "failed" : (r.isBreakGlass() || "BTG".equalsIgnoreCase(r.getPurposeCode()) ? "btg" : "ok");
        boolean emergency = r.isBreakGlass() || "BTG".equalsIgnoreCase(r.getPurposeCode()) || "ETREAT".equalsIgnoreCase(r.getPurposeCode());
        String act = r.getAction() == null ? "R" : r.getAction();
        String actor = r.getAgentName() == null ? "—" : r.getAgentName();
        String pat = r.getPatientRef() != null && r.getPatientRef().startsWith("Patient/") ? r.getPatientRef().substring(8) : null;
        String sys = r.getSubmittedBy();
        return Item.builder().type("row").row(r).id(r.getId())
            .at(r.getRecordedInstant())
            .clock(r.getRecordedInstant() == null ? "—" : clock.format(r.getRecordedInstant()) + " " + zoneShort)
            .patientId(pat).actorName(actor).actorRef(r.getAgentRef())
            .actorRole(Who.role(r.getAgentRef(), r.getAgentName()))
            .systemId(sys).systemLabel(sys == null ? "—" : sysNames.getOrDefault(sys, sys))
            .facilityRef(r.getFacility()).facilityLabel(r.getFacility() == null || "—".equals(r.getFacility()) ? "" : facNames.getOrDefault(r.getFacility(), tail(r.getFacility())))
            .whatKey(emergency && !failed && r.isBreakGlass() ? "explore.what.emergency" : "explore.what." + act)
            .whatText(r.getSubtypeText()).action(act).dept(r.getDepartment()).desc(failed ? r.getOutcomeDesc() : null)
            .result(res)
            .noReason(r.isBreakGlass() && (r.getBreakGlassReason() == null || r.getBreakGlassReason().isBlank()))
            .careUnknown(r.isBreakGlass() || "BTG".equalsIgnoreCase(r.getPurposeCode()))
            .overdue(r.isSlaApplicable() && r.getAgeHours() >= AuditRow.slaOverdueHours)
            .reviewStatus(r.getReviewStatus()).build();
    }

    private static String zoneShort(ZoneId zone, Locale loc, TimeFormat tf, Instant at) {
        String id = zone.getId();
        if (id.equals("Asia/Colombo") || id.equals("Asia/Kolkata") || id.equals("Asia/Calcutta")) return "IST";   // Java's own short name for these is "IT"
        if (id.equals("UTC") || id.equals("Etc/UTC")) return "UTC";
        String s = zone.getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        if (s == null || !s.matches("[A-Z]{3,5}")) return tf.zoneLabel(at);
        return s;
    }

    /** The table lines for the rows of one page, with day headers and collapsed runs of similar views. */
    public View view(AuditFilter f, List<AuditRow> rows, int total, UserPrefs prefs, TimeFormat tf, Locale loc) {
        ZoneId zone = prefs.zone();
        Instant now = Instant.now();
        Map<String, String> sysNames = Map.of(), facNames = Map.of();
        try { sysNames = systems.names(); } catch (RuntimeException ignored) { /* ids are shown as sent */ }
        try { facNames = facilities.names(); } catch (RuntimeException ignored) { /* references are shown as sent */ }
        DateTimeFormatter clock = DateTimeFormatter.ofPattern(prefs.clock24() ? "HH:mm:ss" : "h:mm:ss a", loc).withZone(zone);
        DateTimeFormatter clockShort = DateTimeFormatter.ofPattern(prefs.clock24() ? "HH:mm" : "h:mm a", loc).withZone(zone);
        String zs = zoneShort(zone, loc, tf, now);
        String zo = tf.zoneLabel(now);

        List<Item> rowItems = new ArrayList<>();
        for (AuditRow r : rows) rowItems.add(rowItem(r, prefs, sysNames, facNames, clock, zs));

        // runs of similar views: same person, same patient, same place, plain successful reads, close together in time
        Map<Integer, List<Integer>> clusterOf = new HashMap<>();
        Map<String, List<Integer>> byKey = new LinkedHashMap<>();
        for (int i = 0; i < rowItems.size(); i++) {
            Item it = rowItems.get(i);
            AuditRow r = it.getRow();
            if (!"R".equals(it.getAction()) || !"ok".equals(it.getResult()) || it.getPatientId() == null || r.getAgentRef() == null || it.getAt() == null) continue;
            byKey.computeIfAbsent(r.getAgentRef() + "|" + it.getPatientId() + "|" + r.getFacility(), k -> new ArrayList<>()).add(i);
        }
        for (List<Integer> idx : byKey.values()) {
            List<Integer> cur = new ArrayList<>();
            for (int k = 0; k <= idx.size(); k++) {
                boolean close = k < idx.size() && (cur.isEmpty()
                    || Math.abs(ChronoUnit.MINUTES.between(rowItems.get(cur.get(cur.size() - 1)).getAt(), rowItems.get(idx.get(k)).getAt())) <= SIMILAR_GAP_MIN);
                if (close) { cur.add(idx.get(k)); continue; }
                if (cur.size() >= SIMILAR_MIN) for (int m : cur) clusterOf.put(m, new ArrayList<>(cur));
                cur = new ArrayList<>();
                if (k < idx.size()) cur.add(idx.get(k));
            }
        }

        List<Item> out = new ArrayList<>();
        LocalDate lastDay = null;
        Set<Integer> done = new java.util.HashSet<>();
        int groupNo = 0;
        Locale en = loc;
        DateTimeFormatter dayFmt = DateTimeFormatter.ofPattern("EEEE d MMMM", en).withZone(zone);
        for (int i = 0; i < rowItems.size(); i++) {
            if (done.contains(i)) continue;
            Item it = rowItems.get(i);
            List<Integer> cl = clusterOf.get(i);
            LocalDate day = it.getAt() == null ? null : it.getAt().atZone(zone).toLocalDate();
            if (day != null && !day.equals(lastDay)) {
                out.add(Item.builder().type("day").dayName(dayFmt.format(it.getAt())).zoneShort(zs).zoneOffset(zo).count(dayCount(f, day, zone, total)).build());
                lastDay = day;
            }
            if (cl == null) { out.add(it); continue; }
            groupNo++;
            Instant first = null, last = null;
            for (int m : cl) { Instant t = rowItems.get(m).getAt(); if (first == null || t.isBefore(first)) first = t; if (last == null || t.isAfter(last)) last = t; }
            Item c = Item.builder().type("collapsed").row(it.getRow()).id(it.getId()).patientId(it.getPatientId()).actorName(it.getActorName()).actorRef(it.getActorRef())
                .actorRole(it.getActorRole()).systemId(it.getSystemId()).systemLabel(it.getSystemLabel()).facilityLabel(it.getFacilityLabel()).facilityRef(it.getFacilityRef())
                .count(cl.size()).groupNo(groupNo).result("ok").at(it.getAt()).clock(it.getClock())
                .spanFrom(clockShort.format(first)).spanTo(clockShort.format(last)).build();
            out.add(c);
            for (int m : cl) {
                Item ch = rowItems.get(m);
                ch.setChild(true); ch.setGroupNo(groupNo);
                done.add(m);
                out.add(ch);
            }
        }
        if (f.getOpen() != null) for (Item x : out) if (f.getOpen().equals(x.getId())) x.setSelected(true);
        return new View(chart(f, prefs, tf, loc), out, chips(f, loc, sysNames, facNames), rangeLabel(f, prefs, loc, tf), false, false, 0);
    }

    private int dayCount(AuditFilter f, LocalDate day, ZoneId zone, int fallback) {
        try {
            Map<String, List<String>> p = copy(audit.paramsFor(f));
            Instant a = day.atStartOfDay(zone).toInstant(), b = day.plusDays(1).atStartOfDay(zone).toInstant();
            p.computeIfAbsent("date", k -> new ArrayList<>()).add("ge" + a.truncatedTo(ChronoUnit.SECONDS));
            p.get("date").add("lt" + b.truncatedTo(ChronoUnit.SECONDS));
            return audit.countWhere(p);
        } catch (RuntimeException e) { return fallback; }
    }

    private static Map<String, List<String>> copy(Map<String, List<String>> m) {
        Map<String, List<String>> c = new LinkedHashMap<>();
        for (var e : m.entrySet()) c.put(e.getKey(), new ArrayList<>(e.getValue()));
        return c;
    }

    // ── grouped table ────────────────────────────────────────────────────────

    /** "Group by": counts over the newest {@value #GROUP_SCAN} matching events. Each line filters the table when clicked. */
    public View grouped(AuditFilter f, int total, UserPrefs prefs, TimeFormat tf, Locale loc) {
        Map<String, String> sysNames = Map.of(), facNames = Map.of();
        try { sysNames = systems.names(); } catch (RuntimeException ignored) { }
        try { facNames = facilities.names(); } catch (RuntimeException ignored) { }
        List<AuditRow> rows = new ArrayList<>();
        try {
            Map<String, List<String>> p = audit.paramsFor(f);
            int off = 0;
            while (off < GROUP_SCAN) {
                List<AuditRow> page = audit.pageWhere(p, 100, off, "-date");
                rows.addAll(page);
                off += page.size();
                if (page.size() < 100) break;
            }
        } catch (RuntimeException ignored) { /* an empty table, the page already shows the error */ }
        Map<String, Item> groups = new LinkedHashMap<>();
        String by = f.getGroupBy();
        for (AuditRow r : rows) {
            String key, label, fkey, fval;
            switch (by) {
                case "submitted" -> { key = String.valueOf(r.getSubmittedBy()); label = r.getSubmittedBy() == null ? "—" : sysNames.getOrDefault(key, key); fkey = "client"; fval = r.getSubmittedBy(); }
                case "actor" -> { key = r.getAgentRef() != null ? r.getAgentRef() : "name:" + r.getAgentName(); label = r.getAgentName(); fkey = "agent"; fval = r.getAgentRef(); }
                case "patient" -> { key = String.valueOf(r.getPatientRef()); label = r.getPatientRef() != null && r.getPatientRef().startsWith("Patient/") ? r.getPatientRef().substring(8) : "—"; fkey = "q"; fval = label; }
                default -> { String a = r.getAction() == null ? "R" : r.getAction(); key = a; label = msg("explore.what." + a, loc); fkey = "action"; fval = a; }
            }
            Item g = groups.computeIfAbsent(key, k -> Item.builder().type("group").key(k).label(label).filterKey(fkey).filterValue(fval).build());
            g.setCount(g.getCount() + 1);
            if (r.getOutcomeCode() != null && !"0".equals(r.getOutcomeCode())) g.setFailed(g.getFailed() + 1);
            if (r.isBreakGlass()) g.setBtg(g.getBtg() + 1);
            if (g.getLast() == null || (r.getRecordedInstant() != null && r.getRecordedInstant().isAfter(g.getLast()))) g.setLast(r.getRecordedInstant());
            if (by.equals("actor")) g.setActorRole(Who.role(r.getAgentRef(), r.getAgentName()));
            if (by.equals("submitted") && r.getFacility() != null) g.setFacilityLabel(facNames.getOrDefault(r.getFacility(), tail(r.getFacility())));
        }
        List<Item> list = new ArrayList<>(groups.values());
        list.sort((a, b) -> Integer.compare(b.getCount(), a.getCount()));
        int max = list.isEmpty() ? 1 : list.get(0).getCount();
        for (Item g : list) g.setPct(Math.max(2, g.getCount() * 100 / max));
        boolean capped = total > rows.size();
        return new View(chart(f, prefs, tf, loc), list, chips(f, loc, sysNames, facNames), rangeLabel(f, prefs, loc, tf), true, capped, rows.size());
    }

    // ── chart ────────────────────────────────────────────────────────────────

    private Chart chart(AuditFilter f, UserPrefs prefs, TimeFormat tf, Locale loc) {
        ZoneId zone = prefs.zone();
        Instant now = Instant.now();
        Instant from, to = now;
        try {
            if (f.getSince() != null && !f.getSince().isBlank()) {
                from = Instant.parse(f.getSince().strip());
                if (f.getUntil() != null && !f.getUntil().isBlank()) to = Instant.parse(f.getUntil().strip());
            } else if (AuditEventService.rangeStart(f.getRange()) != null) {
                from = AuditEventService.rangeStart(f.getRange());
            } else if (f.getDateFrom() != null && !f.getDateFrom().isBlank()) {
                from = LocalDate.parse(f.getDateFrom().strip()).atStartOfDay(ZoneOffset.UTC).toInstant();
                if (f.getDateTo() != null && !f.getDateTo().isBlank()) to = LocalDate.parse(f.getDateTo().strip()).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            } else if (f.getDateTo() != null && !f.getDateTo().isBlank()) {
                to = LocalDate.parse(f.getDateTo().strip()).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
                from = to.minus(30, ChronoUnit.DAYS);
            } else {
                from = now.minus(30, ChronoUnit.DAYS);
            }
        } catch (RuntimeException e) {
            return new Chart(List.of(), List.of(), 0, 0, false, false);
        }
        if (!to.isAfter(from)) return new Chart(List.of(), List.of(), 0, 0, false, false);
        long stepMs = Math.max(1000, (to.toEpochMilli() - from.toEpochMilli()) / BUCKETS);
        boolean hourly = ChronoUnit.HOURS.between(from, to) <= 36;
        DateTimeFormatter hf = DateTimeFormatter.ofPattern(prefs.clock24() ? "HH:mm" : "h a", loc).withZone(zone);
        DateTimeFormatter df = DateTimeFormatter.ofPattern("d MMM", loc).withZone(zone);
        DateTimeFormatter dfWeek = DateTimeFormatter.ofPattern(prefs.clock24() ? "EEE HH:mm" : "EEE h a", loc).withZone(zone);
        Map<String, List<String>> base = audit.paramsFor(f);
        List<int[]> counts = new ArrayList<>();
        List<Instant[]> spans = new ArrayList<>();
        int max = 1;
        long total = 0, failed = 0;
        boolean resultFixed = f.getResult() != null || (f.getOutcome() != null && !f.getOutcome().isBlank());
        boolean fixedFailed = "failed".equals(f.getResult()) || (f.getOutcome() != null && !f.getOutcome().isBlank() && !"0".equals(f.getOutcome().strip()));
        try {
            for (int i = 0; i < BUCKETS; i++) {
                Instant a = Instant.ofEpochMilli(from.toEpochMilli() + i * stepMs);
                Instant b = i == BUCKETS - 1 ? to : Instant.ofEpochMilli(from.toEpochMilli() + (i + 1) * stepMs);
                Map<String, List<String>> p = copy(base);
                p.computeIfAbsent("date", k -> new ArrayList<>()).add("ge" + a.truncatedTo(ChronoUnit.SECONDS));
                p.get("date").add("lt" + b.truncatedTo(ChronoUnit.SECONDS));
                int n = audit.countWhere(p);
                int bad;
                if (n == 0) bad = 0;
                else if (resultFixed) bad = fixedFailed ? n : 0;
                else {
                    Map<String, List<String>> q = copy(p);
                    q.put("outcome", new ArrayList<>(List.of("4,8,12")));
                    bad = audit.countWhere(q);
                }
                counts.add(new int[]{n - bad, bad});
                spans.add(new Instant[]{a, b});
                max = Math.max(max, n);
                total += n; failed += bad;
            }
        } catch (RuntimeException e) {
            return new Chart(List.of(), List.of(), 0, 0, false, false);
        }
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < counts.size(); i++) {
            int ok = counts.get(i)[0], bad = counts.get(i)[1];
            Instant a = spans.get(i)[0], b = spans.get(i)[1];
            String tip = msg("explore.chart.bar", loc, (hourly ? hf.format(a) : df.format(a)) + " – " + (hourly ? hf.format(b) : df.format(b)), ok + bad, bad);
            bars.add(new Bar(ok, bad, (ok + bad) * 100 / max, a.truncatedTo(ChronoUnit.SECONDS).toString(), b.truncatedTo(ChronoUnit.SECONDS).toString(), tip));
        }
        List<Axis> axis = new ArrayList<>();
        int every = 6;
        for (int i = 0; i < BUCKETS; i += every) {
            Instant a = spans.get(i)[0];
            String lab = hourly ? (i == 0 ? dfWeek.format(a) : hf.format(a)) : df.format(a);
            axis.add(new Axis(i * 100 / BUCKETS, lab, false));
        }
        boolean live = Math.abs(to.toEpochMilli() - now.toEpochMilli()) < 5000;
        axis.add(new Axis(100, live ? msg("explore.chart.now", loc) : (hourly ? hf.format(to) : df.format(to)), true));
        return new Chart(bars, axis, total, failed, true, false);
    }

    // ── chips and the range button ───────────────────────────────────────────

    private List<Chip> chips(AuditFilter f, Locale loc, Map<String, String> sysNames, Map<String, String> facNames) {
        List<Chip> c = new ArrayList<>();
        add(c, "q", f.getQ());
        String res = f.getResult() == null || f.getResult().isBlank() ? "any" : f.getResult();
        c.add(new Chip("result", msg("explore.chip.result", loc), msg("explore.val." + res, loc)));
        if (f.getOutcome() != null && !f.getOutcome().isBlank()) c.add(new Chip("outcome", msg("explore.chip.outcome", loc), f.getOutcome()));
        add(c, "agent", f.getAgent() == null ? null : people.snapshot().people().stream().filter(x -> f.getAgent().equals(x.ref())).map(PeopleIndex.Person::name).findFirst().orElse(f.getAgent()));
        if (f.getClient() != null && !f.getClient().isBlank()) add(c, "client", sysNames.getOrDefault(f.getClient(), f.getClient()));
        if (f.getFacility() != null && !f.getFacility().isBlank()) add(c, "facility", facNames.getOrDefault(f.getFacility(), tail(f.getFacility())));
        add(c, "encounter", f.getEncounter());
        add(c, "department", f.getDepartment());
        add(c, "purpose", f.getPurpose());
        if (f.getAction() != null && !f.getAction().isBlank()) add(c, "action", msg("explore.what." + f.getAction().toUpperCase(Locale.ROOT), loc));
        if (f.isBreakGlass()) c.add(new Chip("breakGlass", msg("explore.chip.breakGlass", loc), msg("explore.val.yes", loc)));
        if (f.isIncomplete()) c.add(new Chip("incomplete", msg("explore.chip.incomplete", loc), msg("explore.val.yes", loc)));
        if (f.getTab() != null && !"all".equals(f.getTab())) c.add(new Chip("tab", msg("explore.chip.tab", loc), msg("explore.tab." + f.getTab(), loc)));
        add(c, "dateFrom", f.getDateFrom());
        add(c, "dateTo", f.getDateTo());
        if ("none".equals(f.getLink())) c.add(new Chip("link", msg("explore.chip.link", loc), msg("explore.val.nolink", loc)));
        return c;
    }

    private void add(List<Chip> c, String key, String value) {
        if (value == null || value.isBlank()) return;
        c.add(new Chip(key, messages.getMessage("explore.chip." + key, null, key, Locale.ENGLISH), value));
    }

    private String rangeLabel(AuditFilter f, UserPrefs prefs, Locale loc, TimeFormat tf) {
        ZoneId zone = prefs.zone();
        if (f.getSince() != null && !f.getSince().isBlank()) {
            try {
                Instant a = Instant.parse(f.getSince().strip());
                Instant b = f.getUntil() != null && !f.getUntil().isBlank() ? Instant.parse(f.getUntil().strip()) : Instant.now();
                DateTimeFormatter d = DateTimeFormatter.ofPattern(prefs.clock24() ? "d MMM HH:mm" : "d MMM h:mm a", loc).withZone(zone);
                DateTimeFormatter t = DateTimeFormatter.ofPattern(prefs.clock24() ? "HH:mm" : "h:mm a", loc).withZone(zone);
                boolean same = a.atZone(zone).toLocalDate().equals(b.atZone(zone).toLocalDate());
                return d.format(a) + " – " + (same ? t.format(b) : d.format(b));
            } catch (RuntimeException ignored) { /* falls through to the default label */ }
        }
        if ((f.getDateFrom() != null && !f.getDateFrom().isBlank()) || (f.getDateTo() != null && !f.getDateTo().isBlank())) return msg("explore.range.custom", loc);
        String r = f.getRange() == null || f.getRange().isBlank() ? "all" : f.getRange();
        return msg("explore.range." + r, loc);
    }

    // ── "no link to this patient's care" (Demo: stand-in registry, consent first) ──

    public record LinkResult(List<AuditRow> rows, int total, String note) {}

    /**
     * Treatment claims by clinicians with no care link to the patient. Looks at the newest {@value #LINK_SCAN} events that match the other filters,
     * asks the consent service about each distinct patient (every check is logged) and only then the registry.
     */
    public LinkResult withoutCareLink(AuditFilter f, int page, int size) {
        List<AuditRow> scanned = new ArrayList<>();
        Map<String, List<String>> p = audit.paramsFor(f);
        int off = 0;
        while (off < LINK_SCAN) {
            List<AuditRow> chunk = audit.pageWhere(p, 100, off, f.getSort());
            scanned.addAll(chunk);
            off += chunk.size();
            if (chunk.size() < 100) break;
        }
        Map<String, RegistryClient.PatientInfo> info = new HashMap<>();
        Set<String> refused = new java.util.HashSet<>();
        String role = ShellAdvice.primaryRole();
        String user = String.valueOf(PrefsContext.username());
        List<AuditRow> keep = new ArrayList<>();
        for (AuditRow r : scanned) {
            String pr = r.getPatientRef();
            if (pr == null || !pr.startsWith("Patient/")) continue;
            String purpose = r.getPurposeCode() == null ? "" : r.getPurposeCode().toUpperCase(Locale.ROOT);
            boolean claim = purpose.equals("TREAT") || purpose.equals("ETREAT") || purpose.equals("BTG");
            String ag = r.getAgentName() == null ? "" : r.getAgentName();
            if (!claim || !(ag.startsWith("Dr.") || ag.startsWith("Nurse") || (r.getAgentRef() != null && r.getAgentRef().startsWith("Practitioner/")))) continue;
            if (refused.contains(pr)) continue;
            if (!info.containsKey(pr)) {
                if (info.size() >= LINK_PATIENTS) continue;
                ConsentClient.Decision d = consent.check(pr, role, PatientController.PURPOSE);
                try { consentLog.add(user, pr, role, PatientController.PURPOSE, d); } catch (RuntimeException ignored) { /* a log failure never shows data */ }
                if (!d.permitted()) { refused.add(pr); continue; }
                try { info.put(pr, registry.patient(pr.substring(8)).orElse(null)); }
                catch (RegistryClient.RegistryUnavailable e) { refused.add(pr); continue; }
            }
            RegistryClient.PatientInfo pi = info.get(pr);
            if (pi != null && r.getFacility() != null && !pi.wasAt(r.getFacility())) keep.add(r);
        }
        int from = Math.min(keep.size(), Math.max(0, (page - 1) * size));
        int to = Math.min(keep.size(), from + size);
        return new LinkResult(keep.subList(from, to), keep.size(), "link");
    }

    // ── detail panel ─────────────────────────────────────────────────────────

    public record Panel(Item item, String patientLine, String patientState, String careLink, String purpose, long sameCount, long samePeople, long sameSystems, boolean sameKnown,
                        String encounter, boolean canReview) {}

    public Panel panel(String id, UserPrefs prefs, TimeFormat tf, Locale loc) {
        AuditRow r;
        try {
            List<AuditRow> l = audit.pageWhere(Map.of("_id", List.of(id)), 1, 0, "-date");
            if (l.isEmpty()) return null;
            r = l.get(0);
        } catch (RuntimeException e) { return null; }
        Map<String, String> sysNames = Map.of(), facNames = Map.of();
        try { sysNames = systems.names(); } catch (RuntimeException ignored) { }
        try { facNames = facilities.names(); } catch (RuntimeException ignored) { }
        ZoneId zone = prefs.zone();
        Item it = rowItem(r, prefs, sysNames, facNames, DateTimeFormatter.ofPattern(prefs.clock24() ? "HH:mm:ss" : "h:mm:ss a", loc).withZone(zone), "");
        String patientLine = null, state = "none", link = "unknown";
        if (it.getPatientId() != null) {
            String ref = "Patient/" + it.getPatientId();
            String role = ShellAdvice.primaryRole();
            ConsentClient.Decision d = consent.check(ref, role, PatientController.PURPOSE);
            try { consentLog.add(String.valueOf(PrefsContext.username()), ref, role, PatientController.PURPOSE, d); } catch (RuntimeException ignored) { }
            if (!d.permitted()) state = "withheld";
            else {
                try {
                    RegistryClient.PatientInfo pi = registry.patient(it.getPatientId()).orElse(null);
                    if (pi == null) state = "missing";
                    else {
                        state = "shown";
                        String where = pi.stays().isEmpty() ? null : pi.stays().get(0).facilityName();
                        String age = pi.ageBand();
                        patientLine = msg("explore.panel.registered", loc, where == null ? "—" : where, age == null ? "—" : age);
                        link = careLink(r, pi);
                    }
                } catch (RegistryClient.RegistryUnavailable e) { state = "down"; }
            }
        }
        long same = 0, ppl = 0, sys = 0; boolean known = false;
        if (it.getPatientId() != null && r.getRecordedInstant() != null) {
            try {
                Map<String, List<String>> p = new LinkedHashMap<>();
                p.put("entity-ref", new ArrayList<>(List.of("Patient/" + it.getPatientId())));
                p.put("date", new ArrayList<>(List.of("ge" + r.getRecordedInstant().minus(6, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS), "le" + r.getRecordedInstant().truncatedTo(ChronoUnit.SECONDS))));
                // "le" with a full time reads as "up to and including that second" in Chester; add one second so the event itself is counted once
                p.get("date").set(1, "lt" + r.getRecordedInstant().plusSeconds(1).truncatedTo(ChronoUnit.SECONDS));
                same = audit.countWhere(p);
                List<AuditRow> rs = audit.pageWhere(p, 100, 0, "-date");
                ppl = rs.stream().map(x -> x.getAgentRef() != null ? x.getAgentRef() : String.valueOf(x.getAgentName())).distinct().count();
                sys = rs.stream().map(x -> String.valueOf(x.getSubmittedBy())).distinct().count();
                known = true;
            } catch (RuntimeException ignored) { /* the box is left out */ }
        }
        String purpose = r.getPurposeLabel();
        return new Panel(it, patientLine, state, link, purpose, same, ppl, sys, known, r.getEncounterRef() == null ? null : tail(r.getEncounterRef()), Who.canReview());
    }

    /** Same rule as the patient page: only a treatment claim by a clinician is judged; without registry data the answer is "unknown". */
    static String careLink(AuditRow r, RegistryClient.PatientInfo info) {
        String p = r.getPurposeCode() == null ? "" : r.getPurposeCode().toUpperCase(Locale.ROOT);
        boolean claim = p.equals("TREAT") || p.equals("ETREAT") || p.equals("BTG");
        String agent = r.getAgentName() == null ? "" : r.getAgentName();
        boolean clinician = agent.startsWith("Dr.") || agent.startsWith("Nurse") || (r.getAgentRef() != null && r.getAgentRef().startsWith("Practitioner/"));
        if (!claim || !clinician) return "na";
        if (info == null || r.getFacility() == null) return "unknown";
        return info.wasAt(r.getFacility()) ? "ok" : "none";
    }
}

package com.akhester.auditui.breakglass;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.akhester.auditui.config.SlaPolicy;
import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.reviews.CaseRepository;
import com.akhester.auditui.service.AuditEventService;

/**
 * Break-glass records from Chester (the {@code break-glass=BTG} search) with the written reason that the sending system adds afterwards.
 * Reason state: given (in time), late (given after the deadline), waiting (none yet, deadline not reached), overdue (none, deadline passed).
 * The reason travels in two extensions on the AuditEvent; how real vendors will send it is still to be confirmed (AR-09).
 */
@Service
public class BreakGlassService {

    public static final int HISTORY_DAYS = 30;
    public static final int REPEAT_MORE_THAN = 2;
    private static final int LIMIT = 100;

    public record Row(AuditRow r, String state, Long minutesToReason, long waitingHours, int uses7d, int uses30d, String caseId, String caseStatus, String caseOutcome) {
        public boolean isRepeat() { return uses7d > REPEAT_MORE_THAN; }
        public String getEventId() { return r.getId(); }
    }
    public record Person(String name, String facility, int count) {}
    public record Day(LocalDate date, int withReason, int noReason) {}
    public record View(List<Row> rows, int total, int waiting, int overdue, int late, Long avgMinutes, int reviewedOk, int reviewable, int repeatPeople,
                       List<Person> topPeople, List<Day> trend, boolean truncated, String error) {}

    private final AuditEventService audit;
    private final CaseRepository cases;
    private final SlaPolicy sla;

    public BreakGlassService(AuditEventService audit, CaseRepository cases, SlaPolicy sla) {
        this.audit = audit;
        this.cases = cases;
        this.sla = sla;
    }

    public long deadlineHours() { return sla.overdueHours(); }

    /** Everything the page needs for a period (24 hours, 7 or 30 days), computed from the last 30 days of break-glass records. */
    public View load(int periodDays, ZoneId zone) {
        Instant now = Instant.now();
        List<AuditRow> all;
        try {
            Map<String, List<String>> p = new LinkedHashMap<>();
            p.put("break-glass", List.of("BTG"));
            p.put("date", List.of("ge" + now.minus(HISTORY_DAYS, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS)));
            all = audit.recentWhere(p, LIMIT, "-date");
        } catch (RuntimeException e) {
            return new View(List.of(), 0, 0, 0, 0, null, 0, 0, 0, List.of(), List.of(), false, "unreachable");
        }
        boolean truncated = all.size() >= LIMIT;
        List<AuditRow> bg = all.stream().filter(r -> r.getRecordedInstant() != null).sorted(Comparator.comparing(AuditRow::getRecordedInstant).reversed()).toList();

        Map<String, String> caseOf = cases.caseOfEvents(bg.stream().map(AuditRow::getId).toList());
        Map<String, CaseRepository.Case> caseById = new LinkedHashMap<>();
        for (String id : caseOf.values().stream().distinct().toList()) cases.find(id).ifPresent(c -> caseById.put(id, c));

        Instant from = periodDays <= 0 ? now.minus(24, ChronoUnit.HOURS) : now.minus(periodDays, ChronoUnit.DAYS);
        Instant week = now.minus(7, ChronoUnit.DAYS);
        Map<String, List<AuditRow>> byPerson = bg.stream().collect(Collectors.groupingBy(r -> nz(r.getAgentName()), LinkedHashMap::new, Collectors.toList()));

        List<Row> rows = new ArrayList<>();
        for (AuditRow r : bg) {
            if (r.getRecordedInstant().isBefore(from)) continue;
            List<AuditRow> mine = byPerson.get(nz(r.getAgentName()));
            int uses30 = mine.size();
            int uses7 = (int) mine.stream().filter(x -> !x.getRecordedInstant().isBefore(week)).count();
            Instant dl = r.getRecordedInstant().plus(sla.overdueHours(), ChronoUnit.HOURS);
            String state;
            Long minutes = null;
            long waiting = Duration.between(r.getRecordedInstant(), now).toHours();
            if (r.getBreakGlassReason() != null) {
                if (r.getReasonGivenAt() != null) {
                    minutes = Duration.between(r.getRecordedInstant(), r.getReasonGivenAt()).toMinutes();
                    state = r.getReasonGivenAt().isAfter(dl) ? "late" : "given";
                } else state = "given";
            } else state = now.isAfter(dl) ? "overdue" : "waiting";
            CaseRepository.Case c = caseById.get(caseOf.get(r.getId()));
            rows.add(new Row(r, state, minutes, waiting, uses7, uses30, c == null ? null : c.id(), c == null ? null : c.status(), c == null ? null : c.outcome()));
        }

        int waiting = (int) rows.stream().filter(x -> "waiting".equals(x.state())).count();
        int overdue = (int) rows.stream().filter(x -> "overdue".equals(x.state())).count();
        int late = (int) rows.stream().filter(x -> "late".equals(x.state())).count();
        List<Long> mins = rows.stream().map(Row::minutesToReason).filter(m -> m != null).toList();
        Long avg = mins.isEmpty() ? null : Math.round(mins.stream().mapToLong(Long::longValue).average().orElse(0));
        int ok = (int) rows.stream().filter(x -> "reviewed".equals(x.r().getReviewStatus())).count();
        int reviewable = rows.size();
        Map<String, Long> inPeriod = rows.stream().collect(Collectors.groupingBy(x -> nz(x.r().getAgentName()), Collectors.counting()));
        int repeatPeople = (int) inPeriod.values().stream().filter(n -> n > REPEAT_MORE_THAN).count();

        List<Person> top = byPerson.entrySet().stream()
            .map(e -> new Person(e.getKey(), e.getValue().get(0).getFacility(), e.getValue().size()))
            .sorted(Comparator.comparingInt(Person::count).reversed().thenComparing(Person::name)).limit(5).toList();

        // 14 days in the person's own time zone, oldest first
        LocalDate today = now.atZone(zone).toLocalDate();
        Map<LocalDate, int[]> days = new LinkedHashMap<>();
        for (int i = 13; i >= 0; i--) days.put(today.minusDays(i), new int[2]);
        for (AuditRow r : bg) {
            int[] d = days.get(r.getRecordedInstant().atZone(zone).toLocalDate());
            if (d == null) continue;
            boolean ok2 = r.getBreakGlassReason() != null && (r.getReasonGivenAt() == null || !r.getReasonGivenAt().isAfter(r.getRecordedInstant().plus(sla.overdueHours(), ChronoUnit.HOURS)));
            d[ok2 ? 0 : 1]++;
        }
        List<Day> trend = days.entrySet().stream().map(e -> new Day(e.getKey(), e.getValue()[0], e.getValue()[1])).toList();
        return new View(rows, rows.size(), waiting, overdue, late, avg, ok, reviewable, repeatPeople, top, trend, truncated, null);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** "5 h 10 min", "38 min". */
    public static String minutes(Long m) {
        if (m == null) return "—";
        if (m < 60) return m + " min";
        long h = m / 60;
        if (h < 48) return h + " h " + (m % 60) + " min";
        return (h / 24) + " d " + (h % 24) + " h";
    }
}

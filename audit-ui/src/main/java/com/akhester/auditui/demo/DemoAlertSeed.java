package com.akhester.auditui.demo;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.hl7.fhir.r4.model.AuditEvent;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.akhester.auditui.alerts.AlertFiringRepository;
import com.akhester.auditui.alerts.AlertRules;
import com.akhester.auditui.alerts.AlertRules.Rule;

/**
 * DEMONSTRATION ONLY. Works out which alert rules would have fired over the last 7 days of the made-up records, with the real thresholds
 * from configuration, and stores the firings. This is the only place rules are evaluated in this build (the live engine is Planned).
 */
@Component
@Order(30)
@ConditionalOnProperty(name = "audit.ui.demo", havingValue = "true")
public class DemoAlertSeed implements ApplicationRunner {

    private final DemoAuditData data;
    private final AlertRules rules;
    private final AlertFiringRepository firings;
    private final JdbcTemplate jdbc;
    private final Instant now = Instant.now();

    public DemoAlertSeed(DemoAuditData data, AlertRules rules, AlertFiringRepository firings, JdbcTemplate jdbc) {
        this.data = data;
        this.rules = rules;
        this.firings = firings;
        this.jdbc = jdbc;
    }

    private static Instant at(AuditEvent e) { return e.getRecorded().toInstant(); }
    private static String agent(AuditEvent e) { return e.getAgentFirstRep().getName(); }
    private static String facility(AuditEvent e) { return e.getSource().getObserver().getReference(); }
    private static boolean btg(AuditEvent e) { return e.getMeta().getSecurity().stream().anyMatch(c -> "BTG".equals(c.getCode())); }
    private static String patient(AuditEvent e) {
        return e.getEntity().stream().filter(x -> x.hasWhat() && x.getWhat().getReference().startsWith("Patient/")).map(x -> x.getWhat().getReference()).findFirst().orElse(null);
    }
    private static String client(AuditEvent e) {
        return e.getMeta().getTag().stream().filter(t -> t.getSystem() != null && t.getSystem().endsWith("/submitting-client")).map(t -> t.getCode()).findFirst().orElse(null);
    }

    @Override
    public void run(ApplicationArguments args) {
        firings.deleteDemoSeed();
        Instant from = now.minus(7, ChronoUnit.DAYS);
        List<AuditEvent> week = data.events().stream().filter(e -> !at(e).isBefore(from.minus(2, ChronoUnit.DAYS))).sorted(Comparator.comparing(AuditEvent::getRecorded)).toList();

        Rule hv = rules.get("highvolume");
        if (hv.isWatching()) window(week, hv, e -> true, e -> agent(e), from, null);

        Rule fl = rules.get("failures");
        if (fl.isWatching()) window(week, fl, e -> e.getOutcome() != AuditEvent.AuditEventOutcome._0, e -> client(e), from, null);

        Rule ra = rules.get("repeataccess");
        if (ra.isWatching()) window(week, ra, e -> !btg(e) && patient(e) != null, e -> agent(e) + "|" + patient(e), from, e -> agent(e));

        Rule rb = rules.get("repeatbtg");
        if (rb.isWatching()) window(week, rb, DemoAlertSeed::btg, e -> agent(e), from, null);

        Rule br = rules.get("btgreason");
        if (br.isWatching()) {
            for (AuditEvent e : week) {
                if (!btg(e) || !e.getExtensionsByUrl(DemoAuditData.EXT_REASON).isEmpty()) continue;
                Instant due = at(e).plus(br.hours(), ChronoUnit.HOURS);
                if (due.isAfter(now) || due.isBefore(from)) continue;
                insert(br, due, agent(e), facility(e), br.hours(), "btgreason", agent(e));
            }
        }
    }

    /**
     * Slides a window of {@code rule.hours()} hours over the matching records of each key; when more than {@code rule.count()} fall inside it, the rule fires
     * once (then stays quiet for one window length for that key).
     */
    private void window(List<AuditEvent> events, Rule rule, Predicate<AuditEvent> match, Function<AuditEvent, String> key, Instant from, Function<AuditEvent, String> subject) {
        Map<String, List<AuditEvent>> byKey = events.stream().filter(match).filter(e -> key.apply(e) != null).collect(Collectors.groupingBy(key, LinkedHashMap::new, Collectors.toList()));
        long windowSec = rule.hours() * 3600L;
        for (List<AuditEvent> list : byKey.values()) {
            int lo = 0;
            Instant quietUntil = Instant.MIN;
            for (int hi = 0; hi < list.size(); hi++) {
                while (at(list.get(hi)).getEpochSecond() - at(list.get(lo)).getEpochSecond() > windowSec) lo++;
                int n = hi - lo + 1;
                Instant t = at(list.get(hi));
                if (n > rule.count() && t.isAfter(quietUntil) && !t.isBefore(from)) {
                    AuditEvent e = list.get(hi);
                    String who = subject == null ? key.apply(e) : subject.apply(e);
                    // keep counting inside the same window so the stored number is the peak
                    int peak = n;
                    for (int k = hi + 1; k < list.size() && at(list.get(k)).getEpochSecond() - at(list.get(lo)).getEpochSecond() <= windowSec; k++) peak++;
                    insert(rule, t, who, facility(e), peak, rule.id(), who);
                    quietUntil = t.plusSeconds(windowSec);
                }
            }
        }
    }

    private void insert(Rule rule, Instant at, String subject, String facility, int amount, String ruleId, String caseSubject) {
        String caseId = null;
        if (rule.opensCase()) {
            List<String> c = jdbc.query("SELECT id FROM review_case WHERE demo_seed=TRUE AND subject=? AND kind IN (?,?) ORDER BY seq DESC LIMIT 1", (rs, i) -> rs.getString(1),
                caseSubject, ruleId.equals("repeataccess") ? "repeat" : ruleId, ruleId.equals("repeataccess") ? "repeat" : ruleId);
            if (!c.isEmpty()) caseId = c.get(0);
        }
        firings.insert(ruleId, at, rule.severity(), subject, facility, amount, caseId, true);
    }
}

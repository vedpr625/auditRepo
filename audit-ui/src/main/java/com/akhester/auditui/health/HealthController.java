package com.akhester.auditui.health;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

import com.akhester.auditui.page.AppSettingRepository;
import com.akhester.auditui.prefs.PrefsContext;
import com.akhester.auditui.prefs.UserPrefs;
import com.akhester.auditui.service.AuditEventService;

import jakarta.servlet.http.HttpServletRequest;

/** Health: is Chester answering, is the hash chain intact, how many events arrived each day, is this app's own database up. */
@Controller
public class HealthController {

    /** One day of the ingestion table. count is -1 when Chester could not answer. */
    public record Day(LocalDate day, Instant from, int count) {}

    private final AuditEventService audit;
    private final JdbcTemplate jdbc;
    private final AppSettingRepository settings;
    private final PrefsContext prefsContext;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${management.server.port:}")
    private String metricsPort;

    @Value("${management.server.address:}")
    private String metricsAddress;

    public HealthController(AuditEventService audit, JdbcTemplate jdbc, AppSettingRepository settings, PrefsContext prefsContext) {
        this.audit = audit;
        this.jdbc = jdbc;
        this.settings = settings;
        this.prefsContext = prefsContext;
    }

    @GetMapping("/health")
    public String page(Model model) {
        model.addAttribute("registryUrl", settings.get(AppSettingRepository.REGISTRY_BASE_URL).orElse(null));
        model.addAttribute("metricsPort", metricsPort);
        model.addAttribute("metricsAddress", metricsAddress.isBlank() ? "localhost" : metricsAddress);
        return "health";
    }

    /** Lazy panel: Chester and this app's database. */
    @GetMapping("/health/systems")
    public String systems(Model model) {
        model.addAttribute("chester", audit.chesterInfo());
        boolean dbUp;
        try { dbUp = Integer.valueOf(1).equals(jdbc.queryForObject("SELECT 1", Integer.class)); } catch (Exception e) { dbUp = false; }
        model.addAttribute("dbUp", dbUp);
        model.addAttribute("dbKind", dbUrl.startsWith("jdbc:h2:") ? "h2" : dbUrl.startsWith("jdbc:postgresql:") ? "postgresql" : "other");
        return "fragments/health :: systems";
    }

    /** Lazy panel: events received per day for the last 7 days, days cut at midnight in the person's own time zone. */
    @GetMapping("/health/ingestion")
    public String ingestion(Model model, HttpServletRequest request) {
        UserPrefs p = prefsContext.forRequest(request);
        ZoneId zone = p.zone();
        LocalDate today = LocalDate.now(zone);
        List<Day> days = new ArrayList<>();
        boolean failed = false;
        for (int i = 0; i < 7; i++) {
            LocalDate d = today.minusDays(i);
            ZonedDateTime start = d.atStartOfDay(zone);
            ZonedDateTime end = d.plusDays(1).atStartOfDay(zone);
            int c;
            try {
                Map<String, List<String>> params = new LinkedHashMap<>();
                params.put("date", new ArrayList<>(List.of("ge" + start.toInstant(), "lt" + end.toInstant())));
                c = audit.countWhere(params);
            } catch (Exception e) {
                c = -1;
                failed = true;
            }
            days.add(new Day(d, start.toInstant(), c));
            if (failed) break;   // Chester is not answering: do not wait for six more failures
        }
        model.addAttribute("days", days);
        model.addAttribute("failed", failed);
        int max = days.stream().mapToInt(Day::count).max().orElse(0);
        model.addAttribute("maxCount", Math.max(max, 1));
        return "fragments/health :: ingestion";
    }

    /** Button: asks Chester to verify the whole hash chain. Chester decides who may do this. */
    @PostMapping("/health/integrity")
    public String integrity(Model model) {
        model.addAttribute("result", audit.verifyChain(null));
        model.addAttribute("checkedAt", Instant.now());
        return "fragments/health :: integrity";
    }
}

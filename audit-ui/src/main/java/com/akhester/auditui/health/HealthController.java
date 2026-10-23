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
    private final HealthService health;
    private final org.springframework.context.MessageSource messages;

    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${management.server.port:}")
    private String metricsPort;

    @Value("${management.server.address:}")
    private String metricsAddress;

    public HealthController(AuditEventService audit, JdbcTemplate jdbc, AppSettingRepository settings, PrefsContext prefsContext, HealthService health, org.springframework.context.MessageSource messages) {
        this.messages = messages;
        this.health = health;
        this.audit = audit;
        this.jdbc = jdbc;
        this.settings = settings;
        this.prefsContext = prefsContext;
    }

    @GetMapping("/health")
    public String page(Model model) {
        var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        model.addAttribute("allowed", com.akhester.auditui.page.ShellAdvice.primaryRole() != null
            || (auth != null && auth.getAuthorities().stream().anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()))));
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

    /** Live figures: tiles and the per-minute chart. Refreshed every 30 seconds. */
    @GetMapping("/health/live")
    public String live(Model model) {
        try { model.addAttribute("h", health.live()); }
        catch (RuntimeException e) { model.addAttribute("failed", true); }
        return "fragments/health :: live";
    }

    /** Integrity by day, with the result of the last check (if any). */
    @GetMapping("/health/days")
    public String days(Model model) {
        model.addAttribute("days", health.days());
        model.addAttribute("check", health.lastCheck());
        return "fragments/health :: days";
    }

    /** Button: asks Chester to verify each of the last seven days. Chester decides who may do this. */
    @PostMapping("/health/verify")
    public String verify(Model model, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("HX-Trigger", "healthChecked");
        String by = PrefsContext.username();
        var lc = health.verify(by == null ? "?" : by);
        model.addAttribute("days", health.days());
        model.addAttribute("check", lc);
        return "fragments/health :: days";
    }

    /** Integrity tile: from the last check only (nothing is asked of Chester here). */
    @GetMapping("/health/integrity-tile")
    public String tile(Model model) {
        var lc = health.lastCheck();
        model.addAttribute("check", lc);
        int total = lc == null ? 0 : lc.days().size();
        long ok = lc == null ? 0 : lc.days().values().stream().filter(HealthService.DayCheck::valid).count();
        model.addAttribute("total", total);
        model.addAttribute("ok", ok);
        model.addAttribute("bad", total - ok);
        return "fragments/health :: tile";
    }

    /** Oldest record held, for the Keeping records panel. */
    @GetMapping("/health/oldest")
    @org.springframework.web.bind.annotation.ResponseBody
    public String oldest(jakarta.servlet.http.HttpServletRequest request, java.util.Locale locale) {
        try {
            var rows = audit.recentWhere(new LinkedHashMap<>(), 1, "date");
            if (rows.isEmpty() || rows.get(0).getRecordedInstant() == null) return "—";
            Instant oldest = rows.get(0).getRecordedInstant();
            var tf = new com.akhester.auditui.page.TimeFormat(prefsContext.forRequest(request), locale, messages, Instant.now());
            return org.springframework.web.util.HtmlUtils.htmlEscape(tf.exact(oldest));
        } catch (RuntimeException e) { return "—"; }
    }
}

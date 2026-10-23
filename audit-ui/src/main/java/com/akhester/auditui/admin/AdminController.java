package com.akhester.auditui.admin;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.akhester.auditui.demo.DemoUsers;
import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.people.FacilityDirectory;
import com.akhester.auditui.people.PeopleIndex;
import com.akhester.auditui.prefs.PrefsContext;
import com.akhester.auditui.service.AuditEventService;

/**
 * Admin (administrators only, enforced in the security configuration): sending systems, facilities, users (planned), rules (read only),
 * change history, and one page per sending system. Users, roles and Keycloak clients stay in Keycloak in the first release.
 */
@Controller
public class AdminController {

    static final List<String> TABS = List.of("systems", "facilities", "users", "rules", "history");
    private static final Pattern CLIENT = Pattern.compile("[A-Za-z0-9._:@-]{1,255}");

    public record SystemRow(SendingSystemRepository.System system, SystemStats.Summary stats) {}
    public record FacilityRow(FacilityDirectory.Facility facility, int events30, int failed30) {}
    public record Rule(String key, String value, String source) {}

    private final SendingSystemRepository systems;
    private final AuditEventService audit;
    private final SystemStats stats;
    private final FacilityDirectory facilities;
    private final PeopleIndex people;
    private final AdminChangeLog changes;
    private final DemoUserRepository demoUsers;
    private final boolean demo;
    private final String warnHours, overdueHours, noRecord, cacheSeconds, zone, sessionTimeout;

    public AdminController(SendingSystemRepository systems, AuditEventService audit, SystemStats stats, FacilityDirectory facilities, PeopleIndex people, AdminChangeLog changes, DemoUserRepository demoUsers,
                           @Value("${audit.ui.demo:false}") boolean demo,
                           @Value("${audit.sla.warning-hours:6}") String warnHours, @Value("${audit.sla.overdue-hours:24}") String overdueHours,
                           @Value("${audit.ui.consent-no-record:deny}") String noRecord, @Value("${audit.ui.overview-cache-seconds:60}") String cacheSeconds,
                           @Value("${audit.ui.default-time-zone:Asia/Colombo}") String zone, @Value("${server.servlet.session.timeout:30m}") String sessionTimeout) {
        this.systems = systems; this.audit = audit; this.stats = stats; this.facilities = facilities; this.people = people; this.changes = changes; this.demoUsers = demoUsers;
        this.demo = demo; this.warnHours = warnHours; this.overdueHours = overdueHours; this.noRecord = noRecord; this.cacheSeconds = cacheSeconds; this.zone = zone; this.sessionTimeout = sessionTimeout;
    }

    @GetMapping("/admin")
    public String page(@RequestParam(required = false) String tab, Model model) {
        String t = tab != null && TABS.contains(tab) ? tab : "systems";
        model.addAttribute("tab", t);
        model.addAttribute("tabs", TABS);
        switch (t) {
            case "systems" -> {
                List<SystemRow> rows = new ArrayList<>();
                for (SendingSystemRepository.System s : systems.all()) rows.add(new SystemRow(s, stats.summary(s.clientId())));
                model.addAttribute("rows", rows);
            }
            case "facilities" -> {
                PeopleIndex.Snapshot snap = people.snapshot();
                LocalDate today = LocalDate.now(ZoneOffset.UTC);
                List<FacilityRow> rows = new ArrayList<>();
                try {
                    for (FacilityDirectory.Facility f : facilities.withSeen(snap.facilities())) {
                        Map<String, List<String>> q = Map.of("source-ref", List.of(f.ref()), "date", List.of("ge" + today.minusDays(29)));
                        int all = audit.countWhere(q);
                        Map<String, List<String>> qf = new java.util.LinkedHashMap<>(q);
                        qf.put("outcome", List.of("4,8,12"));
                        rows.add(new FacilityRow(f, all, audit.countWhere(qf)));
                    }
                } catch (RuntimeException e) { model.addAttribute("chesterDown", true); }
                model.addAttribute("facRows", rows);
            }
            case "users" -> {
                model.addAttribute("demoUsers", demo ? DemoUsers.ALL : List.of());
                model.addAttribute("userRows", demo ? demoUsers.all() : List.of());
                model.addAttribute("userRoles", DemoUserRepository.ROLES);
            }
            case "rules" -> model.addAttribute("rules", rules());
            default -> model.addAttribute("changes", changes.recent(50));
        }
        model.addAttribute("demoMode", demo);
        if (!"history".equals(t)) model.addAttribute("recent", changes.recent(4));
        return "admin";
    }

    private List<Rule> rules() {
        return List.of(
            new Rule("warn", warnHours + " h", "audit.sla.warning-hours"),
            new Rule("overdue", overdueHours + " h", "audit.sla.overdue-hours"),
            new Rule("norecord", noRecord, "audit.ui.consent-no-record"),
            new Rule("session", sessionTimeout, "server.servlet.session.timeout"),
            new Rule("zone", zone, "audit.ui.default-time-zone"),
            new Rule("cache", cacheSeconds + " s", "audit.ui.overview-cache-seconds"),
            new Rule("quiet", SystemStats.QUIET_HOURS + " h", "built in"),
            new Rule("gone", SystemStats.GONE_DAYS + " d", "built in"),
            new Rule("failing", SystemStats.FAILING_AT + " / 7 d", "built in"),
            new Rule("scan", String.valueOf(PeopleIndex.SCAN_LIMIT), "built in"));
    }

    @GetMapping("/admin/systems/{clientId}")
    public String detail(@PathVariable String clientId, Model model) {
        if (!CLIENT.matcher(clientId).matches()) { model.addAttribute("notfound", true); return "admin-system"; }
        SendingSystemRepository.System s = systems.all().stream().filter(x -> x.clientId().equals(clientId)).findFirst().orElse(null);
        if (s == null) { model.addAttribute("notfound", true); return "admin-system"; }
        List<AuditRow> latest = stats.latest(clientId, 100);
        List<SystemStats.Day> days = stats.perDay(clientId, 14);
        model.addAttribute("s", s);
        model.addAttribute("st", stats.summary(clientId));
        model.addAttribute("days", days);
        model.addAttribute("dayMax", Math.max(1, days.stream().mapToInt(d -> d.ok() + d.failed()).max().orElse(1)));
        model.addAttribute("latest", latest.subList(0, Math.min(10, latest.size())));
        model.addAttribute("facCount", stats.facilities(latest).size());
        model.addAttribute("facNames", facilities.names());
        model.addAttribute("flash", model.asMap().get("flash"));
        return "admin-system";
    }

    /** Looks at the newest records Chester will show this person and remembers each sending client id it finds. */
    @PostMapping("/admin/systems/discover")
    public String discover(RedirectAttributes ra) {
        try {
            List<AuditRow> rows = audit.recentWhere(Map.of(), 100, "-date");
            Set<String> found = new TreeSet<>();
            for (AuditRow r : rows) {
                if (r.getSubmittedBy() == null) continue;
                systems.seen(r.getSubmittedBy(), r.getRecordedInstant() == null ? Instant.now() : r.getRecordedInstant(), PrefsContext.username());
                found.add(r.getSubmittedBy());
            }
            ra.addFlashAttribute("flash", "discovered");
            ra.addFlashAttribute("flashCount", found.size());
        } catch (RuntimeException e) {
            ra.addFlashAttribute("flash", "failed");
        }
        return "redirect:/admin";
    }

    @PostMapping("/admin/systems/rename")
    public String rename(@RequestParam String clientId, @RequestParam(required = false) String displayName,
                         @RequestParam(required = false) String note, RedirectAttributes ra) {
        if (!CLIENT.matcher(clientId).matches()) return "redirect:/admin";
        systems.rename(clientId, displayName, note, PrefsContext.username());
        try {
            changes.add(PrefsContext.username(), "Name shown for " + clientId + " set to \"" + (displayName == null ? "" : displayName.strip().replaceAll("\\p{Cntrl}", "")) + "\"", false, Instant.now());
        } catch (RuntimeException ignored) { /* the rename is done; the history line is a bonus */ }
        ra.addFlashAttribute("flash", "saved");
        return "redirect:/admin/systems/" + java.net.URLEncoder.encode(clientId, java.nio.charset.StandardCharsets.UTF_8);
    }

    // ── Users tab (demo): a made-up list kept in a demo table. Every change is written to the change history, marked Demo. ──
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._-]{1,38}");
    private static final Pattern EMAIL = Pattern.compile("[^@\\s]{1,100}@[^@\\s]{1,150}");

    private String demoChange(String what) {
        try { changes.add(PrefsContext.username(), what, true, Instant.now()); } catch (RuntimeException ignored) { /* the change is done; the history line is a bonus */ }
        return what;
    }

    @PostMapping("/admin/users/invite")
    public String invite(@RequestParam String username, @RequestParam String name, @RequestParam String email, @RequestParam String role, RedirectAttributes ra) {
        if (!demo) return "redirect:/admin?tab=users";
        String u = username.strip().toLowerCase(java.util.Locale.ROOT);
        String n = name.strip().replaceAll("\\p{Cntrl}", "");
        String e = email.strip();
        if (!USERNAME.matcher(u).matches() || n.isEmpty() || n.length() > 200 || !EMAIL.matcher(e).matches() || !DemoUserRepository.ROLES.contains(role)) { ra.addFlashAttribute("flash", "users.invalid"); return "redirect:/admin?tab=users"; }
        if (demoUsers.find(u) != null) { ra.addFlashAttribute("flash", "users.exists"); return "redirect:/admin?tab=users"; }
        demoUsers.add(u, n, e, role, true, Instant.now());
        demoChange("Invited " + u + " as " + role + " (demo)");
        ra.addFlashAttribute("flash", "users.invited");
        return "redirect:/admin?tab=users";
    }

    @PostMapping("/admin/users/role")
    public String role(@RequestParam String username, @RequestParam String role, RedirectAttributes ra) {
        DemoUserRepository.User old = demo ? demoUsers.find(username) : null;
        if (old == null || !DemoUserRepository.ROLES.contains(role)) { ra.addFlashAttribute("flash", "users.invalid"); return "redirect:/admin?tab=users"; }
        if (!old.role().equals(role)) {
            demoUsers.setRole(username, role);
            demoChange("Role of " + username + " changed from " + old.role() + " to " + role + " (demo)");
        }
        ra.addFlashAttribute("flash", "users.role");
        return "redirect:/admin?tab=users";
    }

    @PostMapping("/admin/users/enabled")
    public String enabled(@RequestParam String username, @RequestParam boolean enabled, RedirectAttributes ra) {
        DemoUserRepository.User old = demo ? demoUsers.find(username) : null;
        if (old == null) { ra.addFlashAttribute("flash", "users.invalid"); return "redirect:/admin?tab=users"; }
        if (username.equals(PrefsContext.username()) && !enabled) { ra.addFlashAttribute("flash", "users.self"); return "redirect:/admin?tab=users"; }
        demoUsers.setEnabled(username, enabled);
        demoChange((enabled ? "Enabled " : "Disabled ") + username + " (demo)");
        ra.addFlashAttribute("flash", enabled ? "users.enabled" : "users.disabled");
        return "redirect:/admin?tab=users";
    }

    @PostMapping("/admin/users/reset")
    public String reset(@RequestParam String username, RedirectAttributes ra) {
        DemoUserRepository.User old = demo ? demoUsers.find(username) : null;
        String temp = old == null ? null : demoUsers.resetPassword(username, Instant.now());
        if (temp == null) { ra.addFlashAttribute("flash", "users.invalid"); return "redirect:/admin?tab=users"; }
        demoChange("Password reset for " + username + " (demo; one-time password shown once, only its hash is kept)");
        ra.addFlashAttribute("flash", "users.reset");
        ra.addFlashAttribute("flashUser", username);
        ra.addFlashAttribute("flashTemp", temp);
        return "redirect:/admin?tab=users";
    }
}

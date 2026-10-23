package com.akhester.auditui.access;

import java.util.List;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.akhester.auditui.access.AccessPolicy.Cap;
import com.akhester.auditui.activity.ConsoleActivity;
import com.akhester.auditui.prefs.PrefsContext;

/** The "Who can do what, and how the console earns trust" board, and the request for a restricted record. */
@Controller
public class AccessController {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    public record Row(String key, Cap cap, boolean planned, boolean twoLines, List<String> levels) {}

    private final SensitivePatients sensitive;
    private final AccessRequestRepository requests;
    private final ConsoleActivity activity;
    private final com.akhester.auditui.activity.ActivityRepository activityRepo;
    private final int timeoutMinutes;

    public AccessController(SensitivePatients sensitive, AccessRequestRepository requests, ConsoleActivity activity, com.akhester.auditui.activity.ActivityRepository activityRepo,
                            @Value("${audit.ui.session-timeout-minutes:30}") int timeoutMinutes) {
        this.sensitive = sensitive; this.requests = requests; this.activity = activity; this.activityRepo = activityRepo; this.timeoutMinutes = timeoutMinutes;
    }

    @GetMapping("/access")
    public String board(Model model) {
        List<Row> rows = List.of(
            row("search", Cap.SEARCH, false, false), row("sensitive", Cap.SENSITIVE, false, true), row("decide", Cap.DECIDE, false, false),
            row("violation", Cap.RULE_VIOLATION, false, false), row("reports", Cap.REPORTS, false, false), row("rules", Cap.ALERT_RULES, true, false),
            row("settings", Cap.SETTINGS, false, false), row("activity", Cap.ACTIVITY_LOG, false, false));
        model.addAttribute("roles", AccessPolicy.ROLES);
        model.addAttribute("rows", rows);
        model.addAttribute("mine", AccessPolicy.myRoles());
        model.addAttribute("recent", AccessPolicy.canViewActivity() ? activityRepo.recent(null, 5, 0) : List.of());
        model.addAttribute("canActivity", AccessPolicy.canViewActivity());
        model.addAttribute("timeoutMinutes", timeoutMinutes);
        model.addAttribute("sensitiveCount", sensitive.ids().size());
        model.addAttribute("sensitiveIds", AccessPolicy.canOpenSensitive() ? sensitive.ids().stream().sorted().toList() : List.of());
        return "access";
    }

    private static Row row(String key, Cap cap, boolean planned, boolean two) {
        return new Row(key, cap, planned, two, AccessPolicy.ROLES.stream().map(r -> AccessPolicy.level(r, cap)).toList());
    }

    /** "Ask the privacy officer for access". The request itself is recorded in the console and written to Chester; nothing about the patient is read. */
    @PostMapping("/patient/{id}/request-access")
    public String request(@PathVariable String id, @RequestParam(required = false) String note, RedirectAttributes ra) {
        if (!ID.matcher(id).matches()) return "redirect:/overview?badPatient";
        String user = PrefsContext.username();
        if (user != null && sensitive.restrictedFor(id) && !requests.hasPending(user, "Patient/" + id)) {
            requests.add(user, "Patient/" + id, note);
            activity.record(ConsoleActivity.KIND_REQUEST, "Asked for access to a restricted record", "Decision pending", id);
        }
        ra.addFlashAttribute("requested", true);
        return "redirect:/patient/" + id;
    }
}

package com.akhester.auditui.activity;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Controller;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.akhester.auditui.access.AccessPolicy;
import com.akhester.auditui.access.AccessRequestRepository;
import com.akhester.auditui.prefs.PrefsContext;

/** The console activity log (who watched the watchers), the first-sign-in notice, the session keep-alive and the signed-out page. */
@Controller
public class ActivityController {

    static final int PAGE = 50;
    private static final Set<String> KINDS = Set.of("search", "patient", "report", "decision", "rule", "request", "log");

    private final ActivityRepository repo;
    private final AccessRequestRepository requests;
    private final ConsoleActivity activity;

    public ActivityController(ActivityRepository repo, AccessRequestRepository requests, ConsoleActivity activity) {
        this.repo = repo; this.requests = requests; this.activity = activity;
    }

    @GetMapping("/activity")
    public String log(@RequestParam(required = false) String kind, @RequestParam(defaultValue = "1") int page, Model model) {
        if (!AccessPolicy.canViewActivity()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only privacy officers and ministry auditors can open the activity log.");
        String k = kind != null && KINDS.contains(kind) ? kind : null;
        int p = Math.max(1, Math.min(page, 1000));
        activity.record(ConsoleActivity.KIND_LOG, "Opened the activity log", null, null);   // the log is watched too
        List<ActivityRepository.Entry> rows = repo.recent(k, PAGE, (p - 1) * PAGE);
        int total = repo.count(k);
        model.addAttribute("rows", rows);
        model.addAttribute("kind", k == null ? "all" : k);
        model.addAttribute("kinds", List.of("all", "search", "patient", "report", "decision", "rule", "request", "log"));
        model.addAttribute("page", p);
        model.addAttribute("total", total);
        model.addAttribute("pages", Math.max(1, (total + PAGE - 1) / PAGE));
        model.addAttribute("requests", requests.recent(10));
        model.addAttribute("canSeeRequests", AccessPolicy.canOpenSensitive());
        return "activity";
    }

    /** "Got it" on the first-sign-in notice. */
    @PostMapping("/activity/notice-ack")
    public String ack(@RequestParam(required = false) String next) {
        String u = PrefsContext.username();
        if (u != null) repo.ackNotice(u);
        return "redirect:" + safe(next, "/overview");
    }

    /** Called by the session timer's "Stay signed in": any authenticated request keeps the session, this one does nothing else. */
    @PostMapping("/session/keepalive")
    public ResponseEntity<Void> keepAlive() { return ResponseEntity.noContent().cacheControl(org.springframework.http.CacheControl.noStore()).build(); }

    /** Shown when a sign-in has ended. Public. */
    @GetMapping("/signed-out")
    public String signedOut(@RequestParam(required = false) String from, Model model) {
        String f = safe(from, null);
        model.addAttribute("from", f);
        String l = f == null ? null : label(f);
        model.addAttribute("fromPage", l == null ? null : l.split(":")[0]);
        model.addAttribute("fromCase", l != null && l.contains(":") ? l.split(":")[1] : null);
        return "signed-out";
    }

    /** Only same-site paths: no scheme, no double slash, no line breaks. */
    static String safe(String next, String dflt) {
        if (next == null || next.length() > 300 || !next.startsWith("/") || next.startsWith("//") || next.contains("\\") || next.chars().anyMatch(c -> c < 32)) return dflt;
        return next;
    }

    private static String label(String path) {
        String p = path.split("[?#]")[0];
        String q = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
        if (p.startsWith("/reviews")) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("case=(C-\\d{1,10})").matcher(q);
            return m.find() ? "reviews:" + m.group(1) : "reviews";
        }
        if (p.startsWith("/patient")) return "patient";
        if (p.startsWith("/break-glass")) return "breakglass";
        if (p.startsWith("/alerts")) return "alerts";
        if (p.startsWith("/reports")) return "reports";
        if (p.startsWith("/health")) return "health";
        if (p.startsWith("/people") || p.startsWith("/find")) return "people";
        if (p.startsWith("/settings")) return "settings";
        if (p.startsWith("/overview")) return "overview";
        return null;
    }
}

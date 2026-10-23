package com.akhester.auditui.overview;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.akhester.auditui.page.ShellAdvice;
import com.akhester.auditui.prefs.PrefsContext;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Overview: what needs a human, and how much is arriving. The page is a frame; the figures load as one fragment
 * (/overview/body) so a slow Chester shows grey placeholders instead of a blank page, and the figures refresh every minute.
 */
@Controller
public class OverviewController {

    private final OverviewService overview;
    private final PrefsContext prefsContext;

    public OverviewController(OverviewService overview, PrefsContext prefsContext) { this.overview = overview; this.prefsContext = prefsContext; }

    private static boolean mayOpen() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return ShellAdvice.primaryRole() != null || (a != null && a.getAuthorities().stream().anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority())));
    }

    private static OverviewService.Params params(String range, String from, String to, String facility, boolean compare) {
        String r = switch (range == null ? "24h" : range) { case "7d", "30d", "custom" -> range; default -> "24h"; };
        LocalDate f = null, t = null;
        try { if (from != null && !from.isBlank()) f = LocalDate.parse(from); } catch (DateTimeParseException ignored) { /* shown as the default range */ }
        try { if (to != null && !to.isBlank()) t = LocalDate.parse(to); } catch (DateTimeParseException ignored) { /* shown as the default range */ }
        if ("custom".equals(r) && f == null) r = "24h";
        String fac = facility == null || facility.isBlank() || facility.length() > 200 ? null : facility;
        return new OverviewService.Params(r, f, t, fac, compare);
    }

    @GetMapping("/overview")
    public String page(@RequestParam(required = false) String range, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                       @RequestParam(required = false) String facility, @RequestParam(required = false) String compare, Model model) {
        var p = params(range, from, to, facility, "1".equals(compare) || "true".equals(compare));
        model.addAttribute("p", p);
        model.addAttribute("allowed", mayOpen());
        return "overview";
    }

    @GetMapping("/overview/body")
    public String body(@RequestParam(required = false) String range, @RequestParam(required = false) String from, @RequestParam(required = false) String to,
                       @RequestParam(required = false) String facility, @RequestParam(required = false) String compare,
                       Model model, HttpServletRequest request, Locale locale) {
        var p = params(range, from, to, facility, "1".equals(compare) || "true".equals(compare));
        model.addAttribute("p", p);
        if (!mayOpen()) { model.addAttribute("denied", true); return "fragments/overview :: body"; }
        try {
            model.addAttribute("d", overview.dashboard(p, prefsContext.forRequest(request), locale));
        } catch (RuntimeException e) {
            model.addAttribute("failed", true);
        }
        return "fragments/overview :: body";
    }
}

package com.akhester.auditui.overview;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Overview: what needs a decision now, and how much is arriving. Each panel loads on its own so one slow answer does not hold up the page. */
@Controller
public class OverviewController {

    private final OverviewService overview;
    private final com.akhester.auditui.prefs.PrefsContext prefsContext;

    public OverviewController(OverviewService overview, com.akhester.auditui.prefs.PrefsContext prefsContext) { this.overview = overview; this.prefsContext = prefsContext; }

    @GetMapping("/overview")
    public String page() { return "overview"; }

    @GetMapping("/overview/kpis")
    public String kpis(Model model) {
        model.addAttribute("k", overview.kpis());
        return "fragments/overview :: kpis";
    }

    @GetMapping("/overview/hours")
    public String hours(Model model, jakarta.servlet.http.HttpServletRequest request) {
        var hours = overview.hours(prefsContext.forRequest(request).zone());
        model.addAttribute("hours", hours);
        model.addAttribute("max", Math.max(1, hours.stream().mapToInt(OverviewService.Hour::count).max().orElse(1)));
        return "fragments/overview :: hours";
    }

    @GetMapping("/overview/queue")
    public String queue(Model model) {
        try { model.addAttribute("items", overview.queue()); }
        catch (RuntimeException e) { model.addAttribute("failed", true); }
        return "fragments/overview :: queue";
    }

    @GetMapping("/overview/systems")
    public String systems(Model model) {
        try { model.addAttribute("rows", overview.systemRows()); }
        catch (RuntimeException e) { model.addAttribute("failed", true); }
        return "fragments/overview :: systems";
    }
}

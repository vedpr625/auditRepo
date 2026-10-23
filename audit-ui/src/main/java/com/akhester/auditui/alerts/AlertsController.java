package com.akhester.auditui.alerts;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.beans.factory.annotation.Value;

import com.akhester.auditui.reviews.Labels;
import com.akhester.auditui.reviews.ReviewAccess;

/** Alerts: the rules that watch the audit trail, how often each fired, and the latest firings. */
@Controller
public class AlertsController {

    private static final int RECENT = 12;

    private final AlertRules rules;
    private final AlertFiringRepository firings;
    private final boolean demo;

    public AlertsController(AlertRules rules, AlertFiringRepository firings, @Value("${audit.ui.demo:false}") boolean demo) {
        this.rules = rules;
        this.firings = firings;
        this.demo = demo;
    }

    public static final class FacilityFormat { public String of(String ref) { return Labels.facility(ref); } }

    @GetMapping("/alerts")
    public String page(Model model) {
        Instant week = Instant.now().minus(7, ChronoUnit.DAYS);
        Map<String, Integer> counts = firings.countsSince(week);
        List<AlertFiringRepository.Firing> recent = firings.since(week, RECENT);
        model.addAttribute("rules", rules.all());
        model.addAttribute("counts", counts);
        model.addAttribute("recent", recent);
        model.addAttribute("firedTotal", firings.countSince(week));
        model.addAttribute("emailTo", rules.emailTo());
        model.addAttribute("canReview", ReviewAccess.isReviewer());
        model.addAttribute("isDemoData", demo);
        model.addAttribute("fac", new FacilityFormat());
        return "alerts";
    }

    @PostMapping("/alerts/firing/{id}/normal")
    public String markNormal(@PathVariable long id, RedirectAttributes ra) {
        if (!ReviewAccess.isReviewer()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only reviewers can mark a firing as normal.");
        if (!firings.markNormal(id, ReviewAccess.user())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        ra.addFlashAttribute("flash", "alerts.flash.normal");
        return "redirect:/alerts";
    }
}

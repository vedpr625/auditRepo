package com.akhester.auditui.breakglass;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import com.akhester.auditui.breakglass.BreakGlassService.Row;
import com.akhester.auditui.breakglass.BreakGlassService.View;
import com.akhester.auditui.prefs.PrefsContext;
import com.akhester.auditui.reviews.CaseService;
import com.akhester.auditui.reviews.Labels;
import com.akhester.auditui.reviews.ReviewAccess;

import jakarta.servlet.http.HttpServletRequest;

/** Break-glass: emergency access and whether the person wrote the reason they owe. */
@Controller
public class BreakGlassController {

    private static final List<String> FILTERS = List.of("all", "waiting", "overdue", "repeat", "unreviewed");

    private final BreakGlassService service;
    private final CaseService cases;
    private final PrefsContext prefsContext;
    private final com.akhester.auditui.service.AuditEventService audit;

    public BreakGlassController(BreakGlassService service, CaseService cases, PrefsContext prefsContext, com.akhester.auditui.service.AuditEventService audit) {
        this.service = service;
        this.cases = cases;
        this.prefsContext = prefsContext;
        this.audit = audit;
    }

    private static int days(String period) { return switch (period == null ? "" : period) { case "24h" -> 0; case "30d" -> 30; default -> 7; }; }

    private static boolean unreviewed(Row x) {
        boolean closedCase = x.caseId() != null && "done".equals(x.caseStatus());
        return !closedCase && (x.r().getReviewStatus() == null || "pending".equals(x.r().getReviewStatus()));
    }

    private List<Row> filter(View v, String filter, String facility) {
        List<Row> out = new ArrayList<>();
        for (Row x : v.rows()) {
            if (!facility.isEmpty() && !facility.equals(x.r().getFacility())) continue;
            boolean keep = switch (filter) {
                case "waiting" -> "waiting".equals(x.state());
                case "overdue" -> "overdue".equals(x.state());
                case "repeat" -> x.isRepeat();
                case "unreviewed" -> unreviewed(x);
                default -> true;
            };
            if (keep) out.add(x);
        }
        return out;
    }

    @GetMapping("/break-glass")
    public String page(@RequestParam(defaultValue = "7d") String period, @RequestParam(defaultValue = "all") String filter,
                       @RequestParam(defaultValue = "") String facility, @RequestParam(required = false) String sel,
                       HttpServletRequest request, Model model) {
        String per = List.of("24h", "7d", "30d").contains(period) ? period : "7d";
        String f = FILTERS.contains(filter) ? filter : "all";
        View v = service.load(days(per), prefsContext.forRequest(request).zone());
        String fac = facility.length() > 120 ? "" : facility;
        List<Row> rows = filter(v, f, fac);
        Row selected = null;
        if (sel != null) selected = v.rows().stream().filter(x -> x.r().getId().equals(sel)).findFirst().orElse(null);
        if (selected == null) selected = rows.stream().filter(x -> "overdue".equals(x.state())).findFirst().or(() -> rows.stream().findFirst()).orElse(null);

        Set<String> facilities = new LinkedHashSet<>();
        v.rows().forEach(x -> { if (x.r().getFacility() != null && !"—".equals(x.r().getFacility())) facilities.add(x.r().getFacility()); });
        model.addAttribute("v", v);
        model.addAttribute("rows", rows);
        model.addAttribute("period", per);
        model.addAttribute("filter", f);
        model.addAttribute("facility", fac);
        model.addAttribute("facilities", facilities);
        model.addAttribute("filters", FILTERS);
        model.addAttribute("selRow", selected);
        model.addAttribute("canReview", ReviewAccess.isReviewer());
        model.addAttribute("deadline", service.deadlineHours());
        model.addAttribute("maxDay", v.trend().stream().mapToInt(d -> d.withReason() + d.noReason()).max().orElse(1));
        model.addAttribute("minutes", new MinutesFormat());
        model.addAttribute("fac", new FacilityFormat());
        model.addAttribute("repeatMore", BreakGlassService.REPEAT_MORE_THAN);
        // counts for the filter buttons (period and facility applied)
        for (String k : FILTERS) model.addAttribute("n_" + k, filter(v, k, fac).size());
        return "breakglass";
    }

    /** Small helpers for the template. */
    public static final class MinutesFormat { public String of(Long m) { return BreakGlassService.minutes(m); } }
    public static final class FacilityFormat { public String of(String ref) { return Labels.facility(ref); } }

    /** Opens (or finds) the review case for one break-glass record, then goes to it. */
    @PostMapping("/break-glass/{eventId}/case")
    public String openCase(@PathVariable String eventId) {
        if (!ReviewAccess.isReviewer()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only reviewers can open cases.");
        if (!eventId.matches("[A-Za-z0-9\\-.]{1,64}")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        var found = audit.recentWhere(java.util.Map.of("_id", List.of(eventId), "break-glass", List.of("BTG")), 1, "date");
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return "redirect:/reviews?case=" + cases.openForEvent(found.get(0), ReviewAccess.user()).id();
    }

    @GetMapping(value = "/break-glass.csv")
    public ResponseEntity<byte[]> csv(@RequestParam(defaultValue = "7d") String period, @RequestParam(defaultValue = "all") String filter,
                                      @RequestParam(defaultValue = "") String facility, HttpServletRequest request) {
        String per = List.of("24h", "7d", "30d").contains(period) ? period : "7d";
        View v = service.load(days(per), prefsContext.forRequest(request).zone());
        if (v.error() != null) throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The audit repository did not answer.");
        List<Row> rows = filter(v, FILTERS.contains(filter) ? filter : "all", facility.length() > 120 ? "" : facility);
        StringBuilder b = new StringBuilder("recorded_utc,person,facility,patient,reason_state,minutes_to_reason,reason,review\n");
        for (Row x : rows) {
            b.append(cell(DateTimeFormatter.ISO_INSTANT.format(x.r().getRecordedInstant()))).append(',')
                .append(cell(x.r().getAgentName())).append(',').append(cell(Labels.facility(x.r().getFacility()))).append(',')
                .append(cell(x.r().getPatientRef() == null ? "" : x.r().getPatientRef().replace("Patient/", ""))).append(',')
                .append(cell(x.state())).append(',').append(x.minutesToReason() == null ? "" : x.minutesToReason()).append(',')
                .append(cell(x.r().getBreakGlassReason())).append(',').append(cell(x.caseId() != null && "done".equals(x.caseStatus()) ? "case " + x.caseId() + " " + x.caseOutcome() : x.r().getReviewStatus()))
                .append('\n');
        }
        return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"break-glass-" + per + ".csv\"")
            .header("X-Content-Type-Options", "nosniff")
            .body(b.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** CSV cell; a leading = + - @ is neutralised so a spreadsheet cannot run it as a formula. */
    static String cell(String s) {
        if (s == null) return "";
        String t = s.replace("\r", " ").replace("\n", " ");
        if (!t.isEmpty() && "=+-@\t".indexOf(t.charAt(0)) >= 0) t = "'" + t;
        return "\"" + t.replace("\"", "\"\"") + "\"";
    }

}

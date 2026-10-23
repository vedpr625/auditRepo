package com.akhester.auditui.reviews;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.server.ResponseStatusException;

import com.akhester.auditui.config.SlaPolicy;
import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.reviews.CaseRepository.Case;
import com.akhester.auditui.service.AuditEventService;

/** Reviews: cases that need a person to decide, assigned to one reviewer and confirmed by a second. */
@Controller
public class ReviewsController {

    private static final int EVENTS_SHOWN = 6;

    private final CaseService service;
    private final CaseRepository repo;
    private final AuditEventService audit;
    private final SlaPolicy sla;

    public ReviewsController(CaseService service, CaseRepository repo, AuditEventService audit, SlaPolicy sla) {
        this.service = service;
        this.repo = repo;
        this.audit = audit;
        this.sla = sla;
    }

    /** One case as the list shows it. */
    public record Card(Case c, int events, long openHours, String tone, boolean mine) {}

    @GetMapping("/reviews")
    public String page(@RequestParam(defaultValue = "all") String scope, @RequestParam(required = false) String kind,
                       @RequestParam(required = false) String q, @RequestParam(name = "case", required = false) String caseId,
                       @RequestParam(defaultValue = "false") boolean all, Model model) {
        String me = ReviewAccess.user();
        boolean view = ReviewAccess.canViewAll();
        boolean reviewer = ReviewAccess.isReviewer();
        boolean po = ReviewAccess.isPrivacyOfficer();
        model.addAttribute("canView", view);
        model.addAttribute("canReview", reviewer);
        model.addAttribute("isPo", po);
        model.addAttribute("me", me);
        model.addAttribute("scope", List.of("all", "mine", "unassigned", "closed").contains(scope) ? scope : "all");
        model.addAttribute("kind", kind == null || !CaseService.KINDS.contains(kind) ? "" : kind);
        model.addAttribute("q", q == null ? "" : q.strip());
        model.addAttribute("kinds", CaseService.KINDS);
        model.addAttribute("overdueHours", sla.overdueHours());
        if (!view) return "reviews";

        String sc = (String) model.getAttribute("scope");
        String kd = (String) model.getAttribute("kind");
        String qq = ((String) model.getAttribute("q")).toLowerCase(Locale.ROOT);
        Map<String, Integer> counts = repo.eventCounts();
        Instant now = Instant.now();
        Instant since = now.minus(Duration.ofDays(14));
        List<Card> news = new ArrayList<>(), review = new ArrayList<>(), second = new ArrayList<>(), done = new ArrayList<>();
        int openAll = 0, openMine = 0;
        for (Case c : repo.all()) {
            boolean mine = isMine(c, me, po);
            if (c.isOpen()) { openAll++; if (mine) openMine++; }
            if (!kd.isEmpty() && !kd.equals(c.kind())) continue;
            if (!qq.isEmpty() && !(String.join(" ", nz(c.id()), nz(c.title()), nz(c.subject()), nz(c.patientRef()), nz(c.facility())).toLowerCase(Locale.ROOT).contains(qq))) continue;
            switch (sc) {
                case "mine" -> { if (!mine) continue; }
                case "unassigned" -> { if (!"new".equals(c.status())) continue; }
                case "closed" -> { if (!c.isClosed()) continue; }
                default -> { }
            }
            long hours = Duration.between(c.openedAt(), c.isClosed() && c.closedAt() != null ? c.closedAt() : now).toHours();
            String tone = c.isClosed() ? "ok" : hours >= sla.overdueHours() ? "bad" : hours >= sla.warningHours() ? "warn" : "info";
            Card card = new Card(c, counts.getOrDefault(c.id(), 0), hours, tone, mine);
            switch (c.status()) {
                case "new" -> news.add(card);
                case "review" -> review.add(card);
                case "second", "escalated" -> second.add(card);
                default -> { if ("closed".equals(sc) || (c.closedAt() != null && c.closedAt().isAfter(since))) done.add(card); }
            }
        }
        Comparator<Card> oldest = Comparator.comparing(x -> x.c().openedAt());
        news.sort(oldest); review.sort(oldest); second.sort(oldest);
        done.sort(Comparator.comparing((Card x) -> x.c().closedAt(), Comparator.nullsLast(Comparator.reverseOrder())));
        model.addAttribute("colNew", news);
        model.addAttribute("colReview", review);
        model.addAttribute("colSecond", second);
        model.addAttribute("colDone", done);
        model.addAttribute("openAll", openAll);
        model.addAttribute("openMine", openMine);

        // selected case: the one asked for, else the first that waits for this person, else the first open one
        Case sel = null;
        if (caseId != null && caseId.matches("C-\\d{1,10}")) sel = repo.find(caseId).orElse(null);
        List<Card> every = new ArrayList<>(); every.addAll(review); every.addAll(second); every.addAll(news); every.addAll(done);
        if (sel == null) sel = every.stream().filter(x -> x.mine() && x.c().isOpen()).findFirst().or(() -> every.stream().findFirst()).map(Card::c).orElse(null);
        if (sel != null) detail(sel, me, reviewer, po, all, model);
        return "reviews";
    }

    private static String nz(String s) { return s == null ? "" : s; }

    static boolean isMine(Case c, String me, boolean po) {
        if (me == null) return false;
        return switch (c.status()) {
            case "review" -> me.equals(c.assignee());
            case "second" -> me.equals(c.secondReviewer());
            case "escalated" -> po;
            default -> false;
        };
    }

    private void detail(Case sel, String me, boolean reviewer, boolean po, boolean showAll, Model model) {
        List<String> ids = repo.eventIds(sel.id());
        List<AuditRow> rows = List.of();
        boolean eventsFailed = false;
        if (!ids.isEmpty()) {
            try {
                Map<String, List<String>> p = new LinkedHashMap<>();
                p.put("_id", List.of(String.join(",", ids.subList(0, Math.min(ids.size(), 100)))));
                rows = audit.recentWhere(p, 100, "date");
            } catch (RuntimeException e) { eventsFailed = true; }
        }
        long span = rows.size() < 2 ? 0 : Duration.between(rows.get(0).getRecordedInstant(), rows.get(rows.size() - 1).getRecordedInstant()).toMinutes();
        model.addAttribute("sel", sel);
        model.addAttribute("selEventTotal", ids.size());
        model.addAttribute("selRows", showAll || rows.size() <= EVENTS_SHOWN + 1 ? rows : rows.subList(0, EVENTS_SHOWN));
        model.addAttribute("selMore", showAll ? 0 : Math.max(0, ids.size() - (rows.size() <= EVENTS_SHOWN + 1 ? rows.size() : EVENTS_SHOWN)));
        model.addAttribute("eventsFailed", eventsFailed);
        model.addAttribute("eventsHidden", !eventsFailed && rows.size() < ids.size() && ids.size() <= 100);   // some events are outside this person's access
        model.addAttribute("selPeople", rows.stream().map(AuditRow::getAgentName).distinct().count());
        model.addAttribute("selPatients", rows.stream().map(AuditRow::getPatientRef).distinct().count());
        model.addAttribute("selSpan", span);
        model.addAttribute("selFacility", Labels.facility(sel.facility()));
        List<CaseRepository.Decision> decisions = repo.decisions(sel.id());
        model.addAttribute("decisions", decisions);
        model.addAttribute("firstDecision", decisions.stream().filter(d -> "first".equals(d.role())).findFirst().orElse(null));
        model.addAttribute("log", repo.log(sel.id()));
        long hours = Duration.between(sel.openedAt(), sel.isClosed() && sel.closedAt() != null ? sel.closedAt() : Instant.now()).toHours();
        model.addAttribute("selHours", hours);
        model.addAttribute("selTone", sel.isClosed() ? "ok" : hours >= sla.overdueHours() ? "bad" : hours >= sla.warningHours() ? "warn" : "info");
        // who may act now
        String st = sel.status();
        boolean firstStage = ("new".equals(st) || "review".equals(st)) && reviewer && (sel.assignee() == null || sel.assignee().equals(me));
        boolean secondStage = "second".equals(st) && me != null && me.equals(sel.secondReviewer());
        boolean officerStage = "escalated".equals(st) && po;
        model.addAttribute("canDecide", firstStage || secondStage || officerStage);
        model.addAttribute("stage", firstStage ? "first" : secondStage ? "second" : officerStage ? "officer" : "");
        model.addAttribute("canAssign", reviewer && sel.isOpen() && ("new".equals(st) || "review".equals(st)));
        List<String> others = service.reviewers().stream().filter(x -> !x.equals(me)).toList();
        model.addAttribute("otherReviewers", others);
        model.addAttribute("sealed", sel.isClosed() && service.fingerprintMatches(sel));
        model.addAttribute("reopenedBy", repo.all().stream().filter(c -> sel.id().equals(c.reopenOf())).map(Case::id).collect(Collectors.toList()));
    }

    @GetMapping("/reviews/{id}")
    public String byId(@PathVariable String id) {
        if (!id.matches("C-\\d{1,10}")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return "redirect:/reviews?case=" + id;
    }

    // ── actions (reviewers only; the service checks the rules) ───────────────

    private static void needReviewer() {
        if (!ReviewAccess.isReviewer()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only reviewers can change cases.");
    }

    @PostMapping("/reviews/new")
    public String create(@RequestParam String kind, @RequestParam String title, @RequestParam(required = false) String patient, RedirectAttributes ra) {
        needReviewer();
        String t = title == null ? "" : title.strip();
        if (t.isEmpty() || t.length() > 200) { ra.addFlashAttribute("flashBad", "reviews.flash.title"); return "redirect:/reviews"; }
        String pid = patient == null ? "" : patient.strip();
        if (!pid.isEmpty() && !pid.matches("[A-Za-z0-9\\-.]{1,64}")) { ra.addFlashAttribute("flashBad", "reviews.flash.patient"); return "redirect:/reviews"; }
        List<String> ids = List.of();
        if (!pid.isEmpty()) {
            try {
                Map<String, List<String>> p = new LinkedHashMap<>();
                p.put("entity-ref", List.of("Patient/" + pid));
                ids = audit.recentWhere(p, 20, "-date").stream().map(AuditRow::getId).toList();
            } catch (RuntimeException ignored) { /* the case is still opened, without events */ }
        }
        Case c = service.open(kind, t, null, pid.isEmpty() ? null : "Patient/" + pid, null, pid.isEmpty() ? null : "q=" + pid, ids, ReviewAccess.user(), null, ReviewAccess.user(), null, false);
        ra.addFlashAttribute("flash", "reviews.flash.created");
        return "redirect:/reviews?case=" + c.id();
    }

    @PostMapping("/reviews/{id}/assign")
    public String assign(@PathVariable String id, @RequestParam(required = false) String to, RedirectAttributes ra) {
        needReviewer();
        return run(id, ra, () -> { service.assign(id, ReviewAccess.user(), to); return "reviews.flash.assigned"; });
    }

    @PostMapping("/reviews/{id}/decide")
    public String decide(@PathVariable String id, @RequestParam String decision, @RequestParam(required = false) String note,
                         @RequestParam(required = false) String second, @RequestParam(defaultValue = "send") String act, RedirectAttributes ra) {
        needReviewer();
        if (!CaseService.DECISIONS.contains(decision)) { ra.addFlashAttribute("flashBad", "reviews.flash.pick"); return byId(id); }
        return run(id, ra, () -> switch (service.decide(id, ReviewAccess.user(), decision, note, second, !"save".equals(act))) {
            case SAVED -> "reviews.flash.saved";
            case SENT -> "reviews.flash.sent";
            case CLOSED -> "reviews.flash.closed";
            case ESCALATED -> "reviews.flash.escalated";
        });
    }

    @PostMapping("/reviews/{id}/note")
    public String note(@PathVariable String id, @RequestParam String text, RedirectAttributes ra) {
        needReviewer();
        return run(id, ra, () -> { service.note(id, ReviewAccess.user(), text); return "reviews.flash.noted"; });
    }

    @PostMapping("/reviews/{id}/reopen")
    public String reopen(@PathVariable String id, RedirectAttributes ra) {
        needReviewer();
        try {
            Case c = service.reopen(id, ReviewAccess.user());
            ra.addFlashAttribute("flash", "reviews.flash.reopened");
            return "redirect:/reviews?case=" + c.id();
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().value() == 404) throw e;
            ra.addFlashAttribute("flashText", e.getReason());
            return "redirect:/reviews?case=" + id;
        }
    }

    private String run(String id, RedirectAttributes ra, java.util.function.Supplier<String> action) {
        try {
            ra.addFlashAttribute("flash", action.get());
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().value() == 404) throw e;
            ra.addFlashAttribute("flashText", e.getReason());
        }
        return "redirect:/reviews?case=" + id;
    }
}

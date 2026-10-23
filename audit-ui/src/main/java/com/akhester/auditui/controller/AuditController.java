package com.akhester.auditui.controller;

import com.akhester.auditui.model.AuditDetail;
import com.akhester.auditui.model.AuditFilter;
import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.service.AuditEventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Handles all requests for the Audit Review page.
 *
 * URL map:
 *  GET  /              → full page (audit-review.html)
 *  GET  /audit/rows    → HTMX fragment: complete #auditSection innerHTML
 *  GET  /audit/detail/{id} → HTMX fragment: drawer panel (hx-target="#drawerPanel")
 *  POST /audit/review/{id} → HTMX action: mark reviewed (hx-swap="none", triggers toast)
 *  GET  /audit/status  → HTMX fragment: Chester status pill
 *  GET  /audit/sla-bar → HTMX fragment: SLA alert bar
 */
@Controller
@RequiredArgsConstructor
@Slf4j
public class AuditController {

    private final AuditEventService auditService;
    private final com.akhester.auditui.explore.ExploreService explore;
    private final com.akhester.auditui.prefs.PrefsContext prefsContext;
    private final org.springframework.context.MessageSource messageSource;
    private final com.akhester.auditui.explore.SavedSearchRepository saved;
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();

    // ── Full page ─────────────────────────────────────────────────────────────

    @GetMapping("/")
    public String page(AuditFilter filter, Model model, jakarta.servlet.http.HttpServletRequest request, java.util.Locale locale) {
        // a bare "/" opens on the last 24 hours; a link with its own filters keeps exactly what it asks for
        if (request.getParameterMap().isEmpty()) filter.setRange("24h");
        populateModel(filter, model, request, locale);
        model.addAttribute("chesterUp", auditService.isChesterReachable());
        model.addAttribute("isAdmin", isAdmin());
        model.addAttribute("canVerify", canVerifyChain());
        model.addAttribute("currentUser", currentUsername());
        return "audit-review";
    }

    // ── HTMX: auditSection fragment ───────────────────────────────────────────
    //
    // Returns the complete innerHTML for <div id="auditSection">.
    // HTMX target is #auditSection with swap=innerHTML, so:
    //   - result-count span
    //   - full table (thead + tbody rows)
    //   - pagination wrap
    // are all valid HTML div/table children — no OOB needed, no browser stripping.

    @GetMapping("/audit/rows")
    public String rows(AuditFilter filter, Model model, jakarta.servlet.http.HttpServletRequest request, java.util.Locale locale) {
        populateModel(filter, model, request, locale);
        model.addAttribute("isAdmin", isAdmin());
        return "fragments/audit-rows :: auditSection";
    }

    // ── HTMX: drawer detail panel ─────────────────────────────────────────────

    @GetMapping("/audit/detail/{id}")
    public String detail(@PathVariable String id, Model model) {
        AuditDetail detail = auditService.getDetail(id);
        if (detail == null) {
            model.addAttribute("errorMsg", "AuditEvent/" + id + " not found in Chester.");
            return "fragments/drawer-error :: drawerError";
        }
        model.addAttribute("d", detail);
        // Server-side JSON syntax highlighting
        model.addAttribute("jsonHighlight", syntaxHighlightJson(detail.getRawJson()));
        return "fragments/drawer-detail :: drawerDetail";
    }

    /** Wraps JSON tokens in coloured {@code <span>} elements for display in the Raw FHIR tab. */
    private String syntaxHighlightJson(String json) {
        if (json == null) return "";
        // Escape HTML first
        String safe = json
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
        // Regex-replace tokens: keys, strings, numbers, booleans, null
        safe = safe
            // Keys: "word": → span.jk
            .replaceAll("(?m)^(\\s*)(&quot;([^&]|&[^;]+;)+?&quot;)(\\s*:)", "$1<span class=\"jk\">$2</span>$4")
            // String values: : "..." (after colon+space)
            .replaceAll("(?<=:\\s)(&quot;([^&]|&[^;]+;)*?&quot;)", "<span class=\"js\">$1</span>")
            // Numbers
            .replaceAll("(?<=:\\s)(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)", "<span class=\"jn\">$1</span>")
            // Booleans
            .replaceAll("(?<=:\\s)(true|false)", "<span class=\"jb\">$1</span>")
            // null
            .replaceAll("(?<=:\\s)(null)", "<span class=\"jnull\">$1</span>");
        return safe;
    }

    // ── HTMX: mark reviewed / flagged ─────────────────────────────────────────

    @PostMapping("/audit/review/{id}")
    @ResponseBody
    public ResponseEntity<String> markReview(
            @PathVariable String id,
            @RequestParam(defaultValue = "reviewed") String status,
            @RequestParam(required = false) String note) {

        try {
            auditService.setReviewStatus(id, status, note);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body("");
        }
        catch (java.util.NoSuchElementException e) {
            return ResponseEntity.status(404).body("");
        }

        String trigger = """
            {"auditReviewed": {"id": "%s", "status": "%s"}}
            """.formatted(id, status).strip();

        return ResponseEntity.ok()
            .header("HX-Trigger", trigger)
            .body("");
    }

    // ── HTMX: sidebar facet counts (JSON) ────────────────────────────────────

    @GetMapping("/audit/counts")
    @ResponseBody
    public ResponseEntity<java.util.Map<String, Object>> counts(AuditFilter filter) {
        AuditEventService.FacetCounts fc = auditService.facetCounts(filter);
        return ResponseEntity.ok(java.util.Map.of(
            "outcome", fc.outcomeCounts(),
            "purpose", fc.purposeCounts()
        ));
    }

    // ── CSV export of the current filter (what the signed-in user may see, at most EXPORT_LIMIT rows) ──

    private static final int EXPORT_LIMIT = 5000;

    @GetMapping(value = "/audit/export.csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportCsv(AuditFilter filter) {
        java.util.List<AuditRow> all = new java.util.ArrayList<>();
        int total = 0;
        filter.setSize(100);
        for (int page = 1; all.size() < EXPORT_LIMIT; page++) {
            filter.setPage(page);
            AuditEventService.SearchResult r = auditService.searchWithTotal(filter);
            if (r.error() != null) {
                return ResponseEntity.status(AuditEventService.INVALID_VALUE.equals(r.error()) ? 400 : 502).header("X-Chester-Error", r.error().replaceAll("[^\\x20-\\x7E]", " ")).build();
            }
            total = r.total();
            all.addAll(r.rows());
            if (r.rows().size() < 100) break;
        }
        boolean truncated = total > all.size();
        StringBuilder sb = new StringBuilder("\uFEFFid,recorded,action,outcome,purpose,patient,agent,facility,submitted_by,break_glass_label,incomplete,review_status\r\n");
        for (AuditRow r : all) {
            sb.append(String.join(",",
                csv(r.getId()), csv(r.getRecordedInstant() != null ? r.getRecordedInstant().toString() : ""), csv(r.getAction()),
                csv(r.getOutcomeCode()), csv(r.getPurposeCode()), csv(r.getPatientRef()), csv(r.getAgentName()),
                csv(r.getFacility()), csv(r.getSubmittedBy()), String.valueOf(r.isBreakGlass()),
                String.valueOf(r.isIncomplete()), csv(r.getReviewStatus()))).append("\r\n");
        }
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"audit-events.csv\"")
            .header("X-Export-Rows", String.valueOf(all.size()))
            .header("X-Export-Total", String.valueOf(total))
            .header("X-Export-Truncated", String.valueOf(truncated))
            .contentType(org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .body(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** CSV cell: quoted, and neutralised against spreadsheet formula injection (=, +, -, @, tab, CR at the start). */
    static String csv(String v) {
        if (v == null) return "\"\"";
        String x = v;
        if (!x.isEmpty() && "=+-@\t\r".indexOf(x.charAt(0)) >= 0) x = "'" + x;
        return "\"" + x.replace("\"", "\"\"") + "\"";
    }

    // ── Verify the hash chain (unrestricted readers only; Chester enforces it too) ──

    @GetMapping("/audit/verify-chain")
    @ResponseBody
    public ResponseEntity<java.util.Map<String, Object>> verifyChain(@RequestParam(required = false) String partition) {
        if (!canVerifyChain()) return ResponseEntity.status(403).body(java.util.Map.of("error", "Your role may not verify the chain."));
        if (partition != null && !partition.isBlank()) {
            try { java.time.LocalDate.parse(partition); }
            catch (java.time.format.DateTimeParseException e) {
                return ResponseEntity.badRequest().body(java.util.Map.of("error", "partition must be a real date, yyyy-MM-dd"));
            }
        }
        return ResponseEntity.ok(auditService.verifyChain(partition == null || partition.isBlank() ? null : partition));
    }

    private static final java.util.Set<String> UNRESTRICTED = java.util.Set.of(
        "ROLE_AUDIT_ADMIN", "ROLE_MINISTRY_AUDITOR", "ROLE_SYSTEM", "ROLE_PRIVACY_OFFICER");

    private boolean canVerifyChain() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> UNRESTRICTED.contains(a.getAuthority()));
    }

    // ── HTMX: Chester status pill ─────────────────────────────────────────────

    @GetMapping("/audit/status")
    public String status(Model model) {
        model.addAttribute("chesterUp", auditService.isChesterReachable());
        return "fragments/chester-status :: chesterStatus";
    }

    // ── HTMX: SLA bar ─────────────────────────────────────────────────────────

    @GetMapping("/audit/sla-bar")
    public String slaBar(Model model) {
        AuditEventService.SlaCounts sla = auditService.slaCounts();
        model.addAttribute("overdueCount", sla.overdue());
        model.addAttribute("warningCount", sla.warning());
        model.addAttribute("slaTruncated", sla.truncated());
        model.addAttribute("slaError", sla.error());
        model.addAttribute("slaWarnHours", AuditRow.slaWarningHours);
        model.addAttribute("slaOverHours", AuditRow.slaOverdueHours);
        return "fragments/sla-bar :: slaBar";
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** Returns true if the currently authenticated user has ROLE_ADMIN. */
    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return false;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_MINISTRY_AUDITOR") || a.getAuthority().equals("ROLE_PRIVACY_OFFICER"));
    }

    /** Returns the current user's username. */
    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "unknown";
    }

    /**
     * Populate model attributes needed by both the full page and the HTMX rows fragment.
     * Uses searchWithTotal() to perform a single Chester round-trip for both rows and count.
     */
    private void populateModel(AuditFilter filter, Model model, jakarta.servlet.http.HttpServletRequest request, java.util.Locale locale) {
        explore.normalize(filter);
        try { model.addAttribute("savedSearches", saved.forUser(com.akhester.auditui.prefs.PrefsContext.username())); } catch (RuntimeException e) { model.addAttribute("savedSearches", java.util.List.of()); }
        if (filter.getGroupBy() == null || !java.util.List.of("none", "submitted", "actor", "patient", "event").contains(filter.getGroupBy())) filter.setGroupBy("none");
        if (filter.getResult() == null || !java.util.List.of("ok", "failed").contains(filter.getResult())) filter.setResult(null);
        boolean byLink = "none".equals(filter.getLink());
        AuditEventService.SearchResult result;
        if (byLink) {
            try {
                int size = Math.max(1, Math.min(filter.getSize(), 100)), page = Math.max(1, filter.getPage());
                var lr = explore.withoutCareLink(filter, page, size);
                result = new AuditEventService.SearchResult(lr.rows(), lr.total(), null, messageSource.getMessage("explore.link.note", null, locale));
            } catch (RuntimeException ex) {
                result = new AuditEventService.SearchResult(java.util.List.of(), 0, "Chester could not answer.", null);
            }
        } else {
            result = auditService.searchWithTotal(filter);
        }
        int total      = result.total();
        int totalPages = (total == 0) ? 1 : (int) Math.ceil((double) total / filter.getSize());
        com.akhester.auditui.prefs.UserPrefs prefs = prefsContext.forRequest(request);
        com.akhester.auditui.page.TimeFormat tf = new com.akhester.auditui.page.TimeFormat(prefs, locale, messageSource, java.time.Instant.now());
        boolean grouped = !"none".equals(filter.getGroupBy());
        model.addAttribute("view", grouped && result.error() == null
            ? explore.grouped(filter, total, prefs, tf, locale)
            : explore.view(filter, result.rows(), total, prefs, tf, locale));

        model.addAttribute("canVerify", canVerifyChain());
        model.addAttribute("rows",       result.rows());
        model.addAttribute("total",      total);
        model.addAttribute("filter",     filter);
        model.addAttribute("filterJson", filterJson(filter));
        model.addAttribute("page",       filter.getPage());
        model.addAttribute("size",       filter.getSize());
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("error",      result.error());
        model.addAttribute("note",       result.note());
    }

    /** The filter as JSON for the page script (only values that are set), so the script and the server always agree. */
    private String filterJson(AuditFilter f) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        put(m, "tab", f.getTab()); put(m, "q", f.getQ()); put(m, "purpose", f.getPurpose()); put(m, "outcome", f.getOutcome()); put(m, "result", f.getResult());
        put(m, "agent", f.getAgent()); put(m, "facility", f.getFacility()); put(m, "encounter", f.getEncounter()); put(m, "department", f.getDepartment());
        put(m, "client", f.getClient()); put(m, "action", f.getAction()); put(m, "dateFrom", f.getDateFrom()); put(m, "dateTo", f.getDateTo());
        put(m, "range", f.getRange()); put(m, "since", f.getSince()); put(m, "until", f.getUntil()); put(m, "sort", f.getSort()); put(m, "groupBy", f.getGroupBy());
        put(m, "link", f.getLink()); put(m, "open", f.getOpen());
        if (f.isBreakGlass()) m.put("breakGlass", "true");
        if (f.isIncomplete()) m.put("incomplete", "true");
        m.put("page", String.valueOf(f.getPage())); m.put("size", String.valueOf(f.getSize()));
        try { return json.writeValueAsString(m); } catch (com.fasterxml.jackson.core.JsonProcessingException e) { return "{}"; }
    }

    private static void put(java.util.Map<String, Object> m, String k, String v) { if (v != null && !v.isBlank()) m.put(k, v); }

    // ── HTMX: event detail panel (right-hand side of Explore) ─────────────────

    @GetMapping("/audit/panel/{id}")
    public String panel(@PathVariable String id, Model model, jakarta.servlet.http.HttpServletRequest request, java.util.Locale locale) {
        if (!id.matches("[A-Za-z0-9._-]{1,64}")) return "fragments/explore-panel :: missing";
        com.akhester.auditui.prefs.UserPrefs prefs = prefsContext.forRequest(request);
        com.akhester.auditui.page.TimeFormat tf = new com.akhester.auditui.page.TimeFormat(prefs, locale, messageSource, java.time.Instant.now());
        var p = explore.panel(id, prefs, tf, locale);
        if (p == null) return "fragments/explore-panel :: missing";
        model.addAttribute("p", p);
        model.addAttribute("isAdmin", isAdmin());
        return "fragments/explore-panel :: panel";
    }

    // ── Saved searches (kept per person; Settings lists them) ─────────────────

    @PostMapping("/explore/saved")
    @ResponseBody
    public ResponseEntity<java.util.Map<String, Object>> saveSearch(@RequestParam String name, @RequestParam String query) {
        String user = com.akhester.auditui.prefs.PrefsContext.username();
        if (user == null) return ResponseEntity.status(401).build();
        try {
            long id = saved.add(user, name, query);
            return ResponseEntity.ok(java.util.Map.of("id", id, "name", name.strip()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(java.util.Map.of("error", "invalid"));
        }
    }
}

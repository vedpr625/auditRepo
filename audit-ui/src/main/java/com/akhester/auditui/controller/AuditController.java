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

    // ── Full page ─────────────────────────────────────────────────────────────

    @GetMapping("/")
    public String page(AuditFilter filter, Model model) {
        populateModel(filter, model);
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
    public String rows(AuditFilter filter, Model model) {
        populateModel(filter, model);
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
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
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
    private void populateModel(AuditFilter filter, Model model) {
        AuditEventService.SearchResult result = auditService.searchWithTotal(filter);
        int total      = result.total();
        int totalPages = (total == 0) ? 1 : (int) Math.ceil((double) total / filter.getSize());

        model.addAttribute("rows",       result.rows());
        model.addAttribute("total",      total);
        model.addAttribute("filter",     filter);
        model.addAttribute("page",       filter.getPage());
        model.addAttribute("size",       filter.getSize());
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("error",      result.error());
        model.addAttribute("note",       result.note());
    }
}

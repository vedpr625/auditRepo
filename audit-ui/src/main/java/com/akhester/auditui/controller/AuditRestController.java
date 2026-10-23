package com.akhester.auditui.controller;

import com.akhester.auditui.model.AuditDetail;
import com.akhester.auditui.model.AuditEventResponse;
import com.akhester.auditui.model.AuditEventResponse.AgentDto;
import com.akhester.auditui.model.AuditEventResponse.EntityDto;
import com.akhester.auditui.model.AuditFilter;
import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.service.AuditEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API for the Chester Audit UI — consumed by React or any HTTP client.
 *
 * Base path : /api/v1
 * Auth      : open (permitAll in SecurityConfig) — Keycloak JWT plugs in later.
 *
 * Every filter/feature available in the MVC UI is exposed here:
 *   - patient search (free-text, client-side in service)
 *   - purpose filter  (TREAT | ETREAT | PUBHLTH | HRESCH | BTG)
 *   - outcome filter  (0 | 4 | 8 | 12)
 *   - date range      (dateFrom / dateTo, yyyy-MM-dd)
 *   - tab filter      (all | etreat | pending | flagged)
 *   - review status filter (pending | reviewed | flagged)
 *   - sort            (-date | date | -outcome)
 *   - pagination      (page, size)
 *   - mark reviewed / flagged  POST /api/v1/audit-events/{id}/review
 *   - facet counts    GET /api/v1/audit-events/stats
 *   - SLA summary     GET /api/v1/audit-events/sla
 *   - health check    GET /api/v1/health
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Audit Events", description = "IHE ATNA / BALP AuditEvent REST API")
public class AuditRestController {

    private final AuditEventService auditEventService;

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/audit-events  — list with all filters
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/audit-events")
    @Operation(
        summary     = "List audit events",
        description = """
            Returns a paginated, filtered list of AuditEvent resources from Chester.
            All parameters are optional and combinable.

            **Filters available:**
            - `patient` — free-text match on patient reference (e.g. `P-00123` or `Patient/P-00123`)
            - `purpose` — purpose of event code: TREAT, ETREAT, PUBHLTH, HRESCH, BTG
            - `outcome` — outcome code: 0 (success), 4 (minor), 8 (serious), 12 (major)
            - `dateFrom` / `dateTo` — date range, format `yyyy-MM-dd` (inclusive)
            - `tab` — all | etreat (ETREAT+BTG) | pending | flagged
            - `reviewStatus` — pending | reviewed | flagged (same as tab=pending/flagged but explicit)
            - `sort` — `-date` (newest first, default) | `date` (oldest first) | `-outcome`
            - `page` / `size` — pagination (page is 1-based, size max 100)
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Success",
            content = @Content(schema = @Schema(implementation = AuditListResponse.class))),
        @ApiResponse(responseCode = "502", description = "Chester FHIR server unreachable")
    })
    public ResponseEntity<AuditListResponse> listAuditEvents(

        @Parameter(description = "Patient ID or reference, e.g. `P-00123` or `Patient/P-00123`")
        @RequestParam(required = false) String patient,

        @Parameter(description = "Practitioner (agent-ref), e.g. `P001` or `Practitioner/P001`")
        @RequestParam(required = false) String agent,

        @Parameter(description = "Facility (source-ref), e.g. `EXAMPLE001` or `Organization/EXAMPLE001`")
        @RequestParam(required = false) String facility,

        @Parameter(description = "Encounter (entity-ref), e.g. `E-30011` or `Encounter/E-30011`")
        @RequestParam(required = false) String encounter,

        @Parameter(description = "Department (site), exact text as the sending system wrote it")
        @RequestParam(required = false) String department,

        @Parameter(description = "Submitting client id (_tag submitting-client)")
        @RequestParam(required = false) String client,

        @Parameter(description = "Action code", schema = @Schema(allowableValues = {"C","R","U","D","E"}))
        @RequestParam(required = false) String action,

        @Parameter(description = "Only break-glass events")
        @RequestParam(required = false, defaultValue = "false") boolean breakGlass,

        @Parameter(description = "Only events stored as policy-incomplete")
        @RequestParam(required = false, defaultValue = "false") boolean incomplete,

        @Parameter(description = "Purpose of event code",
                   schema = @Schema(allowableValues = {"TREAT","ETREAT","PUBHLTH","HRESCH","BTG"}))
        @RequestParam(required = false) String purpose,

        @Parameter(description = "Outcome code",
                   schema = @Schema(allowableValues = {"0","4","8","12"}))
        @RequestParam(required = false) String outcome,

        @Parameter(description = "Start date inclusive, format `yyyy-MM-dd`", example = "2026-09-01")
        @RequestParam(required = false) String dateFrom,

        @Parameter(description = "End date inclusive, format `yyyy-MM-dd`", example = "2026-09-30")
        @RequestParam(required = false) String dateTo,

        @Parameter(description = "Tab filter — `etreat` = ETREAT+BTG events, `pending`/`flagged` = review queues",
                   schema = @Schema(allowableValues = {"all","etreat","pending","flagged"},
                                    defaultValue = "all"))
        @RequestParam(required = false, defaultValue = "all") String tab,

        @Parameter(description = "Review status filter — overrides tab for pending/flagged",
                   schema = @Schema(allowableValues = {"pending","reviewed","flagged"}))
        @RequestParam(required = false) String reviewStatus,

        @Parameter(description = "Sort order",
                   schema = @Schema(allowableValues = {"-date","date","-outcome"},
                                    defaultValue = "-date"))
        @RequestParam(required = false, defaultValue = "-date") String sort,

        @Parameter(description = "Page number (1-based)", example = "1")
        @RequestParam(required = false, defaultValue = "1") int page,

        @Parameter(description = "Page size (max 100)", example = "20")
        @RequestParam(required = false, defaultValue = "20") int size
    ) {
        size = Math.max(1, Math.min(size, 100));

        // A malformed date must be refused, not silently ignored (an ignored filter returns more data than asked for).
        for (String d : new String[] { dateFrom, dateTo }) {
            if (d != null && !d.isBlank()) {
                try { java.time.LocalDate.parse(d); }
                catch (java.time.format.DateTimeParseException e) { return ResponseEntity.badRequest().build(); }
            }
        }

        AuditFilter filter = new AuditFilter();
        filter.setQ(patient);
        filter.setPurpose(purpose);
        filter.setAgent(agent);
        filter.setFacility(facility);
        filter.setEncounter(encounter);
        filter.setDepartment(department);
        filter.setClient(client);
        filter.setAction(action);
        filter.setBreakGlass(breakGlass);
        filter.setIncomplete(incomplete);
        filter.setOutcome(outcome);
        filter.setDateFrom(dateFrom);
        filter.setDateTo(dateTo);
        filter.setSort(sort);
        filter.setPage(page);
        filter.setSize(size);

        // reviewStatus param maps to tab (same service logic)
        if (reviewStatus != null && !reviewStatus.isBlank()) {
            filter.setTab(reviewStatus);   // "pending" / "flagged" / "reviewed"
        } else {
            filter.setTab(tab);
        }

        AuditEventService.SearchResult result = auditEventService.searchWithTotal(filter);
        if (result.error() != null) {
            return ResponseEntity.status(AuditEventService.INVALID_VALUE.equals(result.error()) ? 400 : 502).header("X-Chester-Error", result.error().replaceAll("[^\\x20-\\x7E]", " ")).build();
        }

        List<AuditEventResponse> items = result.rows().stream()
            .map(this::rowToResponse)
            .collect(Collectors.toList());

        int totalPages = (result.total() == 0) ? 1
            : (int) Math.ceil((double) result.total() / size);

        return ResponseEntity.ok(new AuditListResponse(
            items, result.total(), page, size, totalPages
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/audit-events/{id}  — full detail
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/audit-events/{id}")
    @Operation(
        summary     = "Get a single audit event",
        description = "Returns full detail for one AuditEvent: all agents, all entities, " +
                      "BALP profile, review status, SLA, and raw FHIR JSON."
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Found",
            content = @Content(schema = @Schema(implementation = AuditEventResponse.class))),
        @ApiResponse(responseCode = "404", description = "Not found"),
        @ApiResponse(responseCode = "502", description = "Chester unreachable")
    })
    public ResponseEntity<AuditEventResponse> getAuditEvent(
        @Parameter(description = "FHIR resource logical ID", example = "ae-1234", required = true)
        @PathVariable String id
    ) {
        AuditDetail detail = auditEventService.getDetail(id);
        if (detail == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(detailToResponse(detail));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // POST /api/v1/audit-events/{id}/review  — mark reviewed / flagged
    // ─────────────────────────────────────────────────────────────────────────

    @PostMapping("/audit-events/{id}/review")
    @Operation(
        summary     = "Mark an audit event as reviewed or flagged",
        description = """
            Sets the review status of an AuditEvent in the in-memory review store.
            Status is one of: `reviewed`, `flagged`, `pending`.
            An optional `note` can be attached.

            **Note:** review status is stored in-memory in audit-ui, not in Chester.
            It will reset on server restart until a database is added.
            """
    )
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Status updated"),
        @ApiResponse(responseCode = "400", description = "Invalid status value"),
        @ApiResponse(responseCode = "403", description = "Admin role required")
    })
    public ResponseEntity<Map<String, String>> markReview(
        @Parameter(description = "FHIR resource logical ID", required = true)
        @PathVariable String id,

        @Parameter(description = "New review status",
                   schema = @Schema(allowableValues = {"reviewed","flagged","pending"}),
                   required = true)
        @RequestParam String status,

        @Parameter(description = "Optional reviewer note")
        @RequestParam(required = false) String note
    ) {
        if (!List.of("reviewed", "flagged", "pending").contains(status)) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Invalid status. Use: reviewed, flagged, pending"));
        }
        try {
            auditEventService.setReviewStatus(id, status, note);
        } catch (java.util.NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", "Event not found"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid event id"));
        }
        log.info("REST: AuditEvent/{} marked as {} (note={})", id, status, note);
        return ResponseEntity.ok(Map.of(
            "id",     id,
            "status", status,
            "note",   note != null ? note : ""
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/audit-events/stats  — facet counts for KPI tiles
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/audit-events/stats")
    @Operation(
        summary     = "Aggregate stats / facet counts",
        description = """
            Returns counts of AuditEvents grouped by outcome and purpose.
            Supports the same date filter as the list endpoint.
            Use this for React dashboard KPI tiles and chart data.

            Example response:
            ```json
            {
              "byOutcome": { "0": 42, "4": 3, "8": 1, "12": 0 },
              "byPurpose": { "TREAT": 30, "ETREAT": 8, "BTG": 2, "PUBHLTH": 4, "HRESCH": 1 }
            }
            ```
            """
    )
    @ApiResponse(responseCode = "200", description = "Success")
    public ResponseEntity<StatsResponse> stats(
        @Parameter(description = "Start date filter, `yyyy-MM-dd`") @RequestParam(required = false) String dateFrom,
        @Parameter(description = "End date filter, `yyyy-MM-dd`")   @RequestParam(required = false) String dateTo
    ) {
        AuditFilter filter = new AuditFilter();
        filter.setDateFrom(dateFrom);
        filter.setDateTo(dateTo);
        filter.setTab("all");
        filter.setPage(1);
        filter.setSize(200);
        filter.setSort("-date");

        AuditEventService.FacetCounts facets = auditEventService.facetCounts(filter);
        return ResponseEntity.ok(new StatsResponse(facets.outcomeCounts(), facets.purposeCounts()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/audit-events/sla  — SLA summary
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/audit-events/sla")
    @Operation(
        summary     = "SLA summary",
        description = """
            Counts the events whose review is late. The clock runs only for break-glass-labelled or incomplete events
            that have no reviewed/flagged decision yet, and only for what the signed-in user may see.
            - `warn` — older than `audit.sla.warning-hours` (default 6) but not yet overdue
            - `over` — older than `audit.sla.overdue-hours` (default 24)
            - `truncated` — true if more than 1000 candidate events exist, so the counts are a lower bound

            Use this to power the SLA alert bar in the React UI.
            """
    )
    @ApiResponse(responseCode = "200", description = "Success")
    public ResponseEntity<SlaResponse> sla() {
        AuditEventService.SlaCounts c = auditEventService.slaCounts();
        if (c.error() != null) {
            return ResponseEntity.status(502).header("X-Chester-Error", c.error().replaceAll("[^\\x20-\\x7E]", " ")).build();
        }
        return ResponseEntity.ok(new SlaResponse(c.warning(), c.overdue(), c.warning() + c.overdue(), c.truncated(),
            AuditRow.slaWarningHours, AuditRow.slaOverdueHours));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /api/v1/health
    // ─────────────────────────────────────────────────────────────────────────

    @GetMapping("/health")
    @Operation(
        summary     = "Health check",
        description = "Returns whether the Chester FHIR server is reachable from audit-ui."
    )
    @ApiResponse(responseCode = "200", description = "Status returned (check `chester` field)")
    public ResponseEntity<Map<String, Object>> health() {
        boolean up = auditEventService.isChesterReachable();
        return ResponseEntity.ok(Map.of(
            "status",  up ? "UP" : "DEGRADED",
            "chester", up ? "reachable" : "unreachable"
        ));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mapping helpers
    // ─────────────────────────────────────────────────────────────────────────

    private AuditEventResponse rowToResponse(AuditRow row) {
        return AuditEventResponse.builder()
            .id(row.getId())
            .recordedAt(row.getRecordedInstant() != null ? row.getRecordedInstant().toString() : null)
            .action(row.getAction())
            .actionLabel(actionLabel(row.getAction()))
            .outcomeCode(row.getOutcomeCode())
            .outcomeLabel(row.getOutcomeLabel())
            .purposeCode(row.getPurposeCode())
            .purposeLabel(row.getPurposeLabel())
            .agentName(row.getAgentName())
            .patientRef(row.getPatientRef())
            .facility(row.getFacility())
            .submittedBy(row.getSubmittedBy())
            .incomplete(row.isIncomplete())
            .breakGlass(row.isBreakGlass())
            .reviewStatus(row.getReviewStatus())
            .ageHours(row.getAgeHours())
            .slaStatus(row.isSlaApplicable() ? slaStatus(row.getAgeHours()) : "none")
            .build();
    }

    private AuditEventResponse detailToResponse(AuditDetail d) {
        List<AgentDto> agents = d.getAgents() == null ? List.of() :
            d.getAgents().stream().map(a -> AgentDto.builder()
                .reference(a.getReference())
                .name(a.getName())
                .requestor(a.isRequestorFlag())
                .networkAddress(a.getNetworkAddress())
                .roleCode(a.getRoleCode())
                .roleDisplay(a.getRoleDisplay())
                .build()
            ).collect(Collectors.toList());

        List<EntityDto> entities = d.getEntities() == null ? List.of() :
            d.getEntities().stream().map(e -> EntityDto.builder()
                .whatReference(e.getWhatReference())
                .typeDisplay(e.getTypeDisplay())
                .roleDisplay(e.getRoleDisplay())
                .name(e.getName())
                .description(e.getDescription())
                .build()
            ).collect(Collectors.toList());

        return AuditEventResponse.builder()
            .id(d.getId())
            .recordedAt(d.getRecorded())
            .balpProfile(d.getBalpProfileShort())
            .action(d.getAction())
            .actionLabel(d.getActionDisplay())
            .outcomeCode(d.getOutcomeCode())
            .outcomeLabel(d.getOutcomeLabel())
            .purposeCode(d.getPurposeCode())
            .purposeLabel(d.getPurposeLabel())
            .agentReference(d.getAgentReference())
            .agentName(d.getAgentName())
            .networkAddress(d.getNetworkAddress())
            .patientRef(d.getPatientRef())
            .reviewStatus(d.getReviewStatus())
            .reviewNote(d.getReviewNote())
            .ageHours(d.getAgeHours())
            .slaStatus("sla-none".equals(d.getSlaCss()) ? "none" : slaStatus(d.getAgeHours()))
            .sourceObserver(d.getSourceObserver())
            .sourceSite(d.getSourceSite())
            .agents(agents)
            .entities(entities)
            .rawFhir(d.getRawJson())
            .build();
    }

    private String actionLabel(String code) {
        if (code == null) return null;
        return switch (code) {
            case "C" -> "C — Create";
            case "R" -> "R — Read";
            case "U" -> "U — Update";
            case "D" -> "D — Delete";
            case "E" -> "E — Execute";
            default  -> code;
        };
    }

    private String slaStatus(long ageHours) {
        if (ageHours >= AuditRow.slaOverdueHours) return "over";
        if (ageHours >= AuditRow.slaWarningHours) return "warn";
        return "ok";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Response wrappers — Swagger documents these automatically
    // ─────────────────────────────────────────────────────────────────────────

    @Schema(description = "Paginated list of AuditEvents")
    public record AuditListResponse(
        @Schema(description = "AuditEvent items for this page")  List<AuditEventResponse> items,
        @Schema(description = "Total matching records")           int total,
        @Schema(description = "Current page number (1-based)")    int page,
        @Schema(description = "Page size")                        int size,
        @Schema(description = "Total number of pages")            int totalPages
    ) {}

    @Schema(description = "Counts grouped by outcome and purpose — for KPI dashboard tiles")
    public record StatsResponse(
        @Schema(description = "Count per outcome code, e.g. {\"0\":42,\"4\":3}") Map<String,Long> byOutcome,
        @Schema(description = "Count per purpose code, e.g. {\"TREAT\":30}")      Map<String,Long> byPurpose
    ) {}

    @Schema(description = "SLA status summary — for the SLA alert bar")
    public record SlaResponse(
        @Schema(description = "Unreviewed break-glass/incomplete events past the warning threshold but not overdue") long warn,
        @Schema(description = "Unreviewed break-glass/incomplete events past the overdue threshold")              long over,
        @Schema(description = "warn + over")                                                                       long total,
        @Schema(description = "True if more than 1000 candidates exist, so the counts are a lower bound")         boolean truncated,
        @Schema(description = "Hours after which an event counts as 'soon'")                                       long warningHours,
        @Schema(description = "Hours after which an event counts as overdue")                                      long overdueHours
    ) {}
}

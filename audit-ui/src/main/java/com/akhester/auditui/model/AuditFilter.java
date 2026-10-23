package com.akhester.auditui.model;

import lombok.Data;

/**
 * Filter parameters bound from the HTMX form / query string.
 * All fields are optional — null/blank means "no filter applied".
 *
 * The `tab` field drives the top-level tab selection:
 *   all     → no extra filter
 *   etreat  → purposeOfEvent in (ETREAT, BTG)
 *   pending → reviewStatus == pending  (client-side, in-memory store)
 *   flagged → reviewStatus == flagged  (client-side, in-memory store)
 */
@Data
public class AuditFilter {

    // ── Tab ──────────────────────────────────────────────────────────────────
    /** Active tab: all | etreat | pending | flagged */
    private String tab = "all";

    // ── Search ────────────────────────────────────────────────────────────────
    /** Free-text search (patient ID match) */
    private String q;

    // ── Sidebar filters ───────────────────────────────────────────────────────
    /** Purpose of event: TREAT | ETREAT | PUBHLTH | HRESCH | BTG */
    private String purpose;
    /** Practitioner (agent-ref), facility (source-ref), submitting client (_tag), action (C/R/U/D/E) */
    private String agent;
    private String facility;
    /** Clinical context: encounter (entity-ref Encounter/..) and department (site) */
    private String encounter;
    private String department;
    private String client;
    private String action;
    /** Only break-glass events (break-glass label) / only events stored as policy-incomplete */
    private boolean breakGlass;
    private boolean incomplete;

    /** Outcome: 0 | 4 | 8 | 12 */
    private String outcome;

    /** ISO date string, e.g. "2026-09-01" */
    private String dateFrom;

    /** ISO date string, e.g. "2026-09-27" */
    private String dateTo;

    // ── Toolbar ───────────────────────────────────────────────────────────────
    /** Sort field (Chester SP names): -date | date | -outcome */
    private String sort = "-date";

    // ── Pagination ────────────────────────────────────────────────────────────
    /** Page number (1-based) */
    private int page = 1;

    /** Page size */
    private int size = 20;

    // ── Legacy (kept for backwards compat) ────────────────────────────────────
    /** Direct review status filter — set by tab=pending/flagged in the service */
    private String reviewStatus;

    /** ETREAT-only toggle — absorbed into tab=etreat, kept for backwards compat */
    private boolean etreatsOnly;

    // ── Explore (added): time range, result group, grouping, extra checks ────────
    /** Time range: 1h | 24h | 7d | 30d | all (blank = no limit unless since/until or dateFrom/dateTo are given). */
    private String range;
    /** Exact window, ISO-8601 instants (set by dragging on the chart); they win over range. */
    private String since;
    private String until;
    /** Result group: ok (outcome 0) or failed (outcome 4, 8 or 12). */
    private String result;
    /** Group the table by: none | submitted | actor | patient | event. */
    private String groupBy = "none";
    /** "none" keeps only treatment claims by clinicians whose care link to the patient cannot be found (Demo: stand-in registry). */
    private String link;
    /** Event id whose detail panel is open when the page loads. */
    private String open;
}

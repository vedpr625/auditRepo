package com.akhester.auditui.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

/**
 * Flattened view-model for a single AuditEvent row shown in the review table.
 *
 * Extracted from a HAPI FHIR AuditEvent resource by AuditEventService.
 * Thymeleaf templates only see this flat DTO — no FHIR types leak into views.
 */
@Data
@Builder
public class AuditRow {

    private static final DateTimeFormatter DISPLAY_FMT =
        DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneId.systemDefault());

    // ── Identifiers ──────────────────────────────────────────────────────────
    /** FHIR resource logical id (e.g. "1234") */
    private String id;

    // ── What happened ────────────────────────────────────────────────────────
    /** Formatted recorded timestamp, e.g. "27 Sep 2026 14:32" */
    private String recorded;

    /** Raw Instant — used for SLA calculations */
    private Instant recordedInstant;

    /** Patient reference, e.g. "Patient/P-00123" */
    private String patientRef;

    /** Practitioner / agent who performed the action */
    private String agentName;
    /** source.observer.reference (facility), submitting client tag, and the two flags */
    private String facility;
    private String submittedBy;
    private boolean incomplete;
    private boolean breakGlass;

    /** Purpose of event code: TREAT, ETREAT, PUBHLTH, HRESCH, BTG */
    private String purposeCode;

    /** Break-glass reason text and when it was given (null when none was sent). Demo: carried in two extensions on the AuditEvent. */
    private String breakGlassReason;
    private Instant reasonGivenAt;
    /** Why a failed request failed (AuditEvent.outcomeDesc), when Chester sends it. */
    private String outcomeDesc;
    /** Patient's Encounter reference, when the record has one. */
    private String encounterRef;
    /** Outcome code: 0=success, 4=minor failure, 8=serious failure, 12=major failure */
    private String outcomeCode;

    // ── IHE BALP ─────────────────────────────────────────────────────────────
    /** AuditEvent.type.code — e.g. "rest" */
    private String eventType;

    /** AuditEvent.action — C/R/U/D/E */
    private String action;

    // ── Review state (managed in the UI layer, not stored in Chester) ────────
    /** "pending" | "reviewed" | "flagged" */
    private String reviewStatus;

    // ── SLA ──────────────────────────────────────────────────────────────────
    /** Hours since recorded — computed at query time */
    private long ageHours;

    // ── Derived helpers (used by Thymeleaf) ──────────────────────────────────

    /** CSS classes for the purpose tag chip */
    public String getPurposeTagCss() {
        if (purposeCode == null) return "tag tag-other";
        return "tag " + switch (purposeCode.toUpperCase()) {
            case "TREAT"   -> "tag-treat";
            case "ETREAT"  -> "tag-etreat";
            case "PUBHLTH" -> "tag-pubhlth";
            case "HRESCH"  -> "tag-hresch";
            case "BTG"     -> "tag-btg";
            case "HOPERAT", "HPAYMT" -> "tag-other";
            default        -> "tag-other";
        };
    }

    /** Human-readable purpose label */
    public String getPurposeLabel() {
        if (purposeCode == null) return "—";
        return switch (purposeCode.toUpperCase()) {
            case "TREAT"   -> "Treatment";
            case "ETREAT"  -> "Emergency";
            case "PUBHLTH" -> "Public Health";
            case "HRESCH"  -> "Research";
            case "BTG"     -> "Break-Glass";
            case "HOPERAT" -> "Operations";
            case "HPAYMT"  -> "Payment";
            default        -> purposeCode;
        };
    }

    /** CSS class for the outcome chip */
    public String getOutcomeCss() {
        if (outcomeCode == null) return "outcome-ok";
        return switch (outcomeCode) {
            case "0"  -> "outcome-ok";
            case "4"  -> "outcome-warn";
            case "8"  -> "outcome-err";
            case "12" -> "outcome-err";
            default   -> "outcome-ok";
        };
    }

    /** Human-readable outcome */
    public String getOutcomeLabel() {
        if (outcomeCode == null) return "Success";
        return switch (outcomeCode) {
            case "0"  -> "Success";
            case "4"  -> "Minor";
            case "8"  -> "Serious";
            case "12" -> "Major";
            default   -> outcomeCode;
        };
    }

    /** Review-time thresholds in hours; set once at start-up from audit.sla.* (see SlaPolicy). */
    public static volatile long slaWarningHours = 6;
    public static volatile long slaOverdueHours = 24;

    /** The review clock runs only for break-glass or incomplete events that have not been reviewed or flagged yet. */
    public boolean isSlaApplicable() {
        return (breakGlass || incomplete) && (reviewStatus == null || "pending".equals(reviewStatus));
    }

    /** CSS class for the SLA badge */
    public String getSlaCss() {
        if (!isSlaApplicable()) return "sla-none";
        if (ageHours >= slaOverdueHours) return "sla-over";
        if (ageHours >= slaWarningHours) return "sla-warn";
        return "sla-ok";
    }

    /** Human-readable SLA label */
    public String getSlaLabel() {
        if (!isSlaApplicable()) return "—";
        if (ageHours >= slaOverdueHours) return ageHours + "h — Overdue";
        if (ageHours >= slaWarningHours) return ageHours + "h — Soon";
        return ageHours + "h";
    }

    /** Whether this is an emergency/break-glass event (highlighted row) */
    public boolean isEtreatOrBtg() {
        if (purposeCode == null) return false;
        String u = purposeCode.toUpperCase();
        return u.equals("ETREAT") || u.equals("BTG");
    }

    /** CSS class for the review status badge */
    public String getReviewStatusCss() {
        if (reviewStatus == null) return "rs rs-pending";
        return switch (reviewStatus) {
            case "reviewed" -> "rs rs-reviewed";
            case "flagged"  -> "rs rs-flagged";
            default         -> "rs rs-pending";
        };
    }

    public String getReviewStatusLabel() {
        if (reviewStatus == null) return "Pending";
        return switch (reviewStatus) {
            case "reviewed" -> "Reviewed";
            case "flagged"  -> "Flagged";
            default         -> "Pending";
        };
    }

    /** Compute ageHours from recordedInstant (call after building) */
    public static long computeAgeHours(Instant recorded) {
        if (recorded == null) return 0;
        return ChronoUnit.HOURS.between(recorded, Instant.now());
    }
}

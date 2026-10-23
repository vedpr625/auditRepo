package com.akhester.auditui.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Flat JSON response for REST API consumers (e.g. React UI).
 *
 * No FHIR types — plain Java fields serialized to camelCase JSON by Jackson.
 * All nullable fields are omitted from the JSON when null (@JsonInclude NON_NULL).
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A single IHE BALP AuditEvent flattened for UI consumption")
public class AuditEventResponse {

    @Schema(description = "FHIR resource logical ID", example = "ae-1234")
    private String id;

    @Schema(description = "ISO-8601 timestamp when the event was recorded", example = "2026-09-27T14:32:00Z")
    private String recordedAt;

    // ── What happened ────────────────────────────────────────────────────────

    @Schema(description = "BALP profile short name", example = "PatientRead",
            allowableValues = {"PatientRead","PatientCreate","PatientUpdate","PatientDelete","PatientQuery"})
    private String balpProfile;

    @Schema(description = "AuditEvent.action code", example = "R",
            allowableValues = {"C","R","U","D","E"})
    private String action;

    @Schema(description = "Human-readable action", example = "R — Read")
    private String actionLabel;

    @Schema(description = "Outcome code", example = "0",
            allowableValues = {"0","4","8","12"})
    private String outcomeCode;

    @Schema(description = "Human-readable outcome", example = "Success")
    private String outcomeLabel;

    // ── Purpose ──────────────────────────────────────────────────────────────

    @Schema(description = "Purpose of event code", example = "TREAT",
            allowableValues = {"TREAT","ETREAT","PUBHLTH","HRESCH","BTG"})
    private String purposeCode;

    @Schema(description = "Human-readable purpose", example = "Treatment")
    private String purposeLabel;

    // ── Who ───────────────────────────────────────────────────────────────────

    @Schema(description = "Primary agent / practitioner reference", example = "Practitioner/dr-smith")
    private String agentReference;

    @Schema(description = "Primary agent display name", example = "Dr. Smith")
    private String agentName;

    @Schema(description = "Source IP / network address of the agent", example = "192.168.1.10")
    private String networkAddress;

    // ── Patient ───────────────────────────────────────────────────────────────

    @Schema(description = "Patient FHIR reference", example = "Patient/P-00123")
    private String patientRef;

    // ── Review state ─────────────────────────────────────────────────────────

    @Schema(description = "Facility (AuditEvent.source.observer.reference)", example = "Organization/EXAMPLE001")
    private String facility;

    @Schema(description = "Keycloak client that submitted the event (stamped by the audit repository)", example = "chester-audit-writer")
    private String submittedBy;

    @Schema(description = "Stored with the policy-incomplete flag")
    private boolean incomplete;

    @Schema(description = "Carries the break-glass label")
    private boolean breakGlass;

    @Schema(description = "Review status (managed in audit-ui, not in the audit repository)",
            example = "pending", allowableValues = {"pending","reviewed","flagged"})
    private String reviewStatus;

    @Schema(description = "Reviewer note, if any")
    private String reviewNote;

    // ── SLA ──────────────────────────────────────────────────────────────────

    @Schema(description = "Age of the event in hours since recorded", example = "3")
    private long ageHours;

    @Schema(description = "SLA status", example = "ok", allowableValues = {"ok","warn","over","none"})
    private String slaStatus;

    // ── Source ───────────────────────────────────────────────────────────────

    @Schema(description = "Audit source observer reference", example = "Device/ehr-gateway")
    private String sourceObserver;

    @Schema(description = "Audit source site", example = "EHR Gateway")
    private String sourceSite;

    // ── All agents (for detail view) ─────────────────────────────────────────

    @Schema(description = "All agents on this AuditEvent (present in detail endpoint only)")
    private List<AgentDto> agents;

    // ── All entities (for detail view) ────────────────────────────────────────

    @Schema(description = "All entities on this AuditEvent (present in detail endpoint only)")
    private List<EntityDto> entities;

    // ── Raw FHIR (for detail view) ────────────────────────────────────────────

    @Schema(description = "Pretty-printed FHIR JSON (present in detail endpoint only)")
    private String rawFhir;

    // ── Nested DTOs ───────────────────────────────────────────────────────────

    @Data @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Agent (actor) on an AuditEvent")
    public static class AgentDto {
        @Schema(example = "Practitioner/dr-smith") private String reference;
        @Schema(example = "Dr. Smith")             private String name;
        @Schema(example = "true")                  private boolean requestor;
        @Schema(example = "192.168.1.10")          private String networkAddress;
        @Schema(example = "HCP")                   private String roleCode;
        @Schema(example = "Healthcare Professional") private String roleDisplay;
    }

    @Data @Builder
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Entity (data object) referenced by an AuditEvent")
    public static class EntityDto {
        @Schema(example = "Patient/P-00123") private String whatReference;
        @Schema(example = "Patient")         private String typeDisplay;
        @Schema(example = "Patient")         private String roleDisplay;
        @Schema(example = "John Doe")        private String name;
        @Schema(example = "Encounter record") private String description;
    }
}

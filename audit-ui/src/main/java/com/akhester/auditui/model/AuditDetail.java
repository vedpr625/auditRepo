package com.akhester.auditui.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Full detail view-model for the slide-in drawer panel.
 * Includes everything in AuditRow plus the raw FHIR fields the drawer shows.
 */
@Data
@Builder
public class AuditDetail {

    // ── Basic identity ────────────────────────────────────────────────────────
    private String id;
    private String recorded;
    private String eventType;
    private String eventTypeDisplay;   // human label for the type code
    private String eventSubtype;       // subtype code (e.g. "read")
    private String action;
    private String actionDisplay;      // C→Create, R→Read …
    private String outcomeCode;
    private String outcomeLabel;
    private String outcomeCss;
    private String outcomeDesc;        // description field on AuditEvent.outcome

    /** BALP StructureDefinition URL from meta.profile[0] */
    private String balpProfile;
    /** Short name extracted from the URL, e.g. "IHE.BasicAudit.PatientRead" */
    private String balpProfileShort;

    // ── Purpose ───────────────────────────────────────────────────────────────
    private String purposeCode;
    private String purposeLabel;
    private String purposeTagCss;

    // ── Agents — first requestor agent (primary display) ─────────────────────
    private String agentName;
    private String agentReference;
    private boolean requestor;
    private String networkAddress;
    private String networkType;        // "1"=machine, "2"=IP, "3"=phone…

    /** All agents (name|ref|requestor|network) for the Agents tab */
    private List<AgentInfo> agents;

    // ── Patient / entity (first patient entity, primary display) ─────────────
    private String patientRef;
    private String entityType;
    private String entityRole;
    private String entityDescription;

    /** All entities for the Overview entity section */
    private List<EntityInfo> entities;

    // ── Source / observer ─────────────────────────────────────────────────────
    private String sourceObserver;
    private String sourceSite;
    private String sourceType;

    // ── IHE BALP extras ──────────────────────────────────────────────────────
    private List<String> securityLabels;

    // ── SHA-256 hash chain ───────────────────────────────────────────────────
    private String chainPosition;
    private String chainHash;
    private String previousHash;
    private String genesisHash;
    private String chainPartitionKey;
    private String chainWrittenAt;

    // ── Review ────────────────────────────────────────────────────────────────
    private String reviewStatus;
    private String reviewNote;
    private String reviewer;
    private String reviewedAt;
    private java.util.List<String> reviewHistory;

    // ── Raw JSON (shown in Raw FHIR tab) ─────────────────────────────────────
    private String rawJson;

    // ── SLA ──────────────────────────────────────────────────────────────────
    private long   ageHours;
    private String slaCss;
    private String slaLabel;

    // ─────────────────────────────────────────────────────────────────────────
    // Nested value-objects (no JPA, just plain records)
    // ─────────────────────────────────────────────────────────────────────────

    /** Represents one AuditEvent.agent entry */
    @Data @Builder
    public static class AgentInfo {
        private String name;
        private String reference;      // who.reference
        private String identifier;     // who.identifier display
        private boolean requestorFlag;
        private String networkAddress;
        private String networkType;
        private String roleCode;       // first role coding code
        private String roleDisplay;    // first role coding display
        private String typeCode;       // agent type code
    }

    /** Represents one AuditEvent.entity entry */
    @Data @Builder
    public static class EntityInfo {
        private String whatReference;  // what.reference
        private String whatDisplay;    // what.display
        private String typeCode;       // type.code
        private String typeDisplay;    // type.display ("Person", "System Object"…)
        private String roleCode;       // role.code
        private String roleDisplay;    // role.display
        private String description;
        private String name;
    }
}

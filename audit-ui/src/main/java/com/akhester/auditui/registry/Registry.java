package com.akhester.auditui.registry;

import java.time.Instant;
import java.util.List;

/**
 * Plain records for what is read from a FHIR R4 registry: Encounter only (Patient has its own record in {@link RegistryClient}).
 * This console is an audit tool, so it reads who was where and when, never what was recorded about the person's health.
 * Every field except the id may be null.
 */
public final class Registry {

    private Registry() {}

    /** OK: asked and answered (the list may be empty). NOT_PROVIDED: the registry has no such resource type or refuses it (404, 403, 400...). UNAVAILABLE: no answer. */
    public enum Status { OK, NOT_PROVIDED, UNAVAILABLE }

    public record Section<T>(Status status, List<T> items) {
        public static <T> Section<T> ok(List<T> items) { return new Section<>(Status.OK, items == null ? List.of() : List.copyOf(items)); }
        public static <T> Section<T> notProvided() { return new Section<>(Status.NOT_PROVIDED, List.of()); }
        public static <T> Section<T> unavailable() { return new Section<>(Status.UNAVAILABLE, List.of()); }
        public boolean isOk() { return status == Status.OK; }
        public boolean isEmpty() { return items.isEmpty(); }
    }

    public record Encounter(String id, String patientId, String status, String classCode, String classDisplay, String type,
                            Instant start, Instant end, String facilityRef, String facilityName, String ward, String attending, List<String> practitioners,
                            String providerName) {
        public boolean current() { return end == null && !"finished".equals(status) && !"cancelled".equals(status); }
        /** The other practitioners (everyone but the attending). */
        public List<String> others() { return practitioners.stream().filter(p -> !p.equals(attending)).toList(); }
        /** outpatient | inpatient | emergency | other, from the v3 ActCode class (AMB, IMP, EMER ...) or the words of the display. */
        public String kind() {
            String c = classCode == null ? "" : classCode.toUpperCase();
            String d = classDisplay == null ? "" : classDisplay.toLowerCase();
            if (c.equals("AMB") || c.equals("VR") || d.contains("ambulatory") || d.contains("outpatient")) return "outpatient";
            if (c.equals("IMP") || c.equals("ACUTE") || c.equals("NONAC") || c.equals("SS") || d.contains("inpatient")) return "inpatient";
            if (c.equals("EMER") || d.contains("emergency")) return "emergency";
            return "other";
        }
    }
}

package com.akhester.auditui.registry;

import java.time.Instant;
import java.util.List;

/**
 * Plain records for the clinical resources read from a FHIR R4 registry (Condition, MedicationRequest, Observation, AllergyIntolerance,
 * Procedure, Encounter). Every field except the id may be null: a real registry fills what it has, and the screens say "not recorded" for the rest.
 */
public final class Clinical {

    private Clinical() {}

    /** OK: asked and answered (the list may be empty). NOT_PROVIDED: the registry has no such resource type or refuses it (404, 403, 400...). UNAVAILABLE: no answer. */
    public enum Status { OK, NOT_PROVIDED, UNAVAILABLE }

    public record Section<T>(Status status, List<T> items) {
        public static <T> Section<T> ok(List<T> items) { return new Section<>(Status.OK, items == null ? List.of() : List.copyOf(items)); }
        public static <T> Section<T> notProvided() { return new Section<>(Status.NOT_PROVIDED, List.of()); }
        public static <T> Section<T> unavailable() { return new Section<>(Status.UNAVAILABLE, List.of()); }
        public boolean isOk() { return status == Status.OK; }
        public boolean isEmpty() { return items.isEmpty(); }
    }

    public record Condition(String id, String text, String code, String clinicalStatus, Instant onset, Instant abated, Instant recorded, String encounterId) {
        /** Active unless the registry says it ended (resolved, inactive, remission) or gives an end date in the past. */
        public boolean active() {
            if (clinicalStatus != null && List.of("resolved", "inactive", "remission").contains(clinicalStatus)) return false;
            return abated == null || abated.isAfter(Instant.now());
        }
    }

    public record Medication(String id, String text, String dose, String status, Instant authoredOn, String requester, String reason, String encounterId) {
        public boolean active() { return "active".equals(status) || "on-hold".equals(status); }
    }

    public record Allergy(String id, String text, String criticality, String status, String reaction, String severity, boolean noKnown, Instant recorded) {
        public boolean high() { return "high".equals(criticality) || "severe".equals(severity); }
    }

    public record Procedure(String id, String text, String code, Instant at, String status, String performer, String encounterId) {}

    /** flag: H, L, N, A (abnormal, no direction) or null (unknown). value is the first number (for a blood pressure: the systolic one); display is the whole text. */
    public record Observation(String id, String code, String text, String category, Double value, String display, String unit, Double low, Double high,
                              String flag, Instant at, String status, String encounterId) {
        public boolean lab() { return "laboratory".equals(category); }
        public boolean vital() { return "vital-signs".equals(category); }
        public boolean abnormal() { return flag != null && !"N".equals(flag); }
        /** The unit as people write it (UCUM "Cel" is "°C", "10*3/uL" is "×10³/µL", "mm[Hg]" is "mmHg"). Other units are shown as they came. */
        public String unitLabel() { return unitLabel(unit); }
        public static String unitLabel(String u) {
            if (u == null) return null;
            return switch (u) {
                case "Cel" -> "°C";
                case "[degF]" -> "°F";
                case "10*3/uL", "10*3/mcL" -> "×10³/µL";
                case "10*6/uL" -> "×10⁶/µL";
                case "mm[Hg]" -> "mmHg";
                case "uL", "mcL" -> "µL";
                case "ug/dL" -> "µg/dL";
                default -> u;
            };
        }
        public String range() {
            if (low == null && high == null) return null;
            if (low != null && high != null) return fmt(low) + "–" + fmt(high);
            return low != null ? "≥ " + fmt(low) : "≤ " + fmt(high);
        }
        static String fmt(double d) { return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d); }
    }

    public record Diagnosis(String conditionId, String text, String use, Integer rank) {}

    public record Encounter(String id, String patientId, String status, String classCode, String classDisplay, String type, String reason,
                            Instant start, Instant end, String facilityRef, String facilityName, String ward, String attending, List<String> practitioners,
                            List<Diagnosis> diagnoses, String providerName, String disposition) {
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

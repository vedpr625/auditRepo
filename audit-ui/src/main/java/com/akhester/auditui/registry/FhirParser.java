package com.akhester.auditui.registry;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads FHIR R4 JSON into the small records of {@link Clinical}. Tolerant on purpose: a missing field is null, a field of an unexpected shape is
 * ignored, a resource that cannot be understood is skipped, and a resource the registry marks "entered-in-error" is never shown. Nothing here throws on odd input.
 */
public final class FhirParser {

    private FhirParser() {}

    private static final Set<String> VITAL_LOINC = Set.of("85354-9", "8480-6", "8462-4", "8867-4", "8310-5", "9279-1", "59408-5", "2708-6", "29463-7", "8302-2", "39156-5", "8329-5");

    // ---------- small helpers ----------

    /** An instant from a FHIR date, dateTime or instant ("2026-10-22T06:00:00Z", "2026-10-22T11:30:00+05:30", "2026-10-22", "2026-10", "2026"). Null when it cannot be read. */
    public static Instant instant(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.strip();
        try { return OffsetDateTime.parse(t).toInstant(); } catch (RuntimeException ignored) { /* try the next shape */ }
        try { return Instant.parse(t); } catch (RuntimeException ignored) { /* next */ }
        try { return java.time.LocalDateTime.parse(t).toInstant(ZoneOffset.UTC); } catch (RuntimeException ignored) { /* next */ }
        try { return LocalDate.parse(t).atStartOfDay().toInstant(ZoneOffset.UTC); } catch (RuntimeException ignored) { /* next */ }
        try { return YearMonth.parse(t).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC); } catch (RuntimeException ignored) { /* next */ }
        try { return LocalDate.of(Integer.parseInt(t), 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC); } catch (RuntimeException ignored) { return null; }
    }

    private static String str(JsonNode n) { return n == null || n.isMissingNode() || n.isNull() || !n.isValueNode() ? null : blank(n.asText(null)); }
    private static String blank(String s) { return s == null || s.isBlank() ? null : s.strip(); }

    /** First of the given date fields that reads as a date. */
    private static Instant firstInstant(JsonNode r, String... fields) {
        for (String f : fields) {
            JsonNode n = r;
            for (String part : f.split("\\.")) n = n.path(part);
            Instant i = instant(str(n));
            if (i != null) return i;
        }
        return null;
    }

    /** Text of a CodeableConcept: its text, else the display, else the code of the first coding. A plain string is returned as it is. */
    public static String text(JsonNode cc) {
        if (cc == null || cc.isMissingNode() || cc.isNull()) return null;
        if (cc.isValueNode()) return blank(cc.asText(null));
        if (cc.isArray()) return cc.size() == 0 ? null : text(cc.get(0));
        String t = str(cc.path("text"));
        if (t != null) return t;
        for (JsonNode c : cc.path("coding")) {
            String d = str(c.path("display"));
            if (d != null) return d;
        }
        for (JsonNode c : cc.path("coding")) {
            String d = str(c.path("code"));
            if (d != null) return d;
        }
        return str(cc.path("display"));
    }

    /** Code of the first coding (optionally only from a system that contains the given text). */
    public static String code(JsonNode cc, String systemContains) {
        if (cc == null) return null;
        if (cc.isArray()) return cc.size() == 0 ? null : code(cc.get(0), systemContains);
        for (JsonNode c : cc.path("coding")) {
            String sys = str(c.path("system"));
            if (systemContains != null && (sys == null || !sys.toLowerCase().contains(systemContains))) continue;
            String v = str(c.path("code"));
            if (v != null) return v;
        }
        return null;
    }

    /** Id part of a reference: "Encounter/E-1", "http://x/fhir/Encounter/E-1/_history/2" and a bare "E-1" all give "E-1"; "urn:uuid:..." gives null. */
    public static String refId(JsonNode ref) {
        String r = ref == null ? null : (ref.isValueNode() ? str(ref) : str(ref.path("reference")));
        if (r == null || r.startsWith("urn:") || r.startsWith("#")) return null;
        int h = r.indexOf("/_history");
        if (h > 0) r = r.substring(0, h);
        int s = r.lastIndexOf('/');
        return blank(s >= 0 ? r.substring(s + 1) : r);
    }

    private static String refDisplay(JsonNode ref) {
        if (ref == null) return null;
        String d = str(ref.path("display"));
        return d;
    }

    private static boolean errorEntry(JsonNode r) {
        return "entered-in-error".equals(str(r.path("status"))) || "entered-in-error".equals(code(r.path("verificationStatus"), null));
    }

    private static boolean is(JsonNode r, String type) { return r != null && type.equals(str(r.path("resourceType"))); }

    // ---------- Condition ----------

    public static Clinical.Condition condition(JsonNode r) {
        if (!is(r, "Condition") || errorEntry(r)) return null;
        String id = str(r.path("id"));
        String text = text(r.path("code"));
        if (text == null) return null;
        String icd = code(r.path("code"), "icd");
        return new Clinical.Condition(id, text, icd != null ? icd : code(r.path("code"), null), code(r.path("clinicalStatus"), null),
            firstInstant(r, "onsetDateTime", "onsetPeriod.start"), firstInstant(r, "abatementDateTime", "abatementPeriod.start"), firstInstant(r, "recordedDate"),
            refId(r.path("encounter")));
    }

    // ---------- MedicationRequest ----------

    public static Clinical.Medication medication(JsonNode r) {
        if (!is(r, "MedicationRequest") || errorEntry(r)) return null;
        String text = text(r.path("medicationCodeableConcept"));
        if (text == null) text = refDisplay(r.path("medicationReference"));
        if (text == null) text = text(r.path("contained").path(0).path("code"));
        if (text == null) return null;
        JsonNode d = r.path("dosageInstruction").path(0);
        String dose = str(d.path("text"));
        if (dose == null) {
            JsonNode q = d.path("doseAndRate").path(0).path("doseQuantity");
            if (!q.isMissingNode()) dose = (str(q.path("value")) == null ? "" : str(q.path("value")) + " ") + (str(q.path("unit")) == null ? "" : str(q.path("unit")));
            dose = blank(dose);
        }
        return new Clinical.Medication(str(r.path("id")), text, dose, str(r.path("status")), firstInstant(r, "authoredOn"), refDisplay(r.path("requester")),
            text(r.path("reasonCode")), refId(r.path("encounter")));
    }

    // ---------- AllergyIntolerance ----------

    public static Clinical.Allergy allergy(JsonNode r) {
        if (!is(r, "AllergyIntolerance") || errorEntry(r)) return null;
        String text = text(r.path("code"));
        if (text == null) return null;
        String c = code(r.path("code"), null);
        boolean none = "716186003".equals(c) || text.toLowerCase().startsWith("no known");
        JsonNode re = r.path("reaction").path(0);
        return new Clinical.Allergy(str(r.path("id")), text, str(r.path("criticality")), code(r.path("clinicalStatus"), null), text(re.path("manifestation")),
            str(re.path("severity")), none, firstInstant(r, "recordedDate", "onsetDateTime"));
    }

    // ---------- Procedure ----------

    public static Clinical.Procedure procedure(JsonNode r) {
        if (!is(r, "Procedure") || errorEntry(r)) return null;
        String text = text(r.path("code"));
        if (text == null) return null;
        return new Clinical.Procedure(str(r.path("id")), text, code(r.path("code"), null), firstInstant(r, "performedDateTime", "performedPeriod.start"),
            str(r.path("status")), refDisplay(r.path("performer").path(0).path("actor")), refId(r.path("encounter")));
    }

    // ---------- Observation ----------

    /** "H", "L", "N", "A" or null for the code of an interpretation. */
    public static String flag(String c) {
        if (c == null) return null;
        return switch (c.strip().toUpperCase()) {
            case "H", "HH", "HU", ">", "CH" -> "H";
            case "L", "LL", "LU", "<", "CL" -> "L";
            case "N" -> "N";
            case "A", "AA", "ABN" -> "A";
            default -> null;
        };
    }

    /** From a value and its reference range; null when there is not enough to say. */
    public static String computeFlag(Double v, Double lo, Double hi) {
        if (v == null || (lo == null && hi == null)) return null;
        if (hi != null && v > hi) return "H";
        if (lo != null && v < lo) return "L";
        return "N";
    }

    private static int rank(String f) { return f == null ? 0 : switch (f) { case "H", "L" -> 3; case "A" -> 2; case "N" -> 1; default -> 0; }; }

    private static Double num(JsonNode n) { return n == null || n.isMissingNode() || !n.isNumber() ? tryParse(str(n)) : Double.valueOf(n.asDouble()); }

    private static Double tryParse(String s) {
        if (s == null) return null;
        try { return Double.valueOf(s.replace(",", ".").strip()); } catch (NumberFormatException e) { return null; }
    }

    private static String fmt(double d) { return d == Math.rint(d) && Math.abs(d) < 1e9 ? String.valueOf((long) d) : String.valueOf(Math.round(d * 100) / 100.0); }

    private record Quantity(Double value, String unit, Double low, Double high, String flag) {}

    private static Quantity quantity(JsonNode holder, JsonNode valueQuantity) {
        Double v = num(valueQuantity.path("value"));
        String unit = str(valueQuantity.path("unit")) != null ? str(valueQuantity.path("unit")) : str(valueQuantity.path("code"));
        JsonNode rr = holder.path("referenceRange").path(0);
        Double lo = num(rr.path("low").path("value")), hi = num(rr.path("high").path("value"));
        String f = flag(code(holder.path("interpretation"), null));
        if (f == null) f = computeFlag(v, lo, hi);
        return new Quantity(v, unit, lo, hi, f);
    }

    /** One result or vital sign. A panel with components (blood pressure) becomes one row: "148/92", flag of the worst component, trend on the first. */
    public static Clinical.Observation observation(JsonNode r) {
        if (!is(r, "Observation") || errorEntry(r) || "cancelled".equals(str(r.path("status")))) return null;
        String text = text(r.path("code"));
        if (text == null) return null;
        String loinc = code(r.path("code"), "loinc");
        if (loinc == null) loinc = code(r.path("code"), null);
        String cat = code(r.path("category"), null);
        if (cat == null) cat = text(r.path("category"));
        if (cat != null) cat = cat.toLowerCase().replace(' ', '-');
        Double value = null, low = null, high = null;
        String display = null, unit = null, flag = null;
        if (r.has("valueQuantity")) {
            Quantity q = quantity(r, r.path("valueQuantity"));
            value = q.value; unit = q.unit; low = q.low; high = q.high; flag = q.flag;
            display = value == null ? null : fmt(value);
        } else if (r.path("component").isArray() && r.path("component").size() > 0) {
            List<String> parts = new ArrayList<>();
            for (JsonNode c : r.path("component")) {
                if (!c.has("valueQuantity")) continue;
                Quantity q = quantity(c, c.path("valueQuantity"));
                if (q.value == null) continue;
                if (parts.isEmpty()) { value = q.value; unit = q.unit; low = q.low; high = q.high; }
                parts.add(fmt(q.value));
                if (rank(q.flag) > rank(flag)) flag = q.flag;
            }
            if (!parts.isEmpty()) display = String.join("/", parts);
            String top = flag(code(r.path("interpretation"), null));
            if (rank(top) > rank(flag)) flag = top;
        } else if (r.has("valueString")) {
            display = str(r.path("valueString"));
            flag = flag(code(r.path("interpretation"), null));
        } else if (r.has("valueCodeableConcept")) {
            display = text(r.path("valueCodeableConcept"));
            flag = flag(code(r.path("interpretation"), null));
        } else if (r.has("valueInteger") || r.has("valueBoolean")) {
            display = str(r.path(r.has("valueInteger") ? "valueInteger" : "valueBoolean"));
            value = r.has("valueInteger") ? num(r.path("valueInteger")) : null;
        }
        if (display == null) return null;   // a result without a value (not yet reported) is not a result
        if (cat == null) cat = loinc != null && VITAL_LOINC.contains(loinc) ? "vital-signs" : (value != null ? "laboratory" : "other");
        return new Clinical.Observation(str(r.path("id")), loinc, text, cat, value, display, unit, low, high, flag,
            firstInstant(r, "effectiveDateTime", "effectivePeriod.start", "effectiveInstant", "issued"), str(r.path("status")), refId(r.path("encounter")));
    }

    // ---------- Encounter ----------

    public static Clinical.Encounter encounter(JsonNode r) {
        if (!is(r, "Encounter") || errorEntry(r)) return null;
        String id = str(r.path("id"));
        Instant start = firstInstant(r, "period.start");
        // class: a Coding in R4, a list of CodeableConcepts in R5; both are read
        JsonNode cls = r.path("class");
        String cc, cd;
        if (cls.isArray()) { cc = code(cls, null); cd = text(cls); } else { cc = str(cls.path("code")); cd = str(cls.path("display")); }
        String facRef = null, facName = null, ward = null;
        for (JsonNode l : r.path("location")) {
            JsonNode loc = l.path("location");
            String pt = code(l.path("physicalType"), null);
            if (pt == null) pt = code(loc.path("physicalType"), null);
            String name = refDisplay(loc);
            if (pt != null && Set.of("wa", "ro", "bd", "lvl").contains(pt.toLowerCase())) { if (ward == null) ward = name != null ? name : refId(loc); }
            else if (facRef == null) { facRef = str(loc.path("reference")); facName = name; }
        }
        JsonNode sp = r.path("serviceProvider");
        String provider = refDisplay(sp);
        if (facRef == null && !sp.isMissingNode()) { facRef = str(sp.path("reference")); facName = provider; }
        if (ward == null) ward = text(r.path("serviceType"));
        List<String> people = new ArrayList<>();
        String attending = null;
        for (JsonNode p : r.path("participant")) {
            String d = refDisplay(p.path("individual"));
            if (d == null) continue;
            if (!people.contains(d)) people.add(d);
            if (attending == null && "ATND".equalsIgnoreCase(code(p.path("type"), null))) attending = d;
        }
        if (attending == null && !people.isEmpty()) attending = people.get(0);
        List<Clinical.Diagnosis> dx = new ArrayList<>();
        for (JsonNode d : r.path("diagnosis")) {
            String dt = refDisplay(d.path("condition"));
            if (dt == null) dt = refId(d.path("condition"));
            if (dt == null) continue;
            dx.add(new Clinical.Diagnosis(refId(d.path("condition")), dt, text(d.path("use")), d.path("rank").isNumber() ? d.path("rank").asInt() : null));
        }
        String reason = text(r.path("reasonCode"));
        if (reason == null) reason = text(r.path("reason").path(0).path("value"));
        return new Clinical.Encounter(id, refId(r.path("subject")), str(r.path("status")), cc, cd, text(r.path("type")), reason, start, firstInstant(r, "period.end"),
            facRef, facName, ward, attending, people, dx, provider, text(r.path("hospitalization").path("dischargeDisposition")));
    }

    // ---------- bundles ----------

    /** The resources of the wanted type in a searchset Bundle. Entries of another type (an OperationOutcome warning, an Organization that was included) are skipped. */
    public static List<JsonNode> resources(JsonNode bundle, String type) {
        List<JsonNode> out = new ArrayList<>();
        if (bundle == null || !"Bundle".equals(str(bundle.path("resourceType")))) return out;
        for (JsonNode e : bundle.path("entry")) {
            JsonNode r = e.path("resource");
            if (is(r, type)) out.add(r);
        }
        return out;
    }
}

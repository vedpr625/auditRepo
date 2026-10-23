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
 * Reads FHIR R4 JSON (Patient and Encounter only) into the small records of {@link Registry}. Tolerant on purpose: a missing field is null, a field of an unexpected shape is
 * ignored, a resource that cannot be understood is skipped, and a resource the registry marks "entered-in-error" is never shown. Nothing here throws on odd input.
 */
public final class FhirParser {

    private FhirParser() {}

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

    // ---------- Encounter ----------

    public static Registry.Encounter encounter(JsonNode r) {
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
        return new Registry.Encounter(id, refId(r.path("subject")), str(r.path("status")), cc, cd, text(r.path("type")), start, firstInstant(r, "period.end"),
            facRef, facName, ward, attending, people, provider);
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

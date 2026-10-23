package com.akhester.auditui.demo;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.IntegerType;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.BooleanType;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import ca.uhn.fhir.context.FhirContext;

/**
 * DEMONSTRATION ONLY (audit.ui.demo=true). A stand-in for Chester that runs inside this program. It answers the same FHIR calls the
 * console makes (search with Chester's own parameters, read, $chain-info, $verify-chain) over the made-up records in DemoAuditData,
 * and applies the same kind of access rule: ministry auditors, privacy officers and admins see everything, a facility auditor sees only
 * their facility, anyone else is refused. It is a small imitation, NOT Chester: real Chester behaviour is what the real tests cover.
 */
@RestController
@RequestMapping("/demo/chester/fhir")
@ConditionalOnProperty(name = "audit.ui.demo", havingValue = "true")
public class DemoChester {

    private static final MediaType FHIR = MediaType.valueOf("application/fhir+json");
    private static final List<String> ALL_ROLES = List.of("AUDIT_ADMIN", "MINISTRY_AUDITOR", "PRIVACY_OFFICER");

    private final DemoAuditData data;
    private final FhirContext ctx = FhirContext.forR4();

    @Autowired
    public DemoChester(DemoAuditData data) { this.data = data; }

    /** Who is asking, read from the demo token: roles and (for a facility auditor) the facility. */
    private record Caller(List<String> roles, String facility) {
        boolean seesAll() { return roles.stream().anyMatch(ALL_ROLES::contains); }
        boolean facilityOnly() { return !seesAll() && roles.contains("FACILITY_AUDITOR") && !facility.isBlank(); }
        boolean allowed() { return seesAll() || facilityOnly(); }
    }

    private Caller caller(String authorization) {
        try {
            if (authorization != null && authorization.startsWith("Bearer demo.")) {
                String[] p = new String(Base64.getUrlDecoder().decode(authorization.substring(12)), StandardCharsets.UTF_8).split("\\|", -1);
                return new Caller(Arrays.asList(p[1].split(",")), p.length > 2 ? p[2] : "");
            }
        } catch (RuntimeException ignored) { /* falls through to nobody */ }
        return new Caller(List.of(), "");
    }

    private ResponseEntity<String> out(HttpStatus s, org.hl7.fhir.r4.model.Resource r) {
        return ResponseEntity.status(s).contentType(FHIR).body(ctx.newJsonParser().encodeResourceToString(r));
    }

    private ResponseEntity<String> refuse(int code, String why) {
        OperationOutcome oo = new OperationOutcome();
        oo.addIssue().setSeverity(OperationOutcome.IssueSeverity.ERROR).setCode(OperationOutcome.IssueType.PROCESSING).setDiagnostics(why);
        return out(HttpStatus.valueOf(code), oo);
    }

    private List<AuditEvent> visible(Caller c) {
        if (c.seesAll()) return data.events();
        return data.events().stream().filter(e -> c.facility().equals(e.getSource().getObserver().getReference())).toList();
    }

    @GetMapping(value = "/metadata")
    public ResponseEntity<String> metadata() {
        CapabilityStatement cs = new CapabilityStatement();
        cs.setStatus(Enumerations.PublicationStatus.ACTIVE).setKind(CapabilityStatement.CapabilityStatementKind.INSTANCE).setFhirVersion(Enumerations.FHIRVersion._4_0_1);
        cs.getSoftware().setName("Stand-in audit repository (sample records)");
        return out(HttpStatus.OK, cs);
    }

    @GetMapping("/AuditEvent")
    public ResponseEntity<String> search(@RequestParam MultiValueMap<String, String> q, @RequestHeader(value = "Authorization", required = false) String auth) {
        Caller c = caller(auth);
        if (!c.allowed()) return refuse(403, "Access denied by rule: default deny");
        Predicate<AuditEvent> filter;
        try { filter = filter(q); } catch (RuntimeException ex) { return refuse(400, "Unsupported or invalid search parameter: " + ex.getMessage()); }
        List<AuditEvent> hits = new ArrayList<>(visible(c).stream().filter(filter).toList());
        boolean asc = "date".equals(q.getFirst("_sort"));
        hits.sort(Comparator.comparing((AuditEvent e) -> e.getRecorded()).thenComparing(e -> e.getIdElement().getIdPart()));
        if (!asc) java.util.Collections.reverse(hits);

        Bundle b = new Bundle();
        b.setType(Bundle.BundleType.SEARCHSET).setTotal(hits.size());
        if (!"count".equals(q.getFirst("_summary"))) {
            int count = parse(q.getFirst("_count"), 20, 1, 500), off = parse(q.getFirst("_offset"), 0, 0, Integer.MAX_VALUE);
            for (AuditEvent e : hits.subList(Math.min(off, hits.size()), Math.min(off + count, hits.size()))) b.addEntry().setResource(e);
        }
        return out(HttpStatus.OK, b);
    }

    /**
     * The console records its own activity here (who searched, opened a patient, downloaded a report, decided a case). Any signed-in person's
     * token may write, because the console writes on that person's behalf; the real Chester will need a write role for the console (open question).
     */
    @org.springframework.web.bind.annotation.PostMapping(value = "/AuditEvent", consumes = {"application/fhir+json", "application/json"})
    public ResponseEntity<String> create(@org.springframework.web.bind.annotation.RequestBody String body, @RequestHeader(value = "Authorization", required = false) String auth) {
        Caller c = caller(auth);
        if (!c.allowed() && c.roles().stream().allMatch(String::isBlank)) return refuse(403, "Access denied by rule: default deny");
        AuditEvent e;
        try { e = ctx.newJsonParser().parseResource(AuditEvent.class, body); } catch (RuntimeException ex) { return refuse(400, "Not a valid AuditEvent"); }
        AuditEvent saved = data.append(e);
        return ResponseEntity.status(HttpStatus.CREATED).contentType(FHIR).header("Location", "AuditEvent/" + saved.getIdElement().getIdPart() + "/_history/1")
            .body(ctx.newJsonParser().encodeResourceToString(saved));
    }

    @GetMapping("/AuditEvent/{id}")
    public ResponseEntity<String> read(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String auth) {
        Caller c = caller(auth);
        if (!c.allowed()) return refuse(403, "Access denied by rule: default deny");
        return visible(c).stream().filter(e -> e.getIdElement().getIdPart().equals(id)).findFirst()
            .map(e -> out(HttpStatus.OK, e)).orElseGet(() -> refuse(404, "Resource AuditEvent/" + id + " is not known"));
    }

    @GetMapping("/AuditEvent/{id}/$chain-info")
    public ResponseEntity<String> chainInfo(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String auth) {
        Caller c = caller(auth);
        if (!c.allowed()) return refuse(403, "Access denied by rule: default deny");
        DemoAuditData.Chain ch = data.chain(id);
        if (ch == null || visible(c).stream().noneMatch(e -> e.getIdElement().getIdPart().equals(id))) return refuse(404, "No chain data");
        Parameters p = new Parameters();
        p.addParameter().setName("previousHash").setValue(new StringType(ch.previousHash()));
        p.addParameter().setName("currentHash").setValue(new StringType(ch.currentHash()));
        p.addParameter().setName("position").setValue(new IntegerType(ch.position()));
        p.addParameter().setName("partitionKey").setValue(new StringType(ch.partition()));
        p.addParameter().setName("chainWrittenAt").setValue(new StringType(ch.writtenAt().toString()));
        p.addParameter().setName("genesisHash").setValue(new StringType(data.genesis()));
        return out(HttpStatus.OK, p);
    }

    /** Re-computes every link. Only people who see everything may ask (like real Chester). */
    @GetMapping("/$verify-chain")
    public ResponseEntity<String> verify(@RequestParam(required = false) String partition, @RequestHeader(value = "Authorization", required = false) String auth) {
        Caller c = caller(auth);
        if (!c.seesAll()) return refuse(403, "Access denied by rule: default deny");
        Map<String, String> last = new java.util.HashMap<>();
        int checked = 0, problems = 0;
        List<AuditEvent> list = data.events().stream().filter(e -> partition == null || e.getRecorded().toInstant().toString().startsWith(partition)).toList();
        for (AuditEvent e : list) {
            DemoAuditData.Chain ch = data.chain(e.getIdElement().getIdPart());
            String prev = last.getOrDefault(ch.partition(), data.genesis());
            String expect = DemoAuditData.sha(prev + "|" + e.getIdElement().getIdPart() + "|" + e.getRecorded().toInstant());
            if (!ch.previousHash().equals(prev) || !ch.currentHash().equals(expect)) problems++;
            last.put(ch.partition(), ch.currentHash());
            checked++;
        }
        Parameters p = new Parameters();
        p.addParameter().setName("valid").setValue(new BooleanType(problems == 0));
        p.addParameter().setName("partitions").setValue(new IntegerType(last.size()));
        p.addParameter().setName("eventsChecked").setValue(new IntegerType(checked));
        p.addParameter().setName("unchainedEvents").setValue(new IntegerType(0));
        p.addParameter().setName("problemCount").setValue(new IntegerType(problems));
        return out(HttpStatus.OK, p);
    }

    // ── search ───────────────────────────────────────────────────────────────

    private static int parse(String v, int dflt, int min, int max) {
        try { return v == null ? dflt : Math.max(min, Math.min(max, Integer.parseInt(v))); } catch (NumberFormatException e) { return dflt; }
    }

    /** Every parameter the console sends. An unknown one is refused, never silently ignored (a silent ignore would show wrong results). */
    private Predicate<AuditEvent> filter(MultiValueMap<String, String> q) {
        Predicate<AuditEvent> p = e -> true;
        for (Map.Entry<String, List<String>> en : q.entrySet()) {
            String k = en.getKey();
            for (String v : en.getValue()) {
                switch (k) {
                    case "_count", "_offset", "_sort", "_summary", "_total", "_format", "_pretty" -> { }
                    case "_id" -> { var ids = Arrays.asList(v.split(",")); p = p.and(e -> ids.contains(e.getIdElement().getIdPart())); }
                    case "entity-ref" -> p = p.and(e -> e.getEntity().stream().anyMatch(x -> x.hasWhat() && v.equals(x.getWhat().getReference())));
                    case "agent-ref" -> p = p.and(e -> e.getAgent().stream().anyMatch(x -> x.hasWho() && v.equals(x.getWho().getReference())));
                    case "source-ref" -> p = p.and(e -> v.equals(e.getSource().getObserver().getReference()));
                    case "site" -> p = p.and(e -> e.getSource().hasSite() && e.getSource().getSite().toLowerCase(Locale.ROOT).startsWith(v.toLowerCase(Locale.ROOT)));
                    case "purpose-of-use", "purposeOfEvent" -> { var cs = Arrays.asList(v.split(",")); p = p.and(e -> e.getPurposeOfEvent().stream().anyMatch(x -> x.getCoding().stream().anyMatch(c -> cs.contains(c.getCode())))); }
                    case "break-glass" -> p = p.and(e -> e.getMeta().getSecurity().stream().anyMatch(c -> v.equals(c.getCode())));
                    case "outcome" -> { var os = Arrays.asList(v.split(",")); p = p.and(e -> os.contains(e.getOutcome().toCode())); }
                    case "action" -> { var as = Arrays.asList(v.split(",")); p = p.and(e -> as.contains(e.getAction().toCode())); }
                    case "_tag" -> {
                        int bar = v.indexOf('|');
                        String sys = bar < 0 ? null : v.substring(0, bar), code = bar < 0 ? v : v.substring(bar + 1);
                        p = p.and(e -> e.getMeta().getTag().stream().anyMatch(t -> code.equals(t.getCode()) && (sys == null || sys.equals(t.getSystem()))));
                    }
                    case "date" -> p = p.and(date(v));
                    default -> throw new IllegalArgumentException(k);
                }
            }
        }
        return p;
    }

    /** Date search like FHIR: a bare date covers the whole UTC day. */
    private static Predicate<AuditEvent> date(String v) {
        String prefix = v.length() >= 2 && Character.isLetter(v.charAt(0)) ? v.substring(0, 2) : "eq";
        String val = prefix.equals("eq") && !v.startsWith("eq") ? v : v.substring(2);
        Instant lo, hi;
        if (val.length() == 10) { LocalDate d = LocalDate.parse(val); lo = d.atStartOfDay().toInstant(ZoneOffset.UTC); hi = d.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC); }
        else { lo = Instant.parse(val); hi = lo.plusSeconds(1); }
        final Instant l = lo, h = hi;
        return switch (prefix) {
            case "ge" -> e -> !e.getRecorded().toInstant().isBefore(l);
            case "gt" -> e -> !e.getRecorded().toInstant().isBefore(h);
            case "le" -> e -> e.getRecorded().toInstant().isBefore(h);
            case "lt" -> e -> e.getRecorded().toInstant().isBefore(l);
            case "eq" -> e -> !e.getRecorded().toInstant().isBefore(l) && e.getRecorded().toInstant().isBefore(h);
            default -> throw new IllegalArgumentException("date");
        };
    }
}

package com.akhester.auditui.consent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Asks the consent service whether a person in a given role may see a patient's details for a given purpose.
 * Fails closed: anything other than a clear "permit" means the details are not shown.
 *
 * <p>Contract used here (the real NEHR consent manager's interface is not yet known and will need an adapter):
 * {@code GET {base}/check?patient=Patient/P-1&role=MINISTRY_AUDITOR&purpose=audit-review}
 * answers {@code {"decision":"permit|deny|none","reason":"...","consentId":"...","scopes":["demographics","encounters",...],"validUntil":"2027-01-01T00:00:00Z"}}.
 * {@code scopes} and {@code validUntil} are optional: an answer without scopes is a permit for everything (the older shape), one with scopes
 * permits only those sections, and a permit whose validUntil has passed counts as expired (deny). Unknown scope names are ignored.
 */
@Component
public class ConsentClient {

    public enum Result { PERMIT, DENY, NONE, UNAVAILABLE }

    /** The sections of a patient's record a consent can cover. */
    public static final List<String> SCOPES = List.of("demographics", "encounters", "conditions", "medications", "labs", "allergies", "procedures");

    public record Decision(Result result, String reason, String consentId, List<String> scopes, Instant validUntil) {
        public Decision {
            scopes = scopes == null ? List.of() : List.copyOf(scopes);
        }
        /** Older shape: no scopes given, so a permit covers every section and anything else covers none. */
        public Decision(Result result, String reason, String consentId) {
            this(result, reason, consentId, result == Result.PERMIT ? SCOPES : List.of(), null);
        }
        public boolean permitted() { return result == Result.PERMIT; }
        /** True only for a permit that covers this section. A denied, missing or unavailable consent covers nothing. */
        public boolean allows(String scope) { return permitted() && scopes.contains(scope); }
        public boolean limited() { return permitted() && scopes.size() < SCOPES.size(); }
        /** Permitted, but for no section at all: nothing is read from the registry. */
        public boolean covered() { return permitted() && !scopes.isEmpty(); }
    }

    private final RestTemplate http;
    private final String base;
    private final String noRecordPolicy;

    public ConsentClient(RestTemplateBuilder builder,
                         @Value("${audit.ui.consent-base-url:}") String base,
                         @Value("${audit.ui.consent-no-record:deny}") String noRecordPolicy) {
        this.http = builder.setConnectTimeout(Duration.ofSeconds(3)).setReadTimeout(Duration.ofSeconds(5)).build();
        this.base = base;
        this.noRecordPolicy = noRecordPolicy;
    }

    public boolean configured() { return base != null && !base.isBlank(); }

    @SuppressWarnings("unchecked")
    public Decision check(String patientRef, String role, String purpose) {
        if (!configured()) return new Decision(Result.UNAVAILABLE, "not-configured", null);
        try {
            String url = UriComponentsBuilder.fromHttpUrl(base.replaceAll("/+$", "") + "/check")
                .queryParam("patient", patientRef).queryParam("role", role == null ? "" : role).queryParam("purpose", purpose)
                .build().encode().toUriString();
            return parse(http.getForObject(url, Map.class), noRecordPolicy, Instant.now());
        } catch (RuntimeException e) {
            return new Decision(Result.UNAVAILABLE, "unreachable", null);
        }
    }

    /** Reads the answer of the consent service. Static and free of side effects so it can be tested; tolerant of missing fields, closed on anything odd. */
    static Decision parse(Map<String, Object> body, String noRecordPolicy, Instant now) {
        if (body == null) return new Decision(Result.UNAVAILABLE, "empty-answer", null);
        String d = String.valueOf(body.get("decision"));
        String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
        String id = body.get("consentId") == null ? null : String.valueOf(body.get("consentId"));
        Instant until = null;
        Object vu = body.get("validUntil");
        if (vu != null && !String.valueOf(vu).isBlank()) {
            until = com.akhester.auditui.registry.FhirParser.instant(String.valueOf(vu));
            if (until == null && "permit".equals(d)) return new Decision(Result.UNAVAILABLE, "unknown-answer", id);   // a validity date we cannot read: not a permit
        }
        List<String> scopes = scopes(body.get("scopes"));
        return switch (d) {
            case "permit" -> until != null && !until.isAfter(now) ? new Decision(Result.DENY, "consent-expired", id, List.of(), until)
                : new Decision(Result.PERMIT, reason, id, scopes == null ? SCOPES : scopes, until);
            case "deny" -> new Decision(Result.DENY, reason, id, List.of(), until);
            case "none" -> "permit".equals(noRecordPolicy) ? new Decision(Result.PERMIT, "no-record-policy-permit", null, SCOPES, null)
                                                            : new Decision(Result.NONE, reason, null, List.of(), null);
            default -> new Decision(Result.UNAVAILABLE, "unknown-answer", null);   // anything unexpected is treated as no
        };
    }

    /** Known scope names from a list or a comma-separated text, in the standard order; null when the answer has no scopes field (meaning: everything). */
    static List<String> scopes(Object raw) {
        if (raw == null) return null;
        List<String> given = new ArrayList<>();
        if (raw instanceof java.util.Collection<?> c) c.forEach(x -> given.add(String.valueOf(x)));
        else for (String x : String.valueOf(raw).split(",")) given.add(x);
        List<String> out = new ArrayList<>();
        for (String s : SCOPES) if (given.stream().anyMatch(g -> g.strip().equalsIgnoreCase(s))) out.add(s);
        return out;
    }
}

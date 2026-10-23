package com.akhester.auditui.consent;

import java.time.Duration;
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
 * answers {@code {"decision":"permit|deny|none","reason":"...","consentId":"..."}}.
 */
@Component
public class ConsentClient {

    public enum Result { PERMIT, DENY, NONE, UNAVAILABLE }

    public record Decision(Result result, String reason, String consentId) {
        public boolean permitted() { return result == Result.PERMIT; }
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
            Map<String, Object> body = http.getForObject(url, Map.class);
            if (body == null) return new Decision(Result.UNAVAILABLE, "empty-answer", null);
            String d = String.valueOf(body.get("decision"));
            String reason = body.get("reason") == null ? null : String.valueOf(body.get("reason"));
            String id = body.get("consentId") == null ? null : String.valueOf(body.get("consentId"));
            return switch (d) {
                case "permit" -> new Decision(Result.PERMIT, reason, id);
                case "deny" -> new Decision(Result.DENY, reason, id);
                case "none" -> "permit".equals(noRecordPolicy) ? new Decision(Result.PERMIT, "no-record-policy-permit", null)
                                                                : new Decision(Result.NONE, reason, null);
                default -> new Decision(Result.UNAVAILABLE, "unknown-answer", null);   // anything unexpected is treated as no
            };
        } catch (RuntimeException e) {
            return new Decision(Result.UNAVAILABLE, "unreachable", null);
        }
    }
}

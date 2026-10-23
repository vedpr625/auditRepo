package com.akhester.auditui.demo;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * DEMONSTRATION ONLY (audit.ui.demo=true). A stand-in consent manager with a few made-up patient decisions.
 * The real NEHR consent manager will need an adapter that answers the same {decision, reason, consentId} shape.
 */
@RestController
@RequestMapping("/demo/consent")
@ConditionalOnProperty(name = "audit.ui.demo", havingValue = "true")
public class DemoConsent {

    private static final List<String> ALL = List.of("demographics", "encounters", "conditions", "medications", "labs", "allergies", "procedures");

    @GetMapping("/check")
    public Map<String, Object> check(@RequestParam String patient, @RequestParam(defaultValue = "") String role,
                                     @RequestParam(defaultValue = "audit-review") String purpose) {
        String id = patient.replace("Patient/", "");
        String decision, reason, consent = "demo-consent-" + id;
        List<String> scopes = ALL;
        Instant until = Instant.now().plus(365, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS);
        switch (id) {
            case "P-00104" -> { decision = "deny"; reason = "patient-opted-out"; }
            case "P-00117" -> { decision = "deny"; reason = "consent-expired"; until = Instant.now().minus(40, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS); }
            // a consent that covers only some sections (the patient shared the problem list and allergies, not medicines or results)
            case "P-00110" -> { decision = "permit"; reason = "scope-limited"; scopes = List.of("demographics", "encounters", "conditions", "allergies"); until = Instant.now().plus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.DAYS); }
            case "P-00113" -> { decision = "permit"; reason = "scope-limited"; scopes = List.of("demographics", "encounters"); }
            // no scopes field at all: the older answer shape, which means everything
            case "P-00108" -> { decision = "permit"; reason = "general-consent"; scopes = null; until = null; }
            case "P-00112" -> {
                boolean ok = "PRIVACY_OFFICER".equals(role);
                decision = ok ? "permit" : "deny"; reason = ok ? "sensitive-record-privacy-officer-only" : "sensitive-record-privacy-officer-only";
            }
            case "P-00116" -> { decision = "none"; reason = "no-consent-on-file"; consent = null; until = null; }
            default -> { decision = "permit"; reason = "general-consent"; }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decision", decision); out.put("reason", reason); out.put("consentId", consent);
        if (scopes != null && !"none".equals(decision)) out.put("scopes", scopes);
        if (until != null) out.put("validUntil", until.toString());
        return out;
    }
}

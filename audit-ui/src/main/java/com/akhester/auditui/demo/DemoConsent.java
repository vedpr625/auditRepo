package com.akhester.auditui.demo;

import java.util.LinkedHashMap;
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

    @GetMapping("/check")
    public Map<String, Object> check(@RequestParam String patient, @RequestParam(defaultValue = "") String role,
                                     @RequestParam(defaultValue = "audit-review") String purpose) {
        String id = patient.replace("Patient/", "");
        String decision, reason, consent = "demo-consent-" + id;
        switch (id) {
            case "P-00104" -> { decision = "deny"; reason = "patient-opted-out"; }
            case "P-00117" -> { decision = "deny"; reason = "consent-expired"; }
            case "P-00112" -> {
                boolean ok = "PRIVACY_OFFICER".equals(role);
                decision = ok ? "permit" : "deny"; reason = ok ? "sensitive-record-privacy-officer-only" : "sensitive-record-privacy-officer-only";
            }
            case "P-00116" -> { decision = "none"; reason = "no-consent-on-file"; consent = null; }
            default -> { decision = "permit"; reason = "general-consent"; }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decision", decision); out.put("reason", reason); out.put("consentId", consent);
        return out;
    }
}

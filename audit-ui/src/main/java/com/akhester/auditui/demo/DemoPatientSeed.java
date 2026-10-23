package com.akhester.auditui.demo;

import org.hl7.fhir.r4.model.AuditEvent;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.akhester.auditui.service.ReviewRepository;

/** DEMONSTRATION ONLY. The casualty break-glass on the demo patient P-10407 (about three weeks ago) is shown as reviewed and found appropriate on the patient page. */
@Component
@Order(30)
@ConditionalOnProperty(name = "audit.ui.demo", havingValue = "true")
public class DemoPatientSeed implements ApplicationRunner {

    private final DemoAuditData data;
    private final ReviewRepository reviews;

    public DemoPatientSeed(DemoAuditData data, ReviewRepository reviews) { this.data = data; this.reviews = reviews; }

    @Override
    public void run(ApplicationArguments args) {
        for (AuditEvent e : data.events()) {
            boolean btg = e.getMeta().getSecurity().stream().anyMatch(c -> "BTG".equals(c.getCode()));
            boolean mine = e.getEntity().stream().anyMatch(x -> x.hasWhat() && "Patient/P-10407".equals(x.getWhat().getReference()))
                && e.getEntity().stream().anyMatch(x -> x.hasWhat() && "Encounter/E-86110".equals(x.getWhat().getReference()));
            if (btg && mine) {
                String id = e.getIdElement().getIdPart();
                if (reviews.find(id).isEmpty()) reviews.save(id, "reviewed", "samantha.privacy", "[demo] appropriate: unconscious patient");
            }
        }
    }
}

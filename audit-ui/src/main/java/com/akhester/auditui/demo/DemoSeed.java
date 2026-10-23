package com.akhester.auditui.demo;

import java.time.Instant;
import java.util.Map;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.akhester.auditui.admin.SendingSystemRepository;

/** DEMONSTRATION ONLY: lists the made-up sending systems with friendly names, so the Admin and Overview pages are not empty on first look. */
@Component
@ConditionalOnProperty(name = "audit.ui.demo", havingValue = "true")
public class DemoSeed implements ApplicationRunner {

    private final SendingSystemRepository systems;

    public DemoSeed(SendingSystemRepository systems) { this.systems = systems; }

    @Override
    public void run(ApplicationArguments args) {
        if (!systems.all().isEmpty()) return;
        Map<String, String[]> names = Map.of(
            "hhims-audit", new String[]{"HHIMS", "Health Information Management, 4 demo hospitals"},
            "lablink-gw", new String[]{"LabLink", "Central laboratory"},
            "pharma-galle", new String[]{"PharmaLink", "Pharmacies"},
            "echannel-svc", new String[]{"e-Channel", "Booking"},
            "pacs-audit", new String[]{"Radiology PACS", "12 hospitals"},
            "moh-etl", new String[]{"Ministry analytics feed", "Public health reporting"},
            "research-portal", new String[]{"Research portal", "Approved research use"});
        names.forEach((id, n) -> { systems.seen(id, Instant.now(), "demo"); systems.rename(id, n[0], n[1], "demo"); });
    }
}

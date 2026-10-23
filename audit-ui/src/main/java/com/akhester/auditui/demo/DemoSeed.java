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
            "hhims-colombo", new String[]{"Colombo hospital system", "Demo: National Hospital Colombo"},
            "hhims-kandy", new String[]{"Kandy hospital system", "Demo: Teaching Hospital Kandy"},
            "moh-etl", new String[]{"Ministry analytics feed", "Demo: public health reporting"},
            "research-portal", new String[]{"Research portal", "Demo: approved research use"});
        names.forEach((id, n) -> { systems.seen(id, Instant.now(), "demo"); systems.rename(id, n[0], n[1], "demo"); });
    }
}

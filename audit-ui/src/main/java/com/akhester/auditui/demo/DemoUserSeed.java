package com.akhester.auditui.demo;

import java.time.Instant;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.akhester.auditui.admin.DemoUserRepository;

/** DEMONSTRATION ONLY: fills the Admin Users tab with the five sign-in people and three more made-up ones. */
@Component
@ConditionalOnProperty(name = "audit.ui.demo", havingValue = "true")
public class DemoUserSeed implements ApplicationRunner {

    private final DemoUserRepository users;

    public DemoUserSeed(DemoUserRepository users) { this.users = users; }

    @Override
    public void run(ApplicationArguments args) {
        if (users.count() > 0) return;
        Instant now = Instant.now();
        for (DemoUsers.User u : DemoUsers.ALL) {
            if ("SOME_OTHER_ROLE".equals(u.role())) continue;
            users.add(u.username(), u.title() + " (" + u.username().split("\\.")[0] + ")", u.username().split("\\.")[0] + "@demo.example", u.role(), false, now.minusSeconds(86400L * 40));
        }
        users.add("anita.reviewer", "Anita Rathnayake", "anita@demo.example", "MINISTRY_AUDITOR", false, now.minusSeconds(86400L * 21));
        users.add("dilani.admin", "Dilani Wijesekara", "dilani@demo.example", "AUDIT_ADMIN", false, now.minusSeconds(86400L * 60));
        users.add("old.vendor", "Vendor sandbox (retired)", "sandbox@demo.example", "FACILITY_AUDITOR", false, now.minusSeconds(86400L * 90));
        users.setEnabled("old.vendor", false);
    }
}

package com.akhester.auditui.demo;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.akhester.auditui.admin.AdminChangeLog;
import com.akhester.auditui.people.FacilityDirectory;
import com.akhester.auditui.reports.ReportSchedules;

/** DEMONSTRATION ONLY: made-up facility names, report schedules and admin history for the Reports, People and Admin screens. Seeded once. */
@Component
@ConditionalOnProperty(name = "audit.ui.demo", havingValue = "true")
public class ScreensCDemoSeed implements ApplicationRunner {

    private final FacilityDirectory facilities;
    private final ReportSchedules schedules;
    private final AdminChangeLog changes;

    public ScreensCDemoSeed(FacilityDirectory facilities, ReportSchedules schedules, AdminChangeLog changes) {
        this.facilities = facilities; this.schedules = schedules; this.changes = changes;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (facilities.listed().isEmpty()) {
            String[][] f = {
                {"Device/hhims-colombo-01", "Colombo NH", "Colombo", "Hospital"}, {"Device/hhims-kandy-01", "Kandy TH", "Kandy", "Hospital"},
                {"Device/hhims-galle-01", "Galle TH", "Galle", "Hospital"}, {"Device/hhims-jaffna-01", "Jaffna TH", "Jaffna", "Hospital"},
                {"Device/lablink-gw-01", "Central lab", "Colombo", "Laboratory"}, {"Device/pharma-galle-01", "Galle pharmacies", "Galle", "Pharmacy"},
                {"Device/echannel-svc-01", "e-Channel booking", "National", "Booking service"}, {"Device/pacs-01", "Radiology PACS", "National", "Imaging"},
                {"Device/moh-etl-01", "MoH analytics", "Colombo", "Ministry"}, {"Device/research-portal-01", "Research portal", "Colombo", "Research"}};
            for (String[] x : f) facilities.add(x[0], x[1], x[2], x[3], true);
        }
        if (schedules.all().isEmpty()) {
            schedules.add("breakglass", "every Monday 08:00", "privacy officer", "demo", true);
            schedules.add("facility", "1st of the month", "MoH directors", "demo", true);
        }
        if (changes.count() == 0) {
            Instant n = Instant.now();
            changes.add("Admin", "Rule \"review deadline\" set to 24 h", true, n.minus(11, ChronoUnit.DAYS));
            changes.add("D. Wijesekara", "PACS client added", true, n.minus(14, ChronoUnit.DAYS));
            changes.add("D. Wijesekara", "Vendor sandbox disabled", true, n.minus(21, ChronoUnit.DAYS));
        }
    }
}

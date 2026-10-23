package com.akhester.auditui.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import com.akhester.auditui.model.AuditRow;

import jakarta.annotation.PostConstruct;

/**
 * Review-time thresholds. They are an organisational choice (not part of FHIR or IHE BALP):
 * an unreviewed break-glass or incomplete event turns "soon" after warning-hours and "overdue" after overdue-hours.
 */
@Configuration
public class SlaPolicy {

    @Value("${audit.sla.warning-hours:6}")
    private long warningHours;

    @Value("${audit.sla.overdue-hours:24}")
    private long overdueHours;

    @PostConstruct
    void apply() {
        if (warningHours < 1 || overdueHours <= warningHours) {
            throw new IllegalStateException("audit.sla.warning-hours must be >= 1 and smaller than audit.sla.overdue-hours");
        }
        AuditRow.slaWarningHours = warningHours;
        AuditRow.slaOverdueHours = overdueHours;
    }

    public long warningHours() { return warningHours; }
    public long overdueHours() { return overdueHours; }
}

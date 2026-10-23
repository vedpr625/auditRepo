package com.akhester.auditui.alerts;

import java.util.List;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.akhester.auditui.config.SlaPolicy;

/**
 * The alert rules and their thresholds. Thresholds come from configuration ({@code audit.alerts.<rule>.count / .hours / .enabled / .email}),
 * with the defaults below. Changing a rule from the screen is Planned; today an administrator changes it in configuration.
 * In this build the rules are only <em>evaluated</em> by the demo seed over the made-up records; a scheduled engine that watches live
 * records is Planned.
 */
@Component
public class AlertRules {

    /**
     * status: "demo" = the demo seed evaluates it; "planned" = no evaluation exists yet; "off" = switched off.
     * count/hours are null when the rule has no threshold of that kind.
     */
    public record Rule(String id, boolean enabled, String support, Integer count, Integer hours, boolean opensCase, boolean email, String severity, String planNote) {
        public boolean isPlanned() { return "planned".equals(support); }
        public boolean isWatching() { return enabled && !isPlanned(); }
    }

    private final List<Rule> rules;
    private final Environment env;

    public AlertRules(Environment env, SlaPolicy sla) {
        this.env = env;
        this.rules = List.of(
            rule("highvolume", true, "demo", 40, 1, false, "high", null),
            rule("failures", true, "demo", 10, 6, false, "medium", null),
            rule("btgreason", true, "demo", null, (int) sla.overdueHours(), true, "high", null),
            rule("repeataccess", true, "demo", 10, 2, true, "medium", null),
            rule("repeatbtg", true, "demo", 2, 24 * 7, false, "medium", null),
            rule("outofscope", true, "planned", null, null, true, "high", "registry"),
            rule("nocare", false, "planned", null, null, false, "medium", "registry"),
            rule("unusual", false, "planned", null, null, false, "low", "history"),
            rule("quiet", false, "planned", null, 2, false, "medium", "pacs"),
            rule("model", false, "planned", null, null, false, "low", "ministry"));
    }

    private Rule rule(String id, boolean enabled, String support, Integer count, Integer hours, boolean opensCase, String severity, String note) {
        String p = "audit.alerts." + id + ".";
        boolean on = "planned".equals(support) ? enabled && "outofscope".equals(id) : env.getProperty(p + "enabled", Boolean.class, enabled);
        return new Rule(id, on, support, count == null ? null : env.getProperty(p + "count", Integer.class, count),
            hours == null ? null : env.getProperty(p + "hours", Integer.class, hours), opensCase, env.getProperty(p + "email", Boolean.class, true), severity, note);
    }

    public List<Rule> all() { return rules; }

    public Rule get(String id) { return rules.stream().filter(r -> r.id().equals(id)).findFirst().orElse(null); }

    /** Addresses told by email (Planned: sending is not connected). Empty when not set. */
    public List<String> emailTo() {
        String v = env.getProperty("audit.alerts.email-to", "");
        return v.isBlank() ? List.of() : List.of(v.split("\\s*,\\s*"));
    }
}

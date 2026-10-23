package com.akhester.auditui.page;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.akhester.auditui.admin.AdminChangeLog;
import com.akhester.auditui.config.SlaPolicy;

/**
 * The rules that apply to everyone: how long a case may wait, how many reviewers it needs, and how patient identifiers show on screen.
 * Stored in app_setting; an administrator edits them and every change is written to the admin change log.
 * Stored does not yet mean enforced: the review and break-glass screens read their own limits for now (Planned), and the identifier choice is Planned (AR-30, AR-31).
 */
@Service
public class ReviewRules {

    public static final String DECIDE = "rules.decide-hours", REASON = "rules.reason-hours", REMINDER = "rules.reminder-hours",
        REVIEWERS = "rules.reviewers", OWN_FACILITY = "rules.own-facility", IDENTIFIERS = "identifiers.mask";
    private static final List<String> KEYS = List.of(DECIDE, REASON, REMINDER, REVIEWERS, OWN_FACILITY, IDENTIFIERS);

    public record Rules(int decideHours, int reasonHours, int reminderHours, String reviewers, boolean ownFacility, String identifiers) {}
    public record Changed(String by, Instant at) {}

    private final AppSettingRepository settings;
    private final AdminChangeLog changes;
    private final SlaPolicy sla;
    private final JdbcTemplate jdbc;

    public ReviewRules(AppSettingRepository settings, AdminChangeLog changes, SlaPolicy sla, JdbcTemplate jdbc) {
        this.settings = settings; this.changes = changes; this.sla = sla; this.jdbc = jdbc;
    }

    private int num(String key, int dflt) {
        try { return settings.get(key).map(Integer::parseInt).orElse(dflt); } catch (NumberFormatException e) { return dflt; }
    }

    public Rules current() {
        String rv = settings.get(REVIEWERS).orElse("two");
        String id = settings.get(IDENTIFIERS).orElse("mask");
        return new Rules(num(DECIDE, 24), num(REASON, (int) sla.overdueHours()), num(REMINDER, 6),
            "one".equals(rv) ? "one" : "two", !"false".equals(settings.get(OWN_FACILITY).orElse("true")), "full".equals(id) ? "full" : "mask");
    }

    /** Who last changed any of these rules and when; null when they were never changed. */
    public Changed lastChanged() {
        String in = String.join(",", KEYS.stream().map(k -> "'" + k + "'").toList());
        List<Changed> l = jdbc.query("SELECT updated_by, updated_at FROM app_setting WHERE setting_key IN (" + in + ") ORDER BY updated_at DESC LIMIT 1",
            (rs, i) -> new Changed(rs.getString(1), rs.getTimestamp(2).toInstant()));
        return l.isEmpty() ? null : l.get(0);
    }

    /** Checks the values; throws IllegalArgumentException naming the field. */
    public static Rules checked(int decide, int reason, int reminder, String reviewers, boolean own, String identifiers) {
        if (decide < 1 || decide > 720) throw new IllegalArgumentException("decide");
        if (reason < 1 || reason > 720) throw new IllegalArgumentException("reason");
        if (reminder < 0 || reminder >= decide) throw new IllegalArgumentException("reminder");
        if (!"one".equals(reviewers) && !"two".equals(reviewers)) throw new IllegalArgumentException("reviewers");
        if (!"mask".equals(identifiers) && !"full".equals(identifiers)) throw new IllegalArgumentException("identifiers");
        return new Rules(decide, reason, reminder, reviewers, own, identifiers);
    }

    /** Stores the rules that changed and records each change (who, what it was, what it is now). Returns how many changed. */
    public int save(String user, Rules n) {
        Rules o = current();
        Instant now = Instant.now();
        int count = 0;
        count += one(user, DECIDE, "time to decide a case", String.valueOf(o.decideHours()), String.valueOf(n.decideHours()), now);
        count += one(user, REASON, "time to give a break-glass reason", String.valueOf(o.reasonHours()), String.valueOf(n.reasonHours()), now);
        count += one(user, REMINDER, "reminder before the deadline", String.valueOf(o.reminderHours()), String.valueOf(n.reminderHours()), now);
        count += one(user, REVIEWERS, "reviewers needed per case", o.reviewers(), n.reviewers(), now);
        count += one(user, OWN_FACILITY, "reviewers cannot review their own facility", String.valueOf(o.ownFacility()), String.valueOf(n.ownFacility()), now);
        count += one(user, IDENTIFIERS, "patient identifiers on screen (planned)", o.identifiers(), n.identifiers(), now);
        return count;
    }

    private int one(String user, String key, String label, String was, String now, Instant at) {
        if (was.equals(now)) return 0;
        settings.set(key, now, user);
        changes.add(user, "Review rule changed: " + label + " " + was + " to " + now, false, at);
        return 1;
    }
}

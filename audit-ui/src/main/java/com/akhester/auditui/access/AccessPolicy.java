package com.akhester.auditui.access;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Who may do what, in one place, so the roles board (/access) shows what is really enforced. The reviewer rule mirrors
 * {@code ReviewAccess} and {@code Who.canReview()} (ROLE_ADMIN, MINISTRY_AUDITOR, PRIVACY_OFFICER); a unit test checks the two agree.
 */
public final class AccessPolicy {

    private AccessPolicy() {}

    /** The columns of the board: the roles the console actually knows. ADMIN is the console admin (Keycloak AUDIT_ADMIN). */
    public static final List<String> ROLES = List.of("MINISTRY_AUDITOR", "PRIVACY_OFFICER", "FACILITY_AUDITOR", "ADMIN");

    /** May open the console activity log. */
    public static final Set<String> ACTIVITY_VIEWERS = Set.of("PRIVACY_OFFICER", "MINISTRY_AUDITOR");
    /** May open a restricted (sensitive) patient. */
    public static final Set<String> SENSITIVE_OPENERS = Set.of("PRIVACY_OFFICER");
    /** May decide a case (the same people who may review events). */
    public static final Set<String> REVIEWERS = Set.of("ADMIN", "MINISTRY_AUDITOR", "PRIVACY_OFFICER");

    /** What a role may do with each capability: yes | no | conditional (the board adds words). */
    public enum Cap { SEARCH, SENSITIVE, DECIDE, RULE_VIOLATION, REPORTS, ALERT_RULES, SETTINGS, ACTIVITY_LOG }

    public static String level(String role, Cap cap) {
        return switch (cap) {
            case SEARCH -> "FACILITY_AUDITOR".equals(role) ? "own" : "yes";
            case SENSITIVE -> SENSITIVE_OPENERS.contains(role) ? "yes" : "asks";
            case DECIDE -> "PRIVACY_OFFICER".equals(role) ? "yes" : REVIEWERS.contains(role) ? "second" : "no";
            case RULE_VIOLATION -> "PRIVACY_OFFICER".equals(role) ? "yes" : REVIEWERS.contains(role) ? "sends" : "no";
            case REPORTS -> "yes";
            case ALERT_RULES -> "PRIVACY_OFFICER".equals(role) ? "planned" : "no";
            case SETTINGS -> "ADMIN".equals(role) ? "yes" : "prefs";
            case ACTIVITY_LOG -> ACTIVITY_VIEWERS.contains(role) ? "yes" : "no";
        };
    }

    private static Set<String> authorities() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? Set.of() : a.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    private static boolean has(String role) { return authorities().contains("ROLE_" + role); }

    /** The board's roles this person holds (ADMIN for the console admin). */
    public static List<String> myRoles() { return ROLES.stream().filter(AccessPolicy::has).toList(); }

    public static boolean canOpenSensitive() { return SENSITIVE_OPENERS.stream().anyMatch(AccessPolicy::has); }
    public static boolean canViewActivity() { return ACTIVITY_VIEWERS.stream().anyMatch(AccessPolicy::has); }
}

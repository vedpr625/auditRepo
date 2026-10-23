package com.akhester.auditui.reviews;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Who may do what with cases. Reviewers decide; privacy officers rule on disagreements and policy violations; ministry-level roles read. */
public final class ReviewAccess {

    private static final Set<String> REVIEWERS = Set.of("ROLE_ADMIN", "ROLE_MINISTRY_AUDITOR", "ROLE_PRIVACY_OFFICER");
    private static final Set<String> VIEW_ALL = Set.of("ROLE_ADMIN", "ROLE_AUDIT_ADMIN", "ROLE_MINISTRY_AUDITOR", "ROLE_PRIVACY_OFFICER", "ROLE_SYSTEM");

    private ReviewAccess() {}

    private static Set<String> roles() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null) return Set.of();
        return a.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    public static String user() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null || a.getName() == null ? null : a.getName();
    }

    public static boolean isReviewer() { return roles().stream().anyMatch(REVIEWERS::contains); }
    public static boolean isPrivacyOfficer() { return roles().contains("ROLE_PRIVACY_OFFICER"); }
    /** Cases span facilities, so only ministry-level roles may read them (a facility-scoped view is Planned). */
    public static boolean canViewAll() { return roles().stream().anyMatch(VIEW_ALL::contains); }
}

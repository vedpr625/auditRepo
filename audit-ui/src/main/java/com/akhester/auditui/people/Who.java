package com.akhester.auditui.people;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** What the signed-in person is, and what an audit record's agent is. Pure helpers, no data access. */
public final class Who {

    private Who() {}

    private static Set<String> authorities() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? Set.of() : a.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    /** Reviewer roles: ROLE_ADMIN, MINISTRY_AUDITOR, PRIVACY_OFFICER. */
    public static boolean canReview() {
        Set<String> a = authorities();
        return a.contains("ROLE_ADMIN") || a.contains("ROLE_MINISTRY_AUDITOR") || a.contains("ROLE_PRIVACY_OFFICER");
    }

    public static boolean isAdmin() { return authorities().contains("ROLE_ADMIN"); }

    /** person | system | organisation | unknown, from the reference Chester holds (Practitioner/..., Device/..., Organization/...). */
    public static String kind(String ref) {
        if (ref == null) return "unknown";
        if (ref.startsWith("Practitioner/")) return "person";
        if (ref.startsWith("Device/")) return "system";
        if (ref.startsWith("Organization/")) return "organisation";
        return "unknown";
    }

    /**
     * Role words for display (message key suffix under "people.role."). AuditEvent does not carry a role here, so this is read from the
     * reference and the name that were sent: a guess, and the pages say "as sent".
     */
    public static String role(String ref, String name) {
        String k = kind(ref);
        if (!k.equals("person")) return k;
        String n = name == null ? "" : name;
        String r = ref == null ? "" : ref.toLowerCase();
        if (n.startsWith("Dr.") || n.startsWith("Dr ")) return "physician";
        if (n.startsWith("Nurse") || r.contains("nurse")) return "nurse";
        if (r.contains("pharm")) return "pharmacist";
        return "practitioner";
    }
}

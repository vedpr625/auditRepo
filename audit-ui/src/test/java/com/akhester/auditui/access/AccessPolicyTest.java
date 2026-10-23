package com.akhester.auditui.access;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.akhester.auditui.reviews.ReviewAccess;

/** The roles board must say what the code enforces. */
class AccessPolicyTest {

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    private static void as(String... roles) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("u", "x",
            java.util.Arrays.stream(roles).map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList()));
    }

    @Test void boardAgreesWithReviewAccessForDeciding() {
        for (String r : AccessPolicy.ROLES) {
            as(r);
            boolean decides = !"no".equals(AccessPolicy.level(r, AccessPolicy.Cap.DECIDE));
            assertEquals(ReviewAccess.isReviewer(), decides, r);
        }
    }

    @Test void onlyPrivacyOfficerOpensSensitive() {
        as("PRIVACY_OFFICER"); assertTrue(AccessPolicy.canOpenSensitive());
        for (String r : List.of("MINISTRY_AUDITOR", "FACILITY_AUDITOR", "ADMIN")) { as(r); assertFalse(AccessPolicy.canOpenSensitive(), r); }
    }

    @Test void activityLogIsForPrivacyOfficerAndMinistryAuditorOnly() {
        for (String r : List.of("PRIVACY_OFFICER", "MINISTRY_AUDITOR")) { as(r); assertTrue(AccessPolicy.canViewActivity(), r); }
        for (String r : List.of("FACILITY_AUDITOR", "ADMIN")) { as(r); assertFalse(AccessPolicy.canViewActivity(), r); }
    }

}

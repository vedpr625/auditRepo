package com.akhester.auditui.demo;

import java.util.List;

/** DEMONSTRATION ONLY. The made-up people who can sign in to the demo build. Passwords are shown on the demo login page on purpose. */
public final class DemoUsers {

    public record User(String username, String password, String role, String facility, String title, String can) {}

    public static final List<User> ALL = List.of(
        new User("priya.admin", "priya@demo", "AUDIT_ADMIN", null, "Audit admin",
            "Sees every record, marks reviews, manages sending systems and settings."),
        new User("nimal.ministry", "nimal@demo", "MINISTRY_AUDITOR", null, "Ministry auditor",
            "Sees every record and takes part in case reviews. Cannot open Admin or settings that affect everyone."),
        new User("kumari.facility", "kumari@demo", "FACILITY_AUDITOR", "Device/hhims-colombo-01", "Facility auditor",
            "Sees only records from one facility (the Colombo hospital system)."),
        new User("samantha.privacy", "samantha@demo", "PRIVACY_OFFICER", null, "Privacy officer",
            "Sees every record and sensitive patient details, and settles review disagreements."),
        new User("erin.noaudit", "erin@demo", "SOME_OTHER_ROLE", null, "No audit role",
            "Signs in but has no audit role: should see nothing."));

    private DemoUsers() {}
}

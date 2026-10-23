package com.akhester.auditui.prefs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/** The signed-in person's preferences for the current request (read from the database once per request). */
@Component
public class PrefsContext {

    private static final String ATTR = PrefsContext.class.getName() + ".prefs";

    private final UserPrefsRepository repo;
    private final String defaultZone;

    public PrefsContext(UserPrefsRepository repo, @Value("${audit.ui.default-time-zone:Asia/Colombo}") String defaultZone) {
        this.repo = repo;
        this.defaultZone = defaultZone;
    }

    public UserPrefs defaults() { return UserPrefs.defaults(defaultZone); }

    /** Signed-in user name, or null when nobody is signed in. */
    public static String username() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return (a != null && a.isAuthenticated() && !(a instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) ? a.getName() : null;
    }

    public UserPrefs forRequest(HttpServletRequest request) {
        Object cached = request.getAttribute(ATTR);
        if (cached instanceof UserPrefs p) return p;
        UserPrefs p = defaults();
        String user = username();
        if (user != null) {
            try {
                p = repo.find(user).map(UserPrefs::checked).orElse(p);
            } catch (RuntimeException e) {
                // a damaged or unreadable row must never lock someone out of the pages: fall back to the defaults
                p = defaults();
            }
        }
        request.setAttribute(ATTR, p);
        return p;
    }

    public void save(HttpServletRequest request, UserPrefs p) {
        String user = username();
        if (user == null) throw new IllegalStateException("Not signed in");
        repo.save(user, p);
        request.setAttribute(ATTR, p);
    }
}

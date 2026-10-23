package com.akhester.auditui.page;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.context.MessageSource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.akhester.auditui.controller.AuditController;
import com.akhester.auditui.controller.MonitorController;
import com.akhester.auditui.prefs.PrefsContext;
import com.akhester.auditui.prefs.UserPrefs;

import jakarta.servlet.http.HttpServletRequest;

/** Puts what every page's header needs into the model: the person, their role, preferences and the time formatter. */
@ControllerAdvice(basePackages = {"com.akhester.auditui.page", "com.akhester.auditui.admin", "com.akhester.auditui.health", "com.akhester.auditui.overview", "com.akhester.auditui.patient", "com.akhester.auditui.reviews", "com.akhester.auditui.breakglass", "com.akhester.auditui.alerts", "com.akhester.auditui.reports", "com.akhester.auditui.people"}, assignableTypes = {AuditController.class, MonitorController.class})
public class ShellAdvice {

    /** Roles worth naming in the header, most powerful first. */
    private static final List<String> ROLE_ORDER = List.of("PRIVACY_OFFICER", "MINISTRY_AUDITOR", "FACILITY_AUDITOR", "AUDIT_ADMIN");

    private final PrefsContext prefsContext;
    private final MessageSource messages;
    private final boolean demo;
    private final org.springframework.beans.factory.ObjectProvider<NavBadges> badges;

    public ShellAdvice(PrefsContext prefsContext, MessageSource messages,
                       @org.springframework.beans.factory.annotation.Value("${audit.ui.demo:false}") boolean demo,
                       org.springframework.beans.factory.ObjectProvider<NavBadges> badges) {
        this.prefsContext = prefsContext;
        this.messages = messages;
        this.demo = demo;
        this.badges = badges;
    }

    /** The most powerful named role the signed-in person holds, or null. */
    public static String primaryRole() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null) return null;
        Set<String> auth = a.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        return ROLE_ORDER.stream().filter(r -> auth.contains("ROLE_" + r)).findFirst().orElse(null);
    }

    @ModelAttribute
    public void shell(HttpServletRequest request, Model model, Locale locale) {
        UserPrefs prefs = prefsContext.forRequest(request);
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        Set<String> auth = a == null ? Set.of()
                : a.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        String roleKey = ROLE_ORDER.stream().filter(r -> auth.contains("ROLE_" + r)).findFirst().orElse(null);
        model.addAttribute("prefs", prefs);
        model.addAttribute("now", Instant.now());
        model.addAttribute("shellPath", request.getRequestURI());
        model.addAttribute("tf", new TimeFormat(prefs, locale, messages, Instant.now()));
        model.addAttribute("shellUser", a == null ? "" : a.getName());
        model.addAttribute("shellRole", roleKey == null ? "" : messages.getMessage("role." + roleKey, null, roleKey, locale));
        model.addAttribute("demo", demo);
        int open = 0;
        try { NavBadges b = badges.getIfAvailable(); if (b != null) open = b.reviewsOpen(); } catch (RuntimeException ignored) { /* a badge must never break a page */ }
        model.addAttribute("navReviewsOpen", open);
        model.addAttribute("canAdmin", auth.contains("ROLE_ADMIN"));
    }
}

package com.akhester.auditui.access;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.akhester.auditui.activity.ActivityRepository;
import com.akhester.auditui.prefs.PrefsContext;

/** What every page needs for the "this console is recorded" notice and the session timer. Failures here never break a page. */
@ControllerAdvice
public class AccessAdvice {

    private final ActivityRepository activity;
    private final int timeoutMinutes, warnSeconds;
    private final java.util.Set<String> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public AccessAdvice(ActivityRepository activity, @Value("${audit.ui.session-timeout-minutes:30}") int timeoutMinutes,
                        @Value("${audit.ui.session-warning-seconds:120}") int warnSeconds) {
        this.activity = activity; this.timeoutMinutes = Math.max(1, timeoutMinutes); this.warnSeconds = Math.max(5, warnSeconds);
    }

    @ModelAttribute
    public void access(Model model) {
        String user = PrefsContext.username();
        boolean due = false;
        if (user != null && !seen.contains(user)) {
            try { if (activity.noticeSeen(user)) seen.add(user); else due = true; } catch (RuntimeException e) { /* no notice rather than a broken page */ }
        }
        model.addAttribute("accNoticeDue", due);
        model.addAttribute("accSessionSeconds", timeoutMinutes * 60);
        model.addAttribute("accWarnSeconds", Math.min(warnSeconds, timeoutMinutes * 60 - 1));
        model.addAttribute("accCanActivity", AccessPolicy.canViewActivity());
        model.addAttribute("accSignedIn", user != null);
    }
}

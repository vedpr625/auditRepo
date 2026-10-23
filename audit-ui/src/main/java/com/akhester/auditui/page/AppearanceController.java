package com.akhester.auditui.page;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.akhester.auditui.prefs.PrefsContext;
import com.akhester.auditui.prefs.UserPrefs;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The header's colour mode (light, dark, automatic) and colour theme switch. Small on purpose: only these two choices are
 * saved here, everything else stays in Settings. With JavaScript the page changes at once and this is called in the
 * background; without it the same buttons are an ordinary form and the person lands back on the page they were on.
 */
@Controller
public class AppearanceController {

    private final PrefsContext prefsContext;

    public AppearanceController(PrefsContext prefsContext) { this.prefsContext = prefsContext; }

    @PostMapping("/prefs/appearance")
    public Object save(@RequestParam(required = false) String theme, @RequestParam(required = false) String accent,
                       @RequestParam(required = false) String back, HttpServletRequest request) {
        boolean background = "fetch".equals(request.getHeader("X-Requested-With"));
        try {
            UserPrefs p = prefsContext.forRequest(request);
            if (theme != null) p = p.withTheme(theme);
            if (accent != null) p = p.withAccent(accent);
            p.checked();
            prefsContext.save(request, p);
            if (background) return ResponseEntity.ok(Map.of("theme", p.theme(), "accent", p.accent()));
        } catch (IllegalArgumentException e) {
            if (background) return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "invalid"));
        }
        return "redirect:" + safeBack(back);
    }

    /** Only a path on this site: starts with one slash, no scheme, no second slash, no line breaks. */
    static String safeBack(String back) {
        if (back == null || back.length() > 300 || !back.startsWith("/") || back.startsWith("//") || back.startsWith("/\\")
                || back.chars().anyMatch(c -> c < 32)) return "/overview";
        return back;
    }
}

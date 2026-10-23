package com.akhester.auditui.controller;

import com.akhester.auditui.config.ChesterTokenProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Login page and the monitor page.
 *
 * GET /login            → sign-in page (a button that starts the Keycloak login)
 * GET /monitor          → monitor page; the browser polls /monitor/history for new events
 * GET /monitor/history  → last N AuditEvents from Chester, fetched with the signed-in user's own token
 *
 * (The earlier live stream and Chester-page proxy are gone: this Chester has no /chester/monitor endpoints and
 * blocks non-FHIR paths.)
 */
@Controller
@Slf4j
public class MonitorController {

    @Value("${chester.fhir.base-url}")
    private String chesterFhirBaseUrl;

    private final ChesterTokenProvider tokens;

    private final boolean demo;

    public MonitorController(ChesterTokenProvider tokens, @org.springframework.beans.factory.annotation.Value("${audit.ui.demo:false}") boolean demo) {
        this.tokens = tokens;
        this.demo = demo;
    }

    @GetMapping("/login")
    public String loginPage(Model model) {
        if (demo) { model.addAttribute("demoUsers", com.akhester.auditui.demo.DemoUsers.ALL); return "login-demo"; }
        return "login";
    }

    @GetMapping("/monitor")
    public String monitorPage(Model model) {
        model.addAttribute("isAdmin", isAdmin());
        model.addAttribute("currentUser", currentUsername());
        return "monitor";
    }

    @GetMapping(value = "/monitor/history", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> history(@RequestParam(value = "count", defaultValue = "50") int count) {
        int safeCount = Math.max(1, Math.min(count, 200));
        String url = chesterFhirBaseUrl + "/AuditEvent?_sort=-date&_count=" + safeCount;
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/fhir+json");
            conn.setRequestProperty("Authorization", "Bearer " + tokens.currentAccessToken());
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                log.warn("Monitor history: Chester answered HTTP {}", code);
                return ResponseEntity.status(code).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"The audit repository refused this user\"}");
            }
            if (code < 200 || code >= 300) {
                log.warn("Monitor history: Chester answered HTTP {}", code);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"Could not read from the audit repository\"}");
            }
            try (InputStream is = conn.getInputStream()) {
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                        .body(new String(is.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (Exception ex) {
            log.warn("Monitor history error: {}", ex.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"Could not reach the audit repository\"}");
        }
    }

    private boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    }

    private String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "unknown";
    }
}

package com.akhester.auditui.page;

import java.net.URI;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.client.RestClient;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.akhester.auditui.config.SlaPolicy;
import com.akhester.auditui.prefs.PrefsContext;
import com.akhester.auditui.prefs.UserPrefs;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Settings: each person's display preferences, and (administrators only) the registry connection. */
@Controller
public class SettingsController {

    private final PrefsContext prefsContext;
    private final LocaleResolver localeResolver;
    private final AppSettingRepository settings;
    private final SlaPolicy sla;

    public SettingsController(PrefsContext prefsContext, LocaleResolver localeResolver, AppSettingRepository settings, SlaPolicy sla) {
        this.prefsContext = prefsContext;
        this.localeResolver = localeResolver;
        this.settings = settings;
        this.sla = sla;
    }

    /** Time zones offered: every "Region/City" id, with the default first. */
    static List<String> zoneIds(String first) {
        TreeSet<String> all = new TreeSet<>();
        for (String id : ZoneId.getAvailableZoneIds()) if (id.matches("[A-Z][A-Za-z_]+/[A-Za-z_/+-]+") && !id.startsWith("Etc/")) all.add(id);
        all.add("UTC");
        List<String> out = new ArrayList<>();
        out.add(first);
        for (String z : all) if (!z.equals(first)) out.add(z);
        return out;
    }

    @GetMapping("/settings")
    public String page(Model model, HttpServletRequest request) {
        UserPrefs p = prefsContext.forRequest(request);
        model.addAttribute("zones", zoneIds(p.timeZone()));
        model.addAttribute("registryUrl", settings.get(AppSettingRepository.REGISTRY_BASE_URL).orElse(""));
        model.addAttribute("slaWarning", sla.warningHours());
        model.addAttribute("slaOverdue", sla.overdueHours());
        return "settings";
    }

    @PostMapping("/settings/prefs")
    public String savePrefs(@RequestParam String language, @RequestParam String timeZone, @RequestParam String timeDisplay,
                            @RequestParam(defaultValue = "false") boolean clock24, @RequestParam(defaultValue = "false") boolean showUtc,
                            @RequestParam String theme, @RequestParam String density,
                            HttpServletRequest request, HttpServletResponse response, RedirectAttributes ra) {
        try {
            UserPrefs p = new UserPrefs(language, timeZone, timeDisplay, clock24, showUtc, theme, density).checked();
            prefsContext.save(request, p);
            localeResolver.setLocale(request, response, java.util.Locale.forLanguageTag(language));
            ra.addFlashAttribute("flash", "saved");
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flash", "invalid");
        }
        return "redirect:/settings";
    }

    /** The registry address must be a plain http(s) address without a password in it. */
    static URI parseRegistry(String raw) {
        if (raw == null) throw new IllegalArgumentException();
        String v = raw.strip();
        if (v.isEmpty() || v.length() > 500) throw new IllegalArgumentException();
        URI u = URI.create(v);
        if (u.getScheme() == null || !(u.getScheme().equals("http") || u.getScheme().equals("https"))) throw new IllegalArgumentException();
        if (u.getHost() == null || u.getUserInfo() != null || u.getFragment() != null) throw new IllegalArgumentException();
        return u;
    }

    @PostMapping("/settings/registry")
    public String saveRegistry(@RequestParam String registryUrl, @RequestParam(defaultValue = "save") String action, RedirectAttributes ra) {
        String user = PrefsContext.username();
        try {
            if (registryUrl == null || registryUrl.isBlank()) {
                settings.set(AppSettingRepository.REGISTRY_BASE_URL, "", user);
                ra.addFlashAttribute("flash", "registryCleared");
                return "redirect:/settings#registry";
            }
            URI u = parseRegistry(registryUrl);
            String clean = u.toString().replaceAll("/+$", "");
            if ("test".equals(action)) {
                ra.addFlashAttribute("registryTest", testRegistry(clean));
                ra.addFlashAttribute("registryTried", clean);
            } else {
                settings.set(AppSettingRepository.REGISTRY_BASE_URL, clean, user);
                ra.addFlashAttribute("flash", "registrySaved");
            }
        } catch (IllegalArgumentException e) {
            ra.addFlashAttribute("flash", "registryInvalid");
        }
        return "redirect:/settings#registry";
    }

    /** Asks the registry for its capability statement. Returns "ok:<fhirVersion>", or "http:<status>" or "unreachable". */
    @SuppressWarnings("unchecked")
    static String testRegistry(String base) {
        try {
            SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
            f.setConnectTimeout(4000);
            f.setReadTimeout(6000);
            RestClient c = RestClient.builder().requestFactory(f).build();
            Map<String, Object> body = c.get().uri(base + "/metadata").accept(MediaType.parseMediaType("application/fhir+json"), MediaType.APPLICATION_JSON)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (rq, rs) -> { throw new IllegalStateException("http:" + rs.getStatusCode().value()); })
                    .body(Map.class);
            if (body != null && "CapabilityStatement".equals(body.get("resourceType"))) return "ok:" + body.getOrDefault("fhirVersion", "?");
            return "notfhir";
        } catch (IllegalStateException e) {
            return e.getMessage() != null && e.getMessage().startsWith("http:") ? e.getMessage() : "unreachable";
        } catch (Exception e) {
            return "unreachable";
        }
    }
}

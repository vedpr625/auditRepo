package com.akhester.auditui.access;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.akhester.auditui.page.AppSettingRepository;

/** How a national id (NIC) is shown. The Settings screen stores the choice in app_setting "mask.nic"; without one the id is masked. */
@Component
public class Masking {

    public static final String KEY = "mask.nic";
    private static final Set<String> SHOW = Set.of("off", "false", "no", "none", "full", "show", "unmasked", "0");
    private static final Pattern DIGITS = Pattern.compile("(\\d{4})\\D*$");

    private final AppSettingRepository settings;

    public Masking(AppSettingRepository settings) { this.settings = settings; }

    public boolean masked() {
        try { return settings.get(KEY).map(v -> !SHOW.contains(v.strip().toLowerCase())).orElse(true); }
        catch (RuntimeException e) { return true; }   // when in doubt, hide
    }

    /** What the header shows after "NIC": "ending 4821" when masked, the registry's own text when not. Null when there is no id. */
    public String nic(String registryValue, String endingWord) {
        if (registryValue == null || registryValue.isBlank()) return null;
        if (!masked()) return registryValue;
        Matcher m = DIGITS.matcher(registryValue);
        return m.find() ? endingWord + " " + m.group(1) : registryValue;   // no digits: nothing to hide (for example "child, no id")
    }
}

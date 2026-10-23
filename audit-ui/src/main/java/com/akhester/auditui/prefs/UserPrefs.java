package com.akhester.auditui.prefs;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;

/** One person's display preferences. Every field is validated by {@link #checked()} before it is stored. */
public record UserPrefs(String language, String timeZone, String timeDisplay, boolean clock24, boolean showUtc,
                        String theme, String density, String accent) {

    public static final List<String> LANGUAGES = List.of("en", "si", "ta");
    public static final List<String> TIME_DISPLAYS = List.of("both", "exact", "relative");
    public static final List<String> THEMES = List.of("system", "light", "dark");
    /** Colour themes (data-accent). "clinic" is the original teal look. */
    public static final List<String> ACCENTS = List.of("clinic", "ocean", "ministry", "observatory");
    public static final List<String> DENSITIES = List.of("comfortable", "compact");

    /** Seven-value form used before colour themes existed; the accent is the default one. */
    public UserPrefs(String language, String timeZone, String timeDisplay, boolean clock24, boolean showUtc, String theme, String density) {
        this(language, timeZone, timeDisplay, clock24, showUtc, theme, density, "clinic");
    }

    public UserPrefs withAccent(String a) { return new UserPrefs(language, timeZone, timeDisplay, clock24, showUtc, theme, density, a); }
    public UserPrefs withTheme(String t) { return new UserPrefs(language, timeZone, timeDisplay, clock24, showUtc, t, density, accent); }
    public UserPrefs withLanguage(String l) { return new UserPrefs(l, timeZone, timeDisplay, clock24, showUtc, theme, density, accent); }

    public static UserPrefs defaults(String zone) {
        return new UserPrefs("en", zone, "both", true, false, "system", "comfortable", "clinic");
    }

    /** Returns this record if every value is allowed, otherwise throws IllegalArgumentException naming the field. */
    public UserPrefs checked() {
        if (!LANGUAGES.contains(language)) throw new IllegalArgumentException("language");
        if (!TIME_DISPLAYS.contains(timeDisplay)) throw new IllegalArgumentException("timeDisplay");
        if (!THEMES.contains(theme)) throw new IllegalArgumentException("theme");
        if (accent == null || !ACCENTS.contains(accent)) throw new IllegalArgumentException("accent");
        if (!DENSITIES.contains(density)) throw new IllegalArgumentException("density");
        if (timeZone == null || timeZone.length() > 64) throw new IllegalArgumentException("timeZone");
        try {
            ZoneId.of(timeZone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("timeZone");
        }
        return this;
    }

    public ZoneId zone() { return ZoneId.of(timeZone); }

    /** Value for the data-theme attribute, or null to follow the system setting. */
    public String themeAttr() { return "system".equals(theme) ? null : theme; }

    /** Value for the data-accent attribute, or null for the default (Clinic) look. */
    public String accentAttr() { return "clinic".equals(accent) ? null : accent; }

    /** Value for the data-density attribute, or null for the comfortable default. */
    public String densityAttr() { return "compact".equals(density) ? "compact" : null; }
}

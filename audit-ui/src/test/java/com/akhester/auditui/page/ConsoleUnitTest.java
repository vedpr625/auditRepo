package com.akhester.auditui.page;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import com.akhester.auditui.prefs.UserPrefs;

/** Plain unit tests: no server, no database. */
class ConsoleUnitTest {

    private static ResourceBundleMessageSource messages() {
        ResourceBundleMessageSource m = new ResourceBundleMessageSource();
        m.setBasename("messages");
        m.setDefaultEncoding("UTF-8");
        m.setFallbackToSystemLocale(false);
        return m;
    }

    private static final Instant NOW = Instant.parse("2026-10-23T04:59:41Z");

    private static TimeFormat tf(UserPrefs p) { return new TimeFormat(p, Locale.ENGLISH, messages(), NOW); }

    @Test void exactTimeNamesTheZoneOffset() {
        UserPrefs p = UserPrefs.defaults("Asia/Colombo");
        assertEquals("Fri 23 Oct 2026, 10:29:41 UTC+05:30", tf(p).exact(NOW));
        assertEquals("2026-10-23 04:59:41 UTC", tf(p).utc(NOW));
    }

    @Test void twelveHourClockAndUtcZoneLabel() {
        UserPrefs p = new UserPrefs("en", "UTC", "both", false, false, "system", "comfortable");
        assertTrue(tf(p).exact(NOW).endsWith(" UTC"));
        assertTrue(tf(p).exact(NOW).contains("4:59:41"), tf(p).exact(NOW));
    }

    @Test void relativeTimeSteps() {
        TimeFormat t = tf(UserPrefs.defaults("UTC"));
        assertEquals("just now", t.relative(NOW.minusSeconds(30)));
        assertEquals("4 min ago", t.relative(NOW.minusSeconds(240)));
        assertEquals("27 h ago", t.relative(NOW.minusSeconds(27 * 3600)));
        assertEquals("3 days ago", t.relative(NOW.minusSeconds(3 * 86400)));
        assertEquals("just now", t.relative(NOW.plusSeconds(20)));   // small clock difference
    }

    @Test void daylightSavingOffsetFollowsTheDate() {
        UserPrefs p = UserPrefs.defaults("Europe/London");
        assertTrue(tf(p).exact(Instant.parse("2026-07-01T12:00:00Z")).endsWith("UTC+01:00"));
        assertTrue(tf(p).exact(Instant.parse("2026-12-01T12:00:00Z")).endsWith("UTC"));
    }

    @Test void prefsValidation() {
        assertDoesNotThrow(() -> UserPrefs.defaults("Asia/Colombo").checked());
        for (UserPrefs bad : List.of(
                new UserPrefs("xx", "UTC", "both", true, false, "system", "comfortable"),
                new UserPrefs("en", "Nope/Zone", "both", true, false, "system", "comfortable"),
                new UserPrefs("en", "UTC", "weird", true, false, "system", "comfortable"),
                new UserPrefs("en", "UTC", "both", true, false, "neon", "comfortable"),
                new UserPrefs("en", "UTC", "both", true, false, "system", "tiny"),
                new UserPrefs("en", "x".repeat(65), "both", true, false, "system", "comfortable"),
                new UserPrefs("en", null, "both", true, false, "system", "comfortable")))
            assertThrows(IllegalArgumentException.class, bad::checked);
    }

    /** Every message key a template or Java class asks for must exist in the English file. */
    @Test void everyUsedMessageKeyExistsInEnglish() throws IOException {
        Properties en = new Properties();
        for (String f : List.of("", "-overview", "-reviews", "-breakglass", "-alerts", "-reports", "-people", "-health", "-admin", "-access", "-find", "-settings")) {
            try (InputStream in = getClass().getResourceAsStream("/messages" + f + ".properties")) {
                if (in != null) en.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            }
        }
        Path root = Paths.get("src/main");
        Pattern tpl = Pattern.compile("#\\{([a-zA-Z0-9_.]+)[(}]");
        Set<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(root.resolve("resources/templates"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".html")).toList()) {
                Matcher m = tpl.matcher(Files.readString(f));
                while (m.find()) if (!en.containsKey(m.group(1))) missing.add(m.group(1) + "  (" + f.getFileName() + ")");
            }
        }
        // the old Explore and Monitor pages still carry fixed English text; only keys they ask for are checked
        assertTrue(missing.isEmpty(), "Missing in messages.properties: " + missing);
    }

    /** The Sinhala and Tamil files may only contain keys that exist in English (a typo would otherwise hide forever). */
    @Test void translationFilesHaveNoUnknownKeys() throws IOException {
        Properties en = new Properties();
        try (InputStream in = getClass().getResourceAsStream("/messages.properties")) { en.load(in); }
        for (String f : List.of("/messages_si.properties", "/messages_ta.properties")) {
            Properties p = new Properties();
            try (InputStream in = getClass().getResourceAsStream(f)) { p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)); }
            for (Object k : p.keySet()) assertTrue(en.containsKey(k), f + " has unknown key " + k);
        }
    }

    /** The product is called "Audit Repository" on screen. The old project name must not reach a person's eyes. */
    @Test void noOldProjectNameInVisibleText() throws IOException {
        List<String> found = new ArrayList<>();
        Path res = Path.of("src/main/resources");
        try (Stream<Path> files = Files.list(res)) {
            for (Path f : files.filter(x -> x.getFileName().toString().startsWith("messages") && x.toString().endsWith(".properties")).toList()) {
                Properties p = new Properties();
                try (java.io.Reader r = Files.newBufferedReader(f, java.nio.charset.StandardCharsets.UTF_8)) { p.load(r); }
                for (String k : p.stringPropertyNames()) if (p.getProperty(k).toLowerCase(Locale.ROOT).contains("chester")) found.add(f.getFileName() + ": " + k);
            }
        }
        // templates: drop comments, template expressions and attributes that a person never sees, then look at what is left
        Pattern comment = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
        Pattern expr = Pattern.compile("[$#@~*]\\{[^{}]*\\}");
        Pattern hidden = Pattern.compile("\\s(?:th:)?(?:if|unless|each|class|classappend|fragment|replace|insert|with|object|switch|case|href|action|src|value|style|id|name|for|data-[a-z-]+|hx-[a-z-]+|onclick|th:attr|th:styleappend)=(\"[^\"]*\"|'[^']*')");
        try (Stream<Path> files = Files.walk(res.resolve("templates"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".html")).toList()) {
                String t = comment.matcher(Files.readString(f)).replaceAll("");
                for (String before = null; !t.equals(before); ) { before = t; t = expr.matcher(t).replaceAll(""); }
                t = hidden.matcher(t).replaceAll("");
                if (t.toLowerCase(Locale.ROOT).contains("chester")) found.add("template " + res.resolve("templates").relativize(f));
            }
        }
        assertTrue(found.isEmpty(), "Visible text still names the old project: " + found);
    }

    @Test void accentsAreValidated() {
        assertTrue(UserPrefs.ACCENTS.containsAll(List.of("clinic", "ocean", "ministry", "observatory")));
        UserPrefs d = UserPrefs.defaults("UTC");
        assertEquals("clinic", d.accent());
        assertEquals(d, d.checked());
        assertThrows(IllegalArgumentException.class, () -> d.withAccent("neon").checked());
        assertEquals("ocean", d.withAccent("ocean").checked().accent());
    }
}

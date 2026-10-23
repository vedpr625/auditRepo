package com.akhester.auditui.page;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import org.springframework.context.MessageSource;

import com.akhester.auditui.prefs.UserPrefs;

/**
 * How one person sees times. Times are stored and sent as UTC instants; this class only formats them.
 * The zone is always named in full numeric form (for example "UTC+05:30") so it can never be mistaken for another zone.
 */
public final class TimeFormat {

    private final UserPrefs prefs;
    private final ZoneId zone;
    private final Locale locale;
    private final MessageSource messages;
    private final Instant now;
    private final DateTimeFormatter exactFmt;
    private final DateTimeFormatter shortFmt;
    private final DateTimeFormatter utcFmt;

    public TimeFormat(UserPrefs prefs, Locale locale, MessageSource messages, Instant now) {
        this.prefs = prefs;
        this.zone = prefs.zone();
        this.locale = locale;
        this.messages = messages;
        this.now = now;
        String clock = prefs.clock24() ? "HH:mm:ss" : "h:mm:ss a";
        String clockShort = prefs.clock24() ? "HH:mm" : "h:mm a";
        this.exactFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy, " + clock, locale);
        this.shortFmt = DateTimeFormatter.ofPattern("d MMM, " + clockShort, locale);
        this.utcFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    }

    public boolean isShowExact() { return !"relative".equals(prefs.timeDisplay()); }
    public boolean isShowRelative() { return !"exact".equals(prefs.timeDisplay()); }
    public boolean isShowUtc() { return prefs.showUtc(); }

    /** "UTC+05:30", or "UTC" for zero offset. */
    public String zoneLabel(Instant at) {
        ZoneOffset off = zone.getRules().getOffset(at == null ? now : at);
        return off.getTotalSeconds() == 0 ? "UTC" : "UTC" + off.getId();
    }

    /** Full time with the weekday, for example "Fri 23 Oct 2026, 10:29:41 UTC+05:30". */
    public String exact(Instant at) {
        return at == null ? "" : exactFmt.format(at.atZone(zone)) + " " + zoneLabel(at);
    }

    /** Compact time for tables, for example "23 Oct, 10:29 UTC+05:30". */
    public String brief(Instant at) {
        return at == null ? "" : shortFmt.format(at.atZone(zone)) + " " + zoneLabel(at);
    }

    /** The same instant in UTC, for example "2026-10-23 04:59:41 UTC". */
    public String utc(Instant at) {
        return at == null ? "" : utcFmt.format(ZonedDateTime.ofInstant(at, ZoneOffset.UTC)) + " UTC";
    }

    /** "4 min ago", "27 h ago", "3 days ago". Future times within a minute (clock difference) read as "just now". */
    public String relative(Instant at) {
        if (at == null) return "";
        long s = Duration.between(at, now).getSeconds();
        if (s < 60) return msg("time.now");
        long m = s / 60;
        if (m < 60) return msg("time.minutes", m);
        long h = m / 60;
        if (h < 48) return msg("time.hours", h);
        long d = h / 24;
        if (d < 14) return msg("time.days", d);
        long w = d / 7;
        if (w < 9) return msg("time.weeks", w);
        return msg("time.months", d / 30);
    }

    /** Hour label for charts, for example "09:00". */
    public String hourLabel(Instant at) {
        return DateTimeFormatter.ofPattern(prefs.clock24() ? "HH:mm" : "h a", locale).format(at.atZone(zone));
    }

    private String msg(String key, Object... args) {
        return messages.getMessage(key, args, key, locale);
    }
}

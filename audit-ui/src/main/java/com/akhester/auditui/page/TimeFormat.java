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

    // ── Short forms used by the patient page (added by the access/patient screen; the zone shows as a short name where one is well known) ──

    /** "IST" for Sri Lanka and India (both UTC+05:30), otherwise the numeric label from {@link #zoneLabel}. */
    public String zoneShort(Instant at) {
        ZoneOffset off = zone.getRules().getOffset(at == null ? now : at);
        String id = zone.getId();
        if (off.getTotalSeconds() == 19800 && (id.equals("Asia/Colombo") || id.equals("Asia/Kolkata") || id.equals("Asia/Calcutta"))) return "IST";
        return zoneLabel(at);
    }

    /** "09:41 IST" (or "9:41 am IST" on a 12-hour clock). */
    public String clock(Instant at) {
        return at == null ? "" : DateTimeFormatter.ofPattern(prefs.clock24() ? "HH:mm" : "h:mm a", locale).format(at.atZone(zone)) + " " + zoneShort(at);
    }

    /** "10:21:40 IST" (seconds shown, for logs). */
    public String clockSeconds(Instant at) {
        return at == null ? "" : DateTimeFormatter.ofPattern(prefs.clock24() ? "HH:mm:ss" : "h:mm:ss a", locale).format(at.atZone(zone)) + " " + zoneShort(at);
    }

    /** "22 Oct 06:00 IST". */
    public String stamp(Instant at) {
        return at == null ? "" : DateTimeFormatter.ofPattern("d MMM", locale).format(at.atZone(zone)) + " " + clock(at);
    }

    /** "Thu 22 Oct 2026". */
    public String dayLong(Instant at) {
        return at == null ? "" : DateTimeFormatter.ofPattern("EEE d MMM yyyy", locale).format(at.atZone(zone));
    }

    /** "18 to 20 Oct 2026" for two different days, otherwise the single day with its weekday. */
    public String dayRange(Instant from, Instant to) {
        if (from == null || to == null) return dayLong(from == null ? to : from);
        java.time.LocalDate a = from.atZone(zone).toLocalDate(), b = to.atZone(zone).toLocalDate();
        if (a.equals(b)) return dayLong(to);
        DateTimeFormatter d = DateTimeFormatter.ofPattern("d", locale), dm = DateTimeFormatter.ofPattern("d MMM", locale), full = DateTimeFormatter.ofPattern("d MMM yyyy", locale);
        return (a.getMonth() == b.getMonth() && a.getYear() == b.getYear() ? d.format(a) : dm.format(a)) + " " + msg("patient.range.to") + " " + full.format(b);
    }

    /** "1 day ago", "3 to 5 days ago" or "today", counted in whole days in this person's zone. */
    public String agoRange(Instant from, Instant to) {
        if (from == null || to == null) return relative(from == null ? to : from);
        long a = java.time.temporal.ChronoUnit.DAYS.between(to.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate());
        long b = java.time.temporal.ChronoUnit.DAYS.between(from.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate());
        if (b <= 0) return msg("patient.ago.today");
        if (a >= 14) return relative(to);   // three weeks ago, not 21 days ago
        if (a == b) return a == 1 ? msg("patient.ago.day") : msg("patient.ago.days", a);
        if (a <= 0) return msg("patient.ago.range.today", b);
        return msg("patient.ago.range", a, b);
    }

    private String msg(String key, Object... args) {
        return messages.getMessage(key, args, key, locale);
    }
}

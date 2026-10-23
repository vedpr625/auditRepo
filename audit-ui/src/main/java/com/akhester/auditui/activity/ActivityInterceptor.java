package com.akhester.auditui.activity;

import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Watches what signed-in people do and hands it to {@link ConsoleActivity}: searches, report downloads, review decisions and changes to rules or
 * settings. Patient pages are recorded by the patient page itself (it knows whether the record was restricted). Only requests that succeeded are recorded.
 * Matching is by path, so a screen added later that uses these paths is covered without a change here.
 */
@Component
public class ActivityInterceptor implements HandlerInterceptor {

    private static final Pattern DECIDE = Pattern.compile("^/reviews/(C-\\d{1,10})/decide$");
    private static final Pattern REOPEN = Pattern.compile("^/reviews/(C-\\d{1,10})/reopen$");
    private static final Pattern EVENT_REVIEW = Pattern.compile("^(?:/audit/review/|/api/v1/audit-events/)([A-Za-z0-9.\\-]{1,64})(?:/review)?$");
    private static final Set<String> SKIP_PARAMS = Set.of("page", "size", "sort", "_csrf", "lang", "tab", "offset", "limit", "hx-request");

    private final ConsoleActivity activity;

    public ActivityInterceptor(ConsoleActivity activity) { this.activity = activity; }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
        try {
            if (ex != null || res.getStatus() >= 400 || res.getStatus() >= 300 && res.getStatus() != 302 && res.getStatus() != 303) return;
            String path = req.getRequestURI().substring(req.getContextPath().length());
            String method = req.getMethod();
            if ("GET".equals(method)) get(req, res, path);
            else if ("POST".equals(method)) post(req, path);
        } catch (RuntimeException ignored) { /* watching must never break a page */ }
    }

    private void get(HttpServletRequest req, HttpServletResponse res, String path) {
        switch (path) {
            case "/audit/rows" -> {
                String d = describe(req);
                if (!d.isEmpty()) activity.record(ConsoleActivity.KIND_SEARCH, "Searched events", d, null);
            }
            case "/find" -> {
                String q = req.getParameter("q");
                if (q != null && !q.isBlank()) activity.record(ConsoleActivity.KIND_SEARCH, "Searched: " + clip(q.strip(), 80), null, null);
            }
            case "/reports/download" -> {
                String rows = res.getHeader("X-Report-Rows");
                String r = req.getParameter("r") == null ? "report" : req.getParameter("r");
                activity.record(ConsoleActivity.KIND_REPORT, "Downloaded report: " + clip(r, 40) + (rows == null ? "" : " (" + rows + " rows)"), format(req), null);
            }
            case "/audit/export.csv" -> activity.record(ConsoleActivity.KIND_REPORT, "Downloaded report: events export (csv)", describe(req), null);
            case "/break-glass.csv" -> activity.record(ConsoleActivity.KIND_REPORT, "Downloaded report: break-glass list (csv)", null, null);
            default -> {
                Matcher m = Pattern.compile("^/reviews/(C-\\d{1,10})/evidence\\.pdf$").matcher(path);
                if (m.matches()) activity.record(ConsoleActivity.KIND_REPORT, "Downloaded evidence pack for case " + m.group(1), "PDF", null);
            }
        }
    }

    private void post(HttpServletRequest req, String path) {
        var flash = org.springframework.web.servlet.support.RequestContextUtils.getOutputFlashMap(req);
        if (flash != null && (flash.containsKey("flashBad") || flash.containsKey("flashText"))) return;   // the screen refused the change
        Matcher m = DECIDE.matcher(path);
        if (m.matches()) {
            if ("save".equals(req.getParameter("act"))) return;   // a draft is not a decision
            activity.record(ConsoleActivity.KIND_DECISION, "Decided case " + m.group(1), clip(req.getParameter("decision"), 30), null);
            return;
        }
        m = REOPEN.matcher(path);
        if (m.matches()) { activity.record(ConsoleActivity.KIND_DECISION, "Reopened case " + m.group(1) + " as a new case", null, null); return; }
        m = EVENT_REVIEW.matcher(path);
        if (m.matches()) { activity.record(ConsoleActivity.KIND_DECISION, "Marked event " + m.group(1), clip(req.getParameter("status"), 30), null); return; }
        if (path.matches("^/alerts/firing/[^/]+/normal$")) { activity.record(ConsoleActivity.KIND_DECISION, "Marked an alert firing as normal", null, null); return; }
        if (path.startsWith("/alerts/")) { activity.record(ConsoleActivity.KIND_RULE, "Changed alert rule", clip(path.substring(8), 80), null); return; }
        if (path.startsWith("/settings/") && !path.equals("/settings/prefs")) { activity.record(ConsoleActivity.KIND_RULE, "Changed a console setting", clip(path.substring(10), 60), null); return; }
        if (path.startsWith("/admin/")) activity.record(ConsoleActivity.KIND_RULE, "Changed an admin setting", clip(path.substring(7), 60), null);
    }

    private static String format(HttpServletRequest req) { return req.getParameter("format") == null ? "csv" : clip(req.getParameter("format"), 8); }

    /** The filters of a search, as "name=value" pairs (empty when it had none, so opening the page is not a search). */
    private static String describe(HttpServletRequest req) {
        StringJoiner j = new StringJoiner(", ");
        for (var e : req.getParameterMap().entrySet()) {
            if (SKIP_PARAMS.contains(e.getKey())) continue;
            String v = String.join("|", List.of(e.getValue()));
            if (!v.isBlank() && !v.equals("all")) j.add(clip(e.getKey(), 20) + "=" + clip(v, 40));
        }
        return clip(j.toString(), 300);
    }

    private static String clip(String s, int n) { return s == null ? null : s.length() <= n ? s : s.substring(0, n - 1) + "…"; }
}

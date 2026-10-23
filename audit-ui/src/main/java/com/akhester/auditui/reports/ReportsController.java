package com.akhester.auditui.reports;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.akhester.auditui.people.PeopleIndex;
import com.akhester.auditui.people.Who;
import com.akhester.auditui.prefs.PrefsContext;

import jakarta.servlet.http.HttpServletRequest;

/** Reports: seven ready-made reports with filters, a table on screen, and the same rows as CSV or JSON. Schedules are demo state. */
@Controller
public class ReportsController {

    private static final int SHOWN = 10;

    private final ReportService reports;
    private final ReportSchedules schedules;
    private final PrefsContext prefsContext;
    private final PeopleIndex people;
    @org.springframework.beans.factory.annotation.Value("${audit.ui.demo:false}")
    private boolean demo;
    private String demoPatient = null;

    @jakarta.annotation.PostConstruct
    void init() { demoPatient = demo ? "P-10407" : null; }

    public ReportsController(ReportService reports, ReportSchedules schedules, PrefsContext prefsContext, PeopleIndex people) {
        this.reports = reports; this.schedules = schedules; this.prefsContext = prefsContext; this.people = people;
    }

    @GetMapping("/reports")
    public String page(@RequestParam(name = "r", required = false) String r, @RequestParam(required = false) String patient, @RequestParam(required = false) String person,
                       @RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(required = false) String include,
                       HttpServletRequest request, Model model) {
        ZoneId zone = prefsContext.forRequest(request).zone();
        ReportService.Key key = ReportService.Key.of(r);
        if (key == ReportService.Key.PATIENT && (patient == null || patient.isBlank()) && from == null && to == null) patient = demoPatient;
        boolean reviewer = Who.canReview();
        if (key == ReportService.Key.REVIEWS && !reviewer) key = ReportService.Key.PATIENT;
        model.addAttribute("key", key.id());
        model.addAttribute("canReview", reviewer);
        List<String> keys = new ArrayList<>();
        for (ReportService.Key k : ReportService.Key.values()) if (k != ReportService.Key.REVIEWS || reviewer) keys.add(k.id());
        model.addAttribute("keys", keys);
        model.addAttribute("schedules", schedules.all());
        model.addAttribute("fPatient", patient == null ? "" : patient.strip());
        model.addAttribute("fPerson", person == null ? "" : person.strip());
        model.addAttribute("fInclude", "reads".equals(include) ? "reads" : "all");
        model.addAttribute("needsPatient", key == ReportService.Key.PATIENT);
        model.addAttribute("needsPerson", key == ReportService.Key.PERSON);
        model.addAttribute("hasInclude", key == ReportService.Key.PATIENT || key == ReportService.Key.PERSON);
                model.addAttribute("generated", Instant.now());
        if (key == ReportService.Key.PERSON) model.addAttribute("peopleList", people.snapshot().people().stream().filter(p -> p.ref() != null && "person".equals(p.kind())).limit(60).toList());
        ReportService.Params p;
        try {
            p = reports.parse(key, patient, person, from, to, include, zone);
        } catch (IllegalArgumentException e) {
            model.addAttribute("problem", e.getMessage());
            model.addAttribute("fFrom", from == null ? "" : from);
            model.addAttribute("fTo", to == null ? "" : to);
            return "reports";
        }
        model.addAttribute("fFrom", p.from().toString());
        model.addAttribute("fTo", p.to().toString());
        ReportService.Result res = reports.run(p, zone, request.getLocale());
        model.addAttribute("res", res);
        model.addAttribute("shown", res.rows().subList(0, Math.min(SHOWN, res.rows().size())));
        long max = res.bars().stream().mapToLong(b -> b.ok() + b.failed()).max().orElse(1);
        model.addAttribute("barMax", Math.max(1, max));
        model.addAttribute("headings", reports.headings(res, request.getLocale()));
        model.addAttribute("csvHref", "/reports/download?" + query(p) + "&format=csv");
        model.addAttribute("pdfHref", "/reports/download?" + query(p) + "&format=pdf");
        model.addAttribute("jsonHref", "/reports/download?" + query(p) + "&format=json");
        return "reports";
    }

    private static String query(ReportService.Params p) {
        StringBuilder b = new StringBuilder("r=").append(p.key().id());
        if (p.patient() != null) b.append("&patient=").append(enc(p.patient()));
        if (p.person() != null) b.append("&person=").append(enc(p.person()));
        b.append("&from=").append(p.from()).append("&to=").append(p.to()).append("&include=").append(p.include());
        return b.toString();
    }

    private byte[] pdf(ReportService.Result res, ReportService.Params p, ZoneId zone, String user, Instant now, Locale locale) {
        java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx");
        List<String> headers = new ArrayList<>();
        if (res.hasWhen()) headers.add(reports.text("reports.col.when", locale));
        headers.addAll(reports.headings(res, locale));
        List<List<String>> rows = new ArrayList<>();
        for (ReportService.Row row : res.rows()) {
            List<String> c = new ArrayList<>();
            if (res.hasWhen()) c.add(row.when() == null ? "" : f.format(row.when().atZone(zone)));
            c.addAll(row.cells());
            rows.add(c);
        }
        List<String> meta = new ArrayList<>();
        meta.add(reports.text("reports.r." + p.key().id() + ".title", locale) + " - " + p.from() + " to " + p.to() + (p.patient() != null ? " - patient " + p.patient() : "") + (p.person() != null ? " - " + p.person() : ""));
        StringBuilder st = new StringBuilder();
        for (ReportService.Stat s : res.stats()) st.append(s.value()).append(' ').append(reports.text("reports.stat." + s.label(), locale)).append("   ");
        if (st.length() > 0) meta.add(st.toString().strip());
        meta.add("Rows in this file: " + res.rows().size() + " of " + res.total() + (res.truncated() ? " (capped; the CSV carries the same cap)" : "") + ". Times in " + zone.getId() + ".");
        String footer = "Generated " + f.format(now.atZone(zone)) + " by " + user + ". Each report download is recorded in the console log.";
        return ReportPdf.table(reports.text("reports.r." + p.key().id() + ".title", locale), meta.subList(1, meta.size()), headers, rows, footer);
    }

    private static String enc(String s) { return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8); }

    @GetMapping("/reports/download")
    public ResponseEntity<byte[]> download(@RequestParam(name = "r", required = false) String r, @RequestParam(required = false) String patient, @RequestParam(required = false) String person,
                                           @RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(required = false) String include,
                                           @RequestParam(defaultValue = "csv") String format, HttpServletRequest request) {
        ZoneId zone = prefsContext.forRequest(request).zone();
        Locale locale = request.getLocale();
        ReportService.Params p;
        try {
            p = reports.parse(ReportService.Key.of(r), patient, person, from, to, include, zone);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().header("X-Report-Error", e.getMessage()).build();
        }
        boolean json = "json".equals(format);
        boolean pdf = "pdf".equals(format);
        if (!json && !pdf && !"csv".equals(format)) return ResponseEntity.badRequest().build();
        ReportService.Result res = reports.run(p, zone, locale);
        if (res.needsInput()) return ResponseEntity.badRequest().header("X-Report-Error", "input").build();
        if ("forbidden".equals(res.error())) return ResponseEntity.status(403).build();
        if (res.error() != null) return ResponseEntity.status(502).header("X-Report-Error", res.error()).build();
        Instant now = Instant.now();
        String user = PrefsContext.username();
        byte[] body = pdf ? pdf(res, p, zone, user, now, locale) : json ? ReportFile.json(res, p, zone, user, now)
            : ReportFile.csv(res, reports.headings(res, locale), zone, reports.text("reports.col.when", locale), reports.text("reports.col.whenutc", locale));
        try { schedules.logDownload(user, p.key().id(), format, query(p), res.rows().size()); } catch (RuntimeException ignored) { /* the file is still the answer; a log failure must not hide it */ }
        String name = "report-" + p.key().id() + "-" + java.time.LocalDate.ofInstant(now, zone) + (pdf ? ".pdf" : json ? ".json" : ".csv");
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
            .header("X-Report-Rows", String.valueOf(res.rows().size()))
            .header("X-Report-Total", String.valueOf(res.total()))
            .header("X-Report-Truncated", String.valueOf(res.truncated()))
            .header("Cache-Control", "no-store")
            .contentType(pdf ? MediaType.APPLICATION_PDF : json ? MediaType.APPLICATION_JSON : MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .body(body);
    }
}

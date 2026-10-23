package com.akhester.auditui.reports;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/** Writes a report as CSV or JSON. The local time and the UTC time are separate columns, so the file reads the same wherever it is opened. */
public final class ReportFile {

    private ReportFile() {}

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx");
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** One CSV cell: quoted, and made safe against spreadsheet formulas (a leading = + - @ tab or CR gets a quote mark in front). */
    public static String cell(String v) {
        if (v == null) return "\"\"";
        String x = v;
        if (!x.isEmpty() && "=+-@\t\r".indexOf(x.charAt(0)) >= 0) x = "'" + x;
        return "\"" + x.replace("\"", "\"\"") + "\"";
    }

    public static byte[] csv(ReportService.Result r, List<String> headers, ZoneId zone, String timeHeaderLocal, String timeHeaderUtc) {
        StringBuilder sb = new StringBuilder("﻿");
        List<String> h = new ArrayList<>();
        if (r.hasWhen()) { h.add(timeHeaderLocal + " (" + zone.getId() + ")"); h.add(timeHeaderUtc); }
        h.addAll(headers);
        sb.append(String.join(",", h.stream().map(ReportFile::cell).toList())).append("\r\n");
        for (ReportService.Row row : r.rows()) {
            List<String> c = new ArrayList<>();
            if (r.hasWhen()) {
                c.add(row.when() == null ? "" : LOCAL.format(row.when().atZone(zone)));
                c.add(row.when() == null ? "" : row.when().toString());
            }
            c.addAll(row.cells());
            sb.append(String.join(",", c.stream().map(ReportFile::cell).toList())).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] json(ReportService.Result r, ReportService.Params p, ZoneId zone, String user, Instant now) {
        Map<String, Object> top = new LinkedHashMap<>();
        top.put("report", r.key().id());
        top.put("generatedAtUtc", now.toString());
        top.put("generatedBy", user);
        Map<String, Object> f = new LinkedHashMap<>();
        if (p.patient() != null) f.put("patient", p.patient());
        if (p.person() != null) f.put("person", p.person());
        if (p.from() != null) f.put("from", p.from().toString());
        if (p.to() != null) f.put("to", p.to().toString());
        f.put("include", p.include());
        top.put("filters", f);
        top.put("timeZone", zone.getId());
        top.put("totalMatching", r.total());
        top.put("rowsInFile", r.rows().size());
        top.put("truncated", r.truncated());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ReportService.Row row : r.rows()) {
            Map<String, Object> o = new LinkedHashMap<>();
            if (r.hasWhen()) { o.put("whenLocal", row.when() == null ? null : LOCAL.format(row.when().atZone(zone))); o.put("whenUtc", row.when() == null ? null : row.when().toString()); }
            for (int i = 0; i < r.columns().size(); i++) o.put(r.columns().get(i), i < row.cells().size() ? row.cells().get(i) : null);
            rows.add(o);
        }
        top.put("rows", rows);
        try { return JSON.writeValueAsBytes(top); } catch (Exception e) { throw new IllegalStateException(e); }
    }
}

package com.akhester.auditui.reports;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A small, dependency-free PDF writer for report tables (no PDF library is available offline).
 * Landscape A4, built-in Helvetica (Latin-1 text only: other characters print as "?"), a repeated header row and page numbers.
 * Long cells are cut with "..." because Helvetica widths are estimated, not measured. The CSV is the complete record.
 */
public final class ReportPdf {

    private static final float W = 842, H = 595, M = 36;
    private static final float ROW = 15, HEAD = 18, FS = 8;

    private ReportPdf() {}

    /** A table report. {@code rows} are lists of cell text with the same length as {@code headers}. */
    public static byte[] table(String title, List<String> meta, List<String> headers, List<List<String>> rows, String footer) {
        int cols = Math.max(1, headers.size());
        float[] w = widths(headers, rows, W - 2 * M);
        List<StringBuilder> pages = new ArrayList<>();
        StringBuilder pg = null;
        float y = 0;
        int rowIdx = 0;
        do {
            if (pg == null || y < M + ROW + 14) {
                pg = new StringBuilder();
                pages.add(pg);
                y = H - M;
                if (pages.size() == 1) {
                    text(pg, "F2", 16, M, y - 14, title);
                    y -= 30;
                    for (String m : meta) { text(pg, "F1", 9, M, y - 9, m); y -= 13; }
                    y -= 6;
                }
                y = headRow(pg, headers, w, y);
            }
            if (rowIdx < rows.size()) {
                List<String> r = rows.get(rowIdx);
                if (rowIdx % 2 == 1) { pg.append("0.95 0.96 0.95 rg ").append(M).append(' ').append(y - ROW + 3).append(' ').append(W - 2 * M).append(' ').append(ROW).append(" re f\n"); }
                float x = M;
                for (int c = 0; c < cols; c++) {
                    text(pg, "F1", FS, x + 3, y - 8, fit(c < r.size() ? r.get(c) : "", w[c] - 6, FS));
                    x += w[c];
                }
                y -= ROW;
                rowIdx++;
            }
        } while (rowIdx < rows.size());
        if (rows.isEmpty()) text(pg, "F1", 9, M, y - 10, "No rows.");
        int n = pages.size();
        List<String> content = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            StringBuilder p = pages.get(i);
            text(p, "F1", 7.5f, M, 20, footer == null ? "" : fit(footer, W - 2 * M - 80, 7.5f));
            text(p, "F1", 7.5f, W - M - 50, 20, "Page " + (i + 1) + " of " + n);
            content.add(p.toString());
        }
        return assemble(content);
    }

    private static float headRow(StringBuilder pg, List<String> headers, float[] w, float y) {
        pg.append("0.04 0.43 0.43 rg ").append(M).append(' ').append(y - HEAD + 4).append(' ').append(W - 2 * M).append(' ').append(HEAD).append(" re f\n");
        float x = M;
        for (int c = 0; c < headers.size(); c++) {
            text(pg, "F2", FS, x + 3, y - 9, "1 1 1 rg", fit(headers.get(c).toUpperCase(), w[c] - 6, FS));
            x += w[c];
        }
        return y - HEAD;
    }

    private static float[] widths(List<String> headers, List<List<String>> rows, float total) {
        int cols = Math.max(1, headers.size());
        float[] want = new float[cols];
        for (int c = 0; c < cols; c++) {
            int max = c < headers.size() ? (int) Math.ceil(headers.get(c).length() * 1.25) : 4;
            for (int r = 0; r < Math.min(rows.size(), 200); r++) { List<String> row = rows.get(r); if (c < row.size() && row.get(c) != null) max = Math.max(max, Math.min(row.get(c).length(), 48)); }
            want[c] = Math.max(6, max);
        }
        float sum = 0; for (float f : want) sum += f;
        float[] w = new float[cols];
        for (int c = 0; c < cols; c++) w[c] = total * want[c] / sum;
        return w;
    }

    private static String fit(String s, float width, float size) {
        String t = latin(s);
        int max = (int) (width / (size * 0.52f));
        if (t.length() <= max) return t;
        return max <= 3 ? t.substring(0, Math.max(0, max)) : t.substring(0, max - 3) + "...";
    }

    private static String latin(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (ch == '’' || ch == '‘') b.append('\'');
            else if (ch == '“' || ch == '”') b.append('"');
            else if (ch == '–' || ch == '—') b.append('-');
            else if (ch == '·') b.append((char) 0xB7);
            else if (ch < 32) b.append(' ');
            else if (ch > 255) b.append('?');
            else b.append(ch);
        }
        return b.toString();
    }

    private static void text(StringBuilder sb, String font, float size, float x, float y, String s) { text(sb, font, size, x, y, "0.11 0.16 0.18 rg", s); }

    private static void text(StringBuilder sb, String font, float size, float x, float y, String color, String s) {
        sb.append("BT ").append(color).append(" /").append(font).append(' ').append(size).append(" Tf ").append(x).append(' ').append(y).append(" Td (");
        for (char ch : latin(s).toCharArray()) {
            if (ch == '(' || ch == ')' || ch == '\\') sb.append('\\').append(ch);
            else if (ch > 126) sb.append(String.format("\\%03o", (int) ch));
            else sb.append(ch);
        }
        sb.append(") Tj ET\n");
    }

    private static byte[] assemble(List<String> pages) {
        int n = pages.size();
        // objects: 1 catalog, 2 pages, 3 font regular, 4 font bold, then per page: page, content
        List<byte[]> objs = new ArrayList<>();
        objs.add("<< /Type /Catalog /Pages 2 0 R >>".getBytes(StandardCharsets.ISO_8859_1));
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) kids.append(5 + 2 * i).append(" 0 R ");
        objs.add(("<< /Type /Pages /Kids [" + kids + "] /Count " + n + " >>").getBytes(StandardCharsets.ISO_8859_1));
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>".getBytes(StandardCharsets.ISO_8859_1));
        objs.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>".getBytes(StandardCharsets.ISO_8859_1));
        for (int i = 0; i < n; i++) {
            objs.add(("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + (int) W + " " + (int) H + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + (6 + 2 * i) + " 0 R >>").getBytes(StandardCharsets.ISO_8859_1));
            byte[] c = pages.get(i).getBytes(StandardCharsets.ISO_8859_1);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.writeBytes(("<< /Length " + c.length + " >>\nstream\n").getBytes(StandardCharsets.ISO_8859_1));
            o.writeBytes(c);
            o.writeBytes("\nendstream".getBytes(StandardCharsets.ISO_8859_1));
            objs.add(o.toByteArray());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("%PDF-1.4\n".getBytes(StandardCharsets.ISO_8859_1));
        int[] off = new int[objs.size()];
        for (int i = 0; i < objs.size(); i++) {
            off[i] = out.size();
            out.writeBytes(((i + 1) + " 0 obj\n").getBytes(StandardCharsets.ISO_8859_1));
            out.writeBytes(objs.get(i));
            out.writeBytes("\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        int xref = out.size();
        StringBuilder x = new StringBuilder("xref\n0 " + (objs.size() + 1) + "\n0000000000 65535 f \n");
        for (int o : off) x.append(String.format("%010d 00000 n \n", o));
        x.append("trailer\n<< /Size ").append(objs.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        out.writeBytes(x.toString().getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }
}

package com.akhester.auditui.reviews;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A very small PDF writer (text, lines and filled boxes, A4, the three standard fonts), so a real PDF can be made without a library.
 * Text is written in WinAnsi: characters outside it become "?". Not a general tool: it only does what the evidence pack needs.
 */
final class MiniPdf {

    static final float W = 595, H = 842, M = 48;
    static final int HELV = 0, BOLD = 1, MONO = 2;

    private static final int[] HW = {278,278,355,556,556,889,667,191,333,333,389,584,278,333,278,278,556,556,556,556,556,556,556,556,556,556,278,278,584,584,584,556,
        1015,667,667,722,722,667,611,778,722,278,500,667,556,833,722,778,667,778,722,667,611,722,667,944,667,667,611,278,278,278,469,556,
        333,556,556,500,556,556,278,556,556,222,222,500,222,833,556,556,556,556,333,500,278,556,500,722,500,500,500,334,260,334,584};

    private final List<StringBuilder> pages = new ArrayList<>();
    private StringBuilder cur;
    private float y;
    private final String footer;

    MiniPdf(String footer) { this.footer = footer; newPage(); }

    // ── measuring ──
    static float width(String s, int font, float size) {
        float w = 0;
        for (char c : s.toCharArray()) {
            if (font == MONO) w += 600;
            else {
                int i = c - 32;
                int cw = i >= 0 && i < HW.length ? HW[i] : 556;
                w += font == BOLD ? cw * 1.07f : cw;
            }
        }
        return w * size / 1000f;
    }

    static List<String> wrap(String text, int font, float size, float maxW) {
        List<String> out = new ArrayList<>();
        for (String para : (text == null ? "" : text).split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                while (width(word, font, size) > maxW && word.length() > 1) {   // a word wider than the line (a hash, an id) is cut
                    int n = word.length();
                    while (n > 1 && width(word.substring(0, n), font, size) > maxW) n--;
                    if (line.length() > 0) { out.add(line.toString()); line.setLength(0); }
                    out.add(word.substring(0, n)); word = word.substring(n);
                }
                String t = line.length() == 0 ? word : line + " " + word;
                if (line.length() > 0 && width(t, font, size) > maxW) { out.add(line.toString()); line.setLength(0); line.append(word); }
                else { line.setLength(0); line.append(t); }
            }
            out.add(line.toString());
        }
        return out;
    }

    // ── page handling ──
    void newPage() { cur = new StringBuilder(); pages.add(cur); y = H - M; }
    float y() { return y; }
    void gap(float h) { y -= h; }
    void ensure(float h) { if (y - h < M + 24) newPage(); }

    // ── drawing ──
    void rect(float x, float yy, float w, float h, float r, float g, float b) {
        cur.append(String.format(java.util.Locale.ROOT, "%.3f %.3f %.3f rg %.2f %.2f %.2f %.2f re f\n", r, g, b, x, yy, w, h));
    }

    void line(float x1, float y1, float x2, float y2, float r, float g, float b) {
        cur.append(String.format(java.util.Locale.ROOT, "%.3f %.3f %.3f RG 0.6 w %.2f %.2f m %.2f %.2f l S\n", r, g, b, x1, y1, x2, y2));
    }

    /** One line of text at an exact position. */
    void textAt(float x, float yy, String s, int font, float size, float r, float g, float b) {
        cur.append(String.format(java.util.Locale.ROOT, "BT /F%d %.1f Tf %.3f %.3f %.3f rg %.2f %.2f Td (%s) Tj ET\n", font + 1, size, r, g, b, x, yy, esc(s)));
    }

    /** Wrapped paragraph at the left margin (or x), moving down. Returns lines used. */
    int para(String text, int font, float size, float x, float maxW, float[] rgb) {
        float lead = size * 1.35f;
        List<String> ls = wrap(text, font, size, maxW);
        for (String l : ls) { ensure(lead); y -= lead; textAt(x, y, l, font, size, rgb[0], rgb[1], rgb[2]); }
        return ls.size();
    }

    int para(String text, int font, float size) { return para(text, font, size, M, W - 2 * M, INK); }

    void heading(String s) {
        ensure(40); gap(14);
        y -= 13; textAt(M, y, s.toUpperCase(java.util.Locale.ROOT), BOLD, 9.5f, TEAL[0], TEAL[1], TEAL[2]);
        gap(4); line(M, y, W - M, y, 0.85f, 0.86f, 0.82f); gap(2);
    }

    /** A table: header row then rows; col widths in points; cells wrap. */
    void table(String[] head, float[] cw, List<String[]> rows, int fontSize) {
        float size = fontSize, lead = size * 1.3f;
        ensure(26);
        rect(M, y - 17, W - 2 * M, 17, 0.93f, 0.95f, 0.93f);
        float x = M + 4; y -= 12;
        for (int i = 0; i < head.length; i++) { textAt(x, y, head[i].toUpperCase(java.util.Locale.ROOT), BOLD, 7.5f, 0.3f, 0.36f, 0.38f); x += cw[i]; }
        y -= 5;
        for (String[] row : rows) {
            List<List<String>> cells = new ArrayList<>();
            int maxL = 1;
            for (int i = 0; i < row.length; i++) { List<String> w = wrap(row[i], HELV, size, cw[i] - 8); cells.add(w); maxL = Math.max(maxL, w.size()); }
            ensure(maxL * lead + 8);
            float top = y;
            x = M + 4;
            for (int i = 0; i < row.length; i++) {
                float yy = top;
                for (String l : cells.get(i)) { yy -= lead; textAt(x, yy, l, HELV, size, INK[0], INK[1], INK[2]); }
                x += cw[i];
            }
            y = top - maxL * lead - 4;
            line(M, y, W - M, y, 0.9f, 0.91f, 0.88f);
            y -= 3;
        }
    }

    static final float[] INK = {0.11f, 0.16f, 0.18f}, TEAL = {0.043f, 0.43f, 0.43f}, GREY = {0.36f, 0.42f, 0.44f};

    // ── output ──
    byte[] finish(String title) {
        int n = pages.size();
        for (int i = 0; i < n; i++) {
            StringBuilder p = pages.get(i);
            p.append(String.format(java.util.Locale.ROOT, "0.85 0.86 0.82 RG 0.6 w %.2f 40 m %.2f 40 l S\n", M, W - M));
            p.append(String.format(java.util.Locale.ROOT, "BT /F1 8 Tf 0.36 0.42 0.44 rg %.2f 28 Td (%s) Tj ET\n", M, esc(footer)));
            String pg = "Page " + (i + 1) + " of " + n;
            p.append(String.format(java.util.Locale.ROOT, "BT /F1 8 Tf 0.36 0.42 0.44 rg %.2f 28 Td (%s) Tj ET\n", W - M - width(pg, HELV, 8), pg));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offs = new ArrayList<>();
        write(out, "%PDF-1.4\n%âãÏÓ\n");
        int total = 5 + 2 * n;
        // 1 catalog, 2 pages, 3-5 fonts, then page/content pairs, then info
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < n; i++) kids.append(6 + 2 * i).append(" 0 R ");
        obj(out, offs, "<< /Type /Catalog /Pages 2 0 R >>");
        obj(out, offs, "<< /Type /Pages /Count " + n + " /Kids [" + kids + "] >>");
        obj(out, offs, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        obj(out, offs, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
        obj(out, offs, "<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>");
        for (int i = 0; i < n; i++) {
            obj(out, offs, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + (int) W + " " + (int) H + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R >> >> /Contents " + (7 + 2 * i) + " 0 R >>");
            byte[] c = pages.get(i).toString().getBytes(StandardCharsets.ISO_8859_1);
            offs.add(out.size());
            write(out, (7 + 2 * i) + " 0 obj\n<< /Length " + c.length + " >>\nstream\n");
            out.writeBytes(c);
            write(out, "\nendstream\nendobj\n");
        }
        obj(out, offs, "<< /Title (" + esc(title) + ") /Producer (Audit Console) >>");
        int xref = out.size();
        write(out, "xref\n0 " + (total + 2) + "\n0000000000 65535 f \n");
        for (int o : offs) write(out, String.format("%010d 00000 n \n", o));
        write(out, "trailer\n<< /Size " + (total + 2) + " /Root 1 0 R /Info " + (total + 1) + " 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream o, String s) { o.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1)); }

    private int objNo = 0;
    private void obj(ByteArrayOutputStream out, List<Integer> offs, String body) {
        offs.add(out.size());
        objNo = offs.size();
        write(out, objNo + " 0 obj\n" + body + "\nendobj\n");
    }

    /** Escapes for a PDF string and maps to WinAnsi (the output is read back as ISO-8859-1 bytes). */
    static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s == null ? new char[0] : s.toCharArray()) {
            char m = switch (c) {
                case '’' -> '\u0092'; case '‘' -> '\u0091'; case '“' -> '\u0093'; case '”' -> '\u0094';
                case '–' -> '\u0096'; case '—' -> '\u0097'; case '…' -> '\u0085'; case '•' -> '\u0095';
                case '\n', '\r', '\t' -> ' ';
                default -> c;
            };
            if (m == '(' || m == ')' || m == '\\') b.append('\\').append(m);
            else if ((m >= 32 && m < 127) || (m >= 0xA0 && m <= 0xFF) || (m >= 0x80 && m <= 0x9F)) b.append(m);
            else b.append('?');
        }
        return b.toString();
    }
}

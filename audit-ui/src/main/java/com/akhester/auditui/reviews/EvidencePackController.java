package com.akhester.auditui.reviews;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.page.TimeFormat;
import com.akhester.auditui.prefs.PrefsContext;
import com.akhester.auditui.reviews.CaseRepository.Case;
import com.akhester.auditui.reviews.CaseRepository.Decision;
import com.akhester.auditui.reviews.CaseRepository.LogEntry;
import com.akhester.auditui.service.AuditEventService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * "Download evidence pack (PDF)" for a closed case: the case, its fingerprint (and whether it still matches), the decisions, the timeline and the
 * events that were attached (copied, not linked). Only closed cases have a pack; the same people who can read cases can download it.
 */
@Controller
public class EvidencePackController {

    private final CaseRepository cases;
    private final CaseService service;
    private final AuditEventService audit;
    private final MessageSource messages;
    private final PrefsContext prefsContext;

    public EvidencePackController(CaseRepository cases, CaseService service, AuditEventService audit, MessageSource messages, PrefsContext prefsContext) {
        this.cases = cases; this.service = service; this.audit = audit; this.messages = messages; this.prefsContext = prefsContext;
    }

    @GetMapping("/reviews/{id}/evidence.pdf")
    public ResponseEntity<byte[]> pack(@PathVariable String id, HttpServletRequest request) {
        if (!id.matches("C-\\d{1,10}")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        if (!ReviewAccess.canViewAll()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only ministry-level roles can read cases.");
        Case c = cases.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!c.isClosed()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a closed case has an evidence pack.");
        Locale en = Locale.ENGLISH;
        Instant now = Instant.now();
        TimeFormat tf = new TimeFormat(prefsContext.forRequest(request), en, messages, now);
        String me = String.valueOf(ReviewAccess.user());

        List<String> ids = cases.eventIds(id);
        List<AuditRow> rows = new ArrayList<>();
        boolean partial = false;
        try {
            for (int i = 0; i < ids.size() && i < 500; i += 100) {
                Map<String, List<String>> p = new LinkedHashMap<>();
                p.put("_id", List.of(String.join(",", ids.subList(i, Math.min(ids.size(), i + 100)))));
                rows.addAll(audit.recentWhere(p, 100, "date"));
            }
        } catch (RuntimeException e) { partial = true; }
        if (rows.size() < ids.size()) partial = true;
        rows.sort(java.util.Comparator.comparing(AuditRow::getRecordedInstant, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));

        String fp = c.fingerprint() == null ? "" : c.fingerprint();
        boolean sealed = service.fingerprintMatches(c);
        MiniPdf pdf = new MiniPdf("Audit Console · evidence pack · case " + c.id() + " · fingerprint " + (fp.length() >= 16 ? fp.substring(0, 16) : fp));
        float[] white = {1, 1, 1};

        // header band
        pdf.rect(0, MiniPdf.H - 74, MiniPdf.W, 74, MiniPdf.TEAL[0], MiniPdf.TEAL[1], MiniPdf.TEAL[2]);
        pdf.textAt(MiniPdf.M, MiniPdf.H - 36, "Evidence pack", MiniPdf.BOLD, 20, 1, 1, 1);
        pdf.textAt(MiniPdf.M, MiniPdf.H - 56, "Case " + c.id() + " · " + t(c.title()), MiniPdf.HELV, 11, 1, 1, 1);
        pdf.gap(60);

        pdf.heading("Case");
        pdf.para(t(c.title()), MiniPdf.BOLD, 14);
        List<String[]> kv = new ArrayList<>();
        kv.add(new String[]{"Kind", msg("reviews.kind." + c.kind(), en)});
        if (c.patientRef() != null) kv.add(new String[]{"Patient", c.patientRef().replace("Patient/", "")});
        if (c.subject() != null && !c.subject().isBlank()) kv.add(new String[]{"Subject", c.subject()});
        String fac = Labels.facility(c.facility());
        if (!fac.isEmpty()) kv.add(new String[]{"Facility", fac});
        kv.add(new String[]{"Opened", tf.exact(c.openedAt()) + " by " + c.openedBy()});
        kv.add(new String[]{"Closed", tf.exact(c.closedAt())});
        kv.add(new String[]{"Result", msg("reviews.outcome." + c.outcome(), en)});
        for (String[] r : kv) { pdf.ensure(16); float yy = pdf.y() - 14; pdf.textAt(MiniPdf.M, yy, r[0], MiniPdf.HELV, 9, MiniPdf.GREY[0], MiniPdf.GREY[1], MiniPdf.GREY[2]);
            pdf.gap(0); pdf.para(r[1], "Result".equals(r[0]) ? MiniPdf.BOLD : MiniPdf.HELV, 10.5f, MiniPdf.M + 80, MiniPdf.W - 2 * MiniPdf.M - 80, MiniPdf.INK); pdf.gap(1); }

        pdf.heading("Fingerprint (SHA-256, sealed when the case closed)");
        if (fp.length() == 64) { pdf.para(fp.substring(0, 32), MiniPdf.MONO, 10.5f); pdf.para(fp.substring(32), MiniPdf.MONO, 10.5f); }
        else pdf.para("(none)", MiniPdf.MONO, 10);
        pdf.gap(2);
        pdf.para(sealed ? "Checked just now against the stored case, decisions and attached events: the fingerprint MATCHES. Nothing has changed since closing."
            : "Checked just now against the stored case, decisions and attached events: the fingerprint DOES NOT MATCH. Something changed after closing.", MiniPdf.BOLD, 9.5f,
            MiniPdf.M, MiniPdf.W - 2 * MiniPdf.M, sealed ? new float[]{0.11f, 0.37f, 0.2f} : new float[]{0.61f, 0.13f, 0.15f});

        pdf.heading("Decisions");
        List<String[]> dr = new ArrayList<>();
        for (Decision d : cases.decisions(id)) dr.add(new String[]{d.reviewer(), msg("reviews.role." + d.role(), en), msg("reviews.decision." + d.decision(), en), d.note() == null ? "" : d.note(), tf.stamp(d.decidedAt())});
        if (dr.isEmpty()) pdf.para("No decisions are stored for this case.", MiniPdf.HELV, 10);
        else pdf.table(new String[]{"Reviewer", "Role", "Decision", "Note", "When"}, new float[]{80, 78, 78, 175, 88}, dr, 9);

        pdf.heading("Timeline");
        List<String[]> tl = new ArrayList<>();
        for (LogEntry l : cases.log(id)) tl.add(new String[]{tf.exact(l.at()), logText(l, en)});
        pdf.table(new String[]{"When", "What happened"}, new float[]{185, 314}, tl, 9);

        pdf.heading("Attached events (" + ids.size() + ", copied, not linked)");
        List<String[]> er = new ArrayList<>();
        for (AuditRow r : rows) er.add(new String[]{r.getId(), tf.exact(r.getRecordedInstant()), String.valueOf(r.getAgentName()), Labels.facility(r.getFacility()),
            String.valueOf(r.getPatientRef()).replace("Patient/", ""), ("0".equals(r.getOutcomeCode()) ? "OK" : "Refused") + (r.isBreakGlass() ? ", break-glass" : "")});
        if (er.isEmpty()) pdf.para("No attached events could be read with your access.", MiniPdf.HELV, 10);
        else pdf.table(new String[]{"Event", "When", "Who", "Facility", "Patient", "Outcome"}, new float[]{38, 170, 88, 72, 50, 81}, er, 8);
        if (partial) pdf.para("Some attached events are not listed: they are outside your access, or Chester did not answer.", MiniPdf.HELV, 9, MiniPdf.M, MiniPdf.W - 2 * MiniPdf.M, MiniPdf.GREY);

        pdf.gap(12);
        pdf.para("Locked. After closing, nothing in this case can be edited. A reopened case becomes a new case that points to this one. Generated by " + me + " on " + tf.exact(now) + ".",
            MiniPdf.HELV, 9, MiniPdf.M, MiniPdf.W - 2 * MiniPdf.M, MiniPdf.GREY);

        byte[] bytes = pdf.finish("Evidence pack " + c.id());
        return ResponseEntity.ok().header("Content-Disposition", "attachment; filename=\"evidence-pack-" + c.id() + ".pdf\"")
            .header("Cache-Control", "no-store").contentType(org.springframework.http.MediaType.APPLICATION_PDF).body(bytes);
    }

    private String logText(LogEntry l, Locale en) {
        String a = l.arg() == null ? "" : l.arg();
        String arg = switch (l.code()) {
            case "decided" -> orRaw("reviews.decision." + a, a, en);
            case "closed" -> orRaw("reviews.outcome." + a, a, en);
            case "escalated" -> orRaw("reviews.why." + a, a, en);
            default -> a;
        };
        return messages.getMessage("reviews.log." + l.code(), new Object[]{l.actor(), arg}, l.code() + " " + a, en);
    }

    private String orRaw(String key, String raw, Locale en) { return messages.getMessage(key, null, raw, en); }
    private String msg(String key, Locale en) { return messages.getMessage(key, null, key.substring(key.lastIndexOf('.') + 1), en); }
    private static String t(String s) { return s == null ? "" : s; }
}

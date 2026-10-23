package com.akhester.auditui.patient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;

import com.akhester.auditui.admin.SendingSystemRepository;
import com.akhester.auditui.consent.ConsentClient;
import com.akhester.auditui.people.FacilityDirectory;
import com.akhester.auditui.people.Who;
import com.akhester.auditui.consent.ConsentLogRepository;
import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.page.ShellAdvice;
import com.akhester.auditui.prefs.PrefsContext;
import com.akhester.auditui.registry.RegistryClient;
import com.akhester.auditui.service.AuditEventService;

/**
 * One patient. Order of work, and why:
 * 1. Chester is asked for this patient's records first (it applies the person's own access). No visible records: the page says "nothing found",
 *    the same words whether the patient exists or not, so ids cannot be probed.
 * 2. The consent service is asked. Only a clear "permit" lets the registry be called at all; the answer is written to consent_check_log.
 * 3. Only then are the patient's details and stays read from the registry, and used to judge each record's care link.
 */
@Controller
public class PatientController {

    public static final String PURPOSE = "audit-review";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    /** link: ok (patient was at that facility), none (flag), unknown (not checked), na (rule does not apply). */
    public record Line(AuditRow row, String link) {
        /** Role words from the reference and name that were sent (a reading, not a registry fact). */
        public String role() { return Who.role(row.getAgentRef(), row.getAgentName()); }
        /** Minutes from the break-glass access to the reason, or null when there is no reason time. */
        public Long reasonMinutes() {
            return row.getRecordedInstant() == null || row.getReasonGivenAt() == null ? null : java.time.Duration.between(row.getRecordedInstant(), row.getReasonGivenAt()).toMinutes();
        }
    }

    private final AuditEventService audit;
    private final ConsentClient consent;
    private final ConsentLogRepository consentLog;
    private final RegistryClient registry;
    private final com.akhester.auditui.prefs.PrefsContext prefsContext;
    private final FacilityDirectory facilities;
    private final SendingSystemRepository systems;

    public PatientController(AuditEventService audit, ConsentClient consent, ConsentLogRepository consentLog, RegistryClient registry,
                             com.akhester.auditui.prefs.PrefsContext prefsContext, FacilityDirectory facilities, SendingSystemRepository systems) {
        this.audit = audit; this.consent = consent; this.consentLog = consentLog; this.registry = registry;
        this.prefsContext = prefsContext; this.facilities = facilities; this.systems = systems;
    }

    @GetMapping("/patient")
    public String open(@RequestParam(required = false) String id) {
        String t = id == null ? "" : id.strip().replaceFirst("^Patient/", "");
        return ID.matcher(t).matches() ? "redirect:/patient/" + t : "redirect:/overview?badPatient";
    }

    /** Most records one patient page reads (newest first); the page says so when there are more. */
    static final int LIMIT = 500;

    @GetMapping("/patient/{id}")
    public String page(@PathVariable String id, @RequestParam(required = false) String days, jakarta.servlet.http.HttpServletRequest request, Model model) {
        if (!ID.matcher(id).matches()) { model.addAttribute("state", "notfound"); return "patient"; }
        String ref = "Patient/" + id;
        model.addAttribute("patientId", id);
        String window = "90".equals(days) ? "90" : "all".equals(days) ? "all" : "30";
        model.addAttribute("window", window);
        java.time.ZoneId zone = prefsContext.forRequest(request).zone();
        List<AuditRow> rows;
        int total;
        try {
            Map<String, List<String>> p = new LinkedHashMap<>();
            p.put("entity-ref", List.of(ref));
            if (!"all".equals(window)) p.put("date", List.of("ge" + java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(Long.parseLong(window))));
            total = audit.countWhere(p);
            rows = new ArrayList<>();
            int offset = 0;
            while (offset < LIMIT) {
                List<AuditRow> page = audit.pageWhere(p, 100, offset, "-date");
                rows.addAll(page);
                offset += page.size();
                if (page.size() < 100) break;
            }
        } catch (RuntimeException e) { model.addAttribute("state", "chester"); return "patient"; }
        if (rows.isEmpty()) {
            // nothing in this window: only say "nothing found" if there is nothing at all (same words whether the patient exists or not)
            try {
                if (!"all".equals(window) && audit.countWhere(Map.of("entity-ref", List.of(ref))) > 0) { model.addAttribute("emptyWindow", true); }
                else { model.addAttribute("state", "notfound"); return "patient"; }
            } catch (RuntimeException e) { model.addAttribute("state", "chester"); return "patient"; }
        }

        String role = ShellAdvice.primaryRole();
        ConsentClient.Decision d = consent.check(ref, role, PURPOSE);
        try { consentLog.add(String.valueOf(PrefsContext.username()), ref, role, PURPOSE, d); } catch (RuntimeException ignored) { /* a log failure must not show data nor hide the refusal */ }
        model.addAttribute("decision", d);

        RegistryClient.PatientInfo info = null;
        if (d.permitted()) {
            try {
                info = registry.patient(id).orElse(null);
                model.addAttribute("registryMissing", info == null);
            } catch (RegistryClient.RegistryUnavailable e) {
                model.addAttribute("registryDown", true);
            }
        }
        model.addAttribute("info", info);
        model.addAttribute("initials", initials(info));
        List<Line> lines = new ArrayList<>();
        for (AuditRow r : rows) lines.add(new Line(r, careLink(r, info)));
        model.addAttribute("lines", lines);
        Map<String, String> fnames = facilities.names();
        java.util.function.Function<String, String> fname = f -> f == null || "—".equals(f) ? "—" : FacilityDirectory.nameIn(fnames, f);
        List<Visits.Visit> visits = Visits.group(lines, info, fname, zone);
        model.addAttribute("visits", visits);
        List<Visits.Looker> lookers = Visits.lookers(lines, fname);
        model.addAttribute("lookers", lookers);
        model.addAttribute("flags", Visits.flags(lines));
        model.addAttribute("systemNames", systems.names());
        // header numbers always cover the last 30 days, whichever window is shown
        Instant cut = Instant.now().minus(java.time.Duration.ofDays(30));
        List<Line> recent = lines.stream().filter(l -> l.row().getRecordedInstant() != null && l.row().getRecordedInstant().isAfter(cut)).toList();
        model.addAttribute("k30", new int[]{recent.size(), (int) recent.stream().map(l -> l.row().getAgentRef() != null ? l.row().getAgentRef() : String.valueOf(l.row().getAgentName())).distinct().count(),
            (int) recent.stream().map(l -> String.valueOf(l.row().getSubmittedBy())).distinct().count(), (int) recent.stream().filter(l -> l.row().isBreakGlass()).count()});
        model.addAttribute("truncated", total > rows.size());
        model.addAttribute("total", total);
        model.addAttribute("fnames", fnames);
        model.addAttribute("canReview", Who.canReview());
        model.addAttribute("state", d.permitted() ? "shown" : "withheld");
        return "patient";
    }

    static String initials(RegistryClient.PatientInfo info) {
        if (info == null || info.name() == null) return "?";
        StringBuilder b = new StringBuilder();
        for (String w : info.name().replace("(demo)", "").trim().split("\\s+")) {
            if (!w.isEmpty() && b.length() < 2) b.appendCodePoint(w.codePointAt(0));
        }
        return b.length() == 0 ? "?" : b.toString().toUpperCase();
    }

    /**
     * Flag "no link to this patient's care" only for a treatment claim made by a clinician; anything else says nothing.
     * Without registry data the answer is "unknown", never a guess. Demo rule: a link means the patient had a stay at the facility that sent the record.
     */
    static String careLink(AuditRow r, RegistryClient.PatientInfo info) {
        String p = r.getPurposeCode() == null ? "" : r.getPurposeCode().toUpperCase();
        boolean treatmentClaim = p.equals("TREAT") || p.equals("ETREAT") || p.equals("BTG");
        String agent = r.getAgentName() == null ? "" : r.getAgentName();
        boolean clinician = agent.startsWith("Dr.") || agent.startsWith("Practitioner/");
        if (!treatmentClaim || !clinician) return "na";
        if (info == null || r.getFacility() == null) return "unknown";
        return info.wasAt(r.getFacility()) ? "ok" : "none";
    }
}

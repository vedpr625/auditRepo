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

import com.akhester.auditui.consent.ConsentClient;
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
    public record Line(AuditRow row, String link) {}

    private final AuditEventService audit;
    private final ConsentClient consent;
    private final ConsentLogRepository consentLog;
    private final RegistryClient registry;

    public PatientController(AuditEventService audit, ConsentClient consent, ConsentLogRepository consentLog, RegistryClient registry) {
        this.audit = audit; this.consent = consent; this.consentLog = consentLog; this.registry = registry;
    }

    @GetMapping("/patient")
    public String open(@RequestParam(required = false) String id) {
        String t = id == null ? "" : id.strip().replaceFirst("^Patient/", "");
        return ID.matcher(t).matches() ? "redirect:/patient/" + t : "redirect:/overview?badPatient";
    }

    @GetMapping("/patient/{id}")
    public String page(@PathVariable String id, Model model) {
        if (!ID.matcher(id).matches()) { model.addAttribute("state", "notfound"); return "patient"; }
        String ref = "Patient/" + id;
        model.addAttribute("patientId", id);
        List<AuditRow> rows;
        try {
            Map<String, List<String>> p = new LinkedHashMap<>();
            p.put("entity-ref", List.of(ref));
            rows = audit.recentWhere(p, 50, "-date");
        } catch (RuntimeException e) { model.addAttribute("state", "chester"); return "patient"; }
        if (rows.isEmpty()) { model.addAttribute("state", "notfound"); return "patient"; }

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
        List<Line> lines = new ArrayList<>();
        for (AuditRow r : rows) lines.add(new Line(r, careLink(r, info)));
        model.addAttribute("lines", lines);
        model.addAttribute("state", d.permitted() ? "shown" : "withheld");
        return "patient";
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

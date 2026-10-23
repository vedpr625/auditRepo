package com.akhester.auditui.patient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.akhester.auditui.access.AccessRequestRepository;
import com.akhester.auditui.access.SensitivePatients;
import com.akhester.auditui.activity.ConsoleActivity;
import com.akhester.auditui.admin.SendingSystemRepository;
import com.akhester.auditui.consent.ConsentClient;
import com.akhester.auditui.consent.ConsentLogRepository;
import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.page.ShellAdvice;
import com.akhester.auditui.people.FacilityDirectory;
import com.akhester.auditui.people.Who;
import com.akhester.auditui.prefs.PrefsContext;
import com.akhester.auditui.registry.Registry;
import com.akhester.auditui.registry.RegistryClient;
import com.akhester.auditui.service.AuditEventService;

/**
 * One encounter (visit or stay). Same order of work as the patient page, and the same gate:
 * 1. The audit records of this encounter are read first (the person's own access applies). None visible: "nothing found", in the same words whether or not the encounter exists.
 * 2. The consent service is asked about the encounter's patient (fail closed, the answer is written to the consent check log). A restricted patient is not read at all.
 * 3. Only on a permit, and only for the sections the consent covers, the registry is read: the encounter itself and only its header (class, period, place, people).
 * The "who looked" half never depends on the consent: it comes from the audit records.
 */
@Controller
public class EncounterController {

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int LIMIT = 500;

    private final AuditEventService audit;
    private final ConsentClient consent;
    private final ConsentLogRepository consentLog;
    private final RegistryClient registry;
    private final EncounterService encounters;
    private final SensitivePatients sensitive;
    private final AccessRequestRepository requests;
    private final ConsoleActivity activity;
    private final FacilityDirectory facilities;
    private final SendingSystemRepository systems;

    public EncounterController(AuditEventService audit, ConsentClient consent, ConsentLogRepository consentLog, RegistryClient registry, EncounterService encounters,
                               SensitivePatients sensitive, AccessRequestRepository requests, ConsoleActivity activity, FacilityDirectory facilities, SendingSystemRepository systems) {
        this.audit = audit; this.consent = consent; this.consentLog = consentLog; this.registry = registry; this.encounters = encounters;
        this.sensitive = sensitive; this.requests = requests; this.activity = activity; this.facilities = facilities; this.systems = systems;
    }

    @GetMapping("/encounter/{id}")
    public String page(@PathVariable String id, @RequestParam(required = false) String patient, Model model) {
        String enc = id.replaceFirst("^Encounter/", "");
        model.addAttribute("encounterId", enc);
        if (!ID.matcher(enc).matches()) { model.addAttribute("state", "notfound"); return "encounter"; }

        // 1. audit records of this encounter
        List<AuditRow> rows;
        try {
            rows = new ArrayList<>();
            Map<String, List<String>> p = new LinkedHashMap<>();
            p.put("entity-ref", List.of("Encounter/" + enc));
            int offset = 0;
            while (offset < LIMIT) {
                List<AuditRow> page = audit.pageWhere(p, 100, offset, "-date");
                rows.addAll(page);
                offset += page.size();
                if (page.size() < 100) break;
            }
        } catch (RuntimeException e) { model.addAttribute("state", "chester"); return "encounter"; }

        String pid = null;
        for (AuditRow r : rows) if (r.getPatientRef() != null && r.getPatientRef().startsWith("Patient/")) { pid = r.getPatientRef().substring(8); break; }
        if (pid == null && patient != null && ID.matcher(patient.replaceFirst("^Patient/", "")).matches()) {
            // an encounter with no audit records of its own (a registry-only visit): the patient named in the link must have records this person may see
            String hint = patient.replaceFirst("^Patient/", "");
            try { if (audit.countWhere(Map.of("entity-ref", List.of("Patient/" + hint))) > 0) pid = hint; }
            catch (RuntimeException e) { model.addAttribute("state", "chester"); return "encounter"; }
        }
        if (pid == null) { model.addAttribute("state", "notfound"); return "encounter"; }
        final String patientId = pid;
        final List<AuditRow> encRows = new ArrayList<>(rows);
        encRows.removeIf(r -> r.getPatientRef() != null && !r.getPatientRef().equals("Patient/" + patientId));
        String ref = "Patient/" + patientId;
        model.addAttribute("patientId", patientId);

        if (sensitive.restrictedFor(patientId)) {
            String u = PrefsContext.username();
            model.addAttribute("state", "restricted");
            model.addAttribute("pending", u != null && requests.hasPending(u, ref));
            activity.record(ConsoleActivity.KIND_PATIENT, "Tried to open a restricted record", "Shown the restricted card, no details", patientId);
            return "encounter";
        }

        // all the patient's records (the same ones the patient page groups into visits), so the visit seen here is the one seen there
        List<AuditRow> patientRows;
        try {
            patientRows = new ArrayList<>();
            Map<String, List<String>> pp = new LinkedHashMap<>();
            pp.put("entity-ref", List.of(ref));
            int offset = 0;
            while (offset < LIMIT) {
                List<AuditRow> page = audit.pageWhere(pp, 100, offset, "-date");
                patientRows.addAll(page);
                offset += page.size();
                if (page.size() < 100) break;
            }
        } catch (RuntimeException e) { patientRows = encRows; }

        // 2. consent
        String role = ShellAdvice.primaryRole();
        ConsentClient.Decision d = consent.check(ref, role, PatientController.PURPOSE);
        try { consentLog.add(String.valueOf(PrefsContext.username()), ref, role, PatientController.PURPOSE, d); } catch (RuntimeException ignored) { /* a log failure must not show data nor hide the refusal */ }
        model.addAttribute("decision", d);
        model.addAttribute("can", PatientController.canMap(d));
        if (!d.covered()) {
            String u = PrefsContext.username();
            model.addAttribute("pendingRequest", u != null && requests.hasPending(u, ref));
        }

        // 3. registry, only what the consent covers
        RegistryClient.PatientInfo info = null;
        Registry.Section<Registry.Encounter> encSection = null;
        EncounterService.Header view = null;
        String patientName = null;
        if (d.covered()) {
            try {
                if (d.allows("demographics")) {
                    RegistryClient.PatientInfo demo = registry.patient(patientId, true, false).orElse(null);
                    patientName = demo == null ? null : demo.name();
                }
                if (d.allows("encounters")) {
                    encSection = registry.encounters(patientId);
                    info = new RegistryClient.PatientInfo(patientId, null, null, null, null, encSection.items().stream().map(RegistryClient::stay).filter(java.util.Objects::nonNull).toList());
                }
            } catch (RegistryClient.RegistryUnavailable e) {
                model.addAttribute("registryDown", true);
            }
        }
        final RegistryClient.PatientInfo stays = info;
        List<PatientController.Line> encLines = new ArrayList<>(), allLines = new ArrayList<>();
        for (AuditRow r : patientRows) {
            PatientController.Line l = new PatientController.Line(r, PatientController.careLink(r, stays));
            allLines.add(l);
            if (r.getEncounterRef() != null && r.getEncounterRef().equals("Encounter/" + enc)) encLines.add(l);
        }
        List<PatientController.Line> lines = encLines;
        if (d.covered()) {
            try {
                view = encounters.header(patientId, enc, d, encLines.isEmpty() ? allLines : encLines, encSection);
                // the visit as the patient page shows it: the records that carry this number plus those at the same facility during the registry stay
                if (view.enc() != null && encSection != null && encSection.isOk()) lines = EncounterService.linesOf(view.enc(), encSection.items(), allLines);
            } catch (RuntimeException e) { model.addAttribute("registryDown", true); }
        }
        lines = new ArrayList<>(lines);
        lines.sort(Comparator.comparing((PatientController.Line l) -> l.row().getRecordedInstant(), Comparator.nullsFirst(Comparator.naturalOrder())).reversed());
        model.addAttribute("view", view);
        model.addAttribute("patientName", patientName == null ? null : patientName.replace(" (demo)", ""));

        Map<String, String> fnames = facilities.names();
        model.addAttribute("fnames", fnames);
        model.addAttribute("systemNames", systems.names());
        model.addAttribute("lines", lines);
        model.addAttribute("btgLines", lines.stream().filter(l -> l.row().isBreakGlass()).toList());
        model.addAttribute("people", (int) lines.stream().map(l -> l.row().getAgentRef() != null ? l.row().getAgentRef() : String.valueOf(l.row().getAgentName())).distinct().count());
        model.addAttribute("systemCount", (int) lines.stream().map(l -> String.valueOf(l.row().getSubmittedBy())).distinct().count());
        model.addAttribute("refusedCount", (int) lines.stream().filter(l -> l.row().getOutcomeCode() != null && !"0".equals(l.row().getOutcomeCode())).count());
        model.addAttribute("kinds", EncounterService.kinds(lines));
        model.addAttribute("care", EncounterService.careLinkCounts(lines));
        model.addAttribute("careChecked", info != null);
        model.addAttribute("canReview", Who.canReview());
        AuditRow first = lines.isEmpty() ? null : lines.get(0).row();
        model.addAttribute("auditFacility", first == null || first.getFacility() == null ? null : FacilityDirectory.nameIn(fnames, first.getFacility()));
        model.addAttribute("auditDept", first == null ? null : first.getDepartment());
        model.addAttribute("state", d.permitted() ? "shown" : "withheld");
        activity.record(ConsoleActivity.KIND_PATIENT, "Opened encounter " + enc, d.permitted() ? null : "Consent not given, registry details not shown", patientId);
        return "encounter";
    }
}

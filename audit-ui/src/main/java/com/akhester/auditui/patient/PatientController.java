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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Instant;

import com.akhester.auditui.access.AccessRequestRepository;
import com.akhester.auditui.access.Masking;
import com.akhester.auditui.access.SensitivePatients;
import com.akhester.auditui.activity.ConsoleActivity;
import com.akhester.auditui.admin.SendingSystemRepository;
import com.akhester.auditui.consent.ConsentClient;
import com.akhester.auditui.people.FacilityDirectory;
import com.akhester.auditui.people.Who;
import com.akhester.auditui.consent.ConsentLogRepository;
import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.page.ShellAdvice;
import com.akhester.auditui.prefs.PrefsContext;
import com.akhester.auditui.registry.Registry;
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
    private final SensitivePatients sensitive;
    private final AccessRequestRepository requests;
    private final ConsoleActivity activity;
    private final Masking masking;

    public PatientController(AuditEventService audit, ConsentClient consent, ConsentLogRepository consentLog, RegistryClient registry,
                             com.akhester.auditui.prefs.PrefsContext prefsContext, FacilityDirectory facilities, SendingSystemRepository systems,
                             SensitivePatients sensitive, AccessRequestRepository requests, ConsoleActivity activity, Masking masking) {
        this.audit = audit; this.consent = consent; this.consentLog = consentLog; this.registry = registry;
        this.prefsContext = prefsContext; this.facilities = facilities; this.systems = systems;
        this.sensitive = sensitive; this.requests = requests; this.activity = activity; this.masking = masking;
    }

    /** The tabs of the patient page (?tab=). Anything else is the overview. */
    static final List<String> TABS = List.of("overview", "encounters", "access", "consent");

    @GetMapping("/patient")
    public String open(@RequestParam(required = false) String id) {
        String t = id == null ? "" : id.strip().replaceFirst("^Patient/", "");
        return ID.matcher(t).matches() ? "redirect:/patient/" + t : "redirect:/overview?badPatient";
    }

    /** Most records one patient page reads (newest first); the page says so when there are more. */
    static final int LIMIT = 500;

    @GetMapping("/patient/{id}")
    public String page(@PathVariable String id, @RequestParam(required = false) String days, @RequestParam(required = false) String tab,
                       @RequestParam(required = false) String day, jakarta.servlet.http.HttpServletRequest request, Model model) {
        // the retired Clinical 360 tab: nothing clinical is shown in this console, so its old address goes to the overview
        if ("clinical".equals(tab) && ID.matcher(id).matches()) return "redirect:/patient/" + id;
        String tabName = tab != null && TABS.contains(tab) ? tab : "overview";
        model.addAttribute("tab", tabName);
        if (!ID.matcher(id).matches()) { model.addAttribute("state", "notfound"); return "patient"; }
        String ref = "Patient/" + id;
        model.addAttribute("patientId", id);
        if (sensitive.restrictedFor(id)) {
            // A restricted record: nothing about the patient is read (not Chester, not the registry), only the request is possible.
            String u = PrefsContext.username();
            model.addAttribute("state", "restricted");
            model.addAttribute("pending", u != null && requests.hasPending(u, ref));
            activity.record(ConsoleActivity.KIND_PATIENT, "Tried to open a restricted record", "Shown the restricted card, no details", id);
            return "patient";
        }
        // the overview has its own period switch; the other tabs always cover the whole record (timeline counts and the access log must not stop at 30 days)
        String window = !"overview".equals(tabName) ? "all" : "90".equals(days) ? "90" : "all".equals(days) ? "all" : "30";
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

        model.addAttribute("can", canMap(d));
        RegistryClient.PatientInfo info = null;
        Registry.Section<Registry.Encounter> encSection = null;
        if (d.covered()) {
            // only the sections the consent covers are read: the patient resource for demographics, the encounter list for visits
            try {
                boolean demo = d.allows("demographics");
                info = registry.patient(id, demo, false).orElse(null);
                model.addAttribute("registryMissing", info == null);
                if (info != null && d.allows("encounters")) {
                    encSection = registry.encounters(id);
                    model.addAttribute("encountersStatus", encSection.status().name());
                    info = new RegistryClient.PatientInfo(info.id(), info.name(), info.sex(), info.birthDate(), info.idMasked(),
                        encSection.items().stream().map(RegistryClient::stay).filter(java.util.Objects::nonNull).toList());
                }
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
        // visits that match a registry stay come first; access that matches no stay follows, folded away
        model.addAttribute("visitsRecorded", visits.stream().filter(Visits.Visit::recorded).toList());
        model.addAttribute("visitsOther", visits.stream().filter(v -> !v.recorded()).toList());
        model.addAttribute("otherEvents", visits.stream().filter(v -> !v.recorded()).mapToInt(Visits.Visit::events).sum());
        if (info != null) {
            model.addAttribute("ageYears", info.birthDate() == null ? null : java.time.Period.between(info.birthDate(), java.time.LocalDate.now()).getYears());
            model.addAttribute("sexLabel", info.sex() == null ? null : info.sex().substring(0, 1).toUpperCase() + info.sex().substring(1));
            model.addAttribute("nic", masking.nic(info.idMasked(), "ending"));
            RegistryClient.Stay last = info.stays().stream().max(java.util.Comparator.comparing(RegistryClient.Stay::from)).orElse(null);
            model.addAttribute("lastStay", last);
            model.addAttribute("regUpdated", last == null ? null : (last.to() != null ? last.to() : last.from()));
        }
        model.addAttribute("refusedWhere", visits.stream().filter(v -> v.refused() > 0).map(v -> v.ward() != null ? v.ward() : v.facilityName()).distinct().limit(2)
            .collect(java.util.stream.Collectors.joining(", ")));
        boolean restrictedRecord = sensitive.isSensitive(id);   // only a privacy officer gets here
        model.addAttribute("restrictedRecord", restrictedRecord);
        if (d.covered() && d.allows("encounters") && "encounters".equals(tabName)) {
            model.addAttribute("encRows", encSection != null && encSection.isOk() ? EncounterService.rows(id, encSection.items(), lines) : List.of());
        }
        if ("access".equals(tabName)) {
            java.time.LocalDate dayFilter = null;
            try { if (day != null) dayFilter = java.time.LocalDate.parse(day); } catch (RuntimeException ignored) { /* no filter */ }
            final java.time.LocalDate dd = dayFilter;
            List<Line> shown = dd == null ? lines : lines.stream().filter(l -> l.row().getRecordedInstant() != null && l.row().getRecordedInstant().atZone(zone).toLocalDate().equals(dd)).toList();
            model.addAttribute("accessDay", dd);
            model.addAttribute("accessDayInstant", dd == null ? null : dd.atStartOfDay(zone).toInstant());
            model.addAttribute("accessTotalShown", shown.size());
            model.addAttribute("accessLines", shown.size() > 200 ? shown.subList(0, 200) : shown);
        }
        if ("consent".equals(tabName)) {
            try { model.addAttribute("consentChecks", consentLog.forPatient(ref, 15)); } catch (RuntimeException e) { model.addAttribute("consentChecks", List.of()); }
        }
        if (!d.covered()) {
            String u = PrefsContext.username();
            model.addAttribute("pendingRequest", u != null && requests.hasPending(u, ref));
        }
        String opened = d.permitted() ? (d.limited() ? "Consent covers " + d.scopes().size() + " of " + ConsentClient.SCOPES.size() + " sections" : null) : "Consent not given, registry details not shown";
        switch (tabName) {
            case "encounters" -> activity.record(ConsoleActivity.KIND_PATIENT, "Opened the encounter list of " + id, restrictedRecord ? "Restricted record, opened by a privacy officer" : opened, id);
            default -> activity.record(ConsoleActivity.KIND_PATIENT, "Opened patient " + id, restrictedRecord ? "Restricted record, opened by a privacy officer" : null, id);
        }
        model.addAttribute("state", d.permitted() ? "shown" : "withheld");
        return "patient";
    }

    /** One flag per section: may it be shown (the consent covers it). */
    static Map<String, Boolean> canMap(ConsentClient.Decision d) {
        Map<String, Boolean> m = new LinkedHashMap<>();
        for (String sc : ConsentClient.SCOPES) m.put(sc, d.allows(sc));
        return m;
    }

    /** "Ask the privacy officer for access" when the consent does not allow the registry details. Recorded like the restricted-record request; nothing about the patient is read. */
    @PostMapping("/patient/{id}/request-consent-access")
    public String requestConsentAccess(@PathVariable String id, @RequestParam(required = false) String note, RedirectAttributes ra) {
        if (!ID.matcher(id).matches()) return "redirect:/overview?badPatient";
        String user = PrefsContext.username();
        if (user != null && !sensitive.restrictedFor(id) && !requests.hasPending(user, "Patient/" + id)) {
            String n = note == null ? "" : note.strip();
            requests.add(user, "Patient/" + id, "Registry details (consent)" + (n.isEmpty() ? "" : ": " + n));
            activity.record(ConsoleActivity.KIND_REQUEST, "Asked the privacy officer for access to registry details", "Decision pending", id);
        }
        ra.addFlashAttribute("requested", true);
        return "redirect:/patient/" + id + "?tab=consent";
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

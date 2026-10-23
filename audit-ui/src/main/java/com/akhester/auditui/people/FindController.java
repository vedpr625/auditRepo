package com.akhester.auditui.people;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.akhester.auditui.admin.SendingSystemRepository;
import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.service.AuditEventService;

/**
 * Find anything: people (agent references seen on records), patients (by number), sending systems and review decisions ("cases").
 * Patients are found by number only: names are read from the registry after the consent check, on the patient page, never for a search.
 */
@Controller
public class FindController {

    static final Pattern ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final int MAX = 20;

    public record SystemHit(String clientId, String label, String note, Instant lastSeen) {}
    public record CaseHit(String eventId, String status, String reviewer, Instant at, String note, String patientId) {}

    private final PeopleIndex index;
    private final SendingSystemRepository systems;
    private final AuditEventService audit;
    private final JdbcTemplate jdbc;

    public FindController(PeopleIndex index, SendingSystemRepository systems, AuditEventService audit, JdbcTemplate jdbc) {
        this.index = index; this.systems = systems; this.audit = audit; this.jdbc = jdbc;
    }

    /** null when the text may be searched, otherwise the message key suffix saying why not. */
    static String problem(String q) {
        if (q == null || q.isBlank()) return "empty";
        String t = q.strip();
        if (t.length() < 2) return "short";
        if (t.length() > 100) return "long";
        for (int i = 0; i < t.length(); i++) if (Character.isISOControl(t.charAt(i))) return "bad";
        return null;
    }

    @GetMapping("/find")
    public String find(@RequestParam(required = false) String q, Model model) {
        String t = q == null ? "" : q.strip();
        model.addAttribute("q", t.length() > 100 ? t.substring(0, 100) : t.replaceAll("\\p{Cntrl}", ""));
        String prob = problem(q);
        model.addAttribute("problem", prob);
        if (prob != null) return "find";
        String n = t.toLowerCase(Locale.ROOT);

        PeopleIndex.Snapshot s = index.snapshot();
        model.addAttribute("chesterDown", s.error() != null);
        List<PeopleIndex.Person> people = s.people().stream().filter(x -> contains(x.name(), n) || contains(x.ref(), n)).limit(MAX).toList();
        List<PeopleIndex.PatientEntry> pats = new ArrayList<>(s.patients().stream().filter(x -> x.id().toLowerCase(Locale.ROOT).contains(n)).limit(MAX).toList());
        if (s.error() == null && ID.matcher(t).matches() && pats.stream().noneMatch(x -> x.id().equalsIgnoreCase(t))) {
            try {   // an exact number that is older than the newest records
                int c = audit.countWhere(Map.of("entity-ref", List.of("Patient/" + t)));
                if (c > 0) pats.add(0, new PeopleIndex.PatientEntry(t, c, -1, -1, -1, null));
            } catch (RuntimeException ignored) { /* the list above still stands */ }
        }
        List<SystemHit> sys = new ArrayList<>();
        try {
            for (SendingSystemRepository.System x : systems.all())
                if (contains(x.clientId(), n) || contains(x.displayName(), n) || contains(x.note(), n)) sys.add(new SystemHit(x.clientId(), x.label(), x.note(), x.lastSeen()));
        } catch (RuntimeException ignored) { /* none shown */ }

        boolean reviewer = Who.canReview();
        List<CaseHit> cases = reviewer ? cases(t) : List.of();
        model.addAttribute("people", people);
        model.addAttribute("patients", pats);
        model.addAttribute("systemHits", sys.stream().limit(MAX).toList());
        model.addAttribute("cases", cases);
        model.addAttribute("canReview", reviewer);
        model.addAttribute("scanned", s.scanned());
        model.addAttribute("total", people.size() + pats.size() + sys.size() + cases.size());
        return "find";
    }

    private static boolean contains(String hay, String needleLower) { return hay != null && hay.toLowerCase(Locale.ROOT).contains(needleLower); }

    /** Review decisions matching an event number, a reviewer or words in the note, limited to events this person can see in Chester. */
    private List<CaseHit> cases(String t) {
        String like = "%" + t.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        List<Object[]> found = new ArrayList<>();
        try {
            jdbc.query("SELECT event_id, status, reviewer, reviewed_at, note FROM audit_review WHERE event_id = ? OR LOWER(reviewer) LIKE ? ESCAPE '\\' OR LOWER(note) LIKE ? ESCAPE '\\' ORDER BY reviewed_at DESC LIMIT 60",
                rs -> { found.add(new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getTimestamp(4).toInstant(), rs.getString(5)}); }, t, like, like);
        } catch (RuntimeException e) { return List.of(); }
        if (found.isEmpty()) return List.of();
        Map<String, AuditRow> visible = new java.util.HashMap<>();
        try {
            for (int i = 0; i < found.size(); i += 50) {
                String ids = String.join(",", found.subList(i, Math.min(i + 50, found.size())).stream().map(o -> (String) o[0]).toList());
                for (AuditRow r : audit.pageWhere(Map.of("_id", List.of(ids)), 100, 0, "-date")) visible.put(r.getId(), r);
            }
        } catch (RuntimeException e) { return List.of(); }
        List<CaseHit> out = new ArrayList<>();
        for (Object[] o : found) {
            AuditRow r = visible.get((String) o[0]);
            if (r == null) continue;
            String pr = r.getPatientRef();
            out.add(new CaseHit((String) o[0], (String) o[1], (String) o[2], (Instant) o[3], (String) o[4], pr != null && pr.startsWith("Patient/") ? pr.substring(8) : null));
            if (out.size() >= MAX) break;
        }
        return out;
    }
}

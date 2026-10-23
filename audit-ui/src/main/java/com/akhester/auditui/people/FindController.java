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
    private final com.akhester.auditui.explore.VisitIndex visits;
    private final com.akhester.auditui.reviews.CaseRepository caseRepo;
    private final FacilityDirectory facilityDir;
    private final org.springframework.context.MessageSource messages;

    public FindController(PeopleIndex index, SendingSystemRepository systems, AuditEventService audit, JdbcTemplate jdbc,
                          com.akhester.auditui.explore.VisitIndex visits, com.akhester.auditui.reviews.CaseRepository caseRepo,
                          FacilityDirectory facilityDir, org.springframework.context.MessageSource messages) {
        this.index = index; this.systems = systems; this.audit = audit; this.jdbc = jdbc;
        this.visits = visits; this.caseRepo = caseRepo; this.facilityDir = facilityDir; this.messages = messages;
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

    // ── Ctrl K palette: JSON suggestions ─────────────────────────────────────

    private static final java.util.Set<String> SCOPES = java.util.Set.of("all", "patient", "person", "facility", "case", "event", "visit");
    private static final int PER_GROUP = 6;

    private static Map<String, Object> item(String kind, String badge, String title, String sub, String meta, String open, String search, Map<String, String> filter) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("kind", kind); m.put("badge", badge); m.put("title", title); m.put("sub", sub == null ? "" : sub); m.put("meta", meta == null ? "" : meta);
        m.put("open", open); m.put("search", search);
        if (filter != null) m.put("filter", filter);
        return m;
    }

    private static Map<String, Object> group(String key, String title, List<Map<String, Object>> items) {
        Map<String, Object> g = new java.util.LinkedHashMap<>();
        g.put("key", key); g.put("title", title); g.put("count", items.size()); g.put("items", items);
        return g;
    }

    private String m(String key, java.util.Locale loc, Object... args) { return messages.getMessage(key, args, key, loc); }

    private static String initials(String name) {
        StringBuilder b = new StringBuilder();
        for (String w : String.valueOf(name).replaceAll("^(Dr\\.?|Nurse)\\s+", "").trim().split("\\s+")) if (!w.isEmpty() && b.length() < 2) b.appendCodePoint(Character.toUpperCase(w.codePointAt(0)));
        return b.length() == 0 ? "?" : b.toString();
    }

    /**
     * Suggestions for the Ctrl K palette. Patients are found by number only: a name would come from the registry, and the registry is
     * only asked after the consent check on the patient page. Everything else comes from what this person may already see in Chester.
     */
    @GetMapping(value = "/find/suggest", produces = "application/json")
    @org.springframework.web.bind.annotation.ResponseBody
    public Map<String, Object> suggest(@RequestParam(required = false) String q, @RequestParam(required = false, defaultValue = "all") String scope, java.util.Locale loc) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        String sc = SCOPES.contains(scope) ? scope : "all";
        String t = q == null ? "" : q.strip();
        out.put("q", t); out.put("scope", sc);
        List<Map<String, Object>> groups = new ArrayList<>();
        out.put("groups", groups);
        String prob = problem(t);
        if (prob != null) { out.put("hint", prob); return out; }
        String n = t.toLowerCase(Locale.ROOT);
        boolean all = sc.equals("all");

        PeopleIndex.Snapshot s = index.snapshot();
        if (s.error() != null) out.put("error", "chester");

        if (all || sc.equals("patient")) {
            List<Map<String, Object>> items = new ArrayList<>();
            List<PeopleIndex.PatientEntry> pats = new ArrayList<>(s.patients().stream().filter(x -> x.id().toLowerCase(Locale.ROOT).contains(n)).limit(PER_GROUP).toList());
            if (s.error() == null && ID.matcher(t).matches() && pats.stream().noneMatch(x -> x.id().equalsIgnoreCase(t))) {
                try {
                    int c = audit.countWhere(Map.of("entity-ref", List.of("Patient/" + t)));
                    if (c > 0) pats.add(0, new PeopleIndex.PatientEntry(t, c, -1, -1, 0, null));
                } catch (RuntimeException ignored) { /* the list stands */ }
            }
            for (PeopleIndex.PatientEntry x : pats) {
                String meta = m("find.palette.events", loc, x.events()) + (x.breakGlass() > 0 ? ", " + m("find.palette.btg", loc, x.breakGlass()) : "");
                items.add(item("patient", "P", x.id(), null, meta, "/patient/" + x.id(), "/?q=" + x.id() + "&range=all", Map.of("q", x.id(), "range", "all")));
            }
            if (!items.isEmpty()) groups.add(group("patients", m("find.palette.g.patients", loc), items));
        }
        if (all || sc.equals("person")) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (PeopleIndex.Person x : s.people().stream().filter(x -> x.ref() != null && (contains(x.name(), n) || contains(x.ref(), n))).limit(PER_GROUP).toList()) {
                String role = m("people.role." + x.role(), loc);
                items.add(item("person", initials(x.name()), x.name(), role + (x.facility() != null ? ", " + facilityDir.names().getOrDefault(x.facility(), FacilityDirectory.tail(x.facility())) : ""),
                    m("find.palette.records", loc, x.events()), "/?agent=" + enc(x.ref()) + "&range=all", "/?agent=" + enc(x.ref()) + "&range=all", Map.of("agent", x.ref(), "range", "all")));
            }
            if (!items.isEmpty()) groups.add(group("people", m("find.palette.g.people", loc), items));
        }
        if (all || sc.equals("facility")) {
            List<Map<String, Object>> items = new ArrayList<>();
            try {
                for (FacilityDirectory.Facility f : facilityDir.withSeen(s.facilities())) {
                    if (!(contains(f.name(), n) || contains(f.ref(), n) || contains(f.district(), n))) continue;
                    if (items.size() >= PER_GROUP) break;
                    String sub = f.kind() == null ? "" : f.kind() + (f.district() != null ? ", " + f.district() : "");
                    items.add(item("facility", "F", f.name(), sub, f.listed() ? "" : m("find.palette.notlisted", loc), "/?facility=" + enc(f.ref()) + "&range=all", "/?facility=" + enc(f.ref()) + "&range=all", Map.of("facility", f.ref(), "range", "all")));
                }
            } catch (RuntimeException ignored) { /* none shown */ }
            if (!items.isEmpty()) groups.add(group("facilities", m("find.palette.g.facilities", loc), items));
        }
        List<Map<String, Object>> also = new ArrayList<>();
        if (all || sc.equals("facility")) {
            try {
                for (SendingSystemRepository.System x : systems.all()) {
                    if (also.size() >= PER_GROUP) break;
                    if (contains(x.clientId(), n) || contains(x.displayName(), n)) also.add(item("system", "S", x.label(), x.clientId(), m("find.palette.system", loc), "/?client=" + enc(x.clientId()) + "&range=all", "/?client=" + enc(x.clientId()) + "&range=all", Map.of("client", x.clientId(), "range", "all")));
                }
            } catch (RuntimeException ignored) { /* none shown */ }
        }
        if (all || sc.equals("patient") || sc.equals("visit")) {
            int k = 0;
            for (com.akhester.auditui.explore.VisitIndex.Visit v : visits.visits()) {
                if (!contains(v.id(), n)) continue;
                if (k++ >= (sc.equals("visit") ? 10 : 3)) break;
                String where = v.facility() == null ? "" : facilityDir.names().getOrDefault(v.facility(), FacilityDirectory.tail(v.facility()));
                String sub = (v.department() == null ? "" : v.department()) + (!where.isEmpty() && v.department() != null ? ", " : "") + where;
                String url = "/?encounter=" + enc(v.id()) + "&range=all";
                also.add(item("visit", "V", m("find.palette.visit", loc, v.id()), sub, m("find.palette.events", loc, v.events()), url, url, Map.of("encounter", v.id(), "range", "all")));
            }
        }
        if ((all || sc.equals("patient") || sc.equals("case")) && com.akhester.auditui.reviews.ReviewAccess.canViewAll()) {
            int k = 0;
            try {
                for (com.akhester.auditui.reviews.CaseRepository.Case c : caseRepo.all()) {
                    if (!(contains(c.id(), n) || contains(c.title(), n) || contains(c.subject(), n) || contains(c.patientId(), n))) continue;
                    if (k++ >= (sc.equals("case") ? 10 : 3)) break;
                    also.add(item("case", "C", m("find.palette.case", loc, c.id(), c.title() == null ? "" : c.title().toLowerCase(loc)), c.patientId() == null ? "" : c.patientId(),
                        m("find.palette.case.status." + c.status(), loc), "/reviews/" + c.id(), c.exploreQs() != null && !c.exploreQs().isBlank() ? "/?" + c.exploreQs() + "&range=all" : "/reviews/" + c.id(), null));
                }
            } catch (RuntimeException ignored) { /* none shown */ }
        }
        if ((all || sc.equals("event")) && t.matches("[A-Za-z0-9._-]{1,64}")) {
            try {
                for (AuditRow r : audit.pageWhere(Map.of("_id", List.of(t)), 1, 0, "-date")) {
                    String pr = r.getPatientRef() != null && r.getPatientRef().startsWith("Patient/") ? r.getPatientRef().substring(8) : "";
                    also.add(item("event", "E", m("find.palette.event", loc, r.getId()), pr, m("find.palette.event.open", loc), "/?open=" + enc(r.getId()) + "&range=all", "/?open=" + enc(r.getId()) + "&range=all", null));
                }
            } catch (RuntimeException ignored) { /* none shown */ }
        }
        if (!also.isEmpty()) groups.add(group("also", m(all || sc.equals("patient") ? "find.palette.g.also" : "find.palette.g.matches", loc), also));
        return out;
    }

    private static String enc(String v) { return java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8); }
}

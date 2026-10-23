package com.akhester.auditui.reviews;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import com.akhester.auditui.config.SlaPolicy;
import com.akhester.auditui.demo.DemoUsers;
import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.page.NavBadges;
import com.akhester.auditui.reviews.CaseRepository.Case;
import com.akhester.auditui.reviews.CaseRepository.Decision;
import com.akhester.auditui.service.ReviewRepository;

/**
 * The case rules. Two reviewers must reach the same decision to close a case; a disagreement, or a policy violation ruled by anyone
 * other than a privacy officer, goes to the privacy officer, whose decision is final. A closed case is locked (fingerprint).
 */
@Service
public class CaseService implements NavBadges {

    public static final List<String> DECISIONS = List.of("follow_up", "appropriate", "violation");
    public static final List<String> KINDS = List.of("repeat", "volume", "btgreason", "btg", "repeatbtg", "night", "failures", "manual");
    private static final Set<String> REVIEWER_ROLES = Set.of("AUDIT_ADMIN", "MINISTRY_AUDITOR", "PRIVACY_OFFICER");

    private final CaseRepository cases;
    private final ReviewRepository eventReviews;
    private final SlaPolicy sla;
    private final boolean demo;

    public CaseService(CaseRepository cases, ReviewRepository eventReviews, SlaPolicy sla, @Value("${audit.ui.demo:false}") boolean demo) {
        this.cases = cases;
        this.eventReviews = eventReviews;
        this.sla = sla;
        this.demo = demo;
    }

    public CaseRepository repo() { return cases; }

    @Override
    public int reviewsOpen() {
        String u = ReviewAccess.user();
        if (u == null || !ReviewAccess.canViewAll()) return 0;
        return cases.countOpenFor(u, ReviewAccess.isReviewer(), ReviewAccess.isPrivacyOfficer());
    }

    /** People who can be asked for a second opinion: the demo's reviewers, plus anyone who has already worked on a case. */
    public List<String> reviewers() {
        Set<String> out = new TreeSet<>();
        if (demo) for (DemoUsers.User u : DemoUsers.ALL) if (REVIEWER_ROLES.contains(u.role())) out.add(u.username());
        out.addAll(cases.knownReviewers());
        return new ArrayList<>(out);
    }

    // ── opening ──────────────────────────────────────────────────────────────

    @Transactional
    public Case open(String kind, String title, String subject, String patientRef, String facility, String exploreQs, Collection<String> eventIds,
                     String openedBy, Instant openedAt, String assignee, String reopenOf, boolean demoSeed) {
        if (!KINDS.contains(kind)) kind = "manual";
        for (int attempt = 0; ; attempt++) {
            long seq = cases.nextSeq();
            Instant at = openedAt == null ? Instant.now().truncatedTo(ChronoUnit.MILLIS) : openedAt.truncatedTo(ChronoUnit.MILLIS);
            Case c = new Case("C-" + seq, seq, kind, clip(title, 200), clip(subject, 200), clip(patientRef, 64), clip(facility, 120), clip(exploreQs, 300),
                assignee == null ? "new" : "review", at, openedBy, at.plus(sla.overdueHours(), ChronoUnit.HOURS), assignee, null, null, null, null, null, null, reopenOf, demoSeed);
            try {
                cases.insert(c);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                if (attempt > 3) throw e;
                continue;
            }
            cases.addEvents(c.id(), eventIds);
            cases.addLog(c.id(), at, openedBy, reopenOf != null ? "reopened" : "opened", reopenOf);
            if (assignee != null) cases.addLog(c.id(), at.plusSeconds(1), openedBy, "assigned", assignee);
            return c;
        }
    }

    /** Opens (or finds the open) case for one break-glass event. */
    @Transactional
    public Case openForEvent(AuditRow row, String user) {
        var existing = cases.caseOfEvents(List.of(row.getId()));
        if (existing.containsKey(row.getId())) {
            Case c = cases.find(existing.get(row.getId())).orElse(null);
            if (c != null && c.isOpen()) return c;
        }
        boolean noReason = row.getBreakGlassReason() == null;
        String patient = row.getPatientRef() == null || "—".equals(row.getPatientRef()) ? null : row.getPatientRef();
        String qs = patient == null ? null : "q=" + patient.replace("Patient/", "");
        return open(noReason ? "btgreason" : "btg", noReason ? "Break-glass without a reason" : "Break-glass access to review", row.getAgentName(), patient,
            row.getFacility(), qs, List.of(row.getId()), user, null, null, null, false);
    }

    // ── actions ──────────────────────────────────────────────────────────────

    @Transactional
    public void assign(String id, String user, String to) {
        Case c = need(id);
        requireOpen(c);
        if (!"new".equals(c.status()) && !"review".equals(c.status())) throw conflict("This case is already past the first review.");
        if (to == null || to.isBlank()) to = user;
        if (!to.equals(user) && !reviewers().contains(to)) throw conflict("That person is not a reviewer.");
        if (!cases.update(id, c.status(), "review", to, c.secondReviewer(), c.draftDecision(), c.draftNote())) throw conflict("The case changed. Reload and try again.");
        cases.addLog(id, now(), user, "assigned", to);
    }

    @Transactional
    public void note(String id, String user, String text) {
        Case c = need(id);
        requireOpen(c);
        if (text == null || text.isBlank()) return;
        cases.addLog(id, now(), user, "note", clip(text.strip(), 500));
    }

    /** Result of a decision, so the page can say what happened. */
    public enum Result { SAVED, SENT, CLOSED, ESCALATED }

    @Transactional
    public Result decide(String id, String user, String decision, String note, String second, boolean send) {
        if (!DECISIONS.contains(decision)) throw new IllegalArgumentException("bad decision");
        Case c = need(id);
        requireOpen(c);
        String n = note == null || note.isBlank() ? null : clip(note.strip(), 2000);
        boolean po = ReviewAccess.isPrivacyOfficer();
        Instant at = now();
        switch (c.status()) {
            case "new", "review" -> {
                if (c.assignee() != null && !c.assignee().equals(user)) throw forbidden("This case is assigned to " + c.assignee() + ".");
                if (!send) {
                    if (!cases.update(id, c.status(), "review", user, null, decision, n)) throw conflict("The case changed. Reload and try again.");
                    if ("new".equals(c.status())) cases.addLog(id, at, user, "assigned", user);
                    return Result.SAVED;
                }
                if ("violation".equals(decision)) {
                    cases.addDecision(id, po ? "officer" : "first", user, decision, n, at);
                    cases.addLog(id, at, user, "decided", decision);
                    if (po) return closeWith(c, c.status(), decision, at, user);
                    if (!cases.update(id, c.status(), "escalated", user, null, null, null)) throw conflict("The case changed. Reload and try again.");
                    cases.addLog(id, at.plusMillis(1), user, "escalated", "violation");
                    return Result.ESCALATED;
                }
                String s = second == null ? "" : second.strip();
                if (s.isEmpty() || s.equals(user)) throw conflict("Choose a second reviewer who is not you.");
                if (!s.matches("[A-Za-z0-9._@-]{1,100}")) throw conflict("That is not a valid user name.");
                List<String> known = reviewers();
                if (!known.isEmpty() && !known.contains(s)) throw conflict("That person is not a reviewer.");
                cases.addDecision(id, "first", user, decision, n, at);
                if (!cases.update(id, c.status(), "second", user, s, null, null)) throw conflict("The case changed. Reload and try again.");
                cases.addLog(id, at, user, "decided", decision);
                cases.addLog(id, at.plusMillis(1), user, "second", s);
                return Result.SENT;
            }
            case "second" -> {
                if (!user.equals(c.secondReviewer())) throw forbidden("Only the second reviewer, " + c.secondReviewer() + ", can decide now.");
                if (!send) {
                    // a second reviewer's decision is final for them; there is no draft stage
                    throw conflict("Choose a decision and send it.");
                }
                Decision first = cases.decisions(id).stream().filter(d -> "first".equals(d.role())).findFirst().orElse(null);
                cases.addDecision(id, po && "violation".equals(decision) ? "officer" : "second", user, decision, n, at);
                cases.addLog(id, at, user, "decided", decision);
                if (po && "violation".equals(decision)) return closeWith(c, "second", decision, at, user);
                if (first != null && first.decision().equals(decision) && !"violation".equals(decision)) return closeWith(c, "second", decision, at, user);
                if (!cases.update(id, "second", "escalated", c.assignee(), c.secondReviewer(), null, null)) throw conflict("The case changed. Reload and try again.");
                cases.addLog(id, at.plusMillis(1), user, "escalated", first != null && !first.decision().equals(decision) ? "differ" : "violation");
                return Result.ESCALATED;
            }
            case "escalated" -> {
                if (!po) throw forbidden("Only a privacy officer can decide an escalated case.");
                cases.addDecision(id, "officer", user, decision, n, at);
                cases.addLog(id, at, user, "decided", decision);
                return closeWith(c, "escalated", decision, at, user);
            }
            default -> throw conflict("This case cannot be changed.");
        }
    }

    private Result closeWith(Case c, String expect, String outcome, Instant at, String user) {
        Case sealedView = new Case(c.id(), c.seq(), c.kind(), c.title(), c.subject(), c.patientRef(), c.facility(), c.exploreQs(), "done", c.openedAt(), c.openedBy(),
            c.dueAt(), c.assignee(), c.secondReviewer(), null, null, outcome, at, null, c.reopenOf(), c.demoSeed());
        String fp = fingerprint(sealedView, cases.eventIds(c.id()), cases.decisions(c.id()));
        if (!cases.close(c.id(), expect, outcome, at, fp)) throw conflict("The case changed. Reload and try again.");
        cases.addLog(c.id(), at.plusMillis(2), user, "closed", outcome);
        bridgeToEvents(c.id(), outcome, user);
        return Result.CLOSED;
    }

    /** Closing a case also records the decision on each of its events, so the per-event Reviewed / Flagged tabs and the review clock agree. */
    private void bridgeToEvents(String caseId, String outcome, String user) {
        String status = "appropriate".equals(outcome) ? "reviewed" : "flagged";
        for (String ev : cases.eventIds(caseId)) {
            if (ev.matches("[A-Za-z0-9\\-.]{1,64}")) eventReviews.save(ev, status, user, "[case " + caseId + "] " + outcome);
        }
    }

    /** Opens a new case that points to a closed one (a closed case itself is never edited). */
    @Transactional
    public Case reopen(String id, String user) {
        Case old = need(id);
        if (!old.isClosed()) throw conflict("Only a closed case can be reopened.");
        return open(old.kind(), old.title(), old.subject(), old.patientRef(), old.facility(), old.exploreQs(), cases.eventIds(id), user, null, null, id, false);
    }

    // ── fingerprint ──────────────────────────────────────────────────────────

    /** Writes the fingerprint of an already-closed case (used by the demo seed). */
    public void seal(String id) {
        Case c = need(id);
        String fp = fingerprint(c, cases.eventIds(id), cases.decisions(id));
        cases.setFingerprint(id, fp);
    }

    public boolean fingerprintMatches(Case c) {
        return c.isClosed() && c.fingerprint() != null && c.fingerprint().equals(fingerprint(c, cases.eventIds(c.id()), cases.decisions(c.id())));
    }

    static String fingerprint(Case c, List<String> events, List<Decision> decisions) {
        StringBuilder b = new StringBuilder();
        b.append(c.id()).append('|').append(c.kind()).append('|').append(c.title()).append('|').append(c.subject()).append('|').append(c.patientRef()).append('|')
            .append(c.outcome()).append('|').append(c.openedAt() == null ? 0 : c.openedAt().toEpochMilli()).append('|').append(c.closedAt() == null ? 0 : c.closedAt().toEpochMilli());
        b.append("|E:").append(events.stream().sorted().collect(Collectors.joining(",")));
        for (Decision d : decisions) b.append("|D:").append(d.role()).append(':').append(d.reviewer()).append(':').append(d.decision()).append(':').append(d.note()).append(':')
            .append(d.decidedAt() == null ? 0 : d.decidedAt().toEpochMilli());
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (byte x : h) s.append(String.format("%02x", x));
            return s.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Case need(String id) {
        if (id == null || !id.matches("C-\\d{1,10}")) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return cases.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static void requireOpen(Case c) { if (c.isClosed()) throw conflict("This case is closed and locked."); }
    private static ResponseStatusException conflict(String m) { return new ResponseStatusException(HttpStatus.CONFLICT, m); }
    private static ResponseStatusException forbidden(String m) { return new ResponseStatusException(HttpStatus.FORBIDDEN, m); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MILLIS); }
    private static String clip(String s, int n) { return s == null ? null : (s.length() > n ? s.substring(0, n) : s); }

    public static Set<String> unique(Collection<String> c) { return new LinkedHashSet<>(c); }
}

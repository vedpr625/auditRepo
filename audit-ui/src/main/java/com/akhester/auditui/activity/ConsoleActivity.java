package com.akhester.auditui.activity;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

import org.hl7.fhir.r4.model.AuditEvent;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.akhester.auditui.page.ShellAdvice;
import com.akhester.auditui.prefs.PrefsContext;

import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;

/**
 * Records what a person does in the console: a row in the console's own table AND an AuditEvent written to Chester through the same client the
 * console reads with (so it appears in Explore and is chained like every other record). A failed Chester write never blocks the person:
 * the row is kept with state "failed" and Chester is not retried for a minute.
 */
@Service
public class ConsoleActivity {

    private static final Logger log = LoggerFactory.getLogger(ConsoleActivity.class);

    public static final String KIND_SEARCH = "search", KIND_PATIENT = "patient", KIND_REPORT = "report", KIND_DECISION = "decision",
        KIND_RULE = "rule", KIND_REQUEST = "request", KIND_LOG = "log";
    private static final String TAGS = "https://akhester.com/chester";
    public static final String CLIENT = "audit-console", OBSERVER = "Device/audit-console";

    private final ActivityRepository repo;
    private final ObjectProvider<IGenericClient> chester;
    private final AtomicLong chesterPausedUntil = new AtomicLong();

    public ConsoleActivity(ActivityRepository repo, ObjectProvider<IGenericClient> chester, @Value("${audit.ui.demo:false}") boolean demo) {
        this.repo = repo; this.chester = chester;
    }

    /** Records one action by the signed-in person. Never throws. */
    public void record(String kind, String summary, String detail, String patientId) {
        try {
            String user = PrefsContext.username();
            if (user == null || summary == null) return;
            Instant now = Instant.now();
            if (!KIND_REQUEST.equals(kind) && repo.repeated(user, kind, summary, detail, now.minus(Duration.ofSeconds(45)))) return;
            String role = ShellAdvice.primaryRole();
            if (role == null) role = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority())) ? "AUDIT_ADMIN" : null;
            String pref = patientId == null ? null : "Patient/" + patientId;
            long id = repo.insert(now, user, role, kind, summary, detail, pref);
            writeToChester(id, now, user, kind, summary, detail, patientId);
        } catch (RuntimeException e) {
            log.warn("Console activity could not be recorded: {}", e.toString());
        }
    }

    private void writeToChester(long rowId, Instant at, String user, String kind, String summary, String detail, String patientId) {
        if (System.currentTimeMillis() < chesterPausedUntil.get()) { repo.setChester(rowId, null, "failed"); return; }
        try {
            IGenericClient c = chester.getIfAvailable();
            if (c == null) { repo.setChester(rowId, null, "none"); return; }
            AuditEvent e = new AuditEvent();
            e.setRecorded(Date.from(at));
            e.getType().setSystem("http://terminology.hl7.org/CodeSystem/audit-event-type").setCode("rest").setDisplay("RESTful Operation");
            e.addSubtype().setSystem(TAGS + "/console-activity").setCode(kind).setDisplay(summary);
            e.setAction(KIND_DECISION.equals(kind) || KIND_RULE.equals(kind) || KIND_REQUEST.equals(kind) ? AuditEvent.AuditEventAction.U : AuditEvent.AuditEventAction.E);
            e.setOutcome(AuditEvent.AuditEventOutcome._0);
            e.setOutcomeDesc(detail == null ? summary : summary + " (" + detail + ")");
            e.addPurposeOfEvent(new CodeableConcept().addCoding(new Coding("http://terminology.hl7.org/CodeSystem/v3-ActReason", "HOPERAT", null)));
            e.getMeta().addTag(new Coding(TAGS + "/submitting-client", CLIENT, null));
            AuditEvent.AuditEventAgentComponent a = e.addAgent();
            a.setName(user).setRequestor(true).setWho(new Reference("Practitioner/" + user));
            e.getSource().setSite("Audit Console").setObserver(new Reference(OBSERVER));
            if (patientId != null) {
                // named by identifier only: the record says which patient was looked at without joining that patient's own access history
                e.addEntity().setWhat(new Reference().setDisplay("Patient " + patientId).setIdentifier(new org.hl7.fhir.r4.model.Identifier().setValue(patientId)))
                    .setType(new Coding("http://terminology.hl7.org/CodeSystem/audit-entity-type", "1", "Person"));
            }
            MethodOutcome out = c.create().resource(e).execute();
            String id = out.getId() == null ? null : out.getId().getIdPart();
            repo.setChester(rowId, id, "written");   // Chester answered 201; the id comes from its Location header
        } catch (RuntimeException ex) {
            chesterPausedUntil.set(System.currentTimeMillis() + 60_000);
            repo.setChester(rowId, null, "failed");
            log.warn("Console activity was not written to Chester (will not retry for a minute): {}", ex.toString());
        }
    }
}

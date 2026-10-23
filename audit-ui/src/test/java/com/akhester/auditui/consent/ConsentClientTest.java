package com.akhester.auditui.consent;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.akhester.auditui.consent.ConsentClient.Decision;
import com.akhester.auditui.consent.ConsentClient.Result;

/** How the consent answer is read: scopes and validity are optional, anything odd keeps the details closed. */
class ConsentClientTest {

    private static final Instant NOW = Instant.parse("2026-10-23T05:00:00Z");

    private static Decision parse(Map<String, Object> body) { return ConsentClient.parse(body, "deny", NOW); }

    @Test void olderAnswerWithoutScopesPermitsEverything() {
        Decision d = parse(Map.of("decision", "permit", "reason", "general-consent", "consentId", "c1"));
        assertEquals(Result.PERMIT, d.result());
        assertEquals(ConsentClient.SCOPES, d.scopes());
        assertTrue(d.allows("labs"));
        assertFalse(d.limited());
        assertNull(d.validUntil());
    }

    @Test void scopesLimitWhatIsAllowed() {
        Decision d = parse(Map.of("decision", "permit", "scopes", List.of("demographics", "Encounters", "bogus")));
        assertEquals(List.of("demographics", "encounters"), d.scopes());   // standard order, unknown names ignored, case ignored
        assertTrue(d.allows("encounters"));
        assertFalse(d.allows("medications"));
        assertTrue(d.limited());
        assertTrue(d.covered());
    }

    @Test void scopesAsCommaText() {
        Decision d = parse(Map.of("decision", "permit", "scopes", "conditions, allergies"));
        assertEquals(List.of("conditions", "allergies"), d.scopes());
    }

    @Test void emptyScopeListIsAPermitThatCoversNothing() {
        Decision d = parse(Map.of("decision", "permit", "scopes", List.of()));
        assertTrue(d.permitted());
        assertFalse(d.covered());
        assertFalse(d.allows("demographics"));
    }

    @Test void denyNoneAndUnknownCoverNothing() {
        for (String s : List.of("deny", "none", "maybe", "")) {
            Decision d = parse(Map.of("decision", s, "scopes", List.of("labs")));
            assertFalse(d.permitted(), s);
            assertFalse(d.allows("labs"), s);   // a scope list on a refusal never opens anything
            assertFalse(d.covered(), s);
        }
        assertEquals(Result.NONE, parse(Map.of("decision", "none")).result());
        assertEquals(Result.UNAVAILABLE, parse(Map.of("decision", "maybe")).result());
    }

    @Test void noRecordPolicyPermitCoversEverything() {
        Decision d = ConsentClient.parse(Map.of("decision", "none"), "permit", NOW);
        assertTrue(d.permitted());
        assertEquals(ConsentClient.SCOPES, d.scopes());
    }

    @Test void expiredPermitIsADeny() {
        Decision d = parse(Map.of("decision", "permit", "validUntil", "2026-10-01T00:00:00Z", "consentId", "c1"));
        assertEquals(Result.DENY, d.result());
        assertEquals("consent-expired", d.reason());
        assertFalse(d.allows("demographics"));
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), d.validUntil());
    }

    @Test void futureAndDateOnlyValidity() {
        assertEquals(Result.PERMIT, parse(Map.of("decision", "permit", "validUntil", "2027-01-01T00:00:00Z")).result());
        assertEquals(Instant.parse("2027-01-01T00:00:00Z"), parse(Map.of("decision", "permit", "validUntil", "2027-01-01")).validUntil());
        assertEquals(Result.DENY, parse(Map.of("decision", "permit", "validUntil", "2026-10-23")).result());   // midnight today has passed
    }

    @Test void unreadableValidityIsNotAPermit() {
        Decision d = parse(Map.of("decision", "permit", "validUntil", "someday"));
        assertEquals(Result.UNAVAILABLE, d.result());
        assertFalse(d.covered());
    }

    @Test void emptyAnswerIsUnavailable() {
        assertEquals(Result.UNAVAILABLE, ConsentClient.parse(null, "deny", NOW).result());
        assertEquals(Result.UNAVAILABLE, parse(Map.of()).result());
    }

    @Test void threeArgumentDecisionKeepsTheOldMeaning() {
        assertTrue(new Decision(Result.PERMIT, "x", null).allows("labs"));
        assertFalse(new Decision(Result.DENY, "x", null).allows("labs"));
        assertTrue(new Decision(Result.PERMIT, "x", null, null, null).scopes().isEmpty());
    }
}

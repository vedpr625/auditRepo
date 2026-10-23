package com.akhester.auditui.registry;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

/** A registry that does not have, refuses or fails the Encounter list must give "not provided" or "unavailable", never an exception. */
class RegistryClientTest {

    private static final String BASE = "http://registry.test/fhir";
    private static final MediaType FHIR = MediaType.valueOf("application/fhir+json");

    private static RegistryClient client(MockRestServiceServer[] out) {
        RestTemplate rt = new RestTemplate();
        out[0] = MockRestServiceServer.bindTo(rt).build();
        return new RegistryClient(rt, BASE);
    }

    @Test void notFoundForbiddenAndBadRequestAreNotProvided() {
        for (HttpStatus s : new HttpStatus[]{HttpStatus.NOT_FOUND, HttpStatus.FORBIDDEN, HttpStatus.BAD_REQUEST, HttpStatus.UNAUTHORIZED, HttpStatus.METHOD_NOT_ALLOWED}) {
            MockRestServiceServer[] m = new MockRestServiceServer[1];
            RegistryClient c = client(m);
            m[0].expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/Encounter"))).andRespond(withStatus(s));
            Registry.Section<Registry.Encounter> r = c.encounters("P-1");
            assertEquals(Registry.Status.NOT_PROVIDED, r.status(), s.toString());
            assertTrue(r.items().isEmpty());
        }
    }

    @Test void serverErrorAndTimeoutAreUnavailable() {
        MockRestServiceServer[] m = new MockRestServiceServer[1];
        RegistryClient c = client(m);
        m[0].expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/Encounter"))).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        assertEquals(Registry.Status.UNAVAILABLE, c.encounters("P-1").status());
        m[0].reset();
        m[0].expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/Encounter"))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        assertEquals(Registry.Status.UNAVAILABLE, c.encounters("P-1").status());
    }

    @Test void operationOutcomeOrOddBodyIsNotAResult() {
        MockRestServiceServer[] m = new MockRestServiceServer[1];
        RegistryClient c = client(m);
        m[0].expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/Encounter"))).andRespond(withSuccess("{\"resourceType\":\"OperationOutcome\",\"issue\":[]}", FHIR));
        assertEquals(Registry.Status.NOT_PROVIDED, c.encounters("P-1").status());
        m[0].reset();
        m[0].expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/Encounter"))).andRespond(withSuccess("{\"hello\":1}", FHIR));
        assertEquals(Registry.Status.UNAVAILABLE, c.encounters("P-1").status());
    }

    @Test void emptyBundleIsOkAndEmpty() {
        MockRestServiceServer[] m = new MockRestServiceServer[1];
        RegistryClient c = client(m);
        m[0].expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/Encounter"))).andRespond(withSuccess("{\"resourceType\":\"Bundle\",\"type\":\"searchset\",\"total\":0}", FHIR));
        Registry.Section<Registry.Encounter> r = c.encounters("P-1");
        assertTrue(r.isOk());
        assertTrue(r.isEmpty());
    }

    @Test void searchUsesStandardParametersAndSkipsBadResources() {
        MockRestServiceServer[] m = new MockRestServiceServer[1];
        RegistryClient c = client(m);
        m[0].expect(requestTo(org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.startsWith(BASE + "/Encounter?"), org.hamcrest.Matchers.containsString("patient=Patient/P-1"),
                org.hamcrest.Matchers.containsString("_count="))))
            .andRespond(withSuccess("{\"resourceType\":\"Bundle\",\"entry\":[{\"resource\":{\"resourceType\":\"Encounter\",\"id\":\"a\",\"period\":{\"start\":\"2026-10-01T00:00:00Z\"}}},"
                + "{\"resource\":{\"resourceType\":\"Encounter\",\"id\":\"b\",\"status\":\"entered-in-error\",\"period\":{\"start\":\"2026-10-02T00:00:00Z\"}}},"
                + "{\"resource\":{\"resourceType\":\"Encounter\",\"id\":\"c\"}}]}", FHIR));
        Registry.Section<Registry.Encounter> r = c.encounters("P-1");
        assertTrue(r.isOk());
        assertEquals(1, r.items().size());                // the one in error and the resource of another type are dropped
        assertEquals("a", r.items().get(0).id());
        m[0].verify();
    }

    @Test void nextLinkIsFollowedOnlyOnTheSameRegistry() throws Exception {
        ObjectMapper om = new ObjectMapper();
        assertEquals("http://registry.test/fhir/Encounter?page=2", RegistryClient.nextLink(om.readTree("{\"link\":[{\"relation\":\"next\",\"url\":\"http://registry.test/fhir/Encounter?page=2\"}]}"), BASE).toString());
        assertNull(RegistryClient.nextLink(om.readTree("{\"link\":[{\"relation\":\"next\",\"url\":\"http://169.254.169.254/latest\"}]}"), BASE));
        assertNull(RegistryClient.nextLink(om.readTree("{\"link\":[{\"relation\":\"self\",\"url\":\"http://registry.test/fhir/Encounter\"}]}"), BASE));
        assertNull(RegistryClient.nextLink(om.readTree("{}"), BASE));
    }

    @Test void pagesAreFollowed() {
        MockRestServiceServer[] m = new MockRestServiceServer[1];
        RegistryClient c = client(m);
        m[0].expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/Encounter?")))
            .andRespond(withSuccess("{\"resourceType\":\"Bundle\",\"link\":[{\"relation\":\"next\",\"url\":\"" + BASE + "/Encounter?page=2\"}],\"entry\":[{\"resource\":{\"resourceType\":\"Encounter\",\"period\":{\"start\":\"2026-10-01\"}}}]}", FHIR));
        m[0].expect(requestTo(BASE + "/Encounter?page=2"))
            .andRespond(withSuccess("{\"resourceType\":\"Bundle\",\"entry\":[{\"resource\":{\"resourceType\":\"Encounter\",\"period\":{\"start\":\"2026-10-02\"}}}]}", FHIR));
        assertEquals(2, c.encounters("P-1").items().size());
        m[0].verify();
    }

    @Test void encountersWithoutAStartAreLeftOut_andReadByIdIsTolerant() {
        MockRestServiceServer[] m = new MockRestServiceServer[1];
        RegistryClient c = client(m);
        m[0].expect(requestTo(org.hamcrest.Matchers.startsWith(BASE + "/Encounter?")))
            .andRespond(withSuccess("{\"resourceType\":\"Bundle\",\"entry\":[{\"resource\":{\"resourceType\":\"Encounter\",\"id\":\"a\"}},{\"resource\":{\"resourceType\":\"Encounter\",\"id\":\"b\",\"period\":{\"start\":\"2026-10-01T00:00:00Z\"}}}]}", FHIR));
        assertEquals(1, c.encounters("P-1").items().size());
        m[0].reset();
        m[0].expect(requestTo(BASE + "/Encounter/E-404")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertEquals(Registry.Status.NOT_PROVIDED, c.encounter("E-404").status());
    }

    @Test void patientWithoutDemographicsConsentIsNeverRead() {
        MockRestServiceServer[] m = new MockRestServiceServer[1];
        RegistryClient c = client(m);
        // no request is expected at all: with demographics=false and stays=false nothing is read
        RegistryClient.PatientInfo info = c.patient("P-1", false, false).orElseThrow();
        assertNull(info.name());
        assertTrue(info.stays().isEmpty());
        m[0].verify();
    }

    @Test void patientForbiddenOrMissingIsEmptyNotAnError() {
        MockRestServiceServer[] m = new MockRestServiceServer[1];
        RegistryClient c = client(m);
        m[0].expect(requestTo(BASE + "/Patient/P-1")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertTrue(c.patient("P-1").isEmpty());
        m[0].reset();
        m[0].expect(requestTo(BASE + "/Patient/P-2")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertTrue(c.patient("P-2").isEmpty());
    }

    @Test void patientDownIsRegistryUnavailable() {
        MockRestServiceServer[] m = new MockRestServiceServer[1];
        RegistryClient c = client(m);
        m[0].expect(requestTo(BASE + "/Patient/P-1")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThrows(RegistryClient.RegistryUnavailable.class, () -> c.patient("P-1"));
    }
}

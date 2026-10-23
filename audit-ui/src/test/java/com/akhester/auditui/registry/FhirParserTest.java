package com.akhester.auditui.registry;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The registry readers (Patient and Encounter only) must cope with what real FHIR servers send: missing fields, other shapes, resources in error. No server, no database. */
class FhirParserTest {

    private static JsonNode json(String s) {
        try { return new ObjectMapper().readTree(s); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Test void datesInEveryFhirShape() {
        assertEquals(Instant.parse("2026-10-22T06:00:00Z"), FhirParser.instant("2026-10-22T06:00:00Z"));
        assertEquals(Instant.parse("2026-10-22T06:00:00Z"), FhirParser.instant("2026-10-22T11:30:00+05:30"));
        assertEquals(Instant.parse("2026-10-22T00:00:00Z"), FhirParser.instant("2026-10-22"));
        assertEquals(Instant.parse("2026-10-01T00:00:00Z"), FhirParser.instant("2026-10"));
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), FhirParser.instant("2026"));
        assertNull(FhirParser.instant("last Tuesday"));
        assertNull(FhirParser.instant(null));
        assertNull(FhirParser.instant(" "));
    }

    @Test void referencesGiveTheIdPart() {
        assertEquals("E-1", FhirParser.refId(json("{\"reference\":\"Encounter/E-1\"}")));
        assertEquals("E-1", FhirParser.refId(json("{\"reference\":\"https://r.example/fhir/Encounter/E-1/_history/3\"}")));
        assertEquals("E-1", FhirParser.refId(json("\"E-1\"")));
        assertNull(FhirParser.refId(json("{\"reference\":\"urn:uuid:123\"}")));
        assertNull(FhirParser.refId(json("{}")));
        assertNull(FhirParser.refId(null));
    }

    @Test void healthFieldsOfAnEncounterAreNotKept() {
        // the registry may send reasons, diagnoses and an outcome; the console reads none of them, so none can reach a page
        Registry.Encounter e = FhirParser.encounter(json("{\"resourceType\":\"Encounter\",\"id\":\"E-3\",\"reasonCode\":[{\"text\":\"secret reason\"}],"
            + "\"diagnosis\":[{\"condition\":{\"display\":\"secret diagnosis\"}}],\"hospitalization\":{\"dischargeDisposition\":{\"text\":\"secret outcome\"}},\"period\":{\"start\":\"2026-10-22\"}}"));
        assertNotNull(e);
        assertFalse(e.toString().contains("secret"));
    }

    @Test void encounterWithEverythingMissing() {
        Registry.Encounter e = FhirParser.encounter(json("{\"resourceType\":\"Encounter\",\"id\":\"e1\"}"));
        assertNotNull(e);
        assertNull(e.start());
        assertNull(e.facilityRef());
        assertEquals("other", e.kind());
        assertTrue(e.practitioners().isEmpty());
    }

    @Test void encounterWardFacilityAndAttending() {
        Registry.Encounter e = FhirParser.encounter(json("{\"resourceType\":\"Encounter\",\"id\":\"E-1\",\"status\":\"finished\",\"class\":{\"system\":\"x\",\"code\":\"IMP\",\"display\":\"inpatient encounter\"},"
            + "\"type\":[{\"text\":\"Inpatient admission\"}],\"subject\":{\"reference\":\"Patient/P-1\"},\"period\":{\"start\":\"2026-10-18T10:00:00Z\",\"end\":\"2026-10-21T10:00:00Z\"},"
            + "\"reasonCode\":[{\"text\":\"Fever\"}],\"participant\":[{\"type\":[{\"coding\":[{\"code\":\"CON\"}]}],\"individual\":{\"display\":\"Dr. B\"}},{\"type\":[{\"coding\":[{\"code\":\"ATND\"}]}],\"individual\":{\"display\":\"Dr. A\"}}],"
            + "\"diagnosis\":[{\"condition\":{\"reference\":\"Condition/c1\",\"display\":\"Pneumonia\"},\"use\":{\"coding\":[{\"code\":\"AD\"}]},\"rank\":1}],"
            + "\"location\":[{\"location\":{\"reference\":\"Device/hhims-colombo-01\",\"display\":\"Colombo NH\"}},{\"location\":{\"reference\":\"Location/w7\",\"display\":\"Ward 7\"},\"physicalType\":{\"coding\":[{\"code\":\"wa\"}]}}],"
            + "\"serviceProvider\":{\"display\":\"National Hospital\"}}"));
        assertEquals("inpatient", e.kind());
        assertEquals("Ward 7", e.ward());
        assertEquals("Device/hhims-colombo-01", e.facilityRef());
        assertEquals("Dr. A", e.attending());           // the participant of type ATND, not the first one
        assertEquals(List.of("Dr. B"), e.others());
        assertEquals("P-1", e.patientId());
        assertFalse(e.current());
    }

    @Test void encounterFacilityFallsBackToServiceProvider_andClassAsList() {
        Registry.Encounter e = FhirParser.encounter(json("{\"resourceType\":\"Encounter\",\"id\":\"E-2\",\"class\":[{\"coding\":[{\"code\":\"AMB\",\"display\":\"ambulatory\"}]}],"
            + "\"serviceProvider\":{\"reference\":\"Organization/colombo\",\"display\":\"Colombo NH\"},\"serviceType\":{\"text\":\"OPD-3\"},\"period\":{\"start\":\"2026-10-22\"}}"));
        assertEquals("outpatient", e.kind());
        assertEquals("Organization/colombo", e.facilityRef());
        assertEquals("OPD-3", e.ward());
        assertTrue(e.current());                        // no end and not finished
    }

    @Test void bundleReaderSkipsOtherTypesAndNonBundles() {
        JsonNode b = json("{\"resourceType\":\"Bundle\",\"entry\":[{\"resource\":{\"resourceType\":\"OperationOutcome\"}},{\"resource\":{\"resourceType\":\"Encounter\",\"id\":\"e\"}},{}]}");
        assertEquals(1, FhirParser.resources(b, "Encounter").size());
        assertTrue(FhirParser.resources(json("{\"resourceType\":\"OperationOutcome\"}"), "Encounter").isEmpty());
        assertTrue(FhirParser.resources(null, "Encounter").isEmpty());
    }
}

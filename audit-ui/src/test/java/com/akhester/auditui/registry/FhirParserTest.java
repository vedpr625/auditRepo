package com.akhester.auditui.registry;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The registry readers must cope with what real FHIR servers send: missing fields, other shapes, resources in error. No server, no database. */
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

    @Test void conditionWithOnlyACode() {
        Clinical.Condition c = FhirParser.condition(json("{\"resourceType\":\"Condition\",\"id\":\"c1\",\"code\":{\"coding\":[{\"system\":\"http://hl7.org/fhir/sid/icd-10\",\"code\":\"E11.9\"}]}}"));
        assertNotNull(c);
        assertEquals("E11.9", c.text());          // no text and no display: the code is shown
        assertEquals("E11.9", c.code());
        assertNull(c.onset());
        assertTrue(c.active());                   // no status and no end date: not claimed to be over
    }

    @Test void conditionStatusAndEndDecideActive() {
        assertFalse(FhirParser.condition(json("{\"resourceType\":\"Condition\",\"code\":{\"text\":\"x\"},\"clinicalStatus\":{\"coding\":[{\"code\":\"resolved\"}]}}")).active());
        assertFalse(FhirParser.condition(json("{\"resourceType\":\"Condition\",\"code\":{\"text\":\"x\"},\"abatementDateTime\":\"2020-01-01\"}")).active());
        assertTrue(FhirParser.condition(json("{\"resourceType\":\"Condition\",\"code\":{\"text\":\"x\"},\"clinicalStatus\":{\"coding\":[{\"code\":\"active\"}]}}")).active());
    }

    @Test void resourcesWithoutNameOrInError_areSkipped() {
        assertNull(FhirParser.condition(json("{\"resourceType\":\"Condition\",\"id\":\"c\"}")));
        assertNull(FhirParser.condition(json("{\"resourceType\":\"Condition\",\"code\":{\"text\":\"x\"},\"verificationStatus\":{\"coding\":[{\"code\":\"entered-in-error\"}]}}")));
        assertNull(FhirParser.medication(json("{\"resourceType\":\"MedicationRequest\",\"status\":\"entered-in-error\",\"medicationCodeableConcept\":{\"text\":\"m\"}}")));
        assertNull(FhirParser.condition(json("{\"resourceType\":\"Observation\"}")));   // wrong type
        assertNull(FhirParser.condition(null));
    }

    @Test void medicationNameFromReferenceAndDoseFromQuantity() {
        Clinical.Medication m = FhirParser.medication(json("{\"resourceType\":\"MedicationRequest\",\"status\":\"active\",\"medicationReference\":{\"display\":\"Amlodipine 5 mg tablet\"},"
            + "\"dosageInstruction\":[{\"doseAndRate\":[{\"doseQuantity\":{\"value\":5,\"unit\":\"mg\"}}]}],\"encounter\":{\"reference\":\"Encounter/E-9\"}}"));
        assertEquals("Amlodipine 5 mg tablet", m.text());
        assertEquals("5 mg", m.dose());
        assertEquals("E-9", m.encounterId());
        assertTrue(m.active());
        assertNull(m.authoredOn());
    }

    @Test void allergyNoKnownIsRecognised() {
        assertTrue(FhirParser.allergy(json("{\"resourceType\":\"AllergyIntolerance\",\"code\":{\"coding\":[{\"system\":\"http://snomed.info/sct\",\"code\":\"716186003\",\"display\":\"No known allergy\"}]}}")).noKnown());
        Clinical.Allergy a = FhirParser.allergy(json("{\"resourceType\":\"AllergyIntolerance\",\"criticality\":\"high\",\"code\":{\"text\":\"Penicillin\"},\"reaction\":[{\"manifestation\":[{\"text\":\"Anaphylaxis\"}],\"severity\":\"severe\"}]}"));
        assertFalse(a.noKnown());
        assertTrue(a.high());
        assertEquals("Anaphylaxis", a.reaction());
    }

    @Test void observationInterpretationFromTheRegistry() {
        Clinical.Observation o = FhirParser.observation(json("{\"resourceType\":\"Observation\",\"status\":\"final\",\"category\":[{\"coding\":[{\"code\":\"laboratory\"}]}],"
            + "\"code\":{\"coding\":[{\"system\":\"http://loinc.org\",\"code\":\"718-7\",\"display\":\"Hemoglobin\"}]},\"effectiveDateTime\":\"2026-10-20T04:00:00Z\","
            + "\"valueQuantity\":{\"value\":11.9,\"unit\":\"g/dL\"},\"referenceRange\":[{\"low\":{\"value\":13},\"high\":{\"value\":17}}],"
            + "\"interpretation\":[{\"coding\":[{\"code\":\"L\"}]}]}"));
        assertEquals("L", o.flag());
        assertEquals(11.9, o.value());
        assertEquals("11.9", o.display());
        assertEquals("13–17", o.range());
        assertEquals("718-7", o.code());
        assertTrue(o.lab());
        assertTrue(o.abnormal());
    }

    @Test void observationFlagWorkedOutFromTheRangeWhenTheRegistryGivesNone() {
        String t = "{\"resourceType\":\"Observation\",\"code\":{\"text\":\"Glucose\"},\"valueQuantity\":{\"value\":%s},\"referenceRange\":[{\"low\":{\"value\":70},\"high\":{\"value\":140}}]}";
        assertEquals("H", FhirParser.observation(json(t.formatted("164"))).flag());
        assertEquals("L", FhirParser.observation(json(t.formatted("38"))).flag());
        assertEquals("N", FhirParser.observation(json(t.formatted("92"))).flag());
    }

    @Test void observationWithoutRangeOrInterpretationHasNoFlag() {
        Clinical.Observation o = FhirParser.observation(json("{\"resourceType\":\"Observation\",\"code\":{\"text\":\"Weight\"},\"valueQuantity\":{\"value\":84.5,\"unit\":\"kg\"}}"));
        assertNull(o.flag());
        assertNull(o.range());
        assertNull(o.at());
        assertFalse(o.abnormal());
    }

    @Test void bloodPressureComponentsBecomeOneRow() {
        Clinical.Observation o = FhirParser.observation(json("{\"resourceType\":\"Observation\",\"code\":{\"text\":\"Blood pressure\",\"coding\":[{\"system\":\"http://loinc.org\",\"code\":\"85354-9\"}]},"
            + "\"component\":[{\"code\":{\"text\":\"Systolic\"},\"valueQuantity\":{\"value\":148,\"unit\":\"mm[Hg]\"},\"referenceRange\":[{\"low\":{\"value\":90},\"high\":{\"value\":140}}]},"
            + "{\"code\":{\"text\":\"Diastolic\"},\"valueQuantity\":{\"value\":92,\"unit\":\"mm[Hg]\"},\"referenceRange\":[{\"low\":{\"value\":60},\"high\":{\"value\":90}}]}]}"));
        assertEquals("148/92", o.display());
        assertEquals(148.0, o.value());
        assertEquals("H", o.flag());
        assertTrue(o.vital());          // no category given: a known vital-sign code
        assertEquals("mmHg", o.unitLabel());
    }

    @Test void observationWithoutAValueIsNotAResult() {
        assertNull(FhirParser.observation(json("{\"resourceType\":\"Observation\",\"code\":{\"text\":\"Pending test\"},\"status\":\"registered\"}")));
        assertNull(FhirParser.observation(json("{\"resourceType\":\"Observation\",\"code\":{\"text\":\"x\"},\"status\":\"cancelled\",\"valueString\":\"y\"}")));
        assertEquals("positive", FhirParser.observation(json("{\"resourceType\":\"Observation\",\"code\":{\"text\":\"Dengue NS1\"},\"valueString\":\"positive\"}")).display());
    }

    @Test void encounterWithEverythingMissing() {
        Clinical.Encounter e = FhirParser.encounter(json("{\"resourceType\":\"Encounter\",\"id\":\"e1\"}"));
        assertNotNull(e);
        assertNull(e.start());
        assertNull(e.facilityRef());
        assertEquals("other", e.kind());
        assertTrue(e.practitioners().isEmpty());
        assertTrue(e.diagnoses().isEmpty());
    }

    @Test void encounterWardFacilityAttendingAndDiagnosis() {
        Clinical.Encounter e = FhirParser.encounter(json("{\"resourceType\":\"Encounter\",\"id\":\"E-1\",\"status\":\"finished\",\"class\":{\"system\":\"x\",\"code\":\"IMP\",\"display\":\"inpatient encounter\"},"
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
        assertEquals("Pneumonia", e.diagnoses().get(0).text());
        assertEquals("Fever", e.reason());
        assertFalse(e.current());
    }

    @Test void encounterFacilityFallsBackToServiceProvider_andClassAsList() {
        Clinical.Encounter e = FhirParser.encounter(json("{\"resourceType\":\"Encounter\",\"id\":\"E-2\",\"class\":[{\"coding\":[{\"code\":\"AMB\",\"display\":\"ambulatory\"}]}],"
            + "\"serviceProvider\":{\"reference\":\"Organization/colombo\",\"display\":\"Colombo NH\"},\"serviceType\":{\"text\":\"OPD-3\"},\"period\":{\"start\":\"2026-10-22\"}}"));
        assertEquals("outpatient", e.kind());
        assertEquals("Organization/colombo", e.facilityRef());
        assertEquals("OPD-3", e.ward());
        assertTrue(e.current());                        // no end and not finished
    }

    @Test void bundleReaderSkipsOtherTypesAndNonBundles() {
        JsonNode b = json("{\"resourceType\":\"Bundle\",\"entry\":[{\"resource\":{\"resourceType\":\"OperationOutcome\"}},{\"resource\":{\"resourceType\":\"Condition\",\"id\":\"c\"}},{}]}");
        assertEquals(1, FhirParser.resources(b, "Condition").size());
        assertTrue(FhirParser.resources(json("{\"resourceType\":\"OperationOutcome\"}"), "Condition").isEmpty());
        assertTrue(FhirParser.resources(null, "Condition").isEmpty());
    }

    @Test void flagCodes() {
        assertEquals("H", FhirParser.flag("HH"));
        assertEquals("L", FhirParser.flag("LL"));
        assertEquals("A", FhirParser.flag("AA"));
        assertEquals("N", FhirParser.flag("n"));
        assertNull(FhirParser.flag("POS"));
        assertNull(FhirParser.flag(null));
    }
}

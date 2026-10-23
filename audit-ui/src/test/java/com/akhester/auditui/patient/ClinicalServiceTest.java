package com.akhester.auditui.patient;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.akhester.auditui.registry.Clinical;

/** Sparklines, trends and the arrangement of a Clinical 360 when parts are missing. Plain functions, no registry. */
class ClinicalServiceTest {

    private static final ClinicalService SVC = new ClinicalService(null);
    private static final ZoneId Z = ZoneId.of("Asia/Colombo");

    private static Clinical.Observation obs(String code, String text, double v, String flag, Double lo, Double hi, String at, String enc, String cat) {
        return new Clinical.Observation("o", code, text, cat, v, String.valueOf(v), "u", lo, hi, flag, Instant.parse(at), "final", enc);
    }

    @Test void trendWords() {
        assertNull(ClinicalService.trend(List.of(1.0)));
        assertNull(ClinicalService.trend(null));
        assertEquals("rising", ClinicalService.trend(List.of(10.0, 12.0)));
        assertEquals("falling", ClinicalService.trend(List.of(12.0, 8.0)));
        assertEquals("steady", ClinicalService.trend(List.of(100.0, 101.0)));
    }

    @Test void sparkNeedsTwoNumbersAndStaysInsideTheBox() {
        assertNull(ClinicalService.spark(List.of(5.0), List.of(Instant.EPOCH), null, null));
        ClinicalService.Spark s = ClinicalService.spark(List.of(1.0, 5.0, 3.0), List.of(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-02T00:00:00Z"), Instant.parse("2026-10-05T00:00:00Z")), 2.0, 4.0);
        assertNotNull(s);
        for (String p : s.points().split(" ")) {
            String[] xy = p.split(",");
            double x = Double.parseDouble(xy[0]), y = Double.parseDouble(xy[1]);
            assertTrue(x >= 0 && x <= ClinicalService.W && y >= 0 && y <= ClinicalService.H, p);
        }
        assertNotNull(s.bandTop());
        assertTrue(s.bandHeight() >= 1);
    }

    @Test void sparkOfIdenticalValuesAndSameTimeDoesNotDivideByZero() {
        Instant t = Instant.parse("2026-10-01T00:00:00Z");
        ClinicalService.Spark s = ClinicalService.spark(List.of(7.0, 7.0), List.of(t, t), null, null);
        assertNotNull(s);
        assertFalse(s.points().contains("NaN"));
        assertNull(s.bandTop());
    }

    @Test void latestPreviousAndTrendPerTest() {
        List<ClinicalService.Result> r = ClinicalService.results(List.of(
            obs("718-7", "Hemoglobin", 11.9, "L", 13.0, 17.0, "2026-10-18T00:00:00Z", "E-1", "laboratory"),
            obs("718-7", "Hemoglobin", 12.6, "L", 13.0, 17.0, "2026-10-21T00:00:00Z", "E-1", "laboratory"),
            obs("8867-4", "Heart rate", 80, "N", 60.0, 100.0, "2026-10-21T00:00:00Z", "E-1", "vital-signs")));
        assertEquals(2, r.size());
        ClinicalService.Result hb = r.stream().filter(x -> x.text().equals("Hemoglobin")).findFirst().orElseThrow();
        assertEquals("12.6", hb.latest());
        assertEquals("11.9", hb.previous());
        assertEquals("rising", hb.trend());
        assertEquals("L", hb.flag());
        assertEquals(2, hb.count());
        assertNotNull(hb.spark());
        ClinicalService.Result hr = r.stream().filter(x -> x.text().equals("Heart rate")).findFirst().orElseThrow();
        assertTrue(hr.vital());
        assertNull(hr.spark());   // one value: no line
    }

    @Test void everythingMissingStillArranges() {
        // consent covered nothing (all sections null) and the registry gave no encounters
        ClinicalService.View v = SVC.arrange("P-1", null, null, null, null, null, null, List.of(), Z);
        assertTrue(v.timeline().isEmpty());
        assertTrue(v.vitals().isEmpty());
        assertTrue(v.activeConditions().isEmpty());
        assertFalse(v.noKnownAllergy());
        assertNull(v.current());
    }

    @Test void sectionsThatWereNotProvidedAreKeptAsTheyAre() {
        ClinicalService.View v = SVC.arrange("P-1", Clinical.Section.notProvided(), Clinical.Section.notProvided(), Clinical.Section.unavailable(), Clinical.Section.notProvided(),
            Clinical.Section.notProvided(), Clinical.Section.notProvided(), List.of(), Z);
        assertEquals(Clinical.Status.NOT_PROVIDED, v.allergies().status());
        assertEquals(Clinical.Status.UNAVAILABLE, v.medications().status());
        assertTrue(v.encounterRows().isEmpty());
    }

    @Test void activeAndPastConditionsAndNoKnownAllergy() {
        Clinical.Condition active = new Clinical.Condition("1", "Diabetes", "E11.9", "active", Instant.parse("2019-01-01T00:00:00Z"), null, null, null);
        Clinical.Condition gone = new Clinical.Condition("2", "Pneumonia", "J18.9", "resolved", Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-05T00:00:00Z"), null, "E-1");
        Clinical.Allergy none = new Clinical.Allergy("a", "No known allergies", null, "active", null, null, true, null);
        ClinicalService.View v = SVC.arrange("P-1", null, Clinical.Section.ok(List.of(active, gone)), null, null, null, Clinical.Section.ok(List.of(none)), List.of(), Z);
        assertEquals(1, v.activeConditions().size());
        assertEquals(1, v.pastConditions().size());
        assertTrue(v.noKnownAllergy());
        assertTrue(v.allergyList().isEmpty());
    }

    @Test void resultsAndProceduresJoinTheTimelineAndUndatedOnesAreKept() {
        Clinical.Encounter e = new Clinical.Encounter("E-1", "P-1", "finished", "AMB", "ambulatory", "Outpatient", "Review", Instant.parse("2026-10-20T04:00:00Z"), Instant.parse("2026-10-20T06:00:00Z"),
            "Device/hhims-colombo-01", "Colombo NH", "OPD-3", "Dr. A", List.of("Dr. A"), List.of(), "Colombo NH", null);
        Clinical.Procedure datedless = new Clinical.Procedure("p", "Foot exam", null, null, "completed", null, "E-1");
        Clinical.Procedure other = new Clinical.Procedure("p2", "X-ray", null, Instant.parse("2026-09-01T00:00:00Z"), "completed", null, null);
        ClinicalService.View v = SVC.arrange("P-1", Clinical.Section.ok(List.of(e)), null, null,
            Clinical.Section.ok(List.of(obs("2345-7", "Glucose", 164, "H", 70.0, 140.0, "2026-10-20T05:00:00Z", "E-1", "laboratory"), obs("718-7", "Hb", 14, "N", 13.0, 17.0, "2026-10-20T05:00:00Z", "E-1", "laboratory"))),
            Clinical.Section.ok(List.of(datedless, other)), null, List.of(), Z);
        assertEquals(1, v.encounterRows().size());
        assertEquals("/encounter/E-1?patient=P-1", v.encounterRows().get(0).link());
        List<ClinicalService.Item> t = v.timeline();
        assertEquals(4, t.size());   // visit, one results entry for the visit, two procedures
        ClinicalService.Item results = t.stream().filter(i -> i.kind().equals("result")).findFirst().orElseThrow();
        assertEquals(2, results.labs());
        assertEquals(1, results.outside());
        assertEquals("encounter", results.linkKind());
        ClinicalService.Item xray = t.stream().filter(i -> "X-ray".equals(i.title())).findFirst().orElseThrow();
        assertEquals("day", xray.linkKind());
        assertEquals("Foot exam", t.get(t.size() - 1).title());   // the undated one goes last
    }

    @Test void encounterKinds() {
        assertEquals("emergency", new Clinical.Encounter("e", null, null, "EMER", null, null, null, null, null, null, null, null, null, List.of(), List.of(), null, null).kind());
        assertEquals("inpatient", new Clinical.Encounter("e", null, null, null, "Inpatient encounter", null, null, null, null, null, null, null, null, List.of(), List.of(), null, null).kind());
    }
}

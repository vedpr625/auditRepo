package com.akhester.auditui.breakglass;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BreakGlassUnitTest {
    @Test void csvCellNeutralisesFormulasAndQuotes() {
        assertEquals("\"'=SUM(A1)\"", BreakGlassController.cell("=SUM(A1)"));
        assertEquals("\"a \"\"b\"\"\"", BreakGlassController.cell("a \"b\""));
        assertEquals("", BreakGlassController.cell(null));
    }
    @Test void minutesRead() {
        assertEquals("38 min", BreakGlassService.minutes(38L));
        assertEquals("2 h 5 min", BreakGlassService.minutes(125L));
        assertEquals("—", BreakGlassService.minutes(null));
    }
}

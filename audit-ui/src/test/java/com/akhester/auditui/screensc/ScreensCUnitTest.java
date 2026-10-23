package com.akhester.auditui.screensc;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.akhester.auditui.admin.ChecklistService;
import com.akhester.auditui.people.Who;
import com.akhester.auditui.reports.ReportFile;

/** Plain unit tests for the Reports, People, Admin and Checklist helpers. */
class ScreensCUnitTest {

    @Test void csvCellsAreQuotedAndFormulasNeutralised() {
        assertEquals("\"a\"\"b\"", ReportFile.cell("a\"b"));
        assertEquals("\"'=SUM(A1)\"", ReportFile.cell("=SUM(A1)"));
        assertEquals("\"'@x\"", ReportFile.cell("@x"));
        assertEquals("\"\"", ReportFile.cell(null));
    }

    @Test void roleIsReadFromReferenceAndName() {
        assertEquals("physician", Who.role("Practitioner/dr-silva", "Dr. A. Silva"));
        assertEquals("nurse", Who.role("Practitioner/nurse-silva", "Nurse S. Silva"));
        assertEquals("system", Who.role("Device/lab-gateway", "lab-gateway"));
        assertEquals("unknown", Who.role(null, "x"));
    }

    @Test void checklistSaysNotBuiltWhenRoutesAreMissing() {
        var item = new ChecklistService.Item(1, false, ChecklistService.Kind.BUILT, true, new String[]{"/a", "/b"});
        assertEquals("notyet", ChecklistService.status(item, Set.of("/a")).status());
        assertEquals("built", ChecklistService.status(item, Set.of("/a", "/b")).status());
        var unchecked = new ChecklistService.Item(2, false, ChecklistService.Kind.BUILT, false, new String[]{});
        assertEquals("builtunchecked", ChecklistService.status(unchecked, Set.of()).status());
        var planned = new ChecklistService.Item(3, false, ChecklistService.Kind.PLANNED, false, new String[]{"/zzz"});
        assertEquals("planned", ChecklistService.status(planned, Set.of()).status());
    }

    @Test void everyChecklistItemHasItsTextInTheMessageFile() throws Exception {
        java.util.Properties p = new java.util.Properties();
        try (var in = getClass().getResourceAsStream("/messages-admin.properties")) { p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)); }
        for (ChecklistService.Item it : ChecklistService.ITEMS)
            for (String k : List.of("id", "req", "where", "board", "rel", "note")) assertTrue(p.containsKey("checklist.i" + it.n() + "." + k), "i" + it.n() + "." + k);
    }
}

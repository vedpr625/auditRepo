package com.akhester.auditui.admin;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** What must be delivered against what this build has. Open to every signed-in person: it describes the product, not any record. */
@Controller
public class ChecklistController {

    private final ChecklistService checklist;

    public ChecklistController(ChecklistService checklist) { this.checklist = checklist; }

    @GetMapping("/checklist")
    public String page(Model model) {
        var lines = checklist.lines();
        model.addAttribute("lines", lines);
        Map<String, Long> counts = new TreeMap<>(lines.stream().collect(Collectors.groupingBy(ChecklistService.Line::status, Collectors.counting())));
        model.addAttribute("counts", counts);
        return "checklist";
    }
}

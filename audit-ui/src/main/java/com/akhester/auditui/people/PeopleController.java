package com.akhester.auditui.people;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.akhester.auditui.admin.SendingSystemRepository;

/** People and patients: who appears in the records, and which patients, with counts. Patients link to the patient page. */
@Controller
public class PeopleController {

    private static final int PAGE = 50;
    private final PeopleIndex index;
    private final FacilityDirectory facilities;

    public PeopleController(PeopleIndex index, FacilityDirectory facilities) { this.index = index; this.facilities = facilities; }

    @GetMapping("/people")
    public String page(@RequestParam(required = false) String tab, @RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int p, Model model) {
        String t = "patients".equals(tab) ? "patients" : "people";
        String needle = q == null ? "" : q.strip();
        if (needle.length() > 100) needle = needle.substring(0, 100);
        needle = needle.replaceAll("\\p{Cntrl}", "");
        PeopleIndex.Snapshot s = index.snapshot();
        String n = needle.toLowerCase(Locale.ROOT);
        List<PeopleIndex.Person> people = s.people().stream()
            .filter(x -> n.isEmpty() || (x.name() != null && x.name().toLowerCase(Locale.ROOT).contains(n)) || (x.ref() != null && x.ref().toLowerCase(Locale.ROOT).contains(n))).toList();
        List<PeopleIndex.PatientEntry> pats = s.patients().stream().filter(x -> n.isEmpty() || x.id().toLowerCase(Locale.ROOT).contains(n)).toList();
        int total = "patients".equals(t) ? pats.size() : people.size();
        int pages = Math.max(1, (total + PAGE - 1) / PAGE);
        int page = Math.max(1, Math.min(p, pages));
        model.addAttribute("tab", t);
        model.addAttribute("q", needle);
        model.addAttribute("s", s);
        model.addAttribute("people", people.subList(Math.min((page - 1) * PAGE, people.size()), Math.min(page * PAGE, people.size())));
        model.addAttribute("patients", pats.subList(Math.min((page - 1) * PAGE, pats.size()), Math.min(page * PAGE, pats.size())));
        model.addAttribute("peopleTotal", people.size());
        model.addAttribute("patientsTotal", pats.size());
        model.addAttribute("allPeople", s.people().size());
        model.addAttribute("allPatients", s.patients().size());
        model.addAttribute("page", page);
        model.addAttribute("pages", pages);
        model.addAttribute("fac", facilities.names());
        model.addAttribute("canReports", true);
        return "people";
    }
}

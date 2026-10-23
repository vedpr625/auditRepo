package com.akhester.auditui.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.akhester.auditui.model.AuditRow;
import com.akhester.auditui.prefs.PrefsContext;
import com.akhester.auditui.service.AuditEventService;

/** Admin: sending systems. Day-to-day Keycloak user management is planned (shown as such), not built. */
@Controller
public class AdminController {

    private final SendingSystemRepository systems;
    private final AuditEventService audit;

    public AdminController(SendingSystemRepository systems, AuditEventService audit) {
        this.systems = systems;
        this.audit = audit;
    }

    @GetMapping("/admin")
    public String page(Model model) {
        model.addAttribute("systems", systems.all());
        return "admin";
    }

    /** Looks at the newest records Chester will show this person and remembers each sending client id it finds. */
    @PostMapping("/admin/systems/discover")
    public String discover(RedirectAttributes ra) {
        try {
            List<AuditRow> rows = audit.recentWhere(Map.of(), 100, "-date");
            Set<String> found = new TreeSet<>();
            for (AuditRow r : rows) {
                if (r.getSubmittedBy() == null) continue;
                systems.seen(r.getSubmittedBy(), r.getRecordedInstant() == null ? Instant.now() : r.getRecordedInstant(), PrefsContext.username());
                found.add(r.getSubmittedBy());
            }
            ra.addFlashAttribute("flash", "discovered");
            ra.addFlashAttribute("flashCount", found.size());
        } catch (RuntimeException e) {
            ra.addFlashAttribute("flash", "failed");
        }
        return "redirect:/admin";
    }

    @PostMapping("/admin/systems/rename")
    public String rename(@RequestParam String clientId, @RequestParam(required = false) String displayName,
                         @RequestParam(required = false) String note, RedirectAttributes ra) {
        systems.rename(clientId, displayName, note, PrefsContext.username());
        ra.addFlashAttribute("flash", "saved");
        return "redirect:/admin";
    }
}

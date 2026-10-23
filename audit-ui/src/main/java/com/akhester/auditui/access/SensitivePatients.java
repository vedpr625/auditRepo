package com.akhester.auditui.access;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Patients the Ministry has marked sensitive (staff, VIPs). Demo: the list comes from audit.ui.sensitive-patient-ids; a real marker will come from the registry. */
@Component
public class SensitivePatients {

    private final Set<String> ids;

    public SensitivePatients(@Value("${audit.ui.sensitive-patient-ids:}") String list) {
        this.ids = Arrays.stream(list.split(",")).map(String::strip).filter(s -> !s.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    /** id is "P-20881" or "Patient/P-20881". */
    public boolean isSensitive(String id) {
        if (id == null) return false;
        return ids.contains(id.startsWith("Patient/") ? id.substring(8) : id);
    }

    /** True when this person must not see the record: it is sensitive and they are not a privacy officer. */
    public boolean restrictedFor(String id) { return isSensitive(id) && !AccessPolicy.canOpenSensitive(); }

    public Set<String> ids() { return ids; }
}

package com.akhester.auditui.reviews;

import java.util.Map;

/** Readable names for the facilities the made-up demo records come from. Unknown references are shown as they are. */
public final class Labels {

    private static final Map<String, String> FACILITY = Map.ofEntries(
        Map.entry("Device/hhims-colombo-01", "Colombo NH"), Map.entry("Device/hhims-kandy-01", "Kandy TH"),
        Map.entry("Device/hhims-galle-01", "Galle TH"), Map.entry("Device/hhims-jaffna-01", "Jaffna TH"),
        Map.entry("Device/lablink-gw-01", "LabLink"), Map.entry("Device/pharma-galle-01", "PharmaLink Galle"),
        Map.entry("Device/echannel-svc-01", "e-Channel"), Map.entry("Device/pacs-01", "Radiology PACS"),
        Map.entry("Device/moh-etl-01", "MoH analytics"), Map.entry("Device/research-portal-01", "Research portal"));

    private Labels() {}

    public static String facility(String ref) {
        if (ref == null || ref.isBlank() || "—".equals(ref)) return "";
        return FACILITY.getOrDefault(ref, ref);
    }
}

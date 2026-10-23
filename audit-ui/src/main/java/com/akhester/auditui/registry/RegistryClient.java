package com.akhester.auditui.registry;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import com.akhester.auditui.page.AppSettingRepository;
import com.fasterxml.jackson.databind.JsonNode;

/** Reads a patient and the patient's encounters from a plain FHIR R4 registry (Bundle 2a). The base address comes from Settings, or from audit.ui.registry-base-url. */
@Component
public class RegistryClient {

    public record Stay(String facilityRef, String facilityName, String ward, Instant from, Instant to) {
        public boolean current() { return to == null; }
    }

    public record PatientInfo(String id, String name, String sex, LocalDate birthDate, String idMasked, List<Stay> stays) {
        public String ageBand() {
            if (birthDate == null) return null;
            int y = Period.between(birthDate, LocalDate.now()).getYears();
            return (y / 10 * 10) + "–" + (y / 10 * 10 + 9);
        }
        public boolean wasAt(String facilityRef) { return stays.stream().anyMatch(s -> s.facilityRef().equals(facilityRef)); }
    }

    public static class RegistryUnavailable extends RuntimeException {
        public RegistryUnavailable(String m) { super(m); }
    }

    private final RestTemplate http;
    private final AppSettingRepository settings;
    private final String fallbackBase;

    public RegistryClient(RestTemplateBuilder builder, AppSettingRepository settings,
                          @Value("${audit.ui.registry-base-url:}") String fallbackBase) {
        this.http = builder.setConnectTimeout(Duration.ofSeconds(3)).setReadTimeout(Duration.ofSeconds(5)).build();
        this.settings = settings;
        this.fallbackBase = fallbackBase;
    }

    private String base() {
        String b = settings.get(AppSettingRepository.REGISTRY_BASE_URL).filter(s -> !s.isBlank()).orElse(fallbackBase);
        return b == null || b.isBlank() ? null : b.replaceAll("/+$", "");
    }

    public boolean configured() { return base() != null; }

    /** Empty when the registry does not know the patient. Throws RegistryUnavailable when it cannot be reached. */
    public Optional<PatientInfo> patient(String id) {
        String b = base();
        if (b == null) throw new RegistryUnavailable("not-configured");
        try {
            JsonNode p = http.getForObject(b + "/Patient/{id}", JsonNode.class, id);
            if (p == null || !"Patient".equals(p.path("resourceType").asText())) throw new RegistryUnavailable("not-fhir");
            JsonNode n = p.path("name").path(0);
            String name = n.path("text").asText(null);
            if (name == null) name = (n.path("given").path(0).asText("") + " " + n.path("family").asText("")).trim();
            String bd = p.path("birthDate").asText(null);
            String ident = p.path("identifier").path(0).path("value").asText(null);
            List<Stay> stays = new ArrayList<>();
            JsonNode enc = http.getForObject(b + "/Encounter?patient={id}&_sort=-date", JsonNode.class, "Patient/" + id);
            for (JsonNode e : enc == null ? List.<JsonNode>of() : enc.path("entry")) {
                JsonNode r = e.path("resource"), loc = r.path("location").path(0);
                String period = r.path("period").path("end").asText(null);
                stays.add(new Stay(loc.path("location").path("reference").asText(""), loc.path("location").path("display").asText(""),
                    r.path("serviceType").path("text").asText(null), Instant.parse(r.path("period").path("start").asText()),
                    period == null ? null : Instant.parse(period)));
            }
            return Optional.of(new PatientInfo(id, name, p.path("gender").asText(null), bd == null ? null : LocalDate.parse(bd), ident, stays));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RegistryUnavailable e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RegistryUnavailable("unreachable");
        }
    }
}

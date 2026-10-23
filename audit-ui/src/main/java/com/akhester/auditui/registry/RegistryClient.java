package com.akhester.auditui.registry;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.akhester.auditui.page.AppSettingRepository;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Reads a patient and the patient's encounters from a plain FHIR R4 registry (Bundle 2a): Patient and Encounter only, using only standard read and search
 * (patient=, _count, _sort, paging by the Bundle's "next" link). Nothing about health (conditions, medicines, results, allergies, procedures) is ever requested.
 * The base address comes from Settings, or from audit.ui.registry-base-url.
 *
 * <p>Fail-safe: a resource type the registry does not have, or refuses (404, 403, 400...), gives a {@link Registry.Section} with status NOT_PROVIDED,
 * and an address that does not answer gives UNAVAILABLE. Nothing here throws to the screen except {@link #patient} (RegistryUnavailable), which the caller handles.
 * This class does not decide whether a person may see the data: callers ask the consent service first.
 */
@Component
public class RegistryClient {

    public record Stay(String facilityRef, String facilityName, String ward, Instant from, Instant to, String id) {
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

    /** Most Bundle pages followed for one search, and the page size asked for. */
    static final int MAX_PAGES = 5, PAGE = 50;

    private final RestTemplate http;
    private final AppSettingRepository settings;
    private final String fallbackBase;

    @org.springframework.beans.factory.annotation.Autowired
    public RegistryClient(RestTemplateBuilder builder, AppSettingRepository settings,
                          @Value("${audit.ui.registry-base-url:}") String fallbackBase) {
        this.http = builder.setConnectTimeout(Duration.ofSeconds(3)).setReadTimeout(Duration.ofSeconds(5)).build();
        this.settings = settings;
        this.fallbackBase = fallbackBase;
    }

    /** For tests: a prepared RestTemplate and a fixed address. */
    RegistryClient(RestTemplate http, String base) {
        this.http = http;
        this.settings = null;
        this.fallbackBase = base;
    }

    private String base() {
        String b = null;
        try { b = settings.get(AppSettingRepository.REGISTRY_BASE_URL).filter(s -> !s.isBlank()).orElse(fallbackBase); } catch (RuntimeException e) { b = fallbackBase; }
        return b == null || b.isBlank() ? null : b.strip().replaceAll("/+$", "");
    }

    public boolean configured() { return base() != null; }

    private JsonNode get(URI uri) {
        HttpHeaders h = new HttpHeaders();
        h.setAccept(List.of(MediaType.valueOf("application/fhir+json"), MediaType.APPLICATION_JSON));
        return http.exchange(uri, HttpMethod.GET, new HttpEntity<>(h), JsonNode.class).getBody();
    }

    private static URI uri(String base, String[] segments, Map<String, String> query) {
        UriComponentsBuilder b = UriComponentsBuilder.fromHttpUrl(base).pathSegment(segments);
        query.forEach(b::queryParam);
        return b.build().encode().toUri();
    }

    // ---------- Patient ----------

    /** Patient with all its stays. Empty when the registry does not know the patient. Throws RegistryUnavailable when it cannot be reached. */
    public Optional<PatientInfo> patient(String id) { return patient(id, true, true); }

    /**
     * Reads only what is asked for: the Patient resource (demographics) and/or the Encounter list (stays). Without demographics nothing is read about who the patient is,
     * and the answer is never empty (the registry was not asked whether the patient exists).
     */
    public Optional<PatientInfo> patient(String id, boolean demographics, boolean withStays) {
        String b = base();
        if (b == null) throw new RegistryUnavailable("not-configured");
        String name = null, sex = null, ident = null;
        LocalDate born = null;
        if (demographics) {
            try {
                JsonNode p = get(uri(b, new String[]{"Patient", id}, Map.of()));
                if (p == null || !"Patient".equals(p.path("resourceType").asText())) throw new RegistryUnavailable("not-fhir");
                JsonNode n = p.path("name").path(0);
                name = n.path("text").asText(null);
                if (name == null) name = (n.path("given").path(0).asText("") + " " + n.path("family").asText("")).trim();
                if (name.isBlank()) name = null;
                sex = p.path("gender").asText(null);
                Instant bd = FhirParser.instant(p.path("birthDate").asText(null));
                born = bd == null ? null : bd.atZone(java.time.ZoneOffset.UTC).toLocalDate();
                ident = p.path("identifier").path(0).path("value").asText(null);
            } catch (HttpClientErrorException e) {
                return Optional.empty();   // 404, 403 and the like: the registry does not provide this patient
            } catch (RegistryUnavailable e) {
                throw e;
            } catch (RuntimeException e) {
                throw new RegistryUnavailable("unreachable");
            }
        }
        List<Stay> stays = new ArrayList<>();
        if (withStays) {
            for (Registry.Encounter e : encounters(id).items()) stays.add(stay(e));
        }
        return Optional.of(new PatientInfo(id, name, sex, born, ident, stays));
    }

    /** A stay from an Encounter, or null when it has no start (it cannot be placed in time). */
    public static Stay stay(Registry.Encounter e) {
        if (e == null || e.start() == null) return null;
        return new Stay(e.facilityRef() == null ? "" : e.facilityRef(), e.facilityName() == null ? "" : e.facilityName(), e.ward(), e.start(), e.end(), e.id());
    }

    // ---------- typed lists ----------

    public Registry.Section<Registry.Encounter> encounters(String patientId) {
        Registry.Section<Registry.Encounter> s = search("Encounter", params("patient", ref("Patient", patientId), "_sort", "-date"), FhirParser::encounter);
        if (!s.isOk()) return s;
        List<Registry.Encounter> keep = new ArrayList<>(s.items().stream().filter(e -> e.start() != null).toList());
        return Registry.Section.ok(keep);
    }

    /** One Encounter by id (read). NOT_PROVIDED when the registry has none (or refuses); the list is empty or has one item. */
    public Registry.Section<Registry.Encounter> encounter(String encounterId) {
        String b = base();
        if (b == null) return Registry.Section.unavailable();
        try {
            JsonNode n = get(uri(b, new String[]{"Encounter", encounterId}, Map.of()));
            Registry.Encounter e = FhirParser.encounter(n);
            return e == null ? Registry.Section.notProvided() : Registry.Section.ok(List.of(e));
        } catch (HttpClientErrorException e) {
            return unlessRetryable(e);
        } catch (RuntimeException e) {
            return Registry.Section.unavailable();
        }
    }

    // ---------- plumbing ----------

    private static String ref(String type, String id) { return type + "/" + id; }

    private static Map<String, String> params(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static <T> Registry.Section<T> unlessRetryable(HttpStatusCodeException e) {
        int c = e.getStatusCode().value();
        return c == 408 || c == 429 ? Registry.Section.unavailable() : Registry.Section.notProvided();
    }

    /** One search, following the Bundle's "next" links (only to the same registry address, at most MAX_PAGES pages). */
    <T> Registry.Section<T> search(String type, Map<String, String> query, Function<JsonNode, T> parse) {
        String b = base();
        if (b == null) return Registry.Section.unavailable();
        Map<String, String> q = new LinkedHashMap<>(query);
        q.put("_count", String.valueOf(PAGE));
        URI next = uri(b, new String[]{type}, q);
        List<T> out = new ArrayList<>();
        for (int page = 0; next != null && page < MAX_PAGES; page++) {
            JsonNode body;
            try {
                body = get(next);
            } catch (HttpClientErrorException e) {
                return unlessRetryable(e);              // 404, 403, 400, 401, 410 ...: not something this registry gives
            } catch (RuntimeException e) {
                return Registry.Section.unavailable();  // 5xx, timeout, refused connection, unreadable answer
            }
            if (body == null) return Registry.Section.unavailable();
            String rt = body.path("resourceType").asText("");
            if ("OperationOutcome".equals(rt)) return Registry.Section.notProvided();
            if (!"Bundle".equals(rt)) return Registry.Section.unavailable();
            for (JsonNode r : FhirParser.resources(body, type)) {
                try { T t = parse.apply(r); if (t != null) out.add(t); } catch (RuntimeException ignored) { /* one odd resource must not hide the others */ }
            }
            next = nextLink(body, b);
        }
        return Registry.Section.ok(out);
    }

    /** The "next" link of a Bundle, only when it points at the configured registry address. */
    static URI nextLink(JsonNode bundle, String base) {
        for (JsonNode l : bundle.path("link")) {
            if (!"next".equals(l.path("relation").asText())) continue;
            String u = l.path("url").asText("");
            if (u.isBlank() || !u.startsWith(base)) return null;
            try { return URI.create(u); } catch (RuntimeException e) { return null; }
        }
        return null;
    }
}

package com.akhester.auditui.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.IQuery;
import ca.uhn.fhir.rest.gclient.TokenClientParam;
import java.time.LocalDate;
import com.akhester.auditui.model.AuditDetail;
import com.akhester.auditui.model.AuditFilter;
import com.akhester.auditui.model.AuditRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.r4.model.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Integrates with Chester (HAPI FHIR R4) via the HAPI FHIR generic client.
 *
 * All FHIR types stay inside this service. Controllers and Thymeleaf templates
 * only see plain AuditRow / AuditDetail view-model objects.
 *
 * Key notes:
 * - purposeOfEvent is a BALP custom SP registered in Chester as "purposeOfEvent" (capital O).
 * - Chester's valid sort param for timestamp is "date" (NOT "recorded" — that's the field name).
 * - HAPI 7.x .offset() is NOT used — Chester does not support _getpagesoffset.
 *   Pagination is done client-side by slicing the returned list.
 * - Tab "pending" / "flagged" filtering is done client-side against the in-memory review store,
 *   because review status is not stored in Chester.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditEventService {

    private static final DateTimeFormatter DISPLAY_FMT =
        DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final IGenericClient fhirClient;
    private final FhirContext    fhirContext;

    @Value("${chester.fhir.base-url}")
    private String chesterFhirBaseUrl;

    private final com.akhester.auditui.config.ChesterTokenProvider tokens;

    private final RestTemplate restTemplate = new RestTemplate();

    // Review decisions live in the UI's own database (see ReviewRepository / schema.sql).
    private final ReviewRepository reviews;

    /** Most decided events a review tab will look at (per status). */
    private static final int REVIEW_ID_LIMIT = 500;
    /** Most Chester events the Pending tab scans to fill a page. */
    private static final int PENDING_SCAN_LIMIT = 2000;

    // ── BALP custom SP — not in core R4 ──────────────────────────────────────
    // Chester registers this as "purposeOfEvent" (capital O, exactly).
    private static final TokenClientParam SP_PURPOSE = new TokenClientParam("purposeOfEvent");

    // ─────────────────────────────────────────────────────────────────────────
    // Public record: combined result of a single Chester fetch
    // ─────────────────────────────────────────────────────────────────────────

    /** rows = one page; total = how many events match; error = set when Chester could not answer; note = shown above the table. */
    public record SearchResult(List<AuditRow> rows, int total, String error, String note) {
        public SearchResult(List<AuditRow> rows, int total) { this(rows, total, null, null); }
    }

    /** Per-value counts for sidebar badges. Each count ignores its own filter, so users see what each option would give. */
    public record FacetCounts(
        java.util.Map<String,Long> outcomeCounts,
        java.util.Map<String,Long> purposeCounts
    ) {}

    private static final List<String> PURPOSES = List.of("TREAT", "ETREAT", "BTG", "PUBHLTH", "HRESCH", "HOPERAT", "HPAYMT");
    private static final List<String> OUTCOMES = List.of("0", "4", "8", "12");

    /** Sidebar badges: one cheap count query per option, asked of Chester (not counted in the browser). */
    public FacetCounts facetCounts(AuditFilter base) {
        java.util.Map<String, Long> purpose = new java.util.LinkedHashMap<>();
        java.util.Map<String, Long> outcome = new java.util.LinkedHashMap<>();
        try {
            for (String pc : PURPOSES) {
                java.util.Map<String, List<String>> params = serverParams(base, true, false);
                params.computeIfAbsent("purpose-of-use", k -> new ArrayList<>()).add(pc);
                purpose.put(pc, (long) countOnly(params));
            }
            for (String oc : OUTCOMES) {
                java.util.Map<String, List<String>> params = serverParams(base, false, true);
                params.put("outcome", new ArrayList<>(List.of(oc)));
                outcome.put(oc, (long) countOnly(params));
            }
        } catch (Exception ex) {
            log.warn("facetCounts failed [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
            return new FacetCounts(java.util.Map.of(), java.util.Map.of());
        }
        return new FacetCounts(outcome, purpose);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    private static boolean isReviewTab(String tab) {
        return "pending".equals(tab) || "flagged".equals(tab) || "reviewed".equals(tab);
    }

    /**
     * One page of events. Every filter except the review tabs is sent to Chester, which also does the
     * paging and the total. Review status lives only in this UI, so the review tabs (pending/flagged/reviewed)
     * can only look at the newest 200 events that match the other filters, and say so.
     */
    public SearchResult searchWithTotal(AuditFilter filter) {
        try {
            int size = Math.max(1, Math.min(filter.getSize(), 100));
            int page = Math.max(1, filter.getPage());
            if (!isReviewTab(filter.getTab())) {
                Bundle bundle = runQuery(serverParams(filter, false, false), size, (page - 1) * size, filter.getSort());
                int total = bundle.hasTotal() ? bundle.getTotal() : bundle.getEntry().size();
                return new SearchResult(mapBundleToRows(bundle), total, null, null);
            }
            return "pending".equals(filter.getTab()) ? pendingTab(filter, size, page) : decidedTab(filter, size, page);
        } catch (Exception ex) {
            log.warn("Chester search failed [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
            return new SearchResult(List.of(), 0, describe(ex), null);
        }
    }

    /** Most events per kind (break-glass / incomplete) the SLA count reads; above this the result says "truncated". */
    private static final int SLA_SCAN_LIMIT = 1000;

    /** overdue / warning = unreviewed break-glass or incomplete events past the thresholds; error is set if Chester could not answer. */
    public record SlaCounts(long overdue, long warning, boolean truncated, String error) {}

    /**
     * Exact SLA counts for what the signed-in user may see: every break-glass-labelled or incomplete event that is at least
     * warning-hours old and has no reviewed/flagged decision. (Events younger than warning-hours are not counted.)
     */
    public SlaCounts slaCounts() {
        try {
            String cutoff = java.time.Instant.now().minus(AuditRow.slaWarningHours, java.time.temporal.ChronoUnit.HOURS)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
            java.util.Map<String, AuditRow> found = new java.util.LinkedHashMap<>();
            boolean truncated = false;
            for (int kind = 0; kind < 2; kind++) {
                java.util.Map<String, List<String>> params = new java.util.LinkedHashMap<>();
                if (kind == 0) params.put("break-glass", List.of("BTG"));
                else params.put("_tag", List.of(tagBaseUri + "/tags|policy-incomplete"));
                params.put("date", List.of("le" + cutoff));
                int offset = 0;
                while (offset < SLA_SCAN_LIMIT) {
                    Bundle b = runQuery(params, 100, offset, "date");
                    for (AuditRow r : mapBundleToRows(b)) found.put(r.getId(), r);
                    int got = b.getEntry().size();
                    offset += got;
                    if (got < 100) break;
                    if (offset >= SLA_SCAN_LIMIT && b.hasTotal() && b.getTotal() > SLA_SCAN_LIMIT) truncated = true;
                }
            }
            long over = found.values().stream().filter(r -> r.isSlaApplicable() && r.getAgeHours() >= AuditRow.slaOverdueHours).count();
            long warn = found.values().stream().filter(r -> r.isSlaApplicable()
                && r.getAgeHours() >= AuditRow.slaWarningHours && r.getAgeHours() < AuditRow.slaOverdueHours).count();
            return new SlaCounts(over, warn, truncated, null);
        } catch (Exception ex) {
            log.warn("Chester SLA count failed [{}]: {}", ex.getClass().getSimpleName(), ex.getMessage());
            return new SlaCounts(0, 0, false, describe(ex));
        }
    }

    public int count(AuditFilter filter) {
        try { return countOnly(serverParams(filter, false, false)); }
        catch (Exception ex) { log.warn("Chester count failed: {}", ex.getMessage()); return 0; }
    }

    /** Plain-language reason shown to the user instead of an empty "no results". */
    private static String describe(Exception ex) {
        if (ex instanceof IllegalArgumentException iae && INVALID_VALUE.equals(iae.getMessage())) return INVALID_VALUE;
        if (ex instanceof ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException b) {
            int c = b.getStatusCode();
            if (c == 401) return "Chester refused your sign-in (HTTP 401). Your session may have expired: sign out and in again.";
            if (c == 403) return "Your account is not allowed to read audit events (HTTP 403).";
            return "Chester returned an error (HTTP " + c + ").";
        }
        if (ex instanceof IllegalStateException && ex.getMessage() != null && ex.getMessage().startsWith("No signed-in")) {
            return "You are not signed in.";
        }
        return "Chester could not be reached.";
    }

    /**
     * Ask Chester to verify the hash chain ($verify-chain), with the signed-in user's own token.
     * Only unrestricted readers are allowed by Chester; others get an error here. partition = yyyy-MM-dd or null for all days.
     */
    public Map<String, Object> verifyChain(String partition) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        try {
            String url = chesterFhirBaseUrl + "/$verify-chain" + (partition != null ? "?partition=" + partition : "");
            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setBearerAuth(tokens.currentAccessToken());
            headers.setAccept(java.util.List.of(org.springframework.http.MediaType.parseMediaType("application/fhir+json")));
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.exchange(url, org.springframework.http.HttpMethod.GET,
                    new org.springframework.http.HttpEntity<>(headers), Map.class).getBody();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> params = resp == null ? null : (List<Map<String, Object>>) resp.get("parameter");
            List<String> issues = new ArrayList<>();
            if (params != null) for (Map<String, Object> p : params) {
                String name = String.valueOf(p.get("name"));
                Object v = p.containsKey("valueBoolean") ? p.get("valueBoolean") : p.containsKey("valueInteger") ? p.get("valueInteger")
                         : p.containsKey("valueString") ? p.get("valueString") : null;
                if (v == null) continue;
                if ("valid".equals(name) || "partitions".equals(name) || "eventsChecked".equals(name)
                        || "unchainedEvents".equals(name) || "problemCount".equals(name)) out.put(name, v);
                else issues.add(name + ": " + v);
            }
            if (!issues.isEmpty()) out.put("details", issues.size() > 20 ? issues.subList(0, 20) : issues);
            out.put("partition", partition == null ? "all days" : partition);
        } catch (org.springframework.web.client.HttpStatusCodeException ex) {
            out.put("error", ex.getStatusCode().value() == 403
                ? "Your account is not allowed to verify the chain (HTTP 403)." : "Chester returned HTTP " + ex.getStatusCode().value() + ".");
        } catch (Exception ex) {
            out.put("error", "Chester could not be reached.");
        }
        return out;
    }

    /**
     * Fetch a single AuditEvent by ID and return the full AuditDetail view-model.
     * Also calls the Chester $chain-info operation to populate hash chain fields.
     */
    public AuditDetail getDetail(String id) {
        if (id == null || !id.matches("[A-Za-z0-9.\\-]{1,64}")) return null;   // never put raw input into a Chester URL
        try {
            AuditEvent ae = fhirClient.read()
                .resource(AuditEvent.class)
                .withId(id)
                .execute();
            AuditDetail detail = mapToDetail(ae);

            // ── Call $chain-info to populate hash chain fields ────────────────
            try {
                String chainUrl = chesterFhirBaseUrl + "/AuditEvent/" + id + "/$chain-info";
                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                headers.setBearerAuth(tokens.currentAccessToken());
                headers.setAccept(java.util.List.of(org.springframework.http.MediaType.parseMediaType("application/fhir+json")));
                @SuppressWarnings("unchecked")
                Map<String, Object> chainResp = restTemplate.exchange(chainUrl, org.springframework.http.HttpMethod.GET,
                        new org.springframework.http.HttpEntity<>(headers), Map.class).getBody();
                if (chainResp != null) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> params = (List<Map<String, Object>>) chainResp.get("parameter");
                    if (params != null) {
                        String prevHash = null, currHash = null, position = null,
                               partKey = null, writtenAt = null, genesisHash = null;
                        for (Map<String, Object> p : params) {
                            String name = (String) p.get("name");
                            if (name == null) continue;
                            String val = p.containsKey("valueString")  ? String.valueOf(p.get("valueString"))
                                       : p.containsKey("valueInteger") ? String.valueOf(p.get("valueInteger"))
                                       : p.containsKey("valueDateTime")? String.valueOf(p.get("valueDateTime"))
                                       : null;
                            if (val == null) continue;
                            switch (name) {
                                case "previousHash"    -> prevHash  = val;
                                case "currentHash",
                                     "canonicalHash"   -> currHash  = val;
                                case "position",
                                     "sequenceNumber"  -> position  = val;
                                case "partitionKey"    -> partKey   = val;
                                case "chainWrittenAt"  -> writtenAt = val;
                                case "genesisHash"     -> genesisHash = val;
                            }
                        }
                        detail.setPreviousHash(prevHash);
                        detail.setChainHash(currHash);
                        detail.setChainPosition(position);
                        detail.setChainPartitionKey(partKey);
                        detail.setChainWrittenAt(writtenAt);
                        detail.setGenesisHash(genesisHash);
                        log.debug("$chain-info for {}: pos={} curr={}", id, position, currHash);
                    }
                }
            } catch (Exception chainEx) {
                log.debug("$chain-info not available for AuditEvent/{}: {}", id, chainEx.getMessage());
                // Not fatal — drawer shows "no chain data" state
            }

            return detail;
        } catch (Exception ex) {
            log.warn("Failed to fetch AuditEvent/{}: {}", id, ex.getMessage());
            return null;
        }
    }

    /**
     * Record a review decision (reviewed / flagged / pending) with the signed-in reviewer, in the UI's database.
     */
    public void setReviewStatus(String id, String status, String note) {
        if (id == null || !id.matches("[A-Za-z0-9\\-.]{1,64}")) throw new IllegalArgumentException("bad event id");
        if (!List.of("reviewed", "flagged", "pending").contains(status)) throw new IllegalArgumentException("bad status");
        // The event must exist and be visible to this user in Chester (read with the user's own token).
        try { fhirClient.read().resource(AuditEvent.class).withId(id).execute(); }
        catch (ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException | ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException e) {
            throw new java.util.NoSuchElementException("event not found");
        }
        org.springframework.security.core.Authentication a =
            org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        String reviewer = a != null ? a.getName() : "unknown";
        reviews.save(id, status, reviewer, note);
        log.info("AuditEvent/{} marked as {} by {}", id, status, reviewer);
    }

    /**
     * Connectivity check — returns true if Chester responds to a metadata request.
     */
    /** What Chester said when asked who it is: answered or not, how long it took, FHIR version and software name. */
    public record ChesterInfo(boolean up, long millis, String fhirVersion, String software, String error) {}

    public ChesterInfo chesterInfo() {
        long t0 = System.nanoTime();
        try {
            CapabilityStatement cs = fhirClient.capabilities().ofType(CapabilityStatement.class).execute();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            String sw = cs.hasSoftware() ? cs.getSoftware().getName() : null;
            return new ChesterInfo(true, ms, cs.hasFhirVersion() ? cs.getFhirVersion().toCode() : null, sw, null);
        } catch (Exception ex) {
            return new ChesterInfo(false, (System.nanoTime() - t0) / 1_000_000, null, null, describe(ex));
        }
    }

    /** Number of events matching raw Chester search parameters (Overview and Health use this). Throws if Chester cannot answer. */
    public int countWhere(java.util.Map<String, List<String>> params) {
        return countOnly(params);
    }

    /** Newest-first (or oldest-first for sort "date") events matching raw Chester search parameters, at most {@code limit} (max 100). */
    public List<AuditRow> recentWhere(java.util.Map<String, List<String>> params, int limit, String sort) {
        return mapBundleToRows(runQuery(params, Math.max(1, Math.min(limit, 100)), 0, sort));
    }

    /** One page of events matching raw Chester search parameters: {@code count} (max 100) starting at {@code offset}. Throws if Chester cannot answer. */
    public List<AuditRow> pageWhere(java.util.Map<String, List<String>> params, int count, int offset, String sort) {
        return mapBundleToRows(runQuery(params, Math.max(1, Math.min(count, 100)), Math.max(0, offset), sort));
    }

    /** Raw Chester parameter value that selects records flagged incomplete. */
    public String incompleteTag() { return tagBaseUri + "/tags|policy-incomplete"; }

    /** Raw Chester parameter value that selects one submitting client (Keycloak client id). */
    public String submittingClientTag(String clientId) {
        return tagBaseUri + "/submitting-client|" + esc(clientId);
    }

    public boolean isChesterReachable() {
        try {
            fhirClient.capabilities().ofType(CapabilityStatement.class).execute();
            return true;
        } catch (Exception ex) {
            log.debug("Chester not reachable: {}", ex.getMessage());
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private — query building
    // ─────────────────────────────────────────────────────────────────────────

    @Value("${chester.tags.base-uri:https://akhester.com/chester}")
    private String tagBaseUri;

    /** Start of the message shown (and mapped to HTTP 400) when a filter value is refused. */
    public static final String INVALID_VALUE = "Invalid search value: use at most 200 characters, no control characters, and dates as yyyy-MM-dd.";

    private static String clean(String v) {
        if (v == null) return null;
        String t = v.strip();     // strip() removes only real white space (trim() would also swallow NUL and make it look empty)
        if (t.isEmpty()) return null;
        // A value that cannot be used must be refused, not dropped: a dropped filter would show every event.
        if (t.length() > 200) throw new IllegalArgumentException(INVALID_VALUE);
        for (int i = 0; i < t.length(); i++) if (Character.isISOControl(t.charAt(i))) throw new IllegalArgumentException(INVALID_VALUE);
        return t;
    }

    /** FHIR search escaping, so that , | $ and \\ typed by a user are searched literally (an unescaped comma means OR). */
    private static String esc(String v) {
        return v == null ? null : v.replace("\\", "\\\\").replace(",", "\\,").replace("|", "\\|").replace("$", "\\$");
    }

    /** "P001" -> "Patient/P001"; "Patient/P001" stays. Chester matches the reference text exactly. */
    private static String asRef(String v, String type) {
        String t = clean(v);
        if (t == null) return null;
        return esc(t.contains("/") ? t : type + "/" + t);
    }

    /**
     * The filters as Chester search parameters. Custom: entity-ref, agent-ref, source-ref, purpose-of-use,
     * break-glass. Standard: date, outcome, action, site (department), _tag.
     */
    private java.util.Map<String, List<String>> serverParams(AuditFilter f, boolean skipPurpose, boolean skipOutcome) {
        java.util.Map<String, List<String>> p = new java.util.LinkedHashMap<>();
        String patient = asRef(f.getQ(), "Patient");
        String encounter = asRef(f.getEncounter(), "Encounter");
        List<String> entities = new ArrayList<>();            // two entity-ref values are ANDed: this patient and this encounter
        if (patient != null) entities.add(patient);
        if (encounter != null) entities.add(encounter);
        if (!entities.isEmpty()) p.put("entity-ref", entities);
        String department = clean(f.getDepartment());
        if (department != null) p.put("site", new ArrayList<>(List.of(esc(department))));
        String agent = asRef(f.getAgent(), "Practitioner");
        if (agent != null) p.put("agent-ref", new ArrayList<>(List.of(agent)));
        String facility = asRef(f.getFacility(), "Organization");
        if (facility != null) p.put("source-ref", new ArrayList<>(List.of(facility)));

        List<String> purposes = new ArrayList<>();
        String pc = clean(f.getPurpose());
        if (!skipPurpose && pc != null) purposes.add(esc(pc.toUpperCase()));
        if ("etreat".equals(f.getTab()) || f.isEtreatsOnly()) purposes.add("ETREAT,BTG");
        if (!purposes.isEmpty()) p.put("purpose-of-use", purposes);

        if (f.isBreakGlass()) p.put("break-glass", new ArrayList<>(List.of("BTG")));

        String oc = clean(f.getOutcome());
        if (!skipOutcome && oc != null) p.put("outcome", new ArrayList<>(List.of(esc(oc))));
        String ac = clean(f.getAction());
        if (ac != null) p.put("action", new ArrayList<>(List.of(esc(ac.toUpperCase()))));

        List<String> tags = new ArrayList<>();
        String client = clean(f.getClient());
        if (client != null) tags.add(tagBaseUri + "/submitting-client|" + esc(client));
        if (f.isIncomplete()) tags.add(tagBaseUri + "/tags|policy-incomplete");
        if (!tags.isEmpty()) p.put("_tag", tags);

        List<String> dates = new ArrayList<>();
        String from = clean(f.getDateFrom()), to = clean(f.getDateTo());
        try {
            if (from != null) dates.add("ge" + LocalDate.parse(from));
            if (to != null)   dates.add("le" + LocalDate.parse(to));
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException(INVALID_VALUE);     // refuse, never silently drop a date filter
        }
        if (!dates.isEmpty()) p.put("date", dates);
        return p;
    }

    private Bundle runQuery(java.util.Map<String, List<String>> params, int count, int offset, String sort) {
        IQuery<Bundle> q = fhirClient.search().forResource(AuditEvent.class).whereMap(params)
            .returnBundle(Bundle.class)
            .count(count)
            .totalMode(ca.uhn.fhir.rest.api.SearchTotalModeEnum.ACCURATE);
        if (offset > 0) q = q.offset(offset);
        String sortField = (sort == null || sort.isBlank()) ? "-date" : sort.replace("recorded", "date");
        if (!"date".equals(sortField) && !"-date".equals(sortField)) sortField = "-date";   // only the date sort is offered
        q = sortField.startsWith("-") ? q.sort().descending(sortField.substring(1)) : q.sort().ascending(sortField);
        return q.execute();
    }

    private int countOnly(java.util.Map<String, List<String>> params) {
        Bundle b = fhirClient.search().forResource(AuditEvent.class).whereMap(params)
            .returnBundle(Bundle.class).summaryMode(ca.uhn.fhir.rest.api.SummaryEnum.COUNT).execute();
        return b.hasTotal() ? b.getTotal() : 0;
    }

    /** Reviewed / Flagged tab: the events this UI has a decision for, fetched from Chester by id and filtered there. */
    private SearchResult decidedTab(AuditFilter filter, int size, int page) {
        List<String> ids = reviews.idsWithStatus(filter.getTab(), REVIEW_ID_LIMIT);
        List<AuditRow> all = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += 50) {
            java.util.Map<String, List<String>> params = new java.util.LinkedHashMap<>(serverParams(filter, false, false));
            params.put("_id", List.of(String.join(",", ids.subList(i, Math.min(i + 50, ids.size())))));
            all.addAll(mapBundleToRows(runQuery(params, 100, 0, filter.getSort())));
        }
        boolean desc = filter.getSort() == null || filter.getSort().isBlank() || filter.getSort().startsWith("-");
        all.sort(java.util.Comparator.comparing(AuditRow::getRecordedInstant, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        if (desc) java.util.Collections.reverse(all);
        int from = (page - 1) * size;
        List<AuditRow> slice = from >= all.size() ? List.of() : new ArrayList<>(all.subList(from, Math.min(from + size, all.size())));
        String note = reviews.countWithStatus(filter.getTab()) > REVIEW_ID_LIMIT
            ? "Showing the " + REVIEW_ID_LIMIT + " most recent " + filter.getTab() + " decisions." : null;
        return new SearchResult(slice, all.size(), null, note);
    }

    /** Pending tab: every matching event that has no reviewed/flagged decision. Total is exact; paging scans Chester in order. */
    private SearchResult pendingTab(AuditFilter filter, int size, int page) {
        java.util.Set<String> decided = new java.util.HashSet<>(reviews.idsWithStatus("reviewed", REVIEW_ID_LIMIT));
        decided.addAll(reviews.idsWithStatus("flagged", REVIEW_ID_LIMIT));
        java.util.Map<String, List<String>> base = serverParams(filter, false, false);
        int matching = countOnly(base);
        int decidedMatching = 0;
        List<String> dl = new ArrayList<>(decided);
        for (int i = 0; i < dl.size(); i += 50) {
            java.util.Map<String, List<String>> params = new java.util.LinkedHashMap<>(base);
            params.put("_id", List.of(String.join(",", dl.subList(i, Math.min(i + 50, dl.size())))));
            decidedMatching += countOnly(params);
        }
        int total = Math.max(0, matching - decidedMatching);
        int need = page * size;
        List<AuditRow> pending = new ArrayList<>();
        int offset = 0, scanned = 0;
        boolean exhausted = false;
        while (pending.size() < need && scanned < PENDING_SCAN_LIMIT && !exhausted) {
            Bundle b = runQuery(base, 100, offset, filter.getSort());
            int got = b.getEntry().size();
            for (AuditRow r : mapBundleToRows(b)) if ("pending".equals(r.getReviewStatus())) pending.add(r);
            offset += got; scanned += got;
            exhausted = got < 100;
        }
        int from = (page - 1) * size;
        List<AuditRow> slice = from >= pending.size() ? List.of() : new ArrayList<>(pending.subList(from, Math.min(from + size, pending.size())));
        String note = (!exhausted && pending.size() < need)
            ? "Could not fill this page within the newest " + PENDING_SCAN_LIMIT + " events; narrow the filters." : null;
        return new SearchResult(slice, total, null, note);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private — mapping
    // ─────────────────────────────────────────────────────────────────────────

    private List<AuditRow> mapBundleToRows(Bundle bundle) {
        List<AuditRow> rows = new ArrayList<>();
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            if (entry.getResource() instanceof AuditEvent ae) {
                AuditRow row = mapToRow(ae);
                log.debug("Mapped AuditEvent/{}: outcome={}, purpose={}",
                    row.getId(), row.getOutcomeCode(), row.getPurposeCode());
                rows.add(row);
            }
        }
        java.util.Map<String, ReviewRepository.Review> decided = reviews.findAll(rows.stream().map(AuditRow::getId).collect(Collectors.toList()));
        for (AuditRow r : rows) {
            ReviewRepository.Review rv = decided.get(r.getId());
            if (rv != null) r.setReviewStatus(rv.status());
        }
        return rows;
    }

    /** Code of the first meta.tag whose system ends with the given suffix, or null. */
    private static String tagCode(AuditEvent ae, String systemSuffix) {
        if (ae.getMeta() == null) return null;
        return ae.getMeta().getTag().stream()
            .filter(t -> t.getSystem() != null && t.getSystem().endsWith(systemSuffix))
            .map(Coding::getCode).findFirst().orElse(null);
    }

    private AuditRow mapToRow(AuditEvent ae) {
        String id       = ae.getIdElement().getIdPart();
        Instant recorded = ae.getRecorded() != null ? ae.getRecorded().toInstant() : null;

        // Purpose of event (first coding)
        String purposeCode = null;
        if (!ae.getPurposeOfEvent().isEmpty()) {
            CodeableConcept poc = ae.getPurposeOfEvent().get(0);
            if (!poc.getCoding().isEmpty()) {
                purposeCode = poc.getCoding().get(0).getCode();
            }
        }

        // Agent (first requestor, or first agent if none flagged as requestor)
        String agentName = null;
        AuditEvent.AuditEventAgentComponent agent = ae.getAgent().stream()
            .filter(AuditEvent.AuditEventAgentComponent::getRequestor)
            .findFirst()
            .orElse(ae.getAgent().isEmpty() ? null : ae.getAgent().get(0));
        if (agent != null) {
            agentName = agent.getName();
            if (agentName == null && agent.getWho() != null) {
                agentName = agent.getWho().getReference();
            }
        }

        // Patient entity reference
        String patientRef = ae.getEntity().stream()
            .filter(e -> e.getWhat() != null && e.getWhat().getReference() != null
                && e.getWhat().getReference().startsWith("Patient/"))
            .map(e -> e.getWhat().getReference())
            .findFirst()
            .orElse(null);

        // Review status from in-memory store
        String reviewStatus = "pending"; // overlaid from the database in mapBundleToRows
        long ageHours = recorded != null ? AuditRow.computeAgeHours(recorded) : 0;

        return AuditRow.builder()
            .id(id)
            .recorded(recorded != null ? DISPLAY_FMT.format(recorded) : "—")
            .recordedInstant(recorded)
            .purposeCode(purposeCode)
            .outcomeCode(ae.getOutcome() != null && ae.getOutcome().toCode() != null
                ? ae.getOutcome().toCode() : "0")
            .agentName(agentName != null ? agentName : "—")
            .agentRef(agent != null && agent.getWho() != null ? agent.getWho().getReference() : null)
            .patientRef(patientRef != null ? patientRef : "—")
            .facility(ae.getSource() != null && ae.getSource().getObserver() != null
                      && ae.getSource().getObserver().getReference() != null ? ae.getSource().getObserver().getReference() : "—")
            .submittedBy(tagCode(ae, "/submitting-client"))
            .breakGlassReason(ae.getExtensionByUrl(tagBaseUri + "/StructureDefinition/break-glass-reason") != null
                ? ae.getExtensionByUrl(tagBaseUri + "/StructureDefinition/break-glass-reason").getValue().primitiveValue() : null)
            .reasonGivenAt(ae.getExtensionByUrl(tagBaseUri + "/StructureDefinition/reason-given-at") != null
                ? java.time.Instant.parse(ae.getExtensionByUrl(tagBaseUri + "/StructureDefinition/reason-given-at").getValue().primitiveValue().replace("+00:00", "Z")) : null)
            .outcomeDesc(ae.getOutcomeDesc())
            .encounterRef(ae.getEntity().stream().filter(e -> e.getWhat() != null && e.getWhat().getReference() != null && e.getWhat().getReference().startsWith("Encounter/"))
                .map(e -> e.getWhat().getReference()).findFirst().orElse(null))
            .incomplete(tagCode(ae, "/tags") != null && ae.getMeta().getTag().stream().anyMatch(t -> "policy-incomplete".equals(t.getCode())))
            .breakGlass(ae.getMeta() != null && ae.getMeta().getSecurity().stream().anyMatch(c -> "BTG".equals(c.getCode())))
            .eventType(ae.getType() != null ? ae.getType().getCode() : null)
            .action(ae.getAction() != null ? ae.getAction().toCode() : null)
            .reviewStatus(reviewStatus)
            .ageHours(ageHours)
            .build();
    }

    private AuditDetail mapToDetail(AuditEvent ae) {
        String id        = ae.getIdElement().getIdPart();
        Instant recorded = ae.getRecorded() != null ? ae.getRecorded().toInstant() : null;
        long ageHours    = recorded != null ? AuditRow.computeAgeHours(recorded) : 0;

        // ── Purpose ───────────────────────────────────────────────────────────
        String purposeCode = null;
        if (!ae.getPurposeOfEvent().isEmpty()) {
            CodeableConcept poc = ae.getPurposeOfEvent().get(0);
            if (!poc.getCoding().isEmpty()) purposeCode = poc.getCoding().get(0).getCode();
        }

        // ── BALP profile URL ─────────────────────────────────────────────────
        String balpProfile = null, balpProfileShort = null;
        if (ae.getMeta() != null && !ae.getMeta().getProfile().isEmpty()) {
            balpProfile = ae.getMeta().getProfile().get(0).getValue();
            if (balpProfile != null) {
                // Extract "IHE.BasicAudit.PatientRead" from the full URL
                int idx = balpProfile.lastIndexOf('/');
                balpProfileShort = (idx >= 0) ? balpProfile.substring(idx + 1) : balpProfile;
            }
        }

        // ── Event type / subtype ─────────────────────────────────────────────
        String eventType = null, eventTypeDisplay = null, eventSubtype = null;
        if (ae.getType() != null) {
            eventType        = ae.getType().getCode();
            eventTypeDisplay = ae.getType().getDisplay();
        }
        if (!ae.getSubtype().isEmpty()) {
            Coding sub = ae.getSubtype().get(0);
            eventSubtype = sub.getCode() + (sub.getDisplay() != null ? " — " + sub.getDisplay() : "");
        }

        // ── Action display ───────────────────────────────────────────────────
        String actionCode    = ae.getAction() != null ? ae.getAction().toCode() : null;
        String actionDisplay = actionCode == null ? null : switch (actionCode) {
            case "C" -> "C — Create"; case "R" -> "R — Read";
            case "U" -> "U — Update"; case "D" -> "D — Delete";
            case "E" -> "E — Execute"; default -> actionCode;
        };

        // ── Outcome ──────────────────────────────────────────────────────────
        String outcomeCode = (ae.getOutcome() != null && ae.getOutcome().toCode() != null)
            ? ae.getOutcome().toCode() : "0";

        // ── Agents — all agents ───────────────────────────────────────────────
        List<AuditDetail.AgentInfo> agentInfos = ae.getAgent().stream().map(a -> {
            String whoRef  = a.getWho() != null ? a.getWho().getReference() : null;
            String whoId   = null;
            if (a.getWho() != null && a.getWho().hasIdentifier()) {
                Identifier identifier = a.getWho().getIdentifier();
                whoId = (identifier.getValue() != null) ? identifier.getValue() : null;
            }
            String net = a.getNetwork() != null ? a.getNetwork().getAddress() : null;
            String netType = a.getNetwork() != null
                && a.getNetwork().getType() != null
                ? a.getNetwork().getType().toCode() : null;
            String roleCode = null, roleDsp = null;
            if (!a.getRole().isEmpty() && !a.getRole().get(0).getCoding().isEmpty()) {
                Coding rc = a.getRole().get(0).getCoding().get(0);
                roleCode = rc.getCode(); roleDsp = rc.getDisplay();
            }
            String typeCode = null;
            if (a.getType() != null && !a.getType().getCoding().isEmpty()) {
                typeCode = a.getType().getCoding().get(0).getCode();
            }
            String name = a.getName();
            if (name == null && whoRef != null) name = whoRef;
            return AuditDetail.AgentInfo.builder()
                .name(name != null ? name : "—")
                .reference(whoRef)
                .identifier(whoId)
                .requestorFlag(a.getRequestor())
                .networkAddress(net)
                .networkType(netType)
                .roleCode(roleCode)
                .roleDisplay(roleDsp)
                .typeCode(typeCode)
                .build();
        }).collect(Collectors.toList());

        // Primary agent (first requestor)
        AuditDetail.AgentInfo primaryAgent = agentInfos.stream()
            .filter(AuditDetail.AgentInfo::isRequestorFlag)
            .findFirst()
            .orElse(agentInfos.isEmpty() ? null : agentInfos.get(0));

        // ── Entities — all entities ──────────────────────────────────────────
        List<AuditDetail.EntityInfo> entityInfos = ae.getEntity().stream().map(e -> {
            String whatRef  = e.getWhat() != null ? e.getWhat().getReference() : null;
            String whatDsp  = e.getWhat() != null ? e.getWhat().getDisplay() : null;
            String tCode = null, tDsp = null, rCode = null, rDsp = null;
            if (e.getType() != null) { tCode = e.getType().getCode(); tDsp = e.getType().getDisplay(); }
            if (e.getRole() != null) { rCode = e.getRole().getCode(); rDsp = e.getRole().getDisplay(); }
            return AuditDetail.EntityInfo.builder()
                .whatReference(whatRef)
                .whatDisplay(whatDsp)
                .typeCode(tCode)
                .typeDisplay(tDsp)
                .roleCode(rCode)
                .roleDisplay(rDsp)
                .description(e.getDescription())
                .name(e.getName())
                .build();
        }).collect(Collectors.toList());

        // Primary patient entity
        AuditDetail.EntityInfo patientEntity = entityInfos.stream()
            .filter(e -> e.getWhatReference() != null
                && e.getWhatReference().startsWith("Patient/"))
            .findFirst().orElse(null);
        String patientRef = patientEntity != null ? patientEntity.getWhatReference() : null;

        // ── Source ───────────────────────────────────────────────────────────
        String sourceObserver = null, sourceSite = null, sourceType = null;
        if (ae.getSource() != null) {
            sourceObserver = ae.getSource().getObserver() != null
                ? ae.getSource().getObserver().getReference() : null;
            sourceSite = ae.getSource().getSite();
            if (!ae.getSource().getType().isEmpty()) {
                // In HAPI FHIR R4 AuditEvent, source.type is List<Coding> directly
                Coding st = ae.getSource().getType().get(0);
                sourceType = st.getCode() + (st.getDisplay() != null ? " — " + st.getDisplay() : "");
            }
        }

        // ── Security labels ──────────────────────────────────────────────────
        List<String> labels = ae.getMeta() != null
            ? ae.getMeta().getSecurity().stream()
                .map(c -> (c.getSystem() != null ? c.getSystem() + " | " : "") + c.getCode())
                .collect(Collectors.toList())
            : List.of();

        // ── Raw JSON ─────────────────────────────────────────────────────────
        String rawJson = fhirContext.newJsonParser().setPrettyPrint(true)
            .encodeResourceToString(ae);

        // ── CSS helpers via AuditRow ─────────────────────────────────────────
        AuditRow row = AuditRow.builder()
            .id(id).outcomeCode(outcomeCode).purposeCode(purposeCode)
            .ageHours(ageHours).reviewStatus(reviews.find(id).map(ReviewRepository.Review::status).orElse("pending"))
            .build();

        return AuditDetail.builder()
            .id(id)
            .recorded(recorded != null ? DISPLAY_FMT.format(recorded) : "—")
            .eventType(eventType)
            .eventTypeDisplay(eventTypeDisplay)
            .eventSubtype(eventSubtype)
            .action(actionCode)
            .actionDisplay(actionDisplay)
            .outcomeCode(outcomeCode)
            .outcomeLabel(row.getOutcomeLabel())
            .outcomeCss(row.getOutcomeCss())
            .balpProfile(balpProfile)
            .balpProfileShort(balpProfileShort)
            .purposeCode(purposeCode)
            .purposeLabel(row.getPurposeLabel())
            .purposeTagCss(row.getPurposeTagCss())
            // primary agent
            .agentName(primaryAgent != null ? primaryAgent.getName() : "—")
            .agentReference(primaryAgent != null ? primaryAgent.getReference() : null)
            .requestor(primaryAgent != null && primaryAgent.isRequestorFlag())
            .networkAddress(primaryAgent != null ? primaryAgent.getNetworkAddress() : null)
            .networkType(primaryAgent != null ? primaryAgent.getNetworkType() : null)
            .agents(agentInfos)
            // entity
            .patientRef(patientRef != null ? patientRef : "—")
            .entityType(patientEntity != null ? patientEntity.getTypeDisplay() : null)
            .entityRole(patientEntity != null ? patientEntity.getRoleDisplay() : null)
            .entities(entityInfos)
            // source
            .sourceObserver(sourceObserver)
            .sourceSite(sourceSite)
            .sourceType(sourceType)
            .securityLabels(labels)
            // review
            .reviewStatus(row.getReviewStatus())
            .reviewNote(reviews.find(id).map(ReviewRepository.Review::note).orElse(null))
            .reviewer(reviews.find(id).map(ReviewRepository.Review::reviewer).orElse(null))
            .reviewedAt(reviews.find(id).map(r -> r.reviewedAt().toString()).orElse(null))
            .reviewHistory(reviews.history(id).stream()
                .map(r -> r.reviewedAt() + " — " + r.status() + " by " + r.reviewer() + (r.note() != null ? ": " + r.note() : ""))
                .collect(Collectors.toList()))
            // raw
            .rawJson(rawJson)
            // sla
            .ageHours(ageHours)
            .slaCss(row.getSlaCss())
            .slaLabel(row.getSlaLabel())
            .build();
    }
}

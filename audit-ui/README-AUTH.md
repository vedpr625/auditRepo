# Audit UI: sign-in and Audit Repository access

Users sign in with Keycloak. The UI calls the Audit Repository with the signed-in user's own token.

Required environment variables:
- `AUDIT_UI_KEYCLOAK_ISSUER` e.g. `https://keycloak.registry.dev.internal.asthrahealthtech.com/realms/bundle-3`
- `AUDIT_UI_CLIENT_SECRET` secret of the `chester-audit-ui` client (Keycloak > Clients > chester-audit-ui > Credentials)
- `CHESTER_FHIR_BASE_URL` e.g. `http://localhost:8082/fhir`
- optional `AUDIT_UI_CLIENT_ID` (default `chester-audit-ui`)

Keycloak: import `keycloak/chester-partial-import.json` from the Audit Repository project (adds client `chester-audit-ui` with redirect `http://localhost:8081/login/oauth2/code/keycloak` and the audience mapper). Change the redirect URI for any other host. Create a human user and give it a realm role such as `AUDIT_ADMIN`.
The UI needs Keycloak reachable at start-up. Seed script: `export TOKEN=<AUDIT_WRITER token>` first.

## How searching works now

Every filter box is sent to the Audit Repository as a real search, using the signed-in user's own token, so the Audit Repository's
role rules still decide what each person can see. Totals and paging also come from the Audit Repository (`_total=accurate`, `_offset`).

| Box in the UI | Audit Repository search | Note |
|---|---|---|
| Patient ID | `entity-ref` | Exact match. `PA001` becomes `Patient/PA001`. Partial ids match nothing. |
| Practitioner | `agent-ref` | Exact. `P001` becomes `Practitioner/P001`. |
| Facility | `source-ref` | Exact. `EXAMPLE001` becomes `Organization/EXAMPLE001`; give `Device/x` in full. |
| Encounter | `entity-ref` | Exact. `E-30011` becomes `Encounter/E-30011`. Works only if the sender includes the encounter as an `entity` (`entity.what` = `Encounter/...`). Combined with Patient it is an AND. |
| Department | `site` | Exact and case-sensitive, matching `source.site` as the sender wrote it (`Emergency` does not match `emergency`). Works only if the sender fills `source.site`. |
| Submitted by | `_tag` (`<base>/submitting-client`) | The Keycloak client that sent the event. |
| Purpose, Outcome, Action | `purpose-of-use`, `outcome`, `action` | |
| Break-glass label only | `break-glass=BTG` | Includes ETREAT events that carry the BTG label. |
| Stored as incomplete only | `_tag` (`<base>/tags|policy-incomplete`) | |
| Dates | `date=ge..&date=le..` | |

Known limits (MVP):
- Review decisions (Reviewed / Flagged, with reviewer, time and note) are stored in the UI's own database, and every change is
  also kept in an append-only history table (`audit_review_log`). The Reviewed and Flagged tabs show up to the 500 most
  recent decisions each. The Pending tab total is exact; to fill a page it scans at most the newest 2000 matching events and says so if it cannot.
- Review clock ("SLA"): it runs only for events that carry the break-glass label or are stored as incomplete, and stop when
  someone marks them reviewed or flagged. The bar and `GET /api/v1/audit-events/sla` count them exactly (asked of the Audit Repository, for what the
  signed-in user may see); if more than 1000 candidates exist the result says "at least". Every other event shows "—".
  The hours (`audit.sla.warning-hours`, `audit.sla.overdue-hours`, default 6 and 24) are an organisational choice, not part of FHIR or
  IHE BALP: agree them with the client. The REST `/sla` response changed: fields are now `warn`, `over`, `total`, `truncated`,
  `warningHours`, `overdueHours` (there is no `ok` any more), and an event's `slaStatus` can be `none`.
- `tag base URI` must equal the Audit Repository's `chester.tags.base-uri` (`chester.tags.base-uri` in the UI's settings).
- "Verify chain" appears only for AUDIT_ADMIN, MINISTRY_AUDITOR, SYSTEM and PRIVACY_OFFICER. the Audit Repository enforces this too.
  With From = To it verifies that one UTC day, otherwise all days (can be slow on large data). The last result stays next to the button.
- "Export CSV" downloads what the signed-in user may see for the current filter, at most 5000 rows (the response headers
  `X-Export-Total` and `X-Export-Truncated` say if it was cut). Cells that start with `= + - @` are prefixed with `'` so a spreadsheet
  does not run them as formulas.
- Filters are kept in the page address (and shown as removable chips), so a link can be shared. The server still applies the reader's own access.
- An Audit Repository refusal or outage is shown as an error banner, never as "no events".
- Search values are used literally (a comma, `|`, `$` or `\` typed by a user is escaped, so `P1,P2` does not become "P1 OR P2").
  A value that cannot be used (over 200 characters, a control character, a date that is not `yyyy-MM-dd`) is refused with HTTP 400 /
  an error banner; it is never silently dropped, because a dropped filter would show every event. Sorting is by date only.
- Marking an event reviewed/flagged first reads the event from the Audit Repository with the user's own token; an unknown id gives 404 and nothing is stored.

## Database for review decisions

Set these for the UI (production uses PostgreSQL; without them it falls back to a local H2 file and logs a warning):

```
AUDIT_UI_DB_URL=jdbc:postgresql://localhost:5432/auditui
AUDIT_UI_DB_USER=auditui
AUDIT_UI_DB_PASSWORD=<password>
```

Create it once on the Lightsail PostgreSQL (the tables are created on start):

```
sudo -u postgres psql -c "CREATE USER auditui WITH PASSWORD '<password>';"
sudo -u postgres psql -c "CREATE DATABASE auditui OWNER auditui;"
```

Use a database separate from the Audit Repository's. Back it up with the Audit Repository's database: it is part of the audit trail.

The UI normalises a few legacy JVM time-zone names (for example `Asia/Calcutta` to `Asia/Kolkata`) at start-up, because
PostgreSQL can reject the old name and the UI then fails to start.

## Other notes
- htmx is served by the UI itself (`/js/htmx.min.js`, version 1.9.12) and all pages use system fonts: no page loads anything
  from a third-party site (checked in a browser: the login, review and monitor pages contacted no external host).

## Keycloak: what each role can do in the UI

The Audit Repository decides what a person can READ from their Keycloak realm role. The UI adds one more rule: who may MARK events
reviewed or flagged (`audit.ui.admin-roles`, environment variable `AUDIT_UI_ADMIN_ROLES`, default `AUDIT_ADMIN`).

| Realm role | Reads in the UI | Verify chain | Mark reviewed (default) |
|---|---|---|---|
| AUDIT_ADMIN | everything | yes | yes |
| MINISTRY_AUDITOR, SYSTEM | everything | yes | no |
| PRIVACY_OFFICER | everything | yes | no |
| FACILITY_AUDITOR | its own facility (`facility_id`) | no | no |
| BTG_REVIEWER | break-glass events only | no | no |
| PATIENT_PORTAL | events about its own patient (`patient_id`) | no | no |
| AUDIT_WRITER | nothing (write only; cannot sign in to the UI usefully) | no | no |

If privacy officers or break-glass reviewers are meant to do the reviewing, set for example
`AUDIT_UI_ADMIN_ROLES=AUDIT_ADMIN,PRIVACY_OFFICER,BTG_REVIEWER` (tested: a BTG_REVIEWER can then mark break-glass events reviewed and the
reviewer name is stored). This is a policy decision for the client.

Known limits on the UI side:
- The UI reads a user's roles once, at sign-in. If a role is removed in Keycloak, the user keeps the right to MARK reviewed
  until their UI session ends (reading is unaffected: the Audit Repository checks the token on every request). Keep Keycloak's
  session max short and see `keycloak/REALM-HARDENING.md`.
- Signing out of Keycloak elsewhere does not end the UI session (no back-channel logout yet).
- Session cookie: HttpOnly, SameSite=Lax, 30 minutes idle. Set `AUDIT_UI_SECURE_COOKIE=true` when the UI is served over HTTPS.

# Audit Console (audit-ui)

Review console for the Audit Repository (HAPI FHIR, Keycloak JWT auth). Spring Boot, Thymeleaf, htmx. MVP.

## Run the sample build (no Keycloak, no Audit Repository)
```
cd audit-ui && mvn package -DskipTests
java -jar target/audit-ui-1.0.0.jar --spring.profiles.active=demo      # http://localhost:8081
```
Five made-up people sign in with a form (names and passwords are on the sign-in page). A stand-in audit repository, registry and consent manager run inside the same program with made-up data. **For evaluation only. Never use with real data.** Set `audit.ui.demo-banner=true` (environment `AUDIT_UI_DEMO_BANNER=true`) to show a coloured "sample data" strip on every page; it is off by default.

## Run against the real Audit Repository and Keycloak
Set `AUDIT_UI_KEYCLOAK_ISSUER`, `AUDIT_UI_CLIENT_SECRET`, `CHESTER_FHIR_BASE_URL` (the address of the Audit Repository's FHIR server), and a PostgreSQL database (`AUDIT_UI_DB_URL`, `AUDIT_UI_DB_USER`, `AUDIT_UI_DB_PASSWORD`). See `audit-ui/README-AUTH.md`.

## What was checked, and what was not
Checked on a local test rig only: page rendering in Chromium (light, dark, tablet), roles and access, review marking, preferences, consent gate, and the shell tests in `e2e/` (`test_selfdemo.sh` for the demo; the others need a local Audit Repository and the stand-in sign-in server `mock_idp.py`).

Not checked: real Keycloak, other browsers, screen readers, Sinhala and Tamil wording (files are empty, English shows), load at real volume, any Lightsail deployment. The stand-in audit repository is a small imitation, not the real one. The consent service interface and the registry field choices are assumptions until the real services are known. Known open issue: the Audit Repository's hash-chain writer may deadlock (separate repository).

## Lightsail sample site (untested on a real server)
`deploy/` has a systemd unit, an nginx site and `install-demo.sh DEMO_HOST [APP_PORT] [METRICS_PORT]` (defaults 8085 and 8095; it stops if a port is in use). Use a NEW host name (not the one serving the current UI). The nginx file must keep `X-Forwarded-For`, or the stand-in registry and audit repository are reachable from outside (the app checks this; tested locally with forged headers). Uncomment `auth_basic` in the nginx file to put a password in front. Needs Java 21 and about 500 MB free memory next to SonarQube.

## Appearance
Colour mode (light, dark, automatic) and colour theme (Clinic, Ocean, Ministry, Observatory) are in the header (sun/moon button) and in the account menu; Settings has the same choices with swatches. The choice is saved per person (`user_pref.accent`, added automatically) and applied at once without a reload. Without JavaScript the same buttons post a normal form. Contrast checks for every theme are in `audit-ui/src/main/resources/static/css/README.md`.

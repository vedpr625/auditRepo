# Audit Console (audit-ui)

Review console for the Chester audit-record repository (HAPI FHIR, Keycloak JWT auth). Spring Boot, Thymeleaf, htmx. MVP.

## Run the demo (no Keycloak, no Chester)
```
cd audit-ui && mvn package -DskipTests
java -jar target/audit-ui-1.0.0.jar --spring.profiles.active=demo      # http://localhost:8081
```
Five made-up people sign in with a form (names and passwords are on the login page). A stand-in Chester, registry and consent manager run inside the same program with made-up data. **Demonstration only. Never use with real data.**

## Run against real Chester and Keycloak
Set `AUDIT_UI_KEYCLOAK_ISSUER`, `AUDIT_UI_CLIENT_SECRET`, `CHESTER_FHIR_BASE_URL`, and a PostgreSQL database (`AUDIT_UI_DB_URL`, `AUDIT_UI_DB_USER`, `AUDIT_UI_DB_PASSWORD`). See `audit-ui/README-AUTH.md`.

## What was checked, and what was not
Checked on a local test rig only: page rendering in Chromium (light, dark, tablet), roles and access, review marking, preferences, consent gate, and the shell tests in `e2e/` (`test_selfdemo.sh` for the demo; the others need a local Chester and the stand-in sign-in server `mock_idp.py`).

Not checked: real Keycloak, other browsers, screen readers, Sinhala and Tamil wording (files are empty, English shows), load at real volume, any Lightsail deployment. The stand-in Chester is a small imitation, not Chester. The consent service interface and the registry field choices are assumptions until the real services are known. Known open issue: Chester's hash-chain writer may deadlock (separate repository).

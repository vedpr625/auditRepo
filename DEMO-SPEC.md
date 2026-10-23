# Demo build spec (for parallel screen builders)

Goal: every screen in `design/*.dc.html` exists as a working Thymeleaf page in `audit-ui`, filled with rich made-up data from the demo profile. Where a feature has no real backend yet, show the best demo version and tag it with the dashed `tag-planned` style ("Demo data" / "Planned").

Rules
- Own only your files: controller/service package, templates `<name>.html`, `fragments/<name>.html`, `static/css/page-<name>.css`, `messages-<name>.properties`. Shared files (layout.html, audit-console.css, ShellAdvice, DemoAuditData, messages.properties) are changed only by adding; never rewrite.
- Head: `th:replace="~{fragments/layout :: headx(#{x.title}, 'page-x.css')}"`; header: `fragments/layout :: header('<active>')`. Nav ids: overview explore people reviews breakglass alerts reports health settings admin.
- Text via message keys prefixed with your screen name. English only; si/ta files stay empty. ConsoleUnitTest checks keys.
- Data: Chester search via existing `AuditEventService` (applies the user's access). Extra demo-only state (cases, rules, schedules) goes in in-memory/JDBC tables created in schema.sql with idempotent seed in a `demo/` ApplicationRunner, enabled by `audit.ui.demo`.
- Charts: inline SVG or CSS, strong colours (blue #2a78d6 for OK, red #d03a3a for failed), not washed out. No new JS libraries.
- Reviewer roles: ROLE_ADMIN, MINISTRY_AUDITOR, PRIVACY_OFFICER (`${canReview}` style; use model attribute from your controller). Admin tabs are ADMIN only.
- Verify: build with `mvn -q -o package -DskipTests`, run `java -jar target/audit-ui-*.jar --spring.profiles.active=demo --server.port=<yours> --management.server.port=<yours>`, log in as priya.admin / see README, fetch every route with curl (expect 200), screenshot with Playwright Chromium, compare against `python3 design/render.py <Board>`. Report honestly what you checked.

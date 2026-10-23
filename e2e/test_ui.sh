#!/usr/bin/env bash
UI=http://127.0.0.1:8081; J=${1:-cj.txt}; rm -f $J
pass=0; fail=0
t() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass+1)); else echo "FAIL  $1  (got '$2', expected '$3')"; fail=$((fail+1)); fi; }
c() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
t "page without login redirects to login (302)" "$(c $UI/)" 302
t "REST api without login is refused (302/401/403)" "$(c $UI/api/v1/audit-events/stats | sed 's/30[0-9]/302/;s/40[13]/302/')" 302
t "swagger without login is refused" "$(c $UI/v3/api-docs | sed 's/30[0-9]/302/;s/40[13]/302/')" 302
t "monitor history without login is refused" "$(c $UI/monitor/history | sed 's/30[0-9]/302/;s/40[13]/302/')" 302
# sign in through the (stand-in) identity provider
curl -s -L -c $J -b $J -o /dev/null $UI/oauth2/authorization/keycloak
t "signed in: / returns 200" "$(c -b $J -c $J $UI/)" 200
curl -s -b $J $UI/ -o page.html
t "list shows events (>=20 rows)" "$([ $(grep -c 'data-id=' page.html) -ge 20 ] && echo yes)" yes
t "user name shown" "$(grep -c alice page.html | sed 's/[1-9][0-9]*/1/')" 1
t "admin badge for AUDIT_ADMIN" "$(grep -c 'role-admin' page.html | sed 's/[1-9][0-9]*/1/')" 1
t "status says Chester Live" "$(curl -s -b $J $UI/audit/status | grep -c 'Chester Live')" 1
t "api stats works with login" "$(c -b $J $UI/api/v1/audit-events/stats)" 200
ID=$(grep -o 'data-id="[0-9]*"' page.html | head -1 | grep -o '[0-9]*')
curl -s -b $J $UI/audit/detail/$ID -o detail.html
t "detail drawer shows a chain hash (64 hex)" "$(grep -cE '[0-9a-f]{64}' detail.html | sed 's/[1-9][0-9]*/1/')" 1
t "purpose filter BTG = 2 rows" "$(curl -s -b $J "$UI/audit/rows?purpose=BTG" | grep -c 'data-id=')" 2
t "monitor page loads" "$(c -b $J $UI/monitor)" 200
t "monitor history returns events" "$(curl -s -b $J "$UI/monitor/history?count=5" | grep -c '"resourceType": *"AuditEvent"' | sed 's/[1-9][0-9]*/1/')" 1
t "old stream endpoint gone (404)" "$(c -b $J $UI/monitor/stream)" 404
echo "passed=$pass failed=$fail"

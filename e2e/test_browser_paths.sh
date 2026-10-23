#!/usr/bin/env bash
# What the browser actually does: read the CSRF token from the page's <meta> and send it as a header on the htmx POST.
UI=http://127.0.0.1:8081; J=cjb.txt; rm -f $J
pass=0; fail=0
t() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass+1)); else echo "FAIL  $1  (got '$2', expected '$3')"; fail=$((fail+1)); fi; }
curl -s -L -c $J -b $J -o /dev/null $UI/oauth2/authorization/keycloak
curl -s -b $J -c $J $UI/ -o pg.html
TOK=$(grep -o 'name="_csrf" content="[^"]*"' pg.html | sed 's/.*content="//;s/"//'); HDR=$(grep -o 'name="_csrf_header" content="[^"]*"' pg.html | sed 's/.*content="//;s/"//')
t "page exposes csrf meta tags" "$([ -n "$TOK" ] && [ -n "$HDR" ] && echo yes)" yes
ID=$(grep -o 'data-id="[0-9]*"' pg.html | head -1 | grep -o '[0-9]*')
t "review POST without csrf header is refused" "$(curl -s -o /dev/null -w '%{http_code}' -b $J -c $J -X POST -d status=reviewed $UI/audit/review/$ID)" 403
t "review POST with the page's csrf header works (admin)" "$(curl -s -o /dev/null -w '%{http_code}' -b $J -c $J -X POST -H "$HDR: $TOK" -d status=reviewed $UI/audit/review/$ID)" 200
t "detail id with path tricks is not forwarded (shows not found)" "$(curl -s -b $J "$UI/audit/detail/1%3F_count%3D1" | grep -c "Event Not Found")" 1
t "no hash-chain tick claim in the detail drawer" "$(curl -s -b $J $UI/audit/detail/$ID | grep -c 'integrity verifiable')" 0
t "monitor page no longer claims BALP conformance" "$(curl -s -b $J $UI/monitor | grep -c 'Fully BALP-conformant')" 0
t "monitor page no longer stores events in localStorage" "$(curl -s -b $J $UI/monitor | grep -c 'localStorage.setItem(STORAGE_KEY')" 0
echo "passed=$pass failed=$fail"

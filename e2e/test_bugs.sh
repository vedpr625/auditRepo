#!/usr/bin/env bash
# Regression tests for bugs found in the end-to-end hunt.
UI=http://127.0.0.1:8081; pass=0; fail=0
t() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass+1)); else echo "FAIL  $1  (got '$2', expected '$3')"; fail=$((fail+1)); fi; }
login() { rm -f $1; echo "$2" > user.json; curl -s -L -c $1 -b $1 -o /dev/null $UI/oauth2/authorization/keycloak; }
g() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
login ja '{"sub":"u-admin","name":"alice","roles":["AUDIT_ADMIN"],"extra":{}}'
curl -s -b ja $UI/ -o pb.html; CSRF=$(grep -o 'name="_csrf" value="[^"]*"' pb.html | head -1 | sed 's/.*value="//;s/"//')
LONG=$(python3 -c "print('A'*500)")
t "BUG1 over-long patient filter is refused (400), not ignored" "$(g -b ja "$UI/api/v1/audit-events?patient=$LONG")" 400
t "BUG1 control char in filter is refused (400)" "$(g -b ja "$UI/api/v1/audit-events?agent=%00")" 400
t "BUG1 over-long filter in the page shows an error banner, not all rows" "$(curl -s -b ja "$UI/audit/rows?q=$LONG" | grep -c 'data-id=')" 0
t "BUG1 ...and says why" "$(curl -s -b ja "$UI/audit/rows?q=$LONG" | grep -c 'Invalid search value')" 1
t "BUG1 csv export with an invalid filter is refused (400)" "$(g -b ja "$UI/audit/export.csv?facility=$LONG")" 400
t "BUG1 blank filter is still fine (all events)" "$(curl -s -b ja "$UI/api/v1/audit-events?patient=%20%20&size=1" | python3 -c "import sys,json;print(json.load(sys.stdin)['total'])")" 20
t "special characters are searched literally (pipe)" "$(curl -s -b ja "$UI/api/v1/audit-events?client=x%7Cy&size=1" | python3 -c "import sys,json;print(json.load(sys.stdin)['total'])")" 0
t "special characters are searched literally (comma)" "$(curl -s -b ja "$UI/api/v1/audit-events?patient=a%2Cb&size=1" | python3 -c "import sys,json;print(json.load(sys.stdin)['total'])")" 0
t "a comma does not turn into OR (PA001,PA002 matches nothing)" "$(curl -s -b ja "$UI/api/v1/audit-events?patient=P-00101%2CP-00102&size=1" | python3 -c "import sys,json;print(json.load(sys.stdin)['total'])")" 0
t "BUG2 verify-chain impossible date refused (400)" "$(g -b ja "$UI/audit/verify-chain?partition=9999-99-99")" 400
t "verify-chain real date still works" "$(g -b ja "$UI/audit/verify-chain?partition=2026-10-12")" 200
t "BUG3 unknown sort does not error" "$(g -b ja "$UI/api/v1/audit-events?sort=evil")" 200
t "BUG3 sort=date ascending works (oldest first)" "$(curl -s -b ja "$UI/api/v1/audit-events?sort=date&size=100" | python3 -c "
import sys,json;d=[i['recordedAt'] for i in json.load(sys.stdin)['items']];print(d==sorted(d))")" True
t "sort=-date newest first" "$(curl -s -b ja "$UI/api/v1/audit-events?sort=-date&size=100" | python3 -c "
import sys,json;d=[i['recordedAt'] for i in json.load(sys.stdin)['items']];print(d==sorted(d,reverse=True))")" True
t "BUG4 review of a non-existent event is refused (404)" "$(g -b ja -X POST -d status=reviewed --data-urlencode "_csrf=$CSRF" $UI/audit/review/999999)" 404
t "BUG4 REST review of a non-existent event is refused (404)" "$(g -b ja -X POST -H "X-CSRF-TOKEN: $CSRF" -d status=reviewed $UI/api/v1/audit-events/999999/review)" 404
t "BUG4 nothing was written to the review table" "$(psql -h /tmp -p 5439 -U auditui -d auditui -tA -c "select count(*) from audit_review where event_id='999999'")" 0
t "BUG5 page: garbage date shows an error, not all rows" "$(curl -s -b ja "$UI/audit/rows?dateFrom=garbage" | grep -c 'data-id=')" 0
t "BUG5 csv: garbage date refused (400)" "$(g -b ja "$UI/audit/export.csv?dateTo=2026-02-31x")" 400
t "BUG5 a real date is still accepted" "$(curl -s -b ja "$UI/api/v1/audit-events?dateFrom=2026-10-12&dateTo=2026-10-12&size=1" | python3 -c "import sys,json;print(json.load(sys.stdin)['total'] > 0)")" True
t "escape: dollar sign searched literally" "$(curl -s -b ja "$UI/api/v1/audit-events?patient=%24x&size=1" | python3 -c "import sys,json;print(json.load(sys.stdin)['total'])")" 0
t "escape: backslash searched literally" "$(curl -s -b ja "$UI/api/v1/audit-events?facility=a%5C%2Cb&size=1" | python3 -c "import sys,json;print(json.load(sys.stdin)['total'])")" 0
t "escape: a real patient id still matches" "$(curl -s -b ja "$UI/api/v1/audit-events?patient=P-00101&size=1" | python3 -c "import sys,json;print(json.load(sys.stdin)['total'] > 0)")" True
echo "passed=$pass failed=$fail"

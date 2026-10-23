#!/usr/bin/env bash
# An event that was just written must show up at once in an identical search and in its total (no search-result reuse).
UI=http://127.0.0.1:8081; pass=0; fail=0
t() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass+1)); else echo "FAIL  $1  (got '$2', expected '$3')"; fail=$((fail+1)); fi; }
tok() { curl -s -d grant_type=client_credentials -d client_id=$1 http://127.0.0.1:9000/realms/test/protocol/openid-connect/token | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])"; }
W=$(tok chester-audit-writer); R=$(tok chester-audit-reader-test)
cnt() { curl -s -H "Authorization: Bearer $R" "localhost:8080/fhir/AuditEvent?_total=accurate&_count=1&_sort=-date" | python3 -c "import sys,json;print(json.load(sys.stdin)['total'])"; }
A=$(cnt); B=$(cnt)
t "same search twice gives the same total" "$A" "$B"
curl -s -H "Authorization: Bearer $R" "localhost:8080/fhir/AuditEvent?_count=1" | python3 -c "
import sys,json
e=json.load(sys.stdin)['entry'][0]['resource']
for k in ('id','meta'): e.pop(k,None)
for a in e['agent']:
    a['name']='=HYPERLINK(\"http://evil\",\"x\")'
    if 'who' in a: a['who']['display']='=HYPERLINK(\"http://evil\",\"x\")'
json.dump(e,open('inj.json','w'))"
t "post new event" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $W" -H 'Content-Type: application/fhir+json' -d @inj.json localhost:8080/fhir/AuditEvent)" 201
t "identical search sees the new event immediately" "$(cnt)" "$((A+1))"
echo '{"sub":"u-admin","name":"alice","roles":["AUDIT_ADMIN"],"extra":{}}' > user.json
J=j_fr; rm -f $J; curl -s -L -c $J -b $J -o /dev/null $UI/oauth2/authorization/keycloak
t "UI total sees it immediately" "$(curl -s -b $J "$UI/api/v1/audit-events?size=1" | python3 -c "import sys,json;print(json.load(sys.stdin)['total'])")" "$((A+1))"
curl -s -b $J "$UI/audit/export.csv" -o fr.csv
t "csv: row count = new total" "$(python3 -c "import csv;print(len(list(csv.reader(open('fr.csv',encoding='utf-8-sig'))))-1)")" "$((A+1))"
t "csv: the formula agent is present" "$(python3 -c "
import csv;print(sum('HYPERLINK' in x['agent'] for x in csv.DictReader(open('fr.csv',encoding='utf-8-sig'))))")" 1
t "csv: no cell starts with =" "$(python3 -c "
import csv;print(sum(c.startswith('=') for row in csv.reader(open('fr.csv',encoding='utf-8-sig')) for c in row))")" 0
t "csv: formula cell is prefixed with a quote" "$(python3 -c "
import csv;print(sum(x['agent'].startswith(\"'=\") for x in csv.DictReader(open('fr.csv',encoding='utf-8-sig'))))")" 1
echo "passed=$pass failed=$fail"

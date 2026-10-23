#!/usr/bin/env bash
UI=http://127.0.0.1:8081; CH=http://127.0.0.1:8080/fhir; IDP=http://127.0.0.1:9000/realms/test/protocol/openid-connect/token
pass=0; fail=0
t() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass+1)); else echo "FAIL  $1  (got '$2', expected '$3')"; fail=$((fail+1)); fi; }
c() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
login() { rm -f $1; echo "$2" > user.json; curl -s -L -c $1 -b $1 -o /dev/null $UI/oauth2/authorization/keycloak; }
rows() { curl -s -b $1 "$UI/api/v1/audit-events?size=1" | python3 -c "import sys,json
try: print(json.load(sys.stdin)['total'])
except Exception: print('error')"; }
admintok() { echo '{"sub":"u-tmp","name":"tmp","roles":["AUDIT_ADMIN"]}' > user.json; }

# ---- non-admin reader
login j_min '{"sub":"u-min","name":"bob","roles":["MINISTRY_AUDITOR"]}'
t "MINISTRY_AUDITOR sees all 20 events (real total from Chester)" "$(rows j_min)" 20
t "page also lists rows" "$(curl -s -b j_min $UI/audit/rows | grep -c data-id=)" 20
curl -s -b j_min $UI/ -o p_min.html
t "no admin badge for a non-admin" "$(grep -c 'class=\"role-badge role-admin\"' p_min.html)" 0
CSRF=$(grep -o 'name="_csrf" value="[^"]*"' p_min.html | head -1 | sed 's/.*value="//;s/"//')
ID=$(grep -o 'data-id="[0-9]*"' p_min.html | head -1 | grep -o '[0-9]*')
t "non-admin cannot mark reviewed (403)" "$(c -b j_min -X POST -d status=reviewed --data-urlencode "_csrf=$CSRF" $UI/audit/review/$ID)" 403
t "non-admin cannot use the REST review either (403)" "$(c -b j_min -X POST -d status=reviewed --data-urlencode "_csrf=$CSRF" $UI/api/v1/audit-events/$ID/review)" 403
t "MINISTRY_AUDITOR (unrestricted reader) may see chain info" "$(curl -s -b j_min $UI/audit/detail/$ID | grep -cE '[0-9a-f]{64}' | sed 's/[1-9][0-9]*/some/')" some

# ---- admin may review (with CSRF)
login j_adm '{"sub":"u-adm","name":"alice","roles":["AUDIT_ADMIN"]}'
curl -s -b j_adm $UI/ -o p_adm.html
CSRF=$(grep -o 'name="_csrf" value="[^"]*"' p_adm.html | head -1 | sed 's/.*value="//;s/"//')
t "admin sees chain hash in drawer" "$(curl -s -b j_adm $UI/audit/detail/$ID | grep -cE '[0-9a-f]{64}' | sed 's/[1-9][0-9]*/some/')" some
t "admin can mark reviewed (2xx)" "$(c -b j_adm -X POST -d status=reviewed --data-urlencode "_csrf=$CSRF" $UI/audit/review/$ID | cut -c1)" 2
t "review without CSRF token is refused (403)" "$(c -b j_adm -X POST -d status=reviewed $UI/audit/review/$ID)" 403

# ---- scoped users: Chester narrows, the UI only relays the user's own token
login j_fac '{"sub":"u-fac","name":"carol","roles":["FACILITY_AUDITOR"],"extra":{"facility_id":"Device/hhims-colombo-01"}}'
t "FACILITY_AUDITOR sees only its facility (10 of 20)" "$(rows j_fac)" 10
login j_fac2 '{"sub":"u-fac2","name":"dave","roles":["FACILITY_AUDITOR"],"extra":{"facility_id":"Device/some-other-system"}}'
t "FACILITY_AUDITOR for another source sees none (no leak)" "$(rows j_fac2)" 0
login j_none '{"sub":"u-none","name":"erin","roles":["SOME_OTHER_ROLE"]}'
t "user with no Chester role gets an error, not data" "$(rows j_none)" error
t "...and the page still renders (200)" "$(c -b j_none $UI/)" 200
t "...and monitor history is refused for them (403)" "$(c -b j_none $UI/monitor/history)" 403
t "scoped user cannot read chain info (no 64-hex)" "$(curl -s -b j_fac $UI/audit/detail/$ID | grep -cE '[0-9a-f]{64}')" 0

# ---- logout
curl -s -b j_adm -c j_adm -o /dev/null -L -X POST --data-urlencode "_csrf=$CSRF" $UI/logout
t "after logout the page needs login again (302)" "$(c -b j_adm $UI/)" 302
echo "passed=$pass failed=$fail"

# ---- new Chester-side filters (admin), each result checked against the independent full list
login j_adm2 '{"sub":"u-admin","name":"alice","roles":["AUDIT_ADMIN"]}'
ALL=$(curl -s -b j_adm2 "$UI/api/v1/audit-events?size=200")
cnt() { curl -s -b j_adm2 "$UI/api/v1/audit-events?size=1&$1" | python3 -c "import sys,json
try: print(json.load(sys.stdin)['total'])
except Exception: print('error')"; }
exp() { echo "$ALL" | python3 -c "import sys,json;d=json.load(sys.stdin)['items'];print(sum(1 for i in d if $1))"; }
t "filter outcome=4 matches full list" "$(cnt outcome=4)" "$(exp "i['outcomeCode']=='4'")"
t "filter outcome=8 matches full list" "$(cnt outcome=8)" "$(exp "i['outcomeCode']=='8'")"
t "filter action=R matches full list" "$(cnt action=R)" "$(exp "i['action']=='R'")"
t "filter breakGlass label matches full list" "$(cnt breakGlass=true)" "$(exp "i['breakGlass']")"
t "filter incomplete matches full list" "$(cnt incomplete=true)" "$(exp "i['incomplete']")"
P=$(echo "$ALL" | python3 -c "import sys,json;print(json.load(sys.stdin)['items'][0]['patientRef'].split('/')[-1])")
t "filter patient (exact) matches full list" "$(cnt patient=$P)" "$(exp "i['patientRef'].split('/')[-1]=='$P'")"
t "filter patient partial id matches nothing (exact search)" "$(cnt patient=${P%?})" 0
t "filter facility via source-ref matches full list" "$(cnt facility=Device/hhims-kandy-01)" "$(exp "i['facility']=='Device/hhims-kandy-01'")"
t "garbage date is rejected, not ignored" "$(c -b j_adm2 "$UI/api/v1/audit-events?dateFrom=not-a-date")" 400
t "paging: page 2 of size 10 has 10 rows" "$(curl -s -b j_adm2 "$UI/audit/rows?size=10&page=2" | grep -c data-id=)" 10
t "page has Verify chain button for admin" "$(curl -s -b j_adm2 $UI/ | grep -c 'id="verifyBtn"')" 1
t "verify-chain allowed for admin (valid true)" "$(curl -s -b j_adm2 $UI/audit/verify-chain | python3 -c "import sys,json;print(json.load(sys.stdin).get('valid'))")" True
t "verify-chain bad partition refused (400)" "$(c -b j_adm2 "$UI/audit/verify-chain?partition=x")" 400
t "verify-chain refused for scoped facility auditor (403)" "$(c -b j_fac $UI/audit/verify-chain)" 403
t "no Verify button for scoped facility auditor" "$(curl -s -b j_fac $UI/ | grep -c 'id="verifyBtn"')" 0
echo "passed=$pass failed=$fail"

#!/usr/bin/env bash
# The self-contained demo build (profile "demo"): form login with fixed users, stand-in Chester, registry and consent inside one program.
UI=http://127.0.0.1:8081; pass=0; fail=0
t() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass+1)); else echo "FAIL  $1  (got '$2', expected '$3')"; fail=$((fail+1)); fi; }
c() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
login() { rm -f $1; T=$(curl -s -c $1 -b $1 $UI/login | grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"//'); curl -s -c $1 -b $1 -o /dev/null -w '%{redirect_url}' -d "username=$2" -d "password=$3" -d "_csrf=$T" $UI/login; }
csrf() { curl -s -b $1 -c $1 $UI/settings | grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"//'; }
total() { curl -s -b $1 "$UI/api/v1/audit-events?size=1$2" | python3 -c "import sys,json
try: print(json.load(sys.stdin)['total'])
except Exception: print('error')"; }
t "login page lists five people" "$(curl -s $UI/login | grep -c 'name="username" value=')" 5
t "no login: /overview redirects" "$(c $UI/overview)" 302
t "wrong password refused" "$(login j_bad priya.admin nope)" "$UI/login?error"
t "right password goes to overview" "$(login j_a priya.admin priya@demo)" "$UI/overview"
login j_m nimal.ministry nimal@demo >/dev/null; login j_f kumari.facility kumari@demo >/dev/null; login j_p samantha.privacy samantha@demo >/dev/null; login j_n erin.noaudit erin@demo >/dev/null
for p in overview health settings "" ; do t "admin sees /$p" "$(c -b j_a $UI/$p)" 200; done
t "admin sees /admin" "$(c -b j_a $UI/admin)" 200
for u in m f p n; do t "$u refused /admin" "$(c -b j_$u $UI/admin)" 403; done
A=$(total j_a); t "admin sees about 140 records" "$([ "$A" != error ] && [ "$A" -ge 120 ] && echo yes)" yes
t "ministry sees all" "$(total j_m)" "$A"; t "privacy officer sees all" "$(total j_p)" "$A"
F=$(total j_f); t "facility auditor sees fewer, not zero" "$([ "$F" != error ] && [ "$F" -lt "$A" ] && [ "$F" -gt 0 ] && echo yes)" yes
t "no-role person gets an error, not data" "$(total j_n)" error
# filters agree with each other
B=$(total j_a "&breakGlass=true"); t "at least 5 break-glass records" "$([ "$B" != error ] && [ "$B" -ge 5 ] && echo yes)" yes
t "break-glass via purpose filter agrees" "$(total j_a "&purpose=BTG")" "$B"
I=$(total j_a "&incomplete=true"); t "at least 3 incomplete records" "$([ "$I" != error ] && [ "$I" -ge 3 ] && echo yes)" yes
T1=$(total j_a "&purpose=TREAT"); P1=$(total j_a "&purpose=PUBHLTH"); H1=$(total j_a "&purpose=HRESCH"); E1=$(total j_a "&purpose=ETREAT")
t "no purpose count exceeds the total" "$([ "$T1" -le "$A" ] && [ "$P1" -le "$A" ] && [ "$H1" -le "$A" ] && [ "$E1" -le "$A" ] && echo yes)" yes
t "patient filter works" "$([ "$(total j_a "&q=P-00101")" -gt 0 ] && echo yes)" yes
t "Overview kpis show data" "$(curl -s -b j_a $UI/overview/body | grep -c 'Needs a human')" 1
t "Overview queue lists at least 3 ranked items" "$([ "$(curl -s -b j_a $UI/overview/body | grep -o 'Risk [0-9]*' | wc -l)" -ge 3 ] && echo yes)" yes
t "Health shows Chester up" "$(curl -s -b j_a $UI/health/systems | grep -c 'Up')" 2
V=$(curl -s -b j_a -X POST -H "X-CSRF-TOKEN: $(csrf j_a)" $UI/health/verify | sed 's/<[^>]*>/ /g' | tr -s ' \n' ' '); t "integrity check says intact" "$(echo "$V" | grep -c 'Intact')" 1
t "facility auditor may not run integrity check" "$(curl -s -b j_f -X POST -H "X-CSRF-TOKEN: $(csrf j_f)" $UI/health/verify | grep -c 'Could not check')" 7
ID=$(curl -s -b j_a "$UI/audit/rows?size=1" | grep -o 'data-id="[0-9]*"' | head -1 | grep -o '[0-9]*'); D=$(curl -s -b j_a $UI/audit/detail/$ID)
t "detail drawer shows a 64-hex chain hash" "$(echo "$D" | grep -cE '[0-9a-f]{64}' | sed 's/[1-9][0-9]*/1/')" 1
t "admin can mark reviewed" "$(c -b j_a -X POST -H "X-CSRF-TOKEN: $(csrf j_a)" -d status=reviewed $UI/audit/review/$ID)" 200
t "ministry auditor may mark reviewed (reviewer role)" "$(c -b j_m -X POST -H "X-CSRF-TOKEN: $(csrf j_m)" -d status=reviewed $UI/audit/review/$ID)" 200
t "facility auditor cannot mark reviewed" "$(c -b j_f -X POST -H "X-CSRF-TOKEN: $(csrf j_f)" -d status=reviewed $UI/audit/review/$ID)" 403
t "stand-in Chester not reachable from outside" "$(c -b j_a -H 'X-Forwarded-For: 9.9.9.9' $UI/demo/chester/fhir/AuditEvent)" 403
P=$(curl -s -b j_m $UI/patient/P-00101 | sed 's/<[^>]*>/ /g' | tr -s ' \n' ' '); t "patient page permitted (name shown)" "$(echo "$P" | grep -c 'Nimali Perera')" 1
P=$(curl -s -b j_m $UI/patient/P-00104 | sed 's/<[^>]*>/ /g' | tr -s ' \n' ' '); t "opted-out patient hidden" "$(echo "$P" | grep -c 'Sithara')" 0
P=$(curl -s -b j_p $UI/patient/P-00112 | sed 's/<[^>]*>/ /g' | tr -s ' \n' ' '); t "privacy officer sees sensitive patient" "$(echo "$P" | grep -c 'Dilani')" 1
t "metrics on management port" "$(c http://127.0.0.1:8091/actuator/prometheus)" 200
t "sign out works" "$(c -b j_m -c j_m -X POST -d _csrf=$(csrf j_m) $UI/logout)" 302
echo "passed=$pass failed=$fail"

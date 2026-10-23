#!/usr/bin/env bash
# Demo sign-in: each demo person gets the access the role promises. Needs the rig with the mock IdP started in demo mode (start_idp_demo.sh).
UI=http://127.0.0.1:8081; pass=0; fail=0
t() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass+1)); else echo "FAIL  $1  (got '$2', expected '$3')"; fail=$((fail+1)); fi; }
c() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
authurl() { curl -s -c $1 -b $1 -o /dev/null -w '%{redirect_url}' $UI/oauth2/authorization/keycloak; }
pick() { rm -f $1; L=$(authurl $1); curl -s -L -c $1 -b $1 -o /dev/null "$L&demo_user=$2"; }
total() { curl -s -b $1 "$UI/api/v1/audit-events?size=1" | python3 -c "import sys,json
try: print(json.load(sys.stdin)['total'])
except Exception: print('error')"; }
rm -f d_pick; t "picker page shows five people" "$(curl -s "$(authurl d_pick)" | grep -o 'name="demo_user"' | wc -l)" 5
for u in priya nimal kumari samantha erin; do pick d_$u $u; done
t "admin: Admin page" "$(c -b d_priya $UI/admin)" 200
for u in nimal kumari samantha erin; do t "$u: Admin page refused" "$(c -b d_$u $UI/admin)" 403; done
A=$(total d_priya)
t "admin sees all events" "$([ "$A" != error ] && [ "$A" -ge 20 ] && echo yes)" yes
t "ministry auditor sees all events" "$(total d_nimal)" "$A"
t "privacy officer sees all events" "$(total d_samantha)" "$A"
F=$(total d_kumari); t "facility auditor sees some, not all" "$([ "$F" != error ] && [ "$F" -lt "$A" ] && [ "$F" -gt 0 ] && echo yes)" yes
t "no-role person sees nothing" "$(total d_erin | sed 's/^0$/none/;s/^error$/none/')" none
t "role label shown (ministry)" "$(curl -s -b d_nimal $UI/overview | grep -c 'Ministry auditor' | sed 's/[1-9][0-9]*/1/')" 1
t "ministry auditor cannot mark reviewed" "$(c -b d_nimal -X POST $UI/audit/review/1)" 403
echo "passed=$pass failed=$fail"

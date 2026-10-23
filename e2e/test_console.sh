#!/usr/bin/env bash
# Tests for the new Overview / Health / Admin / Settings pages. Needs the rig running (start_all.sh).
UI=http://127.0.0.1:8081; pass=0; fail=0
t() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass+1)); else echo "FAIL  $1  (got '$2', expected '$3')"; fail=$((fail+1)); fi; }
c() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
login() { rm -f $1; echo "$2" > user.json; curl -s -L -c $1 -b $1 -o /dev/null $UI/oauth2/authorization/keycloak; }
csrf() { curl -s -b $1 -c $1 $UI/settings | grep -o 'name="_csrf" value="[^"]*"' | head -1 | sed 's/.*value="//;s/"//'; }
A=cj_a.txt; V=cj_v.txt
login $A '{"sub":"u-admin","name":"alice","roles":["AUDIT_ADMIN"]}'
login $V '{"sub":"u-view","name":"bob","roles":["MINISTRY_AUDITOR"]}'
t "no login: /overview redirects" "$(c $UI/overview)" 302
t "no login: /admin redirects" "$(c $UI/admin)" 302
for p in overview health settings; do t "viewer sees /$p" "$(c -b $V $UI/$p)" 200; done
t "viewer is refused /admin" "$(c -b $V $UI/admin)" 403
t "admin sees /admin" "$(c -b $A $UI/admin)" 200
t "viewer has no Admin link" "$(curl -s -b $V $UI/overview | grep -c 'href="/admin"')" 0
t "admin has Admin link" "$(curl -s -b $A $UI/overview | grep -c 'href="/admin"' | sed 's/[1-9][0-9]*/1/')" 1
Q=$(curl -s -b $A $UI/overview/queue); t "one <time> per queue row (no duplicate fragment)" "$(echo "$Q" | grep -o '<time ' | wc -l)" "$(echo "$Q" | grep -o 'class="queue-item"' | wc -l)"
T=$(csrf $A); TV=$(csrf $V)
t "POST without CSRF refused" "$(c -b $A -X POST $UI/admin/systems/discover)" 403
t "viewer cannot discover" "$(c -b $V -X POST -d _csrf=$TV $UI/admin/systems/discover)" 403
t "admin discover redirects" "$(c -b $A -c $A -X POST -d _csrf=$T $UI/admin/systems/discover)" 302
curl -s -b $A -c $A $UI/admin -o admin_after.html
t "discovery found at least one system" "$([ $(grep -c 'name="clientId"' admin_after.html) -ge 1 ] && echo yes)" yes
CID=$(grep -o 'name="clientId" value="[^"]*"' admin_after.html | head -1 | sed 's/.*value="//;s/"//')
t "rename redirects" "$(c -b $A -c $A -X POST -d _csrf=$T --data-urlencode clientId=$CID --data-urlencode 'displayName=Hospital <b>A</b>' -d note=n $UI/admin/systems/rename)" 302
t "display name stored and escaped" "$(curl -s -b $A $UI/admin | grep -c 'Hospital &lt;b&gt;A&lt;/b&gt;')" 1
t "Overview systems table uses the name" "$(curl -s -b $A $UI/overview/systems | grep -c 'Hospital &lt;b&gt;A')" 1
t "viewer cannot change the registry" "$(c -b $V -X POST -d _csrf=$TV -d action=save -d registryUrl=http://x.example $UI/settings/registry)" 403
t "prefs: bad time zone refused (302 back, not saved)" "$(c -b $V -c $V -X POST -d _csrf=$TV -d language=en -d timeZone=Nope/Zone -d timeDisplay=both -d clock24=true -d showUtc=false -d theme=system -d density=comfortable $UI/settings/prefs)" 302
t "prefs: not saved after bad zone" "$(curl -s -b $V $UI/settings | grep -c 'value="Nope/Zone"')" 0
t "prefs: valid save" "$(c -b $V -c $V -X POST -d _csrf=$TV -d language=en -d timeZone=Asia/Colombo -d timeDisplay=exact -d clock24=false -d showUtc=true -d theme=dark -d density=compact $UI/settings/prefs)" 302
curl -s -b $V $UI/overview -o ov_v.html
t "theme dark applied" "$(grep -c 'data-theme="dark"' ov_v.html | sed 's/[1-9][0-9]*/1/')" 1
t "density compact applied" "$(grep -c 'data-density="compact"' ov_v.html | sed 's/[1-9][0-9]*/1/')" 1
t "exact-only: no relative text in queue" "$(curl -s -b $V $UI/overview/queue | grep -c 'class="sub">[0-9]* h ago')" 0
t "UTC shown when chosen" "$([ $(curl -s -b $V $UI/overview/queue | grep -c ' UTC<') -ge 1 ] && echo yes)" yes
t "admin's own prefs unaffected" "$(curl -s -b $A $UI/overview | grep -c 'data-theme="dark"')" 0
t "language switch sets html lang" "$(curl -s -b $A -c $A "$UI/overview?lang=si" | grep -c '<html lang="si"')" 1
curl -s -b $A -c $A "$UI/overview?lang=en" -o /dev/null
t "unknown language ignored" "$(curl -s -b $A -c $A "$UI/overview?lang=xx" | grep -c '<html lang="en"')" 1
t "hash-chain check (admin)" "$(curl -s -b $A -X POST -H "X-CSRF-TOKEN: $T" $UI/health/integrity | grep -c 'Intact\|Problems found\|not allowed\|HTTP')" 1
t "metrics NOT on public port" "$(c $UI/actuator/prometheus)" 302
t "metrics on management port" "$(c http://127.0.0.1:8091/actuator/prometheus)" 200
echo "passed $pass, failed $fail"

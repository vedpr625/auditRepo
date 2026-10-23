#!/usr/bin/env bash
# Review decisions in the UI's PostgreSQL database: tabs, totals, reviewer, history, persistence over a UI restart.
UI=http://127.0.0.1:8081; pass=0; fail=0
t() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass+1)); else echo "FAIL  $1  (got '$2', expected '$3')"; fail=$((fail+1)); fi; }
c() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
PSQL="psql -h /tmp -p 5439 -U auditui -d auditui -tA"
login() { rm -f $1; echo "$2" > user.json; curl -s -L -c $1 -b $1 -o /dev/null $UI/oauth2/authorization/keycloak; }
tot() { curl -s -b $J "$UI/api/v1/audit-events?size=1&reviewStatus=$1" | python3 -c "import sys,json
try: print(json.load(sys.stdin)['total'])
except Exception: print('error')"; }
rowsin() { curl -s -b $J "$UI/audit/rows?tab=$1&size=50" | grep -o 'data-id="[0-9]*"' | grep -o '[0-9]*' | sort | tr '\n' ' '; }
$PSQL -c "truncate audit_review, audit_review_log" >/dev/null
J=j_rv; login $J '{"sub":"u-admin","name":"alice","roles":["AUDIT_ADMIN"],"extra":{}}'
curl -s -b $J $UI/ -o p_rv.html
CSRF=$(grep -o 'name="_csrf" value="[^"]*"' p_rv.html | head -1 | sed 's/.*value="//;s/"//')
IDS=($(curl -s -b $J "$UI/api/v1/audit-events?size=5" | python3 -c "import sys,json;print(' '.join(i['id'] for i in json.load(sys.stdin)['items']))"))
t "fresh: pending total = 20"  "$(tot pending)" 20
t "fresh: reviewed total = 0"  "$(tot reviewed)" 0
mark() { c -b $J -X POST -d "status=$2" --data-urlencode "note=$3" --data-urlencode "_csrf=$CSRF" $UI/audit/review/$1; }
t "mark reviewed works" "$(mark ${IDS[0]} reviewed 'checked ok')" 200
t "mark reviewed (2nd)" "$(mark ${IDS[1]} reviewed '')" 200
t "mark flagged works" "$(mark ${IDS[2]} flagged 'suspicious')" 200
t "bad status refused (400)" "$(mark ${IDS[3]} hacked '')" 400
t "bad event id refused (400)" "$(mark 'x%27%3Bdrop' reviewed '')" 400
t "pending total = 17"  "$(tot pending)" 17
t "reviewed total = 2"  "$(tot reviewed)" 2
t "flagged total = 1"   "$(tot flagged)" 1
t "reviewed tab lists exactly the two reviewed ids" "$(rowsin reviewed)" "$(printf '%s\n' ${IDS[0]} ${IDS[1]} | sort | tr '\n' ' ')"
t "pending tab lists none of the decided ids" "$(rowsin pending | tr ' ' '\n' | grep -cE "^(${IDS[0]}|${IDS[1]}|${IDS[2]})$")" 0
t "pending tab lists 17 rows" "$(rowsin pending | wc -w)" 17
t "DB has the reviewer name" "$($PSQL -c "select reviewer from audit_review where event_id='${IDS[0]}'")" alice
t "DB keeps the note" "$($PSQL -c "select note from audit_review where event_id='${IDS[2]}'")" suspicious
# changing a decision keeps history
t "re-mark reviewed -> flagged" "$(mark ${IDS[0]} flagged 'second look')" 200
t "history has 2 rows for that event" "$($PSQL -c "select count(*) from audit_review_log where event_id='${IDS[0]}'")" 2
t "current table has 1 row for that event" "$($PSQL -c "select count(*) from audit_review where event_id='${IDS[0]}'")" 1
t "drawer shows reviewer name" "$(curl -s -b $J $UI/audit/detail/${IDS[0]} | grep -c 'alice' | sed 's/[1-9][0-9]*/some/')" some
t "drawer names the reviewer" "$(curl -s -b $J $UI/audit/detail/${IDS[0]} | grep -c 'Reviewed by')" 1
t "drawer shows the history note" "$(curl -s -b $J $UI/audit/detail/${IDS[0]} | grep -c 'second look' | sed 's/[1-9][0-9]*/some/')" some
# filters combine with tabs
t "flagged + action=R matches the independent count" "$(curl -s -b $J "$UI/api/v1/audit-events?size=1&reviewStatus=flagged&action=R" | python3 -c "import sys,json;print(json.load(sys.stdin)['total'])")" \
  "$(for i in ${IDS[0]} ${IDS[2]}; do curl -s -b $J "$UI/api/v1/audit-events/$i" | python3 -c "import sys,json;print(json.load(sys.stdin)['action'])"; done | grep -c '^R$')"
# persistence: restart the UI and ask again
for p in $(ps aux | grep "[a]udit-ui-1.0.0.jar" | awk '{print $2}'); do kill $p; done; sleep 4
./start_ui.sh >/dev/null
login $J '{"sub":"u-admin","name":"alice","roles":["AUDIT_ADMIN"],"extra":{}}'
t "after UI restart: reviewed total still 1"  "$(tot reviewed)" 1
t "after UI restart: flagged total still 2"   "$(tot flagged)" 2
t "after UI restart: pending total still 17"  "$(tot pending)" 17
echo "passed=$pass failed=$fail"

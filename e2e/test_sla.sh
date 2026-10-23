#!/usr/bin/env bash
# SLA: exact, only for unreviewed break-glass / incomplete events. Expected values come from the full list, computed here.
UI=http://127.0.0.1:8081; pass=0; fail=0
t() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass+1)); else echo "FAIL  $1  (got '$2', expected '$3')"; fail=$((fail+1)); fi; }
tok() { curl -s -d grant_type=client_credentials -d client_id=$1 http://127.0.0.1:9000/realms/test/protocol/openid-connect/token | python3 -c "import sys,json;print(json.load(sys.stdin)['access_token'])"; }
login() { rm -f $1; echo "$2" > user.json; curl -s -L -c $1 -b $1 -o /dev/null $UI/oauth2/authorization/keycloak; }
W=$(tok chester-audit-writer); R=$(tok chester-audit-reader-test)
PSQL="psql -h /tmp -p 5439 -U auditui -d auditui -tA"; $PSQL -c "truncate audit_review, audit_review_log" >/dev/null
# add an INCOMPLETE event (no purpose of event) recorded 30 h ago
curl -s -H "Authorization: Bearer $R" "localhost:8080/fhir/AuditEvent?_count=1" | python3 -c "
import sys,json,datetime
e=json.load(sys.stdin)['entry'][0]['resource']
for k in ('id','meta'): e.pop(k,None)
e.pop('purposeOfEvent',None)
e['recorded']=(datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(hours=30)).strftime('%Y-%m-%dT%H:%M:%SZ')
json.dump(e,open('inc.json','w'))"
t "post incomplete event (no purpose)" "$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $W" -H 'Content-Type: application/fhir+json' -d @inc.json localhost:8080/fhir/AuditEvent)" 201
login j_sla '{"sub":"u-admin","name":"alice","roles":["AUDIT_ADMIN"],"extra":{}}'
curl -s -b j_sla $UI/ -o p_sla.html; CSRF=$(grep -o 'name="_csrf" value="[^"]*"' p_sla.html | head -1 | sed 's/.*value="//;s/"//')
expect() { curl -s -b j_sla "$UI/api/v1/audit-events?size=100" | python3 -c "
import sys,json,datetime
now=datetime.datetime.now(datetime.timezone.utc); over=warn=0; ids=[]
for i in json.load(sys.stdin)['items']:
    if not (i['breakGlass'] or i['incomplete']) or i['reviewStatus']!='pending': continue
    age=int((now-datetime.datetime.fromisoformat(i['recordedAt'].replace('Z','+00:00'))).total_seconds()//3600)
    if age>=24: over+=1
    elif age>=6: warn+=1
print(over, warn)"; }
bar() { curl -s -b j_sla $UI/audit/sla-bar | python3 -c "
import sys,re
h=sys.stdin.read(); n=re.findall(r'sla-count (?:over|soon)\"[^>]*>(\d+)<',h); print(n[0],n[1])"; }
rest() { curl -s -b j_sla $UI/api/v1/audit-events/sla | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['over'],d['warn'])"; }
E=$(expect)
t "expected is not trivially zero" "$([ "$E" != "0 0" ] && echo yes)" yes
t "sla bar equals independent count" "$(bar)" "$E"
t "REST /sla equals independent count" "$(rest)" "$E"
t "REST /sla is not truncated" "$(curl -s -b j_sla $UI/api/v1/audit-events/sla | python3 -c "import sys,json;print(json.load(sys.stdin)['truncated'])")" False
# marking an overdue break-glass/incomplete event reviewed removes it from the count
ID=$(curl -s -b j_sla "$UI/api/v1/audit-events?size=100" | python3 -c "
import sys,json,datetime
now=datetime.datetime.now(datetime.timezone.utc)
for i in json.load(sys.stdin)['items']:
    age=int((now-datetime.datetime.fromisoformat(i['recordedAt'].replace('Z','+00:00'))).total_seconds()//3600)
    if (i['breakGlass'] or i['incomplete']) and age>=24: print(i['id']); break")
O=${E% *}; Wn=${E#* }
t "mark one overdue event reviewed" "$(curl -s -o /dev/null -w '%{http_code}' -b j_sla -X POST -d status=reviewed --data-urlencode "_csrf=$CSRF" $UI/audit/review/$ID)" 200
t "overdue count drops by exactly one" "$(bar)" "$((O-1)) $Wn"
t "re-expected matches after the review" "$(bar)" "$(expect)"
# rows: SLA shown only for applicable events
curl -s -b j_sla "$UI/api/v1/audit-events?size=100" | python3 -c "
import sys,json
d=json.load(sys.stdin)['items']
bad=[i['id'] for i in d if not (i['breakGlass'] or i['incomplete']) and i['slaStatus']!='none']
bad+=[i['id'] for i in d if i['reviewStatus']!='pending' and i['slaStatus']!='none']
print(len(bad))" > sla_bad.txt
t "non-applicable or reviewed events show slaStatus none" "$(cat sla_bad.txt)" 0
t "html rows: a plain event shows no SLA hours" "$(curl -s -b j_sla "$UI/audit/rows?size=100" | grep -c 'sla-none')" "$(curl -s -b j_sla "$UI/api/v1/audit-events?size=100" | python3 -c "import sys,json;print(sum(1 for i in json.load(sys.stdin)['items'] if i['slaStatus']=='none'))")"
t "bar no longer claims an IHE BALP SLA" "$(curl -s -b j_sla $UI/audit/sla-bar | grep -ci 'IHE BALP')" 0
# scoped user: counts only what that user may see
login j_slf '{"sub":"u-fac","name":"carol","roles":["FACILITY_AUDITOR"],"extra":{"facility_id":"Device/hhims-kandy-01"}}'
FE=$(curl -s -b j_slf "$UI/api/v1/audit-events?size=100" | python3 -c "
import sys,json,datetime
now=datetime.datetime.now(datetime.timezone.utc); over=warn=0
for i in json.load(sys.stdin)['items']:
    if not (i['breakGlass'] or i['incomplete']) or i['reviewStatus']!='pending': continue
    age=int((now-datetime.datetime.fromisoformat(i['recordedAt'].replace('Z','+00:00'))).total_seconds()//3600)
    if age>=24: over+=1
    elif age>=6: warn+=1
print(over, warn)")
t "facility auditor's bar equals its own independent count" "$(curl -s -b j_slf $UI/audit/sla-bar | python3 -c "
import sys,re
h=sys.stdin.read(); n=re.findall(r'sla-count (?:over|soon)\"[^>]*>(\d+)<',h); print(n[0],n[1])")" "$FE"
login j_slx '{"sub":"u-none","name":"erin","roles":["SOME_OTHER_ROLE"],"extra":{}}'
t "user with no Chester role: SLA reports an error, not zero" "$(curl -s -o /dev/null -w '%{http_code}' -b j_slx $UI/api/v1/audit-events/sla)" 502
echo "passed=$pass failed=$fail"

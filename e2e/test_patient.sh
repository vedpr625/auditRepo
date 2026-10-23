#!/usr/bin/env bash
# Patient page, fake registry and fake consent manager. Needs: rig + start_idp_demo.sh + restart_ui_demo.sh
UI=http://127.0.0.1:8081; pass=0; fail=0; PSQL="psql -h /tmp -p 5439 -U auditui -d auditui -tA"
t() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass+1)); else echo "FAIL  $1  (got '$2', expected '$3')"; fail=$((fail+1)); fi; }
c() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
authurl() { curl -s -c $1 -b $1 -o /dev/null -w '%{redirect_url}' $UI/oauth2/authorization/keycloak; }
pick() { rm -f $1; L=$(authurl $1); curl -s -L -c $1 -b $1 -o /dev/null "$L&demo_user=$2"; }
page() { curl -s -b $1 $UI/patient/$2 | sed 's/<[^>]*>/ /g' | tr -s ' \n' ' '; }
for u in priya nimal kumari samantha; do pick p_$u $u; done
$PSQL -c "truncate consent_check_log" >/dev/null
R=$(c -H "X-Forwarded-For: 1.2.3.4" $UI/demo/registry/Patient/P-00101); t "registry not readable through a proxy" "$([ "$R" = 302 ] || [ "$R" = 403 ] && echo refused)" refused
R=$(c -H "X-Forwarded-For: 1.2.3.4" "$UI/demo/consent/check?patient=Patient/P-00101"); t "consent service not readable through a proxy" "$([ "$R" = 302 ] || [ "$R" = 403 ] && echo refused)" refused
t "banner shown in demo mode" "$(curl -s -b p_nimal $UI/overview | grep -c 'DEMONSTRATION')" 1
P=$(page p_nimal P-00101)
t "permitted patient: name shown" "$(echo "$P" | grep -c 'Nimali Perera')" 1
t "permitted patient: tagged demo" "$(echo "$P" | grep -c 'Demo data')" 1
t "Colombo treatment record is linked" "$(echo "$P" | grep -c 'Linked to care')" 1
t "Kandy emergency record for a Colombo-only patient is flagged" "$(echo "$P" | grep -c 'No link to this patient')" 1
P=$(page p_nimal P-00104)
t "opted-out patient: name NOT shown" "$(echo "$P" | grep -c 'Sithara')" 0
t "opted-out patient: reason shown" "$(echo "$P" | grep -c 'opted out')" 1
t "opted-out patient: records still listed" "$(echo "$P" | grep -c 'Records about this patient')" 1
P=$(page p_nimal P-00116); t "no consent on file: name NOT shown" "$(echo "$P" | grep -c 'Ishara')" 0
P=$(page p_nimal P-00117); t "expired consent: name NOT shown" "$(echo "$P" | grep -c 'Gamini')" 0
P=$(page p_nimal P-00112); t "sensitive record: ministry auditor sees no name" "$(echo "$P" | grep -c 'Dilani')" 0
P=$(page p_samantha P-00112); t "sensitive record: privacy officer sees name" "$(echo "$P" | grep -c 'Dilani')" 1
P=$(page p_priya P-00110); t "unknown-to-registry-or-invisible id gives nothing found" "$(page p_priya NOPE-1 | grep -c 'Nothing found')" 1
t "facility auditor cannot open a Kandy-only patient (no visible records)" "$(page p_kumari P-00110 | grep -c 'Nothing found')" 1
t "bad id refused" "$(curl -s -b p_nimal -o /dev/null -w '%{redirect_url}' "$UI/patient?id=a%20b%3Cscript%3E")" "$UI/overview?badPatient"
N=$($PSQL -c "select count(*) from consent_check_log"); t "consent log has the checks" "$([ "$N" -ge 7 ] && echo yes)" yes
t "log records a denial with the role" "$($PSQL -c "select count(*) from consent_check_log where patient_ref='Patient/P-00104' and decision='deny' and actor_role='MINISTRY_AUDITOR'")" 1
t "no registry call for denied patient (name never in page source)" "$(curl -s -b p_nimal $UI/patient/P-00104 | grep -c 'Sithara')" 0
# consent service down: fail closed
echo "passed=$pass failed=$fail"

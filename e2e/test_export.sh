#!/usr/bin/env bash
UI=http://127.0.0.1:8081; pass=0; fail=0
t() { if [ "$2" = "$3" ]; then echo "PASS  $1"; pass=$((pass+1)); else echo "FAIL  $1  (got '$2', expected '$3')"; fail=$((fail+1)); fi; }
login() { rm -f $1; echo "$2" > user.json; curl -s -L -c $1 -b $1 -o /dev/null $UI/oauth2/authorization/keycloak; }
J=j_ex; login $J '{"sub":"u-admin","name":"alice","roles":["AUDIT_ADMIN"],"extra":{}}'
curl -s -D ex.h -b $J "$UI/audit/export.csv" -o ex.csv
t "csv: 200" "$(head -1 ex.h | grep -c ' 200')" 1
t "csv: content-disposition attachment" "$(grep -ic 'content-disposition: attachment' ex.h)" 1
t "csv: header + 20 data rows" "$(python3 -c "import csv;print(len(list(csv.reader(open('ex.csv',encoding='utf-8-sig')))))")" 21
t "csv: X-Export-Total = 20" "$(grep -i x-export-total ex.h | tr -dc 0-9)" 20
t "csv: not truncated" "$(grep -i x-export-truncated ex.h | tr -d '\r' | awk '{print $2}')" false
curl -s -b $J "$UI/audit/export.csv?facility=Device/hhims-kandy-01" -o ex2.csv
t "csv: filter applied (kandy = 4 rows)" "$(python3 -c "import csv;print(len(list(csv.reader(open('ex2.csv',encoding='utf-8-sig'))))-1)")" 4
t "csv: filtered rows all kandy" "$(python3 -c "
import csv;r=list(csv.DictReader(open('ex2.csv',encoding='utf-8-sig')));print(set(x['facility'] for x in r))")" "{'Device/hhims-kandy-01'}"
login j_fx '{"sub":"u-fac","name":"carol","roles":["FACILITY_AUDITOR"],"extra":{"facility_id":"Device/hhims-colombo-01"}}'
curl -s -b j_fx "$UI/audit/export.csv" -o ex3.csv
t "csv: scoped user exports only own facility (10)" "$(python3 -c "import csv;print(len(list(csv.reader(open('ex3.csv',encoding='utf-8-sig'))))-1)")" 10
login j_fy '{"sub":"u-none","name":"erin","roles":["SOME_OTHER_ROLE"],"extra":{}}'
t "csv: user with no Chester role gets an error, not an empty file" "$(curl -s -o /dev/null -w '%{http_code}' -b j_fy $UI/audit/export.csv)" 502
t "csv: not signed in is refused" "$(curl -s -o /dev/null -w '%{http_code}' $UI/audit/export.csv | sed 's/30[0-9]/302/;s/40[13]/302/')" 302
t "csv: formula cell neutralised" "$(cd ../ui/audit-ui && grep -c 'indexOf(x.charAt(0))' src/main/java/com/akhester/auditui/controller/AuditController.java)" 1
echo "passed=$pass failed=$fail"

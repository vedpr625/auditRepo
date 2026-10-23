#!/usr/bin/env bash
# The self-contained demo: ONE program, no Keycloak, no Chester, no database server. Build first: (cd audit-ui && mvn package -DskipTests)
cd "$(dirname "$0")/../audit-ui" && rm -rf data
setsid nohup java -jar target/audit-ui-1.0.0.jar --spring.profiles.active=demo > ../e2e/ui_selfdemo.log 2>&1 &
for i in $(seq 1 40); do sleep 2; curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/login | grep -q 200 && break; done
echo "Demo running at http://127.0.0.1:8081  (people and passwords are on the login page)"

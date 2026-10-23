#!/usr/bin/env bash
# Run on the Ubuntu Lightsail server as a user with sudo, from the repository folder:  bash deploy/install-demo.sh DEMO_HOST
# Installs Java 21 + nginx + certbot, builds the jar, installs the service. Safe to re-run. Does NOT touch other nginx sites or SonarQube.
set -euo pipefail
HOST="${1:?usage: install-demo.sh demo.example.org}"
free -m | awk 'NR==2{print "Memory free (MB): " $7; if ($7 < 700) print "WARNING: under 700 MB free; the demo needs about 500 MB. Check SonarQube first."}'
sudo apt-get update -y && sudo apt-get install -y openjdk-21-jre-headless maven nginx certbot python3-certbot-nginx
(cd audit-ui && mvn -q package -DskipTests)
id auditui >/dev/null 2>&1 || sudo useradd --system --home /opt/audit-ui-demo --shell /usr/sbin/nologin auditui
sudo mkdir -p /opt/audit-ui-demo && sudo cp audit-ui/target/audit-ui-1.0.0.jar /opt/audit-ui-demo/audit-ui.jar && sudo chown -R auditui /opt/audit-ui-demo
sudo cp deploy/audit-ui-demo.service /etc/systemd/system/ && sudo systemctl daemon-reload && sudo systemctl enable --now audit-ui-demo
sed "s/DEMO_HOST/$HOST/g" deploy/nginx-audit-ui-demo.conf | sudo tee /etc/nginx/sites-available/audit-ui-demo >/dev/null
sudo ln -sf /etc/nginx/sites-available/audit-ui-demo /etc/nginx/sites-enabled/audit-ui-demo
sudo nginx -t && sudo systemctl reload nginx
echo "Next: make sure DNS for $HOST points at this server, then run:  sudo certbot --nginx -d $HOST"
echo "Check:  curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8081/login   (expect 200)"

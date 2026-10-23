#!/usr/bin/env bash
# Run on the Ubuntu Lightsail server as a user with sudo, from the repository folder:
#   bash deploy/install-demo.sh DEMO_HOST [APP_PORT] [MGMT_PORT]        (defaults: 8085 and 8095)
# Installs Java 21 + nginx + certbot, builds the jar, installs the service. Safe to re-run.
# It refuses to continue if a port is already in use, and does NOT touch other nginx sites, the old UI, Chester or SonarQube.
set -euo pipefail
HOST="${1:?usage: install-demo.sh demo.example.org [app-port] [metrics-port]}"
APP_PORT="${2:-8085}"; MGMT_PORT="${3:-8095}"
busy() { sudo ss -ltn | awk '{print $4}' | grep -qE "[:.]$1$"; }
for p in "$APP_PORT" "$MGMT_PORT"; do
  # on a re-run our own service may hold the port: stop it first so it is not mistaken for a stranger
  sudo systemctl stop audit-ui-demo 2>/dev/null || true
  if busy "$p"; then echo "Port $p is already in use by something else:"; sudo ss -ltnp | grep -E "[:.]$p\b"; echo "Pick other ports: bash deploy/install-demo.sh $HOST <app-port> <metrics-port>"; exit 1; fi
done
free -m | awk 'NR==2{print "Memory free (MB): " $7; if ($7 < 700) print "WARNING: under 700 MB free; the demo needs about 500 MB. Check SonarQube first."}'
sudo apt-get update -y && sudo apt-get install -y openjdk-21-jre-headless maven nginx certbot python3-certbot-nginx
(cd audit-ui && mvn -q package -DskipTests)
id auditui >/dev/null 2>&1 || sudo useradd --system --home /opt/audit-ui-demo --shell /usr/sbin/nologin auditui
sudo mkdir -p /opt/audit-ui-demo && sudo cp audit-ui/target/audit-ui-1.0.0.jar /opt/audit-ui-demo/audit-ui.jar && sudo chown -R auditui /opt/audit-ui-demo
sed "s/APP_PORT/$APP_PORT/g; s/MGMT_PORT/$MGMT_PORT/g" deploy/audit-ui-demo.service | sudo tee /etc/systemd/system/audit-ui-demo.service >/dev/null
sudo systemctl daemon-reload && sudo systemctl enable audit-ui-demo && sudo systemctl restart audit-ui-demo
sed "s/DEMO_HOST/$HOST/g; s/APP_PORT/$APP_PORT/g" deploy/nginx-audit-ui-demo.conf | sudo tee /etc/nginx/sites-available/audit-ui-demo >/dev/null
sudo ln -sf /etc/nginx/sites-available/audit-ui-demo /etc/nginx/sites-enabled/audit-ui-demo
sudo nginx -t && sudo systemctl reload nginx
echo "Waiting for the app to start..."
for i in $(seq 1 30); do sleep 2; curl -s http://127.0.0.1:$APP_PORT/login | grep -q DEMONSTRATION && { echo "OK: the DEMO answers on port $APP_PORT"; break; }; [ $i = 30 ] && { echo "The demo did not answer. See: sudo journalctl -u audit-ui-demo -n 50 --no-pager"; exit 1; }; done
echo "Next: DNS for $HOST must point here, then:  sudo certbot --nginx -d $HOST   (if it offers to reuse an existing certificate, choose 1)"

#!/usr/bin/env bash
# Builds the server locally and deploys it to a pm2 host over SSH:
#   ~/SupportVisit/{server, jre, data, .env, ecosystem.config.js, start.sh, scripts/backup.sh}
# The previous version is kept in ~/supportvisit-previous-server for a quick rollback.
#
#   Usage: server/deploy/deploy-pm2.sh user@host
#
# First deploy: creates ~/SupportVisit/.env from .env.example with a random LINK_SECRET and
# stops so you can fill in the rest; run the script again afterwards.
set -euo pipefail

target="${1:?usage: deploy-pm2.sh user@host}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
JRE_URL="https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jre/hotspot/normal/eclipse"

"$root/gradlew" -p "$root/server" test installDist --console=plain -q

# Upload the new build next to the live one, then swap.
ssh "$target" 'mkdir -p ~/SupportVisit/scripts ~/SupportVisit/data && rm -rf ~/SupportVisit/server.new'
tar -C "$root/server/build/install" -czf - supportvisit-server | ssh "$target" 'mkdir -p ~/SupportVisit/server.new && tar -C ~/SupportVisit/server.new --strip-components=1 -xzf -'
tar -C "$root/server/deploy" -czf - .env.example pm2/ecosystem.config.js pm2/start.sh pm2/backup.sh \
  | ssh "$target" 'cd ~/SupportVisit && tar -xzf - && mv pm2/ecosystem.config.js pm2/start.sh . && mv pm2/backup.sh scripts/ && rmdir pm2 && chmod +x start.sh scripts/backup.sh'

ssh "$target" bash -s -- "$JRE_URL" <<'REMOTE'
set -euo pipefail
cd ~/SupportVisit
JRE_URL="$1"

# Private Java runtime (no system packages needed).
if [[ ! -x jre/bin/java ]]; then
  echo "Downloading Java 17 runtime..."
  rm -rf jre jre.tmp && mkdir jre.tmp
  curl -fsSL "$JRE_URL" | tar -C jre.tmp --strip-components=1 -xzf -
  mv jre.tmp jre
fi

if [[ ! -f .env ]]; then
  cp .env.example .env
  sed -i "s|^LINK_SECRET=.*|LINK_SECRET=$(openssl rand -base64 48 | tr -d '\n')|" .env
  chmod 600 .env
  echo "Created ~/SupportVisit/.env: fill in AZURE_CLIENT_ID, PUBLIC_BASE_URL (and ALLOWED_TENANT_IDS), then deploy again."
  exit 2
fi
chmod 600 .env

# Keep the running version for rollback, then switch.
if [[ -d server ]]; then
  rm -rf ~/supportvisit-previous-server
  mkdir -p ~/supportvisit-previous-server
  cp -a server ecosystem.config.js start.sh ~/supportvisit-previous-server/
  date -u +%FT%TZ > ~/supportvisit-previous-server/.saved-at
  rm -rf server
fi
mv server.new server

if pm2 describe supportvisit >/dev/null 2>&1; then
  pm2 restart supportvisit --update-env >/dev/null
else
  pm2 start ecosystem.config.js >/dev/null
fi
pm2 save >/dev/null

# Nightly encrypted backup, like the other apps (01:45, after velhunt 01:15 and reptimer 01:30).
line='45 1 * * * bash /home/'"$USER"'/SupportVisit/scripts/backup.sh >> $HOME/supportvisit-backups/backup.log 2>&1'
mkdir -p ~/supportvisit-backups
( crontab -l 2>/dev/null | grep -v 'SupportVisit/scripts/backup.sh' ; echo "$line" ) | crontab -

for i in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:8090/health >/dev/null 2>&1; then echo "Server healthy (pm2: supportvisit)"; exit 0; fi
  sleep 1
done
echo "Server did not become healthy; see: pm2 logs supportvisit --lines 50" >&2
exit 1
REMOTE

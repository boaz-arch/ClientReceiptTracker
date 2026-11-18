#!/usr/bin/env bash
# Builds the server locally and deploys it over SSH.
#   Usage: server/deploy/deploy.sh user@host [remote_dir]
# The remote .env is created once by hand (see SERVER_SETUP.md) and never overwritten.
set -euo pipefail

target="${1:?usage: deploy.sh user@host [remote_dir]}"
remote_dir="${2:-supportvisit-server}"
root="$(cd "$(dirname "$0")/../.." && pwd)"

"$root/gradlew" -p "$root/server" test installDist --console=plain -q

ssh "$target" "mkdir -p '$remote_dir/deploy/data' '$remote_dir/build/install'"
tar -C "$root/server" -czf - Dockerfile deploy/docker-compose.yml deploy/.env.example build/install/supportvisit-server \
  | ssh "$target" "cd '$remote_dir' && rm -rf build/install/supportvisit-server && tar -xzf -"
# Privacy policy and terms, served publicly at <PUBLIC_BASE_URL>/legal/privacy and /legal/terms.
tar -C "$root/docs" -czf - PRIVACY_POLICY.md TERMS.md \
  | ssh "$target" "mkdir -p '$remote_dir/deploy/data/legal' && cd '$remote_dir/deploy/data/legal' && tar -xzf - && mv PRIVACY_POLICY.md privacy.md && mv TERMS.md terms.md"

ssh "$target" "cd '$remote_dir/deploy' && test -f .env || { echo 'Missing $remote_dir/deploy/.env (copy .env.example)'; exit 1; }"
ssh "$target" "cd '$remote_dir/deploy' && docker compose up -d --build && sleep 3 && curl -fsS http://127.0.0.1:8090/health && echo ' <- server healthy'"

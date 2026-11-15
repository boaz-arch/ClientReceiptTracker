#!/usr/bin/env bash
# Starts the Support Visit server with the bundled Java runtime and the settings in .env.
# Used by pm2 (ecosystem.config.js) and by scripts/backup.sh ("start.sh backup <file>").
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -f .env ]]; then
  echo "Missing $(pwd)/.env (copy .env.example and fill it in)" >&2
  exit 1
fi
set -a
# shellcheck disable=SC1091
source .env
set +a

export JAVA_HOME="$PWD/jre"
export DATA_DIR="${DATA_DIR:-$PWD/data}"
export PORT="${PORT:-8090}"
export HOST="${HOST:-127.0.0.1}"
export JAVA_OPTS="${JAVA_OPTS:--Xmx256m}"

exec "$PWD/server/bin/supportvisit-server" "$@"

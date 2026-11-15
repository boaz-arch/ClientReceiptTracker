#!/usr/bin/env bash
# Nightly Support Visit backup (cron). Keeps 30 days in ~/supportvisit-backups, encrypted
# (AES-256, key in ~/.supportvisit_backup_key, made on the first run: keep a copy of it
# somewhere safe). Each backup is decrypted again and checked right after it is made.
# Safe while the server runs. Contains the database and all PDFs/photos.
set -euo pipefail
cd "$(dirname "$0")/.."

DEST="$HOME/supportvisit-backups"
KEY="$HOME/.supportvisit_backup_key"
mkdir -p "$DEST" && chmod 700 "$DEST"
umask 077
if [[ ! -s "$KEY" ]]; then
  openssl rand -hex 32 > "$KEY"
  chmod 600 "$KEY"
  echo "A new backup key was made in $KEY: keep a copy of it somewhere safe."
fi

STAMP=$(date -u +%Y-%m-%dT%H%M%SZ)
FILE="$DEST/supportvisit-$STAMP.tar.gz.enc"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

mkdir -p "$TMP/backup"
./start.sh backup "$TMP/backup/supportvisit.db" >/dev/null
./start.sh check "$TMP/backup/supportvisit.db" >/dev/null || { echo "$STAMP backup FAILED the integrity check"; exit 1; }
[[ -d data/files ]] && cp -a data/files "$TMP/backup/files"

tar -C "$TMP" -czf - backup | openssl enc -aes-256-cbc -pbkdf2 -iter 200000 -salt -pass "file:$KEY" -out "$FILE"

# Checked right away: it decrypts with the key and the database passes the integrity check.
mkdir -p "$TMP/check"
openssl enc -d -aes-256-cbc -pbkdf2 -iter 200000 -pass "file:$KEY" -in "$FILE" | tar -C "$TMP/check" -xzf -
./start.sh check "$TMP/check/backup/supportvisit.db" >/dev/null || { echo "$STAMP backup FAILED the check after encrypting"; rm -f "$FILE"; exit 1; }

chmod 600 "$FILE"
find "$DEST" -name 'supportvisit-*.tar.gz.enc' -mtime +30 -delete
echo "$STAMP backed up to $FILE ($(du -h "$FILE" | cut -f1), encrypted and checked); $(ls "$DEST"/supportvisit-*.tar.gz.enc | wc -l) kept"

#!/usr/bin/env bash
# Generates local passwords for a clean clone (Linux/macOS/Git Bash).
# Creates .env (read automatically by Docker Compose) and infra/alertmanager/webhook_password.
# Usage: scripts/setup-secrets.sh [--force]
set -eu

root="$(cd "$(dirname "$0")/.." && pwd)"
env_file="$root/.env"
webhook_file="$root/infra/alertmanager/webhook_password"

if [ -e "$env_file" ] && [ "${1:-}" != "--force" ]; then
  echo ".env already exists; leaving it alone. Use --force to regenerate."
  echo "(If you regenerate, also run 'make clean' so the database volume is recreated with the new DB_PASSWORD.)"
  exit 0
fi

secret() { head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32; }

viewer="$(secret)"; operator="$(secret)"; webhook="$(secret)"; db="$(secret)"

umask 077
cat > "$env_file" <<ENV
DB_PASSWORD=$db
COMMANDER_VIEWER_PASSWORD=$viewer
COMMANDER_OPERATOR_PASSWORD=$operator
COMMANDER_WEBHOOK_PASSWORD=$webhook
ENV

mkdir -p "$(dirname "$webhook_file")"
printf '%s' "$webhook" > "$webhook_file"
# The Alertmanager container runs as an unprivileged user and must be able to read this file.
chmod 644 "$webhook_file"

echo "Wrote $env_file and $webhook_file"
echo "Both are git-ignored. Operator login: operator / (see COMMANDER_OPERATOR_PASSWORD in .env)"
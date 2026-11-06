#!/usr/bin/env bash
# Install on an existing Ubuntu/systemd host. Secrets and Docker volumes are never changed.
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "Run this installer with sudo." >&2
  exit 1
fi
for command in git docker flock systemctl install stat getent; do
  if ! command -v "$command" >/dev/null; then
    echo "Missing command: $command" >&2
    exit 1
  fi
done
docker compose version >/dev/null

# Default to the checkout containing this installer, even when sudo changes HOME.
app_dir=$(cd "${1:-$(dirname "${BASH_SOURCE[0]}")/..}" && pwd -P)
owner=$(stat -c '%U' "$app_dir")
if [[ $(git -c "safe.directory=$app_dir" -C "$app_dir" rev-parse --show-toplevel) != "$app_dir" ]]; then
  echo "Expected a Git checkout at $app_dir. See README > Automatic server updates for archive deployments." >&2
  exit 1
fi
if [[ ! -f $app_dir/.env ]]; then
  echo "Missing $app_dir/.env; configure and start the stack before installing the timer." >&2
  exit 1
fi
# A newline cannot be represented safely in the systemd EnvironmentFile below.
if [[ $app_dir == *$'\n'* || $app_dir == *$'\r'* ]]; then
  echo "The application directory must not contain a newline." >&2
  exit 1
fi

install -d -m 0755 /usr/local/libexec /etc/divvy
install -m 0755 "$app_dir/deploy/auto-update.sh" /usr/local/libexec/divvy-auto-update
install -m 0644 "$app_dir/deploy/divvy-auto-update.service" /etc/systemd/system/
install -m 0644 "$app_dir/deploy/divvy-auto-update.timer" /etc/systemd/system/
# Run Git and Compose as the checkout owner, not as root against someone else's home directory.
install -d -m 0755 /etc/systemd/system/divvy-auto-update.service.d
{
  printf '[Service]\nUser=%s\n' "$owner"
  if getent group docker >/dev/null; then
    printf 'SupplementaryGroups=docker\n'
  fi
} > /etc/systemd/system/divvy-auto-update.service.d/user.conf
# Double-quoted EnvironmentFile values need backslashes and quotes escaped, not shell expansion.
escaped=${app_dir//\\/\\\\}
escaped=${escaped//\"/\\\"}
(umask 077; printf 'DIVVY_APP_DIR="%s"\n' "$escaped" > /etc/divvy/auto-update.env)

systemctl daemon-reload
systemctl enable --now divvy-auto-update.timer
# Establish the first deployed revision now rather than silently waiting for the first timer tick.
systemctl start divvy-auto-update.service
echo "Automatic server updates enabled. Logs: journalctl -u divvy-auto-update.service"

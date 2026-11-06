#!/usr/bin/env bash
# Runs from the systemd timer or by hand. Only a clean, fast-forward update of main is allowed.
set -euo pipefail
export GIT_TERMINAL_PROMPT=0

cd "${DIVVY_APP_DIR:-$HOME/divvy}"
if [[ $(git rev-parse --show-toplevel) != "$PWD" ]]; then
  echo "DIVVY_APP_DIR must be the root of a Git checkout." >&2
  exit 1
fi

# systemd serializes its own starts; this also protects against a manual run during a build.
exec 9>"$(git rev-parse --git-path divvy-auto-update.lock)"
if ! flock -n 9; then
  echo "Another server update is already running; skipping."
  exit 0
fi

if [[ $(git symbolic-ref --quiet --short HEAD) != main ]]; then
  echo "Automatic deployment requires the main branch; refusing to switch branches." >&2
  exit 1
fi
if [[ -n $(git status --porcelain) ]]; then
  echo "The checkout has local changes or untracked files; refusing to overwrite them." >&2
  exit 1
fi
if [[ ! -f .env ]]; then
  echo "Missing .env; configure the existing deployment before enabling automatic updates." >&2
  exit 1
fi

git fetch --no-tags origin refs/heads/main:refs/remotes/origin/main
target=$(git rev-parse refs/remotes/origin/main)
if ! git merge-base --is-ancestor HEAD "$target"; then
  echo "Local main is ahead of or diverged from origin/main; refusing to reset it." >&2
  exit 1
fi
# In particular, do not let newly tracked files replace ignored secrets such as .env.
git merge --ff-only --no-overwrite-ignore "$target"

state=$(git rev-parse --git-path divvy-server-deployed)
if [[ -f $state ]]; then
  deployed=$(cat "$state")
  if git diff --quiet "$deployed" "$target" -- \
    server/ gradle/ gradlew build.gradle.kts settings.gradle.kts gradle.properties \
    .dockerignore docker-compose.yml; then
    echo "No server build inputs changed; no rebuild needed."
    exit 0
  else
    result=$?
    # A comparison error must not be mistaken for changed source code.
    if [[ $result -ne 1 ]]; then exit "$result"; fi
  fi
fi

echo "Deploying server revision $target"
# Run the same command as a manual deployment. The first run establishes the deployed revision;
# a failed build or Compose command leaves the marker unchanged for the next timer tick.
docker compose up -d --build
printf '%s\n' "$target" > "$state.tmp"
mv "$state.tmp" "$state"
echo "Docker Compose completed for revision $target"

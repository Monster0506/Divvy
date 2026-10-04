#!/usr/bin/env bash
# Runs on the server over SSH. Safe to repeat: installs Docker once, enables the firewall, adds swap.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive

if ! command -v docker >/dev/null; then
  curl -fsSL https://get.docker.com | sh
fi
if ! command -v ufw >/dev/null; then
  apt-get update -qq && apt-get install -y -qq ufw
fi

ufw allow OpenSSH >/dev/null
ufw allow 80/tcp >/dev/null
ufw allow 443/tcp >/dev/null
ufw allow 443/udp >/dev/null
ufw --force enable >/dev/null

# The Gradle build of the server needs more memory than a 4 GB machine spares.
if ! swapon --show | grep -q .; then
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '^/swapfile' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

mkdir -p "${1:-/opt/divvy}"

#!/usr/bin/env bash
set -Eeuo pipefail

show_command() {
  local label="$1"
  shift

  printf '=== %s ===\n' "$label"
  if command -v "$1" >/dev/null 2>&1; then
    "$@"
  else
    printf 'NOT_INSTALLED: %s\n' "$1"
  fi
}

show_command "Docker" docker --version

printf '=== Docker Compose ===\n'
if command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  docker compose version
else
  printf 'NOT_INSTALLED\n'
fi

show_command "Git" git --version

printf '=== OS ===\n'
if [[ -r /etc/os-release ]]; then
  (
    source /etc/os-release
    printf '%s\n' "${PRETTY_NAME:-unknown}"
  )
fi
uname -m

printf '=== Disk ===\n'
df -h /

printf '=== Memory ===\n'
free -h

printf '=== Listening TCP ports ===\n'
if command -v ss >/dev/null 2>&1; then
  ss -lnt
else
  printf 'NOT_INSTALLED: ss\n'
fi

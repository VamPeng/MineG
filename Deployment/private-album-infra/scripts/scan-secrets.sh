#!/usr/bin/env bash
set -Eeuo pipefail

REPO_DIR="$(git rev-parse --show-toplevel)"
cd "$REPO_DIR"

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  printf 'Run this script inside the initialized Git repository.\n' >&2
  exit 1
fi

failed=0

printf '=== Forbidden tracked filenames ===\n'
for tracked_file in $(git ls-files); do
  case "$tracked_file" in
    .env|*/.env|*.pem|*.key|*.p12|*.pfx|*.jks|*/id_rsa|*/id_ed25519|*.tfstate|*.tfstate.*|*/.ossutilconfig)
      printf 'BLOCKED: %s\n' "$tracked_file"
      failed=1
      ;;
  esac
done

printf '=== Secret-like content ===\n'
matches="$(
  git grep -nI -E \
    '-----BEGIN ([A-Z ]+ )?PRIVATE KEY-----|LTAI[A-Za-z0-9]{12,}|AccessKeySecret[[:space:]]*[:=][[:space:]]*["'\'']?[A-Za-z0-9/+_-]{12,}|SecurityToken[[:space:]]*[:=][[:space:]]*["'\'']?[A-Za-z0-9/+_.=-]{20,}' \
    -- . ':!Deployment/private-album-infra/scripts/scan-secrets.sh' 2>/dev/null || true
)"

if [[ -n "$matches" ]]; then
  printf '%s\n' "$matches"
  failed=1
else
  printf 'No obvious credential patterns found.\n'
fi

if (( failed != 0 )); then
  printf 'Secret scan failed. Review the findings before committing.\n' >&2
  exit 1
fi

printf 'Secret scan passed.\n'

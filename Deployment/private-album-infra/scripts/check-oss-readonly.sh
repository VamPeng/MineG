#!/usr/bin/env bash
set -Eeuo pipefail

REPO_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${REPO_DIR}/.env"

if [[ ! -f "$ENV_FILE" ]]; then
  printf 'Missing %s. Copy .env.example to .env and fill private values.\n' "$ENV_FILE" >&2
  exit 1
fi

set -a
source "$ENV_FILE"
set +a

required_vars=(
  ALIYUN_REGION_ID
  OSS_BUCKET_NAME
  OSS_INTERNAL_ENDPOINT
)

for var_name in "${required_vars[@]}"; do
  if [[ -z "${!var_name:-}" ]] || [[ "${!var_name}" == *replace-with* ]] || [[ "${!var_name}" == "cn-example" ]]; then
    printf 'Invalid or missing value: %s\n' "$var_name" >&2
    exit 1
  fi
done

if ! command -v ossutil >/dev/null 2>&1; then
  printf 'ossutil is not installed.\n' >&2
  exit 1
fi

printf '=== ossutil ===\n'
ossutil version

printf '=== Targeted read-only OSS check ===\n'
ossutil ls "oss://${OSS_BUCKET_NAME}/" \
  --mode EcsRamRole \
  --region "$ALIYUN_REGION_ID" \
  --endpoint "$OSS_INTERNAL_ENDPOINT" \
  --limited-num "${OSS_LIST_LIMIT:-10}"

#!/usr/bin/env bash
set -Eeuo pipefail

service_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() {
  cat <<'EOF'
Usage: ./local-oss-backend.sh [--check-only | local-backend options]

Obtain one-hour temporary STS credentials with a narrowly scoped local RAM
caller, remove the caller credentials from the environment, and start the local
MineG backend against the isolated development OSS Bucket.

Required non-secret environment:
  MINEG_OSS_REGION             Example: cn-hangzhou
  MINEG_OSS_BUCKET             Isolated private development Bucket
  MINEG_OSS_PUBLIC_ORIGIN      Example: https://oss-cn-hangzhou.aliyuncs.com
  MINEG_LOCAL_OSS_ROLE_ARN     Exact local AssumeRole target

Caller credentials may be exported as ALIBABA_CLOUD_ACCESS_KEY_ID and
ALIBABA_CLOUD_ACCESS_KEY_SECRET before running. Otherwise, this script reads the
Git-ignored ../Secret/aliyun-local.env file when it has owner-only permissions,
then falls back to prompting without echoing the secret. Use
./save-local-aliyun-secret.sh for one-time local storage. Non-secret resource
identifiers are loaded from the ignored .env.local-oss file when present.
--check-only runs a disposable multipart permission check and starts no server.
Other arguments are forwarded to local-backend.sh.
EOF
}

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

for command_name in aliyun jq; do
  command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required"
done

local_config_file="$service_dir/.env.local-oss"
if [[ -f "$local_config_file" ]]; then
  while IFS='=' read -r variable_name variable_value || [[ -n "$variable_name" ]]; do
    variable_name="${variable_name%$'\r'}"
    variable_value="${variable_value%$'\r'}"
    case "$variable_name" in
      "" | \#*) continue ;;
      MINEG_OSS_REGION | MINEG_OSS_BUCKET | MINEG_OSS_PUBLIC_ORIGIN | MINEG_LOCAL_OSS_ROLE_ARN)
        if [[ -z "${!variable_name:-}" ]]; then
          printf -v "$variable_name" '%s' "$variable_value"
          export "$variable_name"
        fi
        ;;
      *) fail ".env.local-oss contains unsupported key: $variable_name" ;;
    esac
  done <"$local_config_file"
fi

secret_config_file="$service_dir/../Secret/aliyun-local.env"
if [[ -f "$secret_config_file" ]]; then
  [[ ! -L "$secret_config_file" ]] || fail "$secret_config_file must not be a symbolic link"
  secret_dir_path="$(dirname "$secret_config_file")"
  secret_dir_mode="$(stat -f '%Lp' "$secret_dir_path" 2>/dev/null || stat -c '%a' "$secret_dir_path")"
  secret_file_mode="$(stat -f '%Lp' "$secret_config_file" 2>/dev/null || stat -c '%a' "$secret_config_file")"
  secret_file_owner="$(stat -f '%u' "$secret_config_file" 2>/dev/null || stat -c '%u' "$secret_config_file")"
  [[ "$secret_dir_mode" =~ ^[0-7]{3,4}$ && "$secret_file_mode" =~ ^[0-7]{3,4}$ ]] || fail "cannot determine permissions for $secret_config_file"
  [[ "$secret_file_owner" == "$(id -u)" ]] || fail "$secret_config_file must be owned by the current user"
  if (( (8#$secret_dir_mode & 077) != 0 )); then
    fail "$secret_dir_path must not be accessible by group/others; run chmod 700"
  fi
  if (( (8#$secret_file_mode & 077) != 0 )); then
    fail "$secret_config_file must not be readable or writable by group/others; run chmod 600"
  fi
  while IFS='=' read -r variable_name variable_value || [[ -n "$variable_name" ]]; do
    variable_name="${variable_name%$'\r'}"
    variable_value="${variable_value%$'\r'}"
    case "$variable_name" in
      "" | \#*) continue ;;
      ALIBABA_CLOUD_ACCESS_KEY_ID | ALIBABA_CLOUD_ACCESS_KEY_SECRET)
        if [[ -z "${!variable_name:-}" ]]; then
          printf -v "$variable_name" '%s' "$variable_value"
          export "$variable_name"
        fi
        ;;
      *) fail "$secret_config_file contains unsupported key: $variable_name" ;;
    esac
  done <"$secret_config_file"
fi

for variable_name in MINEG_OSS_REGION MINEG_OSS_BUCKET MINEG_OSS_PUBLIC_ORIGIN MINEG_LOCAL_OSS_ROLE_ARN; do
  [[ -n "${!variable_name:-}" ]] || fail "$variable_name is required"
done
[[ "$MINEG_OSS_REGION" =~ ^[a-z0-9-]+$ ]] || fail "MINEG_OSS_REGION contains invalid characters"
[[ "$MINEG_OSS_BUCKET" =~ ^[A-Za-z0-9][A-Za-z0-9.-]*$ ]] || fail "MINEG_OSS_BUCKET contains invalid characters"
[[ "$MINEG_OSS_PUBLIC_ORIGIN" =~ ^https://[^/?#]+/?$ ]] || fail "MINEG_OSS_PUBLIC_ORIGIN must be a credential-free HTTPS origin"
[[ "$MINEG_LOCAL_OSS_ROLE_ARN" =~ ^acs:ram::[0-9]+:role/[^[:space:]]+$ ]] || fail "MINEG_LOCAL_OSS_ROLE_ARN is not a RAM role ARN"

if [[ -z "${ALIBABA_CLOUD_ACCESS_KEY_ID:-}" ]]; then
  read -r -p 'Local STS caller AccessKey ID: ' ALIBABA_CLOUD_ACCESS_KEY_ID
  printf '\n'
  export ALIBABA_CLOUD_ACCESS_KEY_ID
fi
if [[ -z "${ALIBABA_CLOUD_ACCESS_KEY_SECRET:-}" ]]; then
  read -r -s -p 'Local STS caller AccessKey Secret: ' ALIBABA_CLOUD_ACCESS_KEY_SECRET
  printf '\n'
  export ALIBABA_CLOUD_ACCESS_KEY_SECRET
fi
[[ -n "$ALIBABA_CLOUD_ACCESS_KEY_ID" && -n "$ALIBABA_CLOUD_ACCESS_KEY_SECRET" ]] || fail "caller credentials are required"

export ALIBABA_CLOUD_IGNORE_PROFILE=TRUE
temporary_dir="$(mktemp -d)"
identity_file="$temporary_dir/identity.json"
credentials_file="$temporary_dir/assume-role.json"
cleanup() {
  rm -f "$identity_file" "$credentials_file"
  rmdir "$temporary_dir" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

printf '==> Verifying local STS caller identity\n'
aliyun sts GetCallerIdentity --region "$MINEG_OSS_REGION" >"$identity_file"
jq -e '.AccountId and .PrincipalId' "$identity_file" >/dev/null || fail "caller identity response is incomplete"

printf '==> Assuming the scoped local OSS role for 3600 seconds\n'
aliyun sts AssumeRole \
  --RoleArn "$MINEG_LOCAL_OSS_ROLE_ARN" \
  --RoleSessionName mineg-local-dev \
  --DurationSeconds 3600 \
  --region "$MINEG_OSS_REGION" >"$credentials_file"

MINEG_OSS_ACCESS_KEY_ID="$(jq -er '.Credentials.AccessKeyId' "$credentials_file")"
MINEG_OSS_ACCESS_KEY_SECRET="$(jq -er '.Credentials.AccessKeySecret' "$credentials_file")"
MINEG_OSS_SECURITY_TOKEN="$(jq -er '.Credentials.SecurityToken' "$credentials_file")"
MINEG_OSS_STS_EXPIRATION="$(jq -er '.Credentials.Expiration' "$credentials_file")"
export MINEG_OSS_ACCESS_KEY_ID MINEG_OSS_ACCESS_KEY_SECRET MINEG_OSS_SECURITY_TOKEN MINEG_OSS_STS_EXPIRATION

cleanup
trap - EXIT INT TERM
unset ALIBABA_CLOUD_ACCESS_KEY_ID ALIBABA_CLOUD_ACCESS_KEY_SECRET ALIBABA_CLOUD_SECURITY_TOKEN ALIBABA_CLOUD_IGNORE_PROFILE
unset MINEG_LOCAL_OSS_ROLE_ARN

printf '==> Temporary STS credentials loaded\n'
if [[ "${1:-}" == "--check-only" ]]; then
  printf '==> Running disposable OSS permission check\n'
  cd "$service_dir"
  exec go run ./cmd/local-oss-check
fi
printf '==> Starting local backend\n'
exec "$service_dir/local-backend.sh" "$@"

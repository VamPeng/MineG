#!/usr/bin/env bash
set -Eeuo pipefail

service_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
secret_dir="$service_dir/../Secret"
secret_file="$secret_dir/aliyun-local.env"

umask 077
mkdir -p "$secret_dir"
chmod 700 "$secret_dir"

read -r -p 'Local STS caller AccessKey ID: ' access_key_id
printf '\n'
read -r -s -p 'Local STS caller AccessKey Secret: ' access_key_secret
printf '\n'
[[ -n "$access_key_id" && -n "$access_key_secret" ]] || {
  printf 'error: both AccessKey fields are required\n' >&2
  exit 1
}
[[ "$access_key_id" != *$'\n'* && "$access_key_id" != *$'\r'* && \
  "$access_key_secret" != *$'\n'* && "$access_key_secret" != *$'\r'* ]] || {
  printf 'error: AccessKey fields must be single-line values\n' >&2
  exit 1
}

temporary_file="$(mktemp "$secret_dir/.aliyun-local.env.XXXXXX")"
cleanup() {
  rm -f "$temporary_file"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

printf 'ALIBABA_CLOUD_ACCESS_KEY_ID=%s\nALIBABA_CLOUD_ACCESS_KEY_SECRET=%s\n' \
  "$access_key_id" "$access_key_secret" >"$temporary_file"
chmod 600 "$temporary_file"
mv -f "$temporary_file" "$secret_file"
trap - EXIT INT TERM
unset access_key_id access_key_secret

printf 'Saved local credentials to %s with mode 0600. This file is Git-ignored.\n' "$secret_file"

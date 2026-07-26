#!/usr/bin/env bash
set -Eeuo pipefail

frontend_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
build_only=false
prepare_tls_only=false
skip_deps=false
skip_check=false
frontend_host="${MINEG_FRONTEND_HOST:-localhost}"
frontend_port="${MINEG_FRONTEND_PORT:-5173}"
api_target="${MINEG_DEV_API_TARGET:-http://127.0.0.1:8080}"
admin_username="${MINEG_BOOTSTRAP_ADMIN_USERNAME:-reviewer}"
admin_password="${MINEG_BOOTSTRAP_ADMIN_PASSWORD:-Stage01-admin-2026}"

usage() {
  cat <<'EOF'
Usage: ./local-frontend.sh [options]

Build and run the MineG administration frontend.

Options:
  --build-only       Build the production bundle in dist/ and exit.
  --prepare-tls-only Install mkcert when needed, prepare local TLS, and exit.
  --skip-deps        Reuse the current node_modules instead of running npm ci.
  --skip-check       Skip ESLint and Vitest; type checking still runs in build.
  --host HOST        Vite listen host (default: localhost).
  --port PORT        Vite listen port (default: 5173).
  --api-target URL   Backend origin used by the Vite /api proxy
                     (default: http://127.0.0.1:8080).
  --help             Show this help.

Environment overrides:
  MINEG_FRONTEND_HOST       Same as --host.
  MINEG_FRONTEND_PORT       Same as --port.
  MINEG_DEV_API_TARGET      Same as --api-target.
  MINEG_DEV_TLS_KEY         Local HTTPS private-key path.
  MINEG_DEV_TLS_CERT        Local HTTPS certificate path.
  MINEG_BOOTSTRAP_ADMIN_USERNAME  Local administrator username.
  MINEG_BOOTSTRAP_ADMIN_PASSWORD  Local administrator password.

The development server automatically creates a trusted localhost certificate
under Frontend/.local/tls when no custom TLS paths are configured. The first
run may ask for the macOS password while mkcert installs its local CA.
EOF
}

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

dotenv_value() {
  local key="$1"
  local env_file line value
  for env_file in \
    "$frontend_dir/.env.development.local" \
    "$frontend_dir/.env.local" \
    "$frontend_dir/.env.development" \
    "$frontend_dir/.env"; do
    [[ -f "$env_file" ]] || continue
    line="$(sed -n -E "s/^${key}=(.*)$/\\1/p" "$env_file" | tail -n 1)"
    [[ -n "$line" ]] || continue
    value="${line%$'\r'}"
    if [[ "$value" == \"*\" && "$value" == *\" ]]; then
      value="${value:1:${#value}-2}"
    elif [[ "$value" == \'*\' && "$value" == *\' ]]; then
      value="${value:1:${#value}-2}"
    fi
    printf '%s' "$value"
    return 0
  done
}

ensure_local_tls() {
  local default_tls_dir="$frontend_dir/.local/tls"
  local default_tls_key="$default_tls_dir/localhost-key.pem"
  local default_tls_cert="$default_tls_dir/localhost.pem"
  local tls_key tls_cert using_default=false

  tls_key="${MINEG_DEV_TLS_KEY:-$(dotenv_value MINEG_DEV_TLS_KEY)}"
  tls_cert="${MINEG_DEV_TLS_CERT:-$(dotenv_value MINEG_DEV_TLS_CERT)}"
  if [[ -z "$tls_key" && -z "$tls_cert" ]]; then
    tls_key="$default_tls_key"
    tls_cert="$default_tls_cert"
    using_default=true
  elif [[ -z "$tls_key" || -z "$tls_cert" ]]; then
    fail "MINEG_DEV_TLS_KEY and MINEG_DEV_TLS_CERT must be configured together"
  fi

  if [[ ! -f "$tls_key" || ! -f "$tls_cert" ]]; then
    $using_default || fail "configured TLS key or certificate does not exist"
    if ! command -v mkcert >/dev/null 2>&1; then
      command -v brew >/dev/null 2>&1 || fail "mkcert is required and Homebrew is unavailable"
      printf '==> Installing mkcert for the local HTTPS frontend\n'
      brew install mkcert
    fi
    printf '==> Preparing the trusted localhost HTTPS certificate\n'
    mkcert -install
    mkdir -p "$default_tls_dir"
    mkcert \
      -key-file "$default_tls_key" \
      -cert-file "$default_tls_cert" \
      localhost 127.0.0.1 ::1
    chmod 600 "$default_tls_key"
  fi

  export MINEG_DEV_TLS_KEY="$tls_key"
  export MINEG_DEV_TLS_CERT="$tls_cert"
  printf '==> Local HTTPS certificate: %s\n' "$tls_cert"
}

while (($# > 0)); do
  case "$1" in
    --build-only)
      build_only=true
      ;;
    --prepare-tls-only)
      prepare_tls_only=true
      ;;
    --skip-deps)
      skip_deps=true
      ;;
    --skip-check)
      skip_check=true
      ;;
    --host)
      (($# >= 2)) || fail "--host requires a value"
      frontend_host="$2"
      shift
      ;;
    --port)
      (($# >= 2)) || fail "--port requires a value"
      frontend_port="$2"
      shift
      ;;
    --api-target)
      (($# >= 2)) || fail "--api-target requires a value"
      api_target="$2"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
  shift
done

command -v node >/dev/null 2>&1 || fail "Node.js 20.18 or newer is required"
command -v npm >/dev/null 2>&1 || fail "npm is required"
[[ "$frontend_port" =~ ^[0-9]+$ ]] || fail "frontend port must be numeric"
((frontend_port >= 1 && frontend_port <= 65535)) || fail "frontend port must be between 1 and 65535"
[[ -n "$frontend_host" ]] || fail "frontend host must not be empty"

node -e '
  const [major, minor] = process.versions.node.split(".").map(Number)
  if (major < 20 || (major === 20 && minor < 18)) {
    console.error(`error: Node.js ${process.versions.node} is too old; 20.18 or newer is required`)
    process.exit(1)
  }
'

if $prepare_tls_only; then
  ensure_local_tls
  exit 0
elif ! $build_only; then
  ensure_local_tls
fi

cd "$frontend_dir"

if ! $skip_deps; then
  printf '==> Installing locked frontend dependencies\n'
  npm ci
else
  [[ -x node_modules/.bin/vite ]] || fail "node_modules is unavailable; rerun without --skip-deps"
  printf '==> Reusing existing frontend dependencies\n'
fi

printf '==> Generating TypeScript API declarations from OpenAPI\n'
npm run api:generate

if ! $skip_check; then
  printf '==> Running frontend lint and unit tests\n'
  npm run lint
  npm run test
fi

printf '==> Building the production frontend bundle\n'
npm run build

if $build_only; then
  cat <<EOF

============================================================
 MineG frontend build is ready
============================================================
 Output directory: $frontend_dir/dist
 API routing:      same-origin /api
============================================================

EOF
  exit 0
fi

export MINEG_DEV_API_TARGET="$api_target"
export VITE_API_BASE_URL=""

cat <<EOF

============================================================
 MineG local frontend is ready to start
============================================================
 Browser URL:      https://$frontend_host:$frontend_port
 Backend proxy:    $api_target
 Admin username:   $admin_username
 Admin password:   $admin_password
 Production build: $frontend_dir/dist
============================================================
 Keep this terminal open. Press Ctrl+C to stop Vite.

EOF

exec npm run dev -- --host "$frontend_host" --port "$frontend_port" --strictPort

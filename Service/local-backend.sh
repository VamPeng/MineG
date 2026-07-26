#!/usr/bin/env bash
set -Eeuo pipefail

service_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bin_dir="$service_dir/bin"
default_db_name="mineg_stage01_local"
db_name="${MINEG_LOCAL_DB_NAME:-$default_db_name}"
db_user="${MINEG_LOCAL_DB_USER:-$(id -un)}"
db_host="${MINEG_LOCAL_DB_HOST:-127.0.0.1}"
db_port="${MINEG_LOCAL_DB_PORT:-5432}"
reset_db=false
build_only=false

usage() {
  cat <<'EOF'
Usage: ./local-backend.sh [--reset-db] [--build-only] [--help]

Build and run the MineG backend with native Homebrew PostgreSQL 18.

Options:
  --reset-db   Delete and recreate only the default mineg_stage01_local database.
  --build-only Compile the API, migration, and admin-bootstrap binaries, then exit.
  --help       Show this help.

Environment overrides:
  MINEG_LOCAL_DB_NAME          Local database name (default: mineg_stage01_local)
  MINEG_LOCAL_DB_USER          PostgreSQL role (default: current macOS user)
  MINEG_LOCAL_DB_HOST          PostgreSQL host (default: 127.0.0.1)
  MINEG_LOCAL_DB_PORT          PostgreSQL port (default: 5432)
  MINEG_ADMIN_ORIGIN           Admin origin (default: https://localhost:5173)
  MINEG_BOOTSTRAP_ADMIN_USERNAME  Initial local admin (default: reviewer)
  MINEG_BOOTSTRAP_ADMIN_PASSWORD  Initial local password (default: Stage01-admin-2026)

The script deliberately ignores Service/.env. Pass overrides explicitly so an old
Docker or deployment database URL cannot be used accidentally.
EOF
}

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

while (($# > 0)); do
  case "$1" in
    --reset-db)
      reset_db=true
      ;;
    --build-only)
      build_only=true
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

for command_name in go; do
  command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required"
done

cd "$service_dir"
mkdir -p "$bin_dir"

printf '==> Building MineG backend binaries\n'
go build -trimpath -o "$bin_dir/mineg-api" ./cmd/api
go build -trimpath -o "$bin_dir/mineg-migrate" ./cmd/migrate
go build -trimpath -o "$bin_dir/mineg-admin-bootstrap" ./cmd/admin-bootstrap

if $build_only; then
  printf '==> Build complete: %s\n' "$bin_dir"
  exit 0
fi

for command_name in brew curl pg_isready psql createdb dropdb; do
  command -v "$command_name" >/dev/null 2>&1 || fail "$command_name is required"
done

[[ "$db_name" =~ ^[A-Za-z][A-Za-z0-9_]*$ ]] || fail "MINEG_LOCAL_DB_NAME contains unsafe characters"
[[ "$db_user" =~ ^[A-Za-z][A-Za-z0-9_.-]*$ ]] || fail "MINEG_LOCAL_DB_USER contains unsafe characters"
[[ "$db_port" =~ ^[0-9]+$ ]] || fail "MINEG_LOCAL_DB_PORT must be numeric"

if ! pg_isready -h "$db_host" -p "$db_port" >/dev/null 2>&1; then
  printf '==> Starting Homebrew PostgreSQL 18\n'
  brew services start postgresql@18
  for _ in {1..20}; do
    if pg_isready -h "$db_host" -p "$db_port" >/dev/null 2>&1; then
      break
    fi
    sleep 1
  done
fi
pg_isready -h "$db_host" -p "$db_port" >/dev/null 2>&1 || fail "PostgreSQL did not become ready"

if $reset_db; then
  [[ "$db_name" == "$default_db_name" ]] || fail "--reset-db is restricted to $default_db_name"
  printf '==> Recreating dedicated database %s\n' "$db_name"
  dropdb --if-exists -h "$db_host" -p "$db_port" -U "$db_user" "$db_name"
fi

database_exists="$(psql -h "$db_host" -p "$db_port" -U "$db_user" -d postgres -Atqc \
  "SELECT 1 FROM pg_database WHERE datname = '$db_name'")"
if [[ "$database_exists" != "1" ]]; then
  printf '==> Creating database %s\n' "$db_name"
  createdb -h "$db_host" -p "$db_port" -U "$db_user" "$db_name"
fi

export MINEG_ENV="${MINEG_ENV:-local}"
export MINEG_HTTP_ADDRESS="${MINEG_HTTP_ADDRESS:-127.0.0.1:8080}"
export MINEG_DATABASE_URL="postgres://${db_user}@${db_host}:${db_port}/${db_name}?sslmode=disable"
export MINEG_ADMIN_ORIGIN="${MINEG_ADMIN_ORIGIN:-https://localhost:5173}"
export MINEG_CURSOR_HMAC_KEY="${MINEG_CURSOR_HMAC_KEY:-mineg-local-cursor-key-change-before-deployment}"
export MINEG_REQUEST_TIMEOUT="${MINEG_REQUEST_TIMEOUT:-15s}"
export MINEG_SHUTDOWN_TIMEOUT="${MINEG_SHUTDOWN_TIMEOUT:-20s}"
export MINEG_READ_HEADER_TIMEOUT="${MINEG_READ_HEADER_TIMEOUT:-5s}"

printf '==> Applying database migrations\n'
"$bin_dir/mineg-migrate" up

admin_count="$(psql "$MINEG_DATABASE_URL" -Atqc 'SELECT count(*) FROM mineg.admin_users')"
if [[ "$admin_count" == "0" ]]; then
  printf '==> Bootstrapping local administrator\n'
  export MINEG_BOOTSTRAP_ADMIN_USERNAME="${MINEG_BOOTSTRAP_ADMIN_USERNAME:-reviewer}"
  export MINEG_BOOTSTRAP_ADMIN_PASSWORD="${MINEG_BOOTSTRAP_ADMIN_PASSWORD:-Stage01-admin-2026}"
  "$bin_dir/mineg-admin-bootstrap"
else
  printf '==> Administrator already exists; bootstrap skipped\n'
fi

case "$MINEG_HTTP_ADDRESS" in
  :*)
    api_base_url="http://127.0.0.1${MINEG_HTTP_ADDRESS}"
    ;;
  0.0.0.0:*)
    api_base_url="http://127.0.0.1:${MINEG_HTTP_ADDRESS##*:}"
    ;;
  *)
    api_base_url="http://${MINEG_HTTP_ADDRESS}"
    ;;
esac
api_port="${MINEG_HTTP_ADDRESS##*:}"

printf '==> Starting MineG API at %s\n' "$MINEG_HTTP_ADDRESS"
"$bin_dir/mineg-api" &
api_pid=$!

shutdown_api() {
  if kill -0 "$api_pid" >/dev/null 2>&1; then
    kill -TERM "$api_pid" >/dev/null 2>&1 || true
    wait "$api_pid" 2>/dev/null || true
  fi
}
trap shutdown_api EXIT INT TERM

api_ready=false
for _ in {1..40}; do
  if curl --silent --fail "$api_base_url/health/ready" >/dev/null 2>&1; then
    api_ready=true
    break
  fi
  if ! kill -0 "$api_pid" >/dev/null 2>&1; then
    wait "$api_pid" || true
    fail "MineG API stopped before becoming ready"
  fi
  sleep 0.25
done
$api_ready || fail "MineG API did not become ready at $api_base_url"

admin_username="$(psql "$MINEG_DATABASE_URL" -Atqc 'SELECT username FROM mineg.admin_users ORDER BY created_at LIMIT 1')"

cat <<EOF

============================================================
 MineG local backend is ready
============================================================
 Backend URL:       $api_base_url
 API base URL:      $api_base_url/api/v1
 Health readiness:  $api_base_url/health/ready
 Database:          $db_name

 Frontend browser:  $MINEG_ADMIN_ORIGIN
 Frontend proxy:    MINEG_DEV_API_TARGET=$api_base_url
 Frontend API base: VITE_API_BASE_URL=<empty>

 Android API base:  $api_base_url
 Android build:     -PminegDebugApiBaseUrl=$api_base_url
 USB port mapping:  adb reverse tcp:$api_port tcp:$api_port

 Admin username:    $admin_username
 Admin password:    the MINEG_BOOTSTRAP_ADMIN_PASSWORD value used at first bootstrap
============================================================
 Keep this terminal open. Press Ctrl+C to stop the API.

EOF

set +e
wait "$api_pid"
api_status=$?
set -e
trap - EXIT INT TERM
exit "$api_status"

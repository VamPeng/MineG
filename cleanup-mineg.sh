#!/usr/bin/env bash
set -Eeuo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
db_name="mineg_stage01_local"
db_user="${MINEG_LOCAL_DB_USER:-$(id -un)}"
db_host="${MINEG_LOCAL_DB_HOST:-127.0.0.1}"
db_port="${MINEG_LOCAL_DB_PORT:-5432}"
clear_app_data=false
drop_database=false
remove_build_artifacts=false
remove_project_tls=false

usage() {
  cat <<'EOF'
Usage: ./cleanup-mineg.sh [options]

Stop the MineG backend/frontend and clean the local verification environment.
With no options, the script removes the Android port mapping and local service
logs. It always preserves the installed Android APK.

Options:
  --app-data        Clear com.mineg.mobile data but keep the APK installed.
  --database        Drop only the dedicated mineg_stage01_local database.
  --build-artifacts Remove generated Service, Frontend, Android, and C++ builds.
  --project-tls     Remove this repository's generated localhost key/certificate.
                    The shared mkcert local CA remains installed and trusted.
  --full-reset      Clear app data without uninstalling, drop the dedicated
                    database, and remove generated build artifacts.
  --help            Show this help.

Examples:
  ./cleanup-mineg.sh
  ./cleanup-mineg.sh --app-data
  ./cleanup-mineg.sh --full-reset
EOF
}

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

while (($# > 0)); do
  case "$1" in
    --app-data)
      clear_app_data=true
      ;;
    --database)
      drop_database=true
      ;;
    --build-artifacts)
      remove_build_artifacts=true
      ;;
    --project-tls)
      remove_project_tls=true
      ;;
    --full-reset)
      clear_app_data=true
      drop_database=true
      remove_build_artifacts=true
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

command -v lsof >/dev/null 2>&1 || fail "lsof is required"

stop_orchestrator() {
  local pid_file="$repo_dir/.local/pids/local-mineg.pid"
  local process_id command_line attempt
  [[ -f "$pid_file" ]] || return
  process_id="$(sed -n '1p' "$pid_file")"
  if [[ ! "$process_id" =~ ^[0-9]+$ ]] || ! kill -0 "$process_id" >/dev/null 2>&1; then
    rm -f -- "$pid_file"
    return
  fi
  command_line="$(ps -p "$process_id" -o command=)"
  [[ "$command_line" == *"local-mineg.sh"* ]] || fail "refusing to stop unexpected PID $process_id"
  printf '==> Stopping MineG local orchestrator PID %s\n' "$process_id"
  kill -TERM "$process_id"
  for ((attempt = 1; attempt <= 40; attempt++)); do
    kill -0 "$process_id" >/dev/null 2>&1 || break
    sleep 0.25
  done
}

stop_mineg_listener() {
  local port="$1"
  local expected_dir="$2"
  local label="$3"
  local process_ids process_id process_cwd attempt
  process_ids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
  [[ -n "$process_ids" ]] || return
  for process_id in $process_ids; do
    process_cwd="$(lsof -a -p "$process_id" -d cwd -Fn 2>/dev/null | sed -n 's/^n//p')"
    case "$process_cwd" in
      "$expected_dir"|"$expected_dir"/*)
        printf '==> Stopping MineG %s PID %s\n' "$label" "$process_id"
        kill -TERM "$process_id"
        ;;
      *)
        fail "port $port belongs to an unexpected process in $process_cwd; refusing to stop it"
        ;;
    esac
  done
  for ((attempt = 1; attempt <= 40; attempt++)); do
    lsof -tiTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1 || return
    sleep 0.25
  done
  fail "$label did not stop within 10 seconds"
}

cleanup_adb() {
  command -v adb >/dev/null 2>&1 || {
    printf '==> adb unavailable; Android cleanup skipped\n'
    return
  }
  local device_count app_path
  device_count="$(adb devices | awk '$2 == "device" { count++ } END { print count + 0 }')"
  if ((device_count == 0)); then
    printf '==> No authorized Android device; Android cleanup skipped\n'
    return
  fi
  if ((device_count > 1)) && [[ -z "${ANDROID_SERIAL:-}" ]]; then
    fail "multiple Android devices are connected; set ANDROID_SERIAL to select one"
  fi

  printf '==> Removing Android reverse mapping tcp:8080\n'
  adb reverse --remove tcp:8080 >/dev/null 2>&1 || true

  if $clear_app_data; then
    app_path="$(adb shell pm path com.mineg.mobile 2>/dev/null | tr -d '\r')"
    if [[ -n "$app_path" ]]; then
      printf '==> Clearing com.mineg.mobile local data\n'
      adb shell pm clear com.mineg.mobile >/dev/null
    else
      printf '==> MineG Android app is not installed\n'
    fi
  fi
}

remove_generated_directory() {
  local target="$1"
  case "$target" in
    "$repo_dir/Service/bin"|\
    "$repo_dir/Frontend/dist"|\
    "$repo_dir/Mobile/MineG_Android/build"|\
    "$repo_dir/Mobile/MineG_Android/app/build"|\
    "$repo_dir/Mobile/core/build")
      ;;
    *)
      fail "refusing to remove unexpected build path: $target"
      ;;
  esac
  if [[ -d "$target" ]]; then
    printf '==> Removing generated directory %s\n' "$target"
    rm -r -- "$target"
  fi
}

stop_orchestrator
stop_mineg_listener 5173 "$repo_dir/Frontend" "frontend"
stop_mineg_listener 8080 "$repo_dir/Service" "backend"
cleanup_adb

printf '==> Removing local verification logs\n'
rm -f -- "$repo_dir/.local/logs/backend.log" "$repo_dir/.local/logs/frontend.log"

if $drop_database; then
  command -v dropdb >/dev/null 2>&1 || fail "dropdb is required for --database"
  [[ "$db_name" == "mineg_stage01_local" ]] || fail "refusing to drop unexpected database: $db_name"
  printf '==> Dropping dedicated local database %s\n' "$db_name"
  dropdb --if-exists -h "$db_host" -p "$db_port" -U "$db_user" "$db_name"
fi

if $remove_build_artifacts; then
  remove_generated_directory "$repo_dir/Service/bin"
  remove_generated_directory "$repo_dir/Frontend/dist"
  remove_generated_directory "$repo_dir/Mobile/MineG_Android/build"
  remove_generated_directory "$repo_dir/Mobile/MineG_Android/app/build"
  remove_generated_directory "$repo_dir/Mobile/core/build"
fi

if $remove_project_tls; then
  tls_dir="$repo_dir/Frontend/.local/tls"
  if [[ -d "$tls_dir" ]]; then
    printf '==> Removing repository-local TLS key and certificate\n'
    rm -r -- "$tls_dir"
  fi
fi

cat <<EOF

============================================================
 MineG local verification cleanup is complete
============================================================
 ADB reverse:       removed when a device was available
 Backend/frontend:  stopped
 Service logs:      removed
 Android app data:  $clear_app_data
 Android APK:       preserved
 Database dropped:  $drop_database
 Builds removed:    $remove_build_artifacts
 Project TLS removed: $remove_project_tls
============================================================

Preserved intentionally: the installed Android APK, Homebrew PostgreSQL, Node
dependencies, Gradle caches, mkcert, and its shared local certificate authority.
EOF

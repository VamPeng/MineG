#!/usr/bin/env bash
set -Eeuo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
log_dir="$repo_dir/.local/logs"
pid_dir="$repo_dir/.local/pids"
orchestrator_pid_file="$pid_dir/local-mineg.pid"
backend_url="http://127.0.0.1:8080"
frontend_url="https://localhost:5173"
admin_username="${MINEG_BOOTSTRAP_ADMIN_USERNAME:-reviewer}"
admin_password="${MINEG_BOOTSTRAP_ADMIN_PASSWORD:-Stage01-admin-2026}"
fast_mode=false
reset_db=false
skip_android=false
install_android=true
open_clients=true
backend_pid=""
frontend_pid=""
cleanup_started=false
environment_ready=false

usage() {
  cat <<'EOF'
Usage: ./local-mineg.sh [options]

Start the complete MineG local verification environment from one terminal:
backend, HTTPS administration frontend, and Android Debug APK build/install.

Options:
  --fast          Reuse frontend dependencies and skip frontend/Android checks.
  --reset-db      Recreate only the dedicated mineg_stage01_local database.
                  The backend must not already be running.
  --no-android    Start backend and frontend without building Android.
  --no-install    Build the Android APK but do not install it.
  --no-open       Do not open the browser or launch the installed Android app.
  --help          Show this help.

The first run automatically installs mkcert with Homebrew when needed and may
ask for the macOS password to trust its local certificate authority. Press
Ctrl+C once to stop the MineG backend and frontend used by this run, remove the
ADB reverse mapping and logs, and keep the installed Android app.
EOF
}

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

while (($# > 0)); do
  case "$1" in
    --fast)
      fast_mode=true
      ;;
    --reset-db)
      reset_db=true
      ;;
    --no-android)
      skip_android=true
      ;;
    --no-install)
      install_android=false
      ;;
    --no-open)
      open_clients=false
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

command -v curl >/dev/null 2>&1 || fail "curl is required"
command -v lsof >/dev/null 2>&1 || fail "lsof is required"
mkdir -p "$log_dir" "$pid_dir"
backend_log="$log_dir/backend.log"
frontend_log="$log_dir/frontend.log"

if [[ -f "$orchestrator_pid_file" ]]; then
  existing_orchestrator_pid="$(sed -n '1p' "$orchestrator_pid_file")"
  if [[ "$existing_orchestrator_pid" =~ ^[0-9]+$ ]] && kill -0 "$existing_orchestrator_pid" >/dev/null 2>&1; then
    fail "another local-mineg.sh is already running with PID $existing_orchestrator_pid"
  fi
  rm -f -- "$orchestrator_pid_file"
fi
printf '%s\n' "$$" >"$orchestrator_pid_file"

http_ready() {
  curl --silent --show-error --fail --max-time 2 "$1" >/dev/null 2>&1
}

https_ready() {
  curl --silent --show-error --fail --max-time 2 --insecure "$1" >/dev/null 2>&1
}

show_log_tail() {
  local label="$1"
  local log_file="$2"
  printf '\n==> Last %s log lines (%s)\n' "$label" "$log_file" >&2
  tail -n 40 "$log_file" >&2 || true
}

wait_for_service() {
  local label="$1"
  local url="$2"
  local mode="$3"
  local process_id="$4"
  local log_file="$5"
  local attempt
  for ((attempt = 1; attempt <= 240; attempt++)); do
    if [[ "$mode" == "https" ]]; then
      https_ready "$url" && return 0
    else
      http_ready "$url" && return 0
    fi
    if [[ -n "$process_id" ]] && ! kill -0 "$process_id" >/dev/null 2>&1; then
      show_log_tail "$label" "$log_file"
      fail "$label stopped before becoming ready"
    fi
    sleep 0.5
  done
  show_log_tail "$label" "$log_file"
  fail "$label did not become ready at $url"
}

cleanup() {
  local process_id attempt
  $cleanup_started && return
  cleanup_started=true
  for process_id in "$frontend_pid" "$backend_pid"; do
    [[ -n "$process_id" ]] || continue
    if kill -0 "$process_id" >/dev/null 2>&1; then
      kill -TERM "$process_id" >/dev/null 2>&1 || true
      wait "$process_id" 2>/dev/null || true
      for ((attempt = 1; attempt <= 40; attempt++)); do
        kill -0 "$process_id" >/dev/null 2>&1 || break
        sleep 0.25
      done
      if kill -0 "$process_id" >/dev/null 2>&1; then
        printf 'warning: MineG process %s did not stop within 10 seconds\n' "$process_id" >&2
      fi
    fi
  done
  if command -v adb >/dev/null 2>&1; then
    adb reverse --remove tcp:8080 >/dev/null 2>&1 || true
  fi
  if [[ -f "$orchestrator_pid_file" ]] && [[ "$(sed -n '1p' "$orchestrator_pid_file")" == "$$" ]]; then
    rm -f -- "$orchestrator_pid_file"
  fi
  if $environment_ready; then
    rm -f -- "$backend_log" "$frontend_log"
  fi
}

handle_signal() {
  printf '\n==> Stopping MineG local services\n'
  cleanup
  exit 130
}

trap cleanup EXIT
trap handle_signal INT TERM

printf '==> Preparing local HTTPS support\n'
"$repo_dir/Frontend/local-frontend.sh" --prepare-tls-only

if http_ready "$backend_url/health/ready"; then
  $reset_db && fail "--reset-db cannot run while a backend is already listening at $backend_url"
  printf '==> Reusing ready backend at %s\n' "$backend_url"
  backend_pid="$(lsof -tiTCP:8080 -sTCP:LISTEN | head -n 1)"
else
  : >"$backend_log"
  printf '==> Starting local backend (log: %s)\n' "$backend_log"
  if $reset_db; then
    "$repo_dir/Service/local-backend.sh" --reset-db >"$backend_log" 2>&1 &
  else
    "$repo_dir/Service/local-backend.sh" >"$backend_log" 2>&1 &
  fi
  backend_pid=$!
  wait_for_service "backend" "$backend_url/health/ready" "http" "$backend_pid" "$backend_log"
  printf '==> Backend is ready\n'
fi

if https_ready "$frontend_url"; then
  printf '==> Reusing ready frontend at %s\n' "$frontend_url"
  frontend_pid="$(lsof -tiTCP:5173 -sTCP:LISTEN | head -n 1)"
else
  frontend_args=("--api-target" "$backend_url")
  if $fast_mode; then
    frontend_args+=("--skip-check")
    [[ -x "$repo_dir/Frontend/node_modules/.bin/vite" ]] && frontend_args+=("--skip-deps")
  fi
  : >"$frontend_log"
  printf '==> Starting local frontend (log: %s)\n' "$frontend_log"
  "$repo_dir/Frontend/local-frontend.sh" "${frontend_args[@]}" >"$frontend_log" 2>&1 &
  frontend_pid=$!
  wait_for_service "frontend" "$frontend_url" "https" "$frontend_pid" "$frontend_log"
  printf '==> Frontend is ready\n'
fi

if ! $skip_android; then
  android_args=("--api-base-url" "$backend_url")
  $install_android && android_args+=("--install")
  $fast_mode && android_args+=("--skip-check")
  "$repo_dir/Mobile/MineG_Android/build-apk.sh" "${android_args[@]}"
fi

environment_ready=true

cat <<EOF

============================================================
 MineG local verification environment is ready
============================================================
 Backend:       $backend_url
 Backend health: $backend_url/health/ready
 Admin frontend: $frontend_url
 Admin username: $admin_username
 Admin password: $admin_password
 Android:       $([[ "$skip_android" == true ]] && printf 'skipped' || printf 'built')
 Backend log:   $backend_log
 Frontend log:  $frontend_log
============================================================
 Keep this terminal open. Press Ctrl+C to stop backend/frontend,
 remove the ADB mapping and logs, and keep the installed App.

EOF

if $open_clients; then
  command -v open >/dev/null 2>&1 && open "$frontend_url" || true
  if ! $skip_android && $install_android && command -v adb >/dev/null 2>&1; then
    adb shell monkey -p com.mineg.mobile -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  fi
fi

while true; do
  sleep 2
  if [[ -n "$backend_pid" ]] && ! kill -0 "$backend_pid" >/dev/null 2>&1; then
    show_log_tail "backend" "$backend_log"
    fail "backend stopped unexpectedly"
  fi
  if [[ -n "$frontend_pid" ]] && ! kill -0 "$frontend_pid" >/dev/null 2>&1; then
    show_log_tail "frontend" "$frontend_log"
    fail "frontend stopped unexpectedly"
  fi
done

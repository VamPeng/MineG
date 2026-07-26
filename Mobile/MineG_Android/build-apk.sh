#!/usr/bin/env bash
set -Eeuo pipefail

android_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
variant="debug"
api_base_url="${MINEG_ANDROID_API_BASE_URL:-}"
install_apk=false
skip_check=false
clean_build=false

usage() {
  cat <<'EOF'
Usage: ./build-apk.sh [options]

Test and build the MineG Android APK.

Options:
  --debug              Build a Debug APK (default).
  --release            Build an unsigned, minified Release APK.
  --api-base-url URL   API origin embedded in the APK. Debug defaults to
                       http://127.0.0.1:8080; Release requires HTTPS.
  --install            Install the Debug APK with adb install -r after build.
  --skip-check         Skip unit tests and Android lint.
  --clean              Run the Gradle clean task before building.
  --help               Show this help.

Environment override:
  MINEG_ANDROID_API_BASE_URL  Same as --api-base-url.

Release signing credentials are intentionally not accepted by this script.
Sign the resulting Release APK in the controlled release environment.
EOF
}

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

while (($# > 0)); do
  case "$1" in
    --debug)
      variant="debug"
      ;;
    --release)
      variant="release"
      ;;
    --api-base-url)
      (($# >= 2)) || fail "--api-base-url requires a value"
      api_base_url="$2"
      shift
      ;;
    --install)
      install_apk=true
      ;;
    --skip-check)
      skip_check=true
      ;;
    --clean)
      clean_build=true
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

command -v java >/dev/null 2>&1 || fail "JDK 17 is required"
[[ -x "$android_dir/gradlew" ]] || fail "Gradle wrapper is not executable: $android_dir/gradlew"

if [[ -z "$api_base_url" ]]; then
  if [[ "$variant" == "debug" ]]; then
    api_base_url="http://127.0.0.1:8080"
  else
    fail "--release requires --api-base-url with an HTTPS origin"
  fi
fi

if [[ "$variant" == "release" && "$api_base_url" != https://* ]]; then
  fail "Release API base URL must use HTTPS"
fi
if [[ "$variant" == "release" ]] && $install_apk; then
  fail "--install only supports the signed Debug APK; Release output is unsigned"
fi

cd "$android_dir"

if $clean_build; then
  printf '==> Cleaning Android build outputs\n'
  ./gradlew clean
fi

if [[ "$variant" == "debug" ]]; then
  property_name="minegDebugApiBaseUrl"
  variant_name="Debug"
  apk_path="$android_dir/app/build/outputs/apk/debug/app-debug.apk"
else
  property_name="minegReleaseApiBaseUrl"
  variant_name="Release"
  apk_path="$android_dir/app/build/outputs/apk/release/app-release-unsigned.apk"
fi

gradle_tasks=()
if ! $skip_check; then
  if [[ "$variant" == "debug" ]]; then
    gradle_tasks+=(":app:testDebugUnitTest" ":app:lintDebug")
  else
    gradle_tasks+=(":app:testDebugUnitTest" ":app:lintRelease")
  fi
fi
gradle_tasks+=(":app:assemble$variant_name")

printf '==> Building MineG Android %s APK\n' "$variant_name"
./gradlew "${gradle_tasks[@]}" "-P${property_name}=${api_base_url}"

if [[ "$variant" == "release" && ! -f "$apk_path" ]]; then
  signed_release="$android_dir/app/build/outputs/apk/release/app-release.apk"
  [[ -f "$signed_release" ]] && apk_path="$signed_release"
fi
[[ -f "$apk_path" ]] || fail "Gradle completed but the APK was not found at $apk_path"

apk_size="$(du -h "$apk_path" | awk '{print $1}')"
if command -v shasum >/dev/null 2>&1; then
  apk_digest="$(shasum -a 256 "$apk_path" | awk '{print $1}')"
elif command -v sha256sum >/dev/null 2>&1; then
  apk_digest="$(sha256sum "$apk_path" | awk '{print $1}')"
else
  apk_digest="unavailable"
fi

if $install_apk; then
  command -v adb >/dev/null 2>&1 || fail "adb is required for --install"
  device_count="$(adb devices | awk '$2 == "device" { count++ } END { print count + 0 }')"
  ((device_count > 0)) || fail "no authorized Android device is connected"
  if ((device_count > 1)) && [[ -z "${ANDROID_SERIAL:-}" ]]; then
    fail "multiple Android devices are connected; set ANDROID_SERIAL to select one"
  fi

  if [[ "$api_base_url" =~ ^http://(127\.0\.0\.1|localhost):([0-9]+)/?$ ]]; then
    api_port="${BASH_REMATCH[2]}"
    printf '==> Mapping device port %s to the development machine\n' "$api_port"
    adb reverse "tcp:$api_port" "tcp:$api_port"
  fi

  printf '==> Installing APK without instrumentation-test auto-uninstall\n'
  adb install -r "$apk_path"
fi

cat <<EOF

============================================================
 MineG Android APK is ready
============================================================
 Variant:       $variant_name
 API base URL:  $api_base_url
 APK:           $apk_path
 Size:          $apk_size
 SHA-256:       $apk_digest
 Installed:     $install_apk
============================================================

EOF

if [[ "$variant" == "release" && "$apk_path" == *-unsigned.apk ]]; then
  printf 'Note: the Release APK is unsigned and must be signed before installation or distribution.\n'
elif [[ "$variant" == "debug" ]] && ! $install_apk; then
  printf 'Install and preserve app data with:\n  adb install -r %q\n' "$apk_path"
fi

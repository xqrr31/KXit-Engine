#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

if command -v studio >/dev/null 2>&1; then
    studio build "$ROOT"
elif command -v gradle >/dev/null 2>&1; then
    gradle --no-daemon --max-workers=1 --console=plain :app:assembleDebug
else
    echo "Neither 'studio' nor 'gradle' is available in PATH." >&2
    exit 1
fi

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -s "$APK" ]]; then
    echo "Build command returned, but expected APK was not found: $APK" >&2
    exit 1
fi
printf '\nKXit Engine APK built successfully:\n%s\n' "$APK"

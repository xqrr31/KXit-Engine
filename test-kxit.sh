#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

fail() { echo "[TEST FAIL] $*" >&2; exit 1; }
pass() { echo "[TEST OK]   $*"; }

# A failed new test/build must never leave an old publishable marker behind.
rm -f .kxit-tests-passed

for f in \
    app/src/main/assets/blocks.png \
    app/src/main/assets/blocks_atlas.json \
    app/src/main/assets/shaders/terrain.vert \
    app/src/main/assets/shaders/terrain.frag \
    app/src/main/assets/models/sample_marker.obj \
    app/src/main/assets/modules/README.txt \
    app/src/main/java/com/rookmender/kxit/KXitGame.java \
    app/src/main/java/com/rookmender/kxit/MainActivity.java \
    app/src/main/java/com/rookmender/kxit/KXitObjLoader.java \
    app/src/main/java/com/rookmender/kxit/module/KXitModuleLoader.java; do
    [[ -s "$f" ]] || fail "Required file is missing or empty: $f"
done
pass "Required renderer, texture and shader files exist"

# Read the PNG IHDR directly; this does not depend on ImageMagick or Python/Pillow.
PNG_DIMENSIONS="$(od -An -tu1 -j 16 -N 8 app/src/main/assets/blocks.png)"
read -r PNG_W1 PNG_W2 PNG_W3 PNG_W4 PNG_H1 PNG_H2 PNG_H3 PNG_H4 <<< "$PNG_DIMENSIONS"
PNG_WIDTH=$((PNG_W1 * 16777216 + PNG_W2 * 65536 + PNG_W3 * 256 + PNG_W4))
PNG_HEIGHT=$((PNG_H1 * 16777216 + PNG_H2 * 65536 + PNG_H3 * 256 + PNG_H4))
[[ "$PNG_WIDTH" -eq 128 && "$PNG_HEIGHT" -eq 128 ]] || fail "blocks.png must be 128x128, got ${PNG_WIDTH}x${PNG_HEIGHT}"
pass "Texture atlas PNG dimensions are 128x128"

GAME_SOURCE="app/src/main/java/com/rookmender/kxit/KXitGame.java"
UV_TOP_COUNT="$(grep -F -c 'float vTop = 1f - (ty * 16f + .5f) / 128f;' "$GAME_SOURCE" || true)"
UV_BOTTOM_COUNT="$(grep -F -c 'float vBottom = 1f - ((ty + 1) * 16f - .5f) / 128f;' "$GAME_SOURCE" || true)"
[[ "$UV_TOP_COUNT" -eq 3 && "$UV_BOTTOM_COUNT" -eq 3 ]] || fail "All three mesh primitives must invert PNG top-left rows into OpenGL texture V coordinates"
if grep -Fq 'float vTop = (ty * 16f + .5f) / 128f;' "$GAME_SOURCE"; then
    fail "Non-inverted atlas UV mapping detected"
fi
pass "Atlas UV orientation compensates for libGDX PNG upload coordinates"

grep -Fq 'drawArrow(cx' "$GAME_SOURCE" || fail "Vector D-pad icons are missing"
grep -Fq 'drawButton(breakRect, actionHeld[0], "MINE")' "$GAME_SOURCE" || fail "Mine action control is missing"
grep -Fq 'drawButton(placeRect, actionHeld[1], "PLACE")' "$GAME_SOURCE" || fail "Place action control is missing"
pass "HUD controls use drawn icons and have mine/place bindings"

grep -Fq 'SCREEN_ORIENTATION_LANDSCAPE' app/src/main/java/com/rookmender/kxit/MainActivity.java || fail "Landscape orientation is missing"
grep -Fq 'Android/data/com.mazfons.kxit' README.md || fail "Runtime storage path is missing from documentation"
grep -Fq 'KXitObjLoader' app/src/main/java/com/rookmender/kxit/KXitObjLoader.java || fail "OBJ loader wrapper is missing"
pass "Landscape, runtime folder and future OBJ loader checks passed"

[[ "$(grep -F -c 'include(":app")' settings.gradle.kts)" -eq 1 ]] || fail "Expected one standalone app module"
if grep -R -E 'externalNativeBuild|CMakeLists.txt|ndkVersion|cmakeVersion' app/build.gradle.kts build.gradle.kts; then
    fail "KXit should use the prebuilt libGDX runtime, not a local NDK build"
fi
pass "Single-module libGDX project has no custom NDK/CMake build"

bash -n build-termux.sh || fail "build-termux.sh has a shell syntax error"
bash -n test-kxit.sh || fail "test-kxit.sh has a shell syntax error"
bash -n publish-kxit.sh || fail "publish-kxit.sh has a shell syntax error"
pass "Build, test and release scripts pass bash -n"

grep -Fq 'gh repo create' publish-kxit.sh || fail "GitHub repository creation is missing"
grep -Fq 'REPOSITORY=' publish-kxit.sh || fail "kxit.lock repository identity is missing"
grep -Fq 'git add -A' publish-kxit.sh || fail "Git source staging is missing"
grep -Fq 'git commit' publish-kxit.sh || fail "Git commit step is missing"
grep -Fq 'gh release create' publish-kxit.sh || fail "GitHub release creation is missing"
grep -Fq 'gh release upload' publish-kxit.sh || fail "GitHub release asset upload is missing"
if grep -E -- '--force($| )|--force-with-lease' publish-kxit.sh; then
    fail "Publishing must never force-push"
fi
pass "GitHub create, lock, commit and APK release workflow is present without force-push"

if command -v studio >/dev/null 2>&1; then
    studio build "$ROOT"
elif command -v gradle >/dev/null 2>&1; then
    gradle --no-daemon --max-workers=1 --console=plain :app:assembleDebug
else
    fail "Neither studio nor gradle is available"
fi

APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -s "$APK" ]]; then
    # Accept the most recent APK if the build helper uses a different variant name.
    APK="$(find "$ROOT/app/build/outputs/apk" -type f -name '*.apk' ! -name '*unaligned*' 2>/dev/null | sort | tail -n 1 || true)"
fi
[[ -n "$APK" && -s "$APK" ]] || fail "No APK was produced"
pass "Debug APK exists: ${APK#"$ROOT/"}"

fingerprint() {
    local list
    list="$(mktemp)"
    {
        find app/src/main -type f -print
        printf '%s\n' build.gradle.kts settings.gradle.kts gradle.properties app/build.gradle.kts .gitignore build-termux.sh test-kxit.sh publish-kxit.sh README.md module-template/HelloModule.java module-template/hello.kxm.example module-template/README.md
    } | sort -u > "$list"
    while IFS= read -r file; do
        [[ -f "$file" ]] && sha256sum "$file"
    done < "$list" | sha256sum | awk '{print $1}'
    rm -f "$list"
}
SOURCE_HASH="$(fingerprint)"
APK_HASH="$(sha256sum "$APK" | awk '{print $1}')"
APK_REL="${APK#"$ROOT/"}"
{
    printf 'SOURCE_SHA256=%s\n' "$SOURCE_HASH"
    printf 'APK_SHA256=%s\n' "$APK_HASH"
    printf 'APK_PATH=%s\n' "$APK_REL"
    printf 'TESTED_AT_UTC=%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
} > .kxit-tests-passed
pass "Test marker written; run the game on-device before publishing"
printf '\nAll build checks passed. APK: %s\n' "$APK_REL"
printf 'After you have launched and smoke-tested the APK, publish with:\n  bash ./publish-kxit.sh --tests-passed\n'

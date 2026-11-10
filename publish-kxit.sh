#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

PROJECT_NAME="KXit Engine"
REPO_SLUG="${KXIT_GH_REPO_NAME:-KXit-Engine}"
VISIBILITY="${KXIT_GH_VISIBILITY:-public}"

fail() { echo "[PUBLISH ERROR] $*" >&2; exit 1; }
info() { printf '[KXit Publish] %s\n' "$*"; }

[[ "${1:-}" == "--tests-passed" ]] || fail "Run this only after tests and device smoke test: bash ./publish-kxit.sh --tests-passed"
[[ -f .kxit-tests-passed ]] || fail "No .kxit-tests-passed marker. Run bash ./test-kxit.sh first, then launch and test the APK."

for tool in gh git sha256sum; do command -v "$tool" >/dev/null 2>&1 || fail "Missing tool: $tool (Termux: pkg install gh git coreutils)"; done
gh auth status >/dev/null 2>&1 || fail "GitHub CLI is not authenticated. Run: gh auth login"

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

TESTED_SOURCE="$(sed -n 's/^SOURCE_SHA256=//p' .kxit-tests-passed | head -n 1)"
CURRENT_SOURCE="$(fingerprint)"
[[ -n "$TESTED_SOURCE" && "$TESTED_SOURCE" == "$CURRENT_SOURCE" ]] || fail "Project sources changed after tests. Run ./test-kxit.sh again."
APK_REL="$(sed -n 's/^APK_PATH=//p' .kxit-tests-passed | head -n 1)"
TESTED_APK="$(sed -n 's/^APK_SHA256=//p' .kxit-tests-passed | head -n 1)"
[[ -n "$APK_REL" && -s "$ROOT/$APK_REL" ]] || fail "Tested APK is missing: $APK_REL"
CURRENT_APK="$(sha256sum "$ROOT/$APK_REL" | awk '{print $1}')"
[[ -n "$TESTED_APK" && "$TESTED_APK" == "$CURRENT_APK" ]] || fail "APK changed after tests. Run ./test-kxit.sh again."
APK="$ROOT/$APK_REL"

[[ "$VISIBILITY" == "public" || "$VISIBILITY" == "private" ]] || fail "KXIT_GH_VISIBILITY must be public or private"
OWNER="${KXIT_GH_OWNER:-$(gh api user --jq '.login')}"
[[ -n "$OWNER" ]] || fail "Could not read authenticated GitHub username"
LOCKED_REPO=""
if [[ -f kxit.lock ]]; then
    LOCKED_REPO="$(sed -n 's/^REPOSITORY=//p' kxit.lock | head -n 1)"
fi
if [[ -n "${KXIT_GH_REPO:-}" ]]; then
    FULL_REPO="$KXIT_GH_REPO"
elif [[ -n "$LOCKED_REPO" ]]; then
    FULL_REPO="$LOCKED_REPO"
else
    FULL_REPO="$OWNER/$REPO_SLUG"
fi
[[ "$FULL_REPO" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]] || fail "Invalid repository name '$FULL_REPO'. Expected OWNER/REPOSITORY."

if gh repo view "$FULL_REPO" --json nameWithOwner >/dev/null 2>&1; then
    info "GitHub repository already exists: $FULL_REPO"
else
    info "Creating GitHub repository: $FULL_REPO ($VISIBILITY)"
    if [[ "$VISIBILITY" == "private" ]]; then
        gh repo create "$FULL_REPO" --private --description "KXit Engine — lightweight LibGDX voxel sandbox and extensible 2D/3D engine by Rookmender Studios"
    else
        gh repo create "$FULL_REPO" --public --description "KXit Engine — lightweight LibGDX voxel sandbox and extensible 2D/3D engine by Rookmender Studios"
    fi
fi

if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    git init -b main "$ROOT" >/dev/null
fi

EXPECTED_HTTPS="https://github.com/$FULL_REPO.git"
if git remote get-url origin >/dev/null 2>&1; then
    ORIGIN_URL="$(git remote get-url origin)"
    NORMALIZED="${ORIGIN_URL%.git}"
    NORMALIZED="${NORMALIZED#https://github.com/}"
    NORMALIZED="${NORMALIZED#http://github.com/}"
    NORMALIZED="${NORMALIZED#ssh://git@github.com/}"
    NORMALIZED="${NORMALIZED#git@github.com:}"
    NORMALIZED="${NORMALIZED#git@github.com/}"
    [[ "${NORMALIZED,,}" == "${FULL_REPO,,}" ]] || fail "origin points to '$ORIGIN_URL', not '$FULL_REPO'. Refusing to push to the wrong repository."
else
    git remote add origin "$EXPECTED_HTTPS"
fi

if [[ ! -f kxit.lock ]]; then
    cat > kxit.lock <<LOCK
KXIT_LOCK_VERSION=1
PROJECT_NAME=KXit Engine
REPOSITORY=$FULL_REPO
CREATED_AT_UTC=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
LOCK
    info "Created project marker: kxit.lock"
else
    info "Found kxit.lock; this folder is already initialized as KXit Engine"
    if [[ -z "$LOCKED_REPO" ]]; then
        printf 'REPOSITORY=%s\n' "$FULL_REPO" >> kxit.lock
        info "Added repository identity to existing kxit.lock"
    elif [[ "${LOCKED_REPO,,}" != "${FULL_REPO,,}" ]]; then
        if [[ -z "${KXIT_GH_REPO:-}" ]]; then
            fail "kxit.lock points to '$LOCKED_REPO' but selected repository is '$FULL_REPO'. Set KXIT_GH_REPO explicitly to override."
        fi
        LOCK_TMP="$(mktemp)"
        awk -v repo="$FULL_REPO" '
            BEGIN { replaced = 0 }
            /^REPOSITORY=/ { print "REPOSITORY=" repo; replaced = 1; next }
            { print }
            END { if (!replaced) print "REPOSITORY=" repo }
        ' kxit.lock > "$LOCK_TMP"
        mv "$LOCK_TMP" kxit.lock
        info "Updated kxit.lock repository identity to $FULL_REPO"
    fi
fi

# Configure a local commit identity from the authenticated GitHub account if needed.
if ! git config user.name >/dev/null 2>&1; then
    GITHUB_NAME="$(gh api user --jq '.name // .login')"
    git config user.name "${GITHUB_NAME:-KXit Publisher}"
fi
if ! git config user.email >/dev/null 2>&1; then
    USER_ID="$(gh api user --jq '.id')"
    LOGIN="$(gh api user --jq '.login')"
    git config user.email "${USER_ID}+${LOGIN}@users.noreply.github.com"
fi

DEFAULT_BRANCH="$(gh api "repos/$FULL_REPO" --jq '.default_branch')"
[[ -n "$DEFAULT_BRANCH" ]] || DEFAULT_BRANCH="main"
BRANCH="$(git symbolic-ref --quiet --short HEAD 2>/dev/null || true)"
if [[ -z "$BRANCH" ]]; then
    git checkout -b "$DEFAULT_BRANCH" >/dev/null
    BRANCH="$DEFAULT_BRANCH"
elif ! git rev-parse --verify HEAD >/dev/null 2>&1; then
    # git init -b creates an unborn symbolic branch; rename its HEAD without
    # attempting to create a branch that already exists symbolically.
    git symbolic-ref HEAD "refs/heads/$DEFAULT_BRANCH"
    BRANCH="$DEFAULT_BRANCH"
elif [[ "$BRANCH" != "$DEFAULT_BRANCH" ]] && ! git show-ref --verify --quiet "refs/remotes/origin/$BRANCH"; then
    git branch -M "$DEFAULT_BRANCH"
    BRANCH="$DEFAULT_BRANCH"
fi

# Commit local additions first; build outputs and private signing keys are ignored.
git add -A
if ! git diff --cached --quiet; then
    git commit -m "Update KXit Engine sources"
else
    info "No source changes to commit"
fi

# Merge an existing remote history safely instead of force-pushing over it.
if git ls-remote --exit-code --heads origin "$DEFAULT_BRANCH" >/dev/null 2>&1; then
    git fetch origin "$DEFAULT_BRANCH"
    if git rev-parse --verify HEAD >/dev/null 2>&1; then
        if git merge-base HEAD "origin/$DEFAULT_BRANCH" >/dev/null 2>&1; then
            git merge --no-edit "origin/$DEFAULT_BRANCH" >/dev/null || fail "Could not merge remote '$DEFAULT_BRANCH'. Resolve conflicts, rerun tests, and retry."
        else
            git merge --allow-unrelated-histories -X ours --no-edit "origin/$DEFAULT_BRANCH" >/dev/null || fail "Could not merge the existing GitHub history. Resolve conflicts manually."
        fi
    fi
fi

BRANCH="$(git branch --show-current)"
git push --set-upstream origin "$BRANCH"
info "Sources committed and pushed to https://github.com/$FULL_REPO"

# The release tag comes from app/build.gradle.kts. The APK is uploaded as a release asset, not committed.
VERSION="$(sed -nE 's/.*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' app/build.gradle.kts | head -n 1)"
VERSION_CODE="$(sed -nE 's/.*versionCode[[:space:]]*=[[:space:]]*([0-9]+).*/\1/p' app/build.gradle.kts | head -n 1)"
[[ -n "$VERSION" ]] || VERSION="0.0.$(date -u '+%Y%m%d%H%M')"
[[ -n "$VERSION_CODE" ]] || VERSION_CODE="$(date -u '+%Y%m%d%H%M')"
COMMIT_SHORT="$(git rev-parse --short HEAD)"
TAG="v${VERSION}-build${VERSION_CODE}-${COMMIT_SHORT}"
CHECKSUM="$APK.sha256"
sha256sum "$APK" > "$CHECKSUM"

if gh release view "$TAG" --repo "$FULL_REPO" >/dev/null 2>&1; then
    info "Release $TAG exists; replacing its APK asset"
    gh release upload "$TAG" "$APK" "$CHECKSUM" --repo "$FULL_REPO" --clobber
else
    info "Creating GitHub Release $TAG"
    NOTES_FILE="$(mktemp)"
    printf '%s\n\n%s\n%s\n%s\n' \
        "KXit Engine Android build from Rookmender Studios." \
        "Automated source checks and Android build passed before publishing." \
        "Device smoke testing is recommended before wider distribution." \
        "The APK SHA-256 checksum is attached as a separate asset." > "$NOTES_FILE"
    gh release create "$TAG" "$APK" "$CHECKSUM" \
        --repo "$FULL_REPO" \
        --title "$PROJECT_NAME $VERSION" \
        --notes-file "$NOTES_FILE" \
        --target "$BRANCH"
    rm -f "$NOTES_FILE"
fi
rm -f "$CHECKSUM"
rm -f .kxit-tests-passed
info "Release published: https://github.com/$FULL_REPO/releases/tag/$TAG"

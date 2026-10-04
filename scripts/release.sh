#!/usr/bin/env bash
# Build, verify and (optionally) publish a signed release APK.
#
#   scripts/release.sh            test, build and verify into dist/; publishes nothing
#   scripts/release.sh --publish  also create a DRAFT GitHub release with the APK and its checksum
#
# Before a release: bump versionName and versionCode in android/app/build.gradle.kts. Signing uses
# android/keystore.properties and the keystore it names; neither leaves this machine (README,
# "Building"). The signing certificate is pinned in scripts/release-cert.sha256 (written on the
# first run; commit it): a build signed with any other key is refused. --publish needs the GitHub
# CLI (gh), a clean tree whose HEAD is pushed to origin, and a token in `git config github.token`.
set -euo pipefail
set +x # never trace: the token is read below

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID="$ROOT/android"
DIST="$ROOT/dist"
CERT_PIN="$ROOT/scripts/release-cert.sha256"
VECTORS="$ROOT/test-vectors"
PUBLISH=false

for arg in "$@"; do
  case "$arg" in
    --publish) PUBLISH=true ;;
    -h|--help) sed -n '2,11p' "$0"; exit 0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

die() { echo "error: $*" >&2; exit 1; }
step() { printf '\n==> %s\n' "$*"; }

# ---- preflight (everything that can fail cheaply fails before the build) ----

GRADLE_FILE="$ANDROID/app/build.gradle.kts"
VERSION="$(sed -n 's/^ *versionName = "\([0-9A-Za-z.+-]*\)".*/\1/p' "$GRADLE_FILE" | head -n 1 || true)"
CODE="$(sed -n 's/^ *versionCode = \([0-9][0-9]*\).*/\1/p' "$GRADLE_FILE" | head -n 1 || true)"
[ -n "$VERSION" ] && [ -n "$CODE" ] || die "could not read versionName/versionCode from $GRADLE_FILE"
TAG="v$VERSION"

[ -f "$ANDROID/keystore.properties" ] || die "android/keystore.properties is missing: create the release keystore first (README, Building)"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$SDK" ] && [ -f "$ANDROID/local.properties" ]; then
  SDK="$(sed -n 's/^sdk.dir=//p' "$ANDROID/local.properties" || true)"
fi
[ -n "$SDK" ] && [ -d "$SDK/build-tools" ] || die "Android SDK not found (set ANDROID_HOME or sdk.dir in android/local.properties)"
BUILD_TOOLS="$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n 1)"
APKSIGNER="$BUILD_TOOLS/apksigner"
AAPT2="$BUILD_TOOLS/aapt2"
[ -x "$APKSIGNER" ] && [ -x "$AAPT2" ] || die "apksigner/aapt2 not found in $SDK/build-tools"

if [ -z "${JAVA_HOME:-}" ] && [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
  export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

if $PUBLISH; then
  command -v gh >/dev/null || die "GitHub CLI (gh) is not installed"
  [ -f "$CERT_PIN" ] || die "no pinned signing certificate yet: run without --publish once, check and commit scripts/release-cert.sha256"
  # Release exactly what is committed and pushed: a release built from local edits could not be
  # reproduced from the repository.
  [ -z "$(git -C "$ROOT" status --porcelain)" ] || die "working tree has uncommitted changes; commit (and push) first"
  HEAD_SHA="$(git -C "$ROOT" rev-parse HEAD)"
  git -C "$ROOT" fetch --quiet --prune origin || die "cannot fetch from origin"
  PUSHED="$(git -C "$ROOT" branch -r --contains "$HEAD_SHA" --list 'origin/*')"
  [ -n "$PUSHED" ] || die "HEAD $HEAD_SHA is not pushed to origin yet"
  # An existing tag would make GitHub attach this APK to whatever commit the tag names.
  if git -C "$ROOT" ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null 2>&1; then
    die "tag $TAG already exists on origin: bump versionName and versionCode"
  fi
  REPO="$(git -C "$ROOT" config --get remote.origin.url | sed -E 's#^(ssh://)?([^@/]+@)?github\.com[:/]##; s#^https://([^@/]+@)?github\.com/##; s#/$##; s#\.git$##')"
  [[ "$REPO" =~ ^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$ ]] || die "origin is not a GitHub repository"
  # The token is read here but only ever handed to gh for a single command (never exported, so the
  # build and its plugins below never see it).
  TOKEN="$(git -C "$ROOT" config --get github.token || true)"
  [ -n "$TOKEN" ] || die "no GitHub token in git config github.token"
  if ! VIEW="$(cd "$ROOT" && GH_TOKEN="$TOKEN" gh release view "$TAG" --repo "$REPO" 2>&1)"; then
    case "$VIEW" in
      *"not found"*|*"Not Found"*) ;; # absent: good
      *) die "could not check GitHub for an existing release (token, network or repository problem)" ;;
    esac
  else
    die "release $TAG already exists on GitHub: bump versionName and versionCode"
  fi
fi

# ---- test and build ----

step "Testing and building $TAG (versionCode $CODE)"
# Cross-language crypto vectors: fresh JS vectors, then a second Kotlin run that checks them.
rm -f "$VECTORS/js.json" "$VECTORS/kotlin.json"
"$ANDROID/gradlew" -p "$ANDROID" --console=plain clean testDebugUnitTest
node --test "$ROOT"/reader/test/*.mjs
[ -f "$VECTORS/js.json" ] || die "reader tests did not write test-vectors/js.json"
"$ANDROID/gradlew" -p "$ANDROID" --console=plain --rerun-tasks testDebugUnitTest assembleRelease lintRelease

APK="$ANDROID/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || die "signed release APK not found at $APK (is keystore.properties correct?)"

# ---- verify ----

step "Verifying the APK"
VERIFY="$("$APKSIGNER" verify --verbose --print-certs "$APK" 2>&1)" || { echo "$VERIFY"; die "signature verification failed"; }
CERT_SHA256="$(printf '%s\n' "$VERIFY" | sed -n 's/.*certificate SHA-256 digest: //p' | sed -n 1p)"
[[ "$CERT_SHA256" =~ ^[0-9a-f]{64}$ ]] || die "could not read the signing certificate digest"
CERT_COUNT="$(printf '%s\n' "$VERIFY" | grep -c 'certificate SHA-256 digest:' || true)"
[ "$CERT_COUNT" = "1" ] || die "expected exactly one signer, found $CERT_COUNT"

if [ -f "$CERT_PIN" ]; then
  PINNED="$(tr -d ' \n' < "$CERT_PIN")"
  [ "$CERT_SHA256" = "$PINNED" ] || die "APK is signed with certificate $CERT_SHA256, but the pinned release certificate is $PINNED. Wrong keystore? Never release with a different key."
else
  echo "$CERT_SHA256" > "$CERT_PIN"
  echo "First run: pinned signing certificate $CERT_SHA256 in scripts/release-cert.sha256. Check it, then commit that file."
fi

# Captured once and searched as text: grep -q on a live pipe can end it early (SIGPIPE) and, under
# pipefail, turn a check into a silent pass.
BADGING="$("$AAPT2" dump badging "$APK")"
PACKAGE_LINE="$(printf '%s\n' "$BADGING" | sed -n 1p)"
case "$PACKAGE_LINE" in
  "package: name='dev.smsrelay' versionCode='$CODE' versionName='$VERSION'"*) ;;
  *) die "APK identity does not match build.gradle.kts: $PACKAGE_LINE" ;;
esac
case "$BADGING" in
  *application-debuggable*) die "APK is debuggable; refusing to release it" ;;
esac

mkdir -p "$DIST"
OUT="$DIST/sms-relay-$TAG.apk"
cp "$APK" "$OUT"
APK_SHA256="$(shasum -a 256 "$OUT" | cut -d' ' -f1)"
echo "$APK_SHA256  $(basename "$OUT")" > "$OUT.sha256"

cat <<EOF

APK:                    $OUT
APK SHA-256:            $APK_SHA256
Signing cert SHA-256:   $CERT_SHA256
EOF

if ! $PUBLISH; then
  printf '\nNot published (run with --publish to create a draft GitHub release).\n'
  exit 0
fi

# ---- publish (draft) ----

step "Creating draft release $TAG on $REPO"
NOTES="$(mktemp)"
trap 'rm -f "$NOTES"' EXIT
cat > "$NOTES" <<EOF
SMS Relay $TAG (versionCode $CODE)

**Verify before installing**
- APK SHA-256: \`$APK_SHA256\`
- Signing certificate SHA-256: \`$CERT_SHA256\`

Every release is signed with the same certificate; Android refuses an update signed with a
different one. If the certificate above ever changes, do not install that APK.

**Install or update on the phone**
1. Download \`$(basename "$OUT")\` from this page on the phone.
2. Open it; allow installing from this source when Android asks.
3. Updating keeps the pairing, keys and settings.
EOF
(cd "$ROOT" && GH_TOKEN="$TOKEN" gh release create "$TAG" "$OUT" "$OUT.sha256" \
  --repo "$REPO" --target "$HEAD_SHA" --title "SMS Relay $TAG" --notes-file "$NOTES" --draft)
printf '\nDraft created. Review it on GitHub and press Publish when ready.\n'

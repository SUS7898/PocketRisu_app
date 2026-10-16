#!/usr/bin/env bash
# Builds the PocketRisu web UI at the tag in upstream.lock (plus patches/) and copies the
# result into app/src/main/assets/bundle/ for the bundled UI mode (docs/DESIGN.md §4).
#
#   scripts/build-frontend.sh                 # clone into build/upstream, build, copy
#   UPSTREAM_DIR=/path/to/clone scripts/build-frontend.sh   # reuse an existing clone
#   SKIP_INSTALL=1 ...                        # reuse node_modules
#
# Output: app/src/main/assets/bundle/{index.html,assets/,...} + BUNDLE.json (version, file list).
# Excluded from the bundle (served by the server instead, see BundleRepository): source maps,
# tokenizer blobs (public/token, 56MB), samples, and a few large logos nobody renders in-app.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TAG="$(tr -d '[:space:]' < "$ROOT/upstream.lock")"
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+ ]] || { echo "upstream.lock must hold a tag like v1.14.0 (got '$TAG')"; exit 1; }
UPSTREAM_DIR="${UPSTREAM_DIR:-$ROOT/build/upstream}"
UPSTREAM_REPO="${UPSTREAM_REPO:-https://github.com/PocketRisu/PocketRisu}"
OUT="$ROOT/app/src/main/assets/bundle"
PNPM="${PNPM:-npx -y pnpm@10.34.1}"

echo "[frontend] upstream $TAG -> $OUT"

# --- checkout ---------------------------------------------------------------
if [ ! -d "$UPSTREAM_DIR/.git" ]; then
  git clone --depth 1 --branch "$TAG" "$UPSTREAM_REPO" "$UPSTREAM_DIR"
else
  git -C "$UPSTREAM_DIR" fetch --depth 1 origin "refs/tags/$TAG:refs/tags/$TAG" 2>/dev/null \
    || git -C "$UPSTREAM_DIR" fetch --depth 1 origin tag "$TAG"
fi
git -C "$UPSTREAM_DIR" checkout -q --detach "$TAG"
git -C "$UPSTREAM_DIR" reset -q --hard
git -C "$UPSTREAM_DIR" clean -qfd -e node_modules -e dist
UPSTREAM_SHA="$(git -C "$UPSTREAM_DIR" rev-parse --short HEAD)"
echo "[frontend] checked out $TAG ($UPSTREAM_SHA)"

# --- patches ----------------------------------------------------------------
PATCHES=("$ROOT"/patches/*.patch)
APPLIED=0
if [ -e "${PATCHES[0]}" ]; then
  for p in "${PATCHES[@]}"; do
    echo "[frontend] apply $(basename "$p")"
    git -C "$UPSTREAM_DIR" apply --3way "$p" || { echo "::error::patch failed: $(basename "$p") (rebase needed, see docs/UPSTREAM-UPDATE.md)"; exit 2; }
    APPLIED=$((APPLIED + 1))
  done
fi
echo "[frontend] patches applied: $APPLIED"

# --- build ------------------------------------------------------------------
cd "$UPSTREAM_DIR"
export NODE_OPTIONS="${NODE_OPTIONS:---max-old-space-size=4096}"
if [ -z "${SKIP_INSTALL:-}" ]; then
  $PNPM install --frozen-lockfile
fi
# Upstream's npm script is `vite build --sourcemap`; maps are excluded below, so build without.
$PNPM exec vite build

# --- copy -------------------------------------------------------------------
rm -rf "$OUT"
mkdir -p "$OUT"
# rsync is not guaranteed (Git Bash on Windows): use tar with excludes.
tar -C "$UPSTREAM_DIR/dist" \
  --exclude='*.map' \
  --exclude='./token' --exclude='./sample' \
  --exclude='./logo_original.png' --exclude='./logo_typo_trans_bg.png' \
  -cf - . | tar -C "$OUT" -xf -

FILE_LIST="$(cd "$OUT" && find . -type f | sed 's#^\./##' | LC_ALL=C sort)"
COUNT="$(printf '%s\n' "$FILE_LIST" | wc -l | tr -d ' ')"
SIZE="$(du -sk "$OUT" | cut -f1)"
{
  echo "{"
  echo "  \"upstreamVersion\": \"${TAG#v}\","
  echo "  \"upstreamSha\": \"$UPSTREAM_SHA\","
  echo "  \"patches\": $APPLIED,"
  echo "  \"builtAt\": \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\","
  echo "  \"fileCount\": $COUNT,"
  echo "  \"files\": ["
  printf '%s\n' "$FILE_LIST" | sed 's/\\/\\\\/g; s/"/\\"/g; s/^/    "/; s/$/",/' | sed '$ s/,$//'
  echo "  ]"
  echo "}"
} > "$OUT/BUNDLE.json"
echo "[frontend] bundle: $COUNT files, ${SIZE}KB -> $OUT/BUNDLE.json"

#!/usr/bin/env sh
set -eu

if [ "$#" -ne 2 ]; then
  printf '%s\n' "usage: $0 VERSION RELEASE_DIRECTORY" >&2
  exit 2
fi

VERSION=$1
RELEASE_DIRECTORY=$(CDPATH= cd -- "$2" && pwd)
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
TEMPORARY_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/sprout-distribution-test.XXXXXX")
trap 'rm -rf "$TEMPORARY_ROOT"' EXIT HUP INT TERM

tar -xzf "$RELEASE_DIRECTORY/sprout-$VERSION.tar.gz" -C "$TEMPORARY_ROOT"
PACKAGED_SPROUT="$TEMPORARY_ROOT/sprout-$VERSION/bin/sprout"
"$PACKAGED_SPROUT" --version | grep -F "Sprout $VERSION" >/dev/null

SPROUT_DOWNLOAD_ROOT="file://$RELEASE_DIRECTORY" \
SPROUT_INSTALL_ROOT="$TEMPORARY_ROOT/installed" \
SPROUT_BIN_DIR="$TEMPORARY_ROOT/bin" \
  "$REPOSITORY_ROOT/install.sh" --version "$VERSION" >/dev/null

INSTALLED_SPROUT="$TEMPORARY_ROOT/bin/sprout"
"$INSTALLED_SPROUT" --help >/dev/null

mkdir -p "$TEMPORARY_ROOT/projects"
(
  cd "$TEMPORARY_ROOT/projects"
  "$INSTALLED_SPROUT" new hello >/dev/null
  cd hello
  "$INSTALLED_SPROUT" run | grep -F "Hello from Sprout!" >/dev/null
  "$INSTALLED_SPROUT" test | grep -F "1 test(s) passed" >/dev/null
  "$INSTALLED_SPROUT" clean >/dev/null
  [ ! -e .sprout ]
)

printf 'Distribution smoke test passed for Sprout %s\n' "$VERSION"

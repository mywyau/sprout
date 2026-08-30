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

INSTALLED_BIN=$(CDPATH= cd -- "$TEMPORARY_ROOT/bin" && pwd)
INSTALLED_SPROUT="$INSTALLED_BIN/sprout"
"$INSTALLED_SPROUT" --help >/dev/null

mkdir -p "$TEMPORARY_ROOT/projects"
(
  cd "$TEMPORARY_ROOT/projects"
  "$INSTALLED_SPROUT" new hello >/dev/null
  cd hello
  "$INSTALLED_SPROUT" setup-ide >/dev/null
  grep -F "\"$INSTALLED_SPROUT\"" .bsp/sprout.json >/dev/null
  ruby "$REPOSITORY_ROOT/scripts/test-bsp.rb" "$INSTALLED_SPROUT" "$PWD" >/dev/null
  "$INSTALLED_SPROUT" add org.typelevel::cats-effect:3.6.3 | grep -F "Added cats-effect" >/dev/null
  grep -F 'cats-effect = "org.typelevel::cats-effect:3.6.3"' sprout.toml >/dev/null
  "$INSTALLED_SPROUT" graph | grep -F "cats-effect 3.6.3" >/dev/null
  "$INSTALLED_SPROUT" why cats-core | grep -F "cats-effect 3.6.3" >/dev/null
  "$INSTALLED_SPROUT" remove cats-effect | grep -F "Removed cats-effect" >/dev/null
  ! grep -F 'cats-effect = ' sprout.toml >/dev/null
  "$INSTALLED_SPROUT" remove --test munit >/dev/null
  "$INSTALLED_SPROUT" add --test org.scalameta::munit:1.1.1 | grep -F "Added munit" >/dev/null
  "$INSTALLED_SPROUT" run | grep -F "Hello from Sprout!" >/dev/null
  "$INSTALLED_SPROUT" test | grep -F "1 test(s) passed" >/dev/null
  "$INSTALLED_SPROUT" package | grep -F "Package created" >/dev/null
  .sprout/package/hello/bin/hello | grep -F "Hello from Sprout!" >/dev/null
  [ -f .sprout/package/hello/lib/hello.jar ]
  [ -f .sprout/package/hello.tar.gz ]
  [ -f .sprout/package/hello.zip ]
  if command -v sha256sum >/dev/null 2>&1; then
    (cd .sprout/package && sha256sum -c hello-checksums.txt >/dev/null)
  else
    (cd .sprout/package && shasum -a 256 -c hello-checksums.txt >/dev/null)
  fi
  if find .sprout/package/hello/lib -name '*munit*' | grep -q .; then
    printf '%s\n' "test dependency leaked into the application package" >&2
    exit 1
  fi
  "$INSTALLED_SPROUT" clean >/dev/null
  [ ! -e .sprout ]
)

printf 'Distribution smoke test passed for Sprout %s\n' "$VERSION"

#!/usr/bin/env sh
set -eu

REPOSITORY="https://github.com/mywyau/sprout"
REQUESTED_VERSION=${SPROUT_VERSION:-latest}
INSTALL_ROOT=${SPROUT_INSTALL_ROOT:-"${XDG_DATA_HOME:-${HOME:?}}/sprout"}
BIN_DIRECTORY=${SPROUT_BIN_DIR:-"${HOME:?}/.local/bin"}

usage() {
  cat <<'EOF'
Install Sprout without sbt.

Usage: install.sh [--version VERSION] [--install-root DIRECTORY] [--bin-dir DIRECTORY]

Environment variables:
  SPROUT_VERSION          Version to install; defaults to latest
  SPROUT_INSTALL_ROOT     Versioned installation root
  SPROUT_BIN_DIR          Launcher directory; defaults to ~/.local/bin
  SPROUT_DOWNLOAD_ROOT    Exact release asset base URL, primarily for mirrors
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --version)
      [ "$#" -ge 2 ] || { printf '%s\n' "error: --version requires a value" >&2; exit 2; }
      REQUESTED_VERSION=$2
      shift 2
      ;;
    --install-root)
      [ "$#" -ge 2 ] || { printf '%s\n' "error: --install-root requires a value" >&2; exit 2; }
      INSTALL_ROOT=$2
      shift 2
      ;;
    --bin-dir)
      [ "$#" -ge 2 ] || { printf '%s\n' "error: --bin-dir requires a value" >&2; exit 2; }
      BIN_DIRECTORY=$2
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf "error: unknown option '%s'\n" "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

for command in curl tar; do
  command -v "$command" >/dev/null 2>&1 || {
    printf "error: required command '%s' was not found\n" "$command" >&2
    exit 1
  }
done

if [ "$REQUESTED_VERSION" = latest ]; then
  LATEST_URL=$(curl -fsSL -o /dev/null -w '%{url_effective}' "$REPOSITORY/releases/latest")
  REQUESTED_VERSION=${LATEST_URL##*/}
  REQUESTED_VERSION=${REQUESTED_VERSION#v}
fi

case "$REQUESTED_VERSION" in
  *[!0-9A-Za-z.-]* | "")
    printf "error: invalid release version '%s'\n" "$REQUESTED_VERSION" >&2
    exit 2
    ;;
esac

DOWNLOAD_ROOT=${SPROUT_DOWNLOAD_ROOT:-"$REPOSITORY/releases/download/v$REQUESTED_VERSION"}
ARCHIVE="sprout-$REQUESTED_VERSION.tar.gz"
CHECKSUMS="sprout-$REQUESTED_VERSION-checksums.txt"
TEMPORARY_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/sprout-install.XXXXXX")
TEMPORARY_LINK="$BIN_DIRECTORY/.sprout-link.$$"
trap 'rm -rf "$TEMPORARY_ROOT"; rm -f "$TEMPORARY_LINK"' EXIT HUP INT TERM

printf 'Downloading Sprout %s...\n' "$REQUESTED_VERSION"
curl -fsSL "$DOWNLOAD_ROOT/$ARCHIVE" -o "$TEMPORARY_ROOT/$ARCHIVE"
curl -fsSL "$DOWNLOAD_ROOT/$CHECKSUMS" -o "$TEMPORARY_ROOT/$CHECKSUMS"

EXPECTED_CHECKSUM=$(awk -v archive="$ARCHIVE" '$2 == archive { print $1 }' "$TEMPORARY_ROOT/$CHECKSUMS")
if [ -z "$EXPECTED_CHECKSUM" ]; then
  printf '%s\n' "error: release checksums do not contain $ARCHIVE" >&2
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  ACTUAL_CHECKSUM=$(sha256sum "$TEMPORARY_ROOT/$ARCHIVE" | awk '{ print $1 }')
else
  ACTUAL_CHECKSUM=$(shasum -a 256 "$TEMPORARY_ROOT/$ARCHIVE" | awk '{ print $1 }')
fi

if [ "$ACTUAL_CHECKSUM" != "$EXPECTED_CHECKSUM" ]; then
  printf '%s\n' "error: downloaded archive failed SHA-256 verification" >&2
  exit 1
fi

VERSION_DIRECTORY="$INSTALL_ROOT/versions/$REQUESTED_VERSION"
if [ ! -d "$VERSION_DIRECTORY" ]; then
  mkdir -p "$INSTALL_ROOT/versions"
  tar -xzf "$TEMPORARY_ROOT/$ARCHIVE" -C "$TEMPORARY_ROOT"
  mv "$TEMPORARY_ROOT/sprout-$REQUESTED_VERSION" "$VERSION_DIRECTORY"
fi

mkdir -p "$BIN_DIRECTORY"
if [ -e "$BIN_DIRECTORY/sprout" ] && [ ! -L "$BIN_DIRECTORY/sprout" ]; then
  printf '%s\n' "error: $BIN_DIRECTORY/sprout exists and is not a symbolic link" >&2
  exit 1
fi
ln -s "$VERSION_DIRECTORY/bin/sprout" "$TEMPORARY_LINK"
mv -f "$TEMPORARY_LINK" "$BIN_DIRECTORY/sprout"

printf '\nInstalled Sprout %s to %s\n' "$REQUESTED_VERSION" "$VERSION_DIRECTORY"
case ":$PATH:" in
  *":$BIN_DIRECTORY:"*) printf '%s\n' "Run 'sprout --help' to get started." ;;
  *)
    printf '\nAdd this directory to PATH:\n\n  export PATH="%s:$PATH"\n' "$BIN_DIRECTORY"
    ;;
esac

if ! command -v java >/dev/null 2>&1 && [ -z "${JAVA_HOME:-}" ] && [ -z "${SPROUT_JAVA_HOME:-}" ]; then
  printf '\nNote: install Java 17 or newer before running Sprout.\n'
fi

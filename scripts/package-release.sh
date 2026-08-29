#!/usr/bin/env sh
set -eu

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  printf '%s\n' "usage: $0 VERSION [OUTPUT_DIRECTORY]" >&2
  exit 2
fi

VERSION=$1
case "$VERSION" in
  *[!0-9A-Za-z.-]* | "")
    printf "error: invalid release version '%s'\n" "$VERSION" >&2
    exit 2
    ;;
esac

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
REPOSITORY_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
OUTPUT_DIRECTORY=${2:-"$REPOSITORY_ROOT/target/release"}
ASSEMBLY_JAR="$REPOSITORY_ROOT/target/sprout.jar"
ARCHIVE_NAME="sprout-$VERSION"

if [ ! -f "$ASSEMBLY_JAR" ]; then
  printf '%s\n' "error: assembled Sprout jar not found." >&2
  printf '%s\n' "Run 'sbt cli/assembly' first." >&2
  exit 1
fi

STAGING_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/sprout-release.XXXXXX")
trap 'rm -rf "$STAGING_ROOT"' EXIT HUP INT TERM
PACKAGE_ROOT="$STAGING_ROOT/$ARCHIVE_NAME"

mkdir -p "$PACKAGE_ROOT/bin" "$PACKAGE_ROOT/lib" "$OUTPUT_DIRECTORY"
cp "$REPOSITORY_ROOT/bin/sprout" "$PACKAGE_ROOT/bin/sprout"
cp "$REPOSITORY_ROOT/packaging/windows/sprout.cmd" "$PACKAGE_ROOT/bin/sprout.cmd"
cp "$ASSEMBLY_JAR" "$PACKAGE_ROOT/lib/sprout.jar"
cp "$REPOSITORY_ROOT/LICENSE" "$PACKAGE_ROOT/LICENSE"
cp "$REPOSITORY_ROOT/README.md" "$PACKAGE_ROOT/README.md"
printf '%s\n' "$VERSION" > "$PACKAGE_ROOT/VERSION"
chmod 755 "$PACKAGE_ROOT/bin/sprout"

TAR_PATH="$OUTPUT_DIRECTORY/$ARCHIVE_NAME.tar.gz"
ZIP_PATH="$OUTPUT_DIRECTORY/$ARCHIVE_NAME.zip"

tar -C "$STAGING_ROOT" -czf "$TAR_PATH" "$ARCHIVE_NAME"
(cd "$STAGING_ROOT" && zip -qr "$ZIP_PATH" "$ARCHIVE_NAME")

checksum() {
  file=$1
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$file"
  else
    shasum -a 256 "$file"
  fi
}

CHECKSUM_PATH="$OUTPUT_DIRECTORY/$ARCHIVE_NAME-checksums.txt"
(
  cd "$OUTPUT_DIRECTORY"
  checksum "$ARCHIVE_NAME.tar.gz"
  checksum "$ARCHIVE_NAME.zip"
) > "$CHECKSUM_PATH"

printf 'Created %s\n' "$TAR_PATH"
printf 'Created %s\n' "$ZIP_PATH"
printf 'Created %s\n' "$CHECKSUM_PATH"

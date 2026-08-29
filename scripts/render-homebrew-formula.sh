#!/usr/bin/env sh
set -eu

if [ "$#" -ne 3 ]; then
  printf '%s\n' "usage: $0 VERSION TAR_SHA256 OUTPUT_FILE" >&2
  exit 2
fi

VERSION=$1
TAR_SHA256=$2
OUTPUT_FILE=$3

case "$VERSION" in *[!0-9A-Za-z.-]* | "") exit 2 ;; esac
case "$TAR_SHA256" in *[!0-9a-f]* | "") exit 2 ;; esac

mkdir -p "$(dirname -- "$OUTPUT_FILE")"
sed -e "s/@VERSION@/$VERSION/g" -e "s/@SHA256@/$TAR_SHA256/g" \
  "$(dirname -- "$0")/../packaging/homebrew/sprout.rb.template" > "$OUTPUT_FILE"

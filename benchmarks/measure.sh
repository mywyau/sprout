#!/usr/bin/env sh
set -eu

SPROUT=${SPROUT:-"$(pwd)/bin/sprout"}
FIXTURE=${1:-"$(pwd)/fixtures/hello-world"}
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
TEMPORARY_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/sprout-benchmark.XXXXXX")
trap 'rm -rf "$TEMPORARY_ROOT"' EXIT HUP INT TERM
PROJECT="$TEMPORARY_ROOT/project"

mkdir -p "$PROJECT"
cp -R "$FIXTURE/." "$PROJECT"

printf 'Sprout benchmark baseline\n'
printf 'timestamp\t%s\n' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
printf 'system\t%s\n' "$(uname -sm)"
printf 'java\t%s\n' "$(java -version 2>&1 | sed -n '1p')"
printf 'sprout\t%s\n' "$("$SPROUT" --version)"
printf 'fixture\t%s\n' "$FIXTURE"
printf '\nscenario\tmilliseconds\n'

measure() {
  label=$1
  shift
  ruby "$SCRIPT_DIR/time-command.rb" "$label" "$@"
}

measure "cli-startup" "$SPROUT" --help

(cd "$PROJECT" && "$SPROUT" clean >/dev/null 2>&1 || true)
measure "cold-compile" --chdir "$PROJECT" "$SPROUT" compile

(cd "$PROJECT" && "$SPROUT" clean >/dev/null)
measure "warm-compile" --chdir "$PROJECT" "$SPROUT" compile
measure "no-change-compile" --chdir "$PROJECT" "$SPROUT" compile

printf '\n// benchmark single-file change\n' >> "$PROJECT/src/main/scala/Main.scala"
measure "single-file-change" --chdir "$PROJECT" "$SPROUT" compile

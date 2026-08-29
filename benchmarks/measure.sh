#!/usr/bin/env sh
set -eu

SPROUT=${SPROUT:-"$(pwd)/bin/sprout"}
FIXTURE=${1:-"$(pwd)/fixtures/hello-world"}

measure() {
  label=$1
  shift
  start=$(date +%s)
  "$@" >/dev/null
  end=$(date +%s)
  printf '%-28s %ss\n' "$label" "$((end - start))"
}

measure "CLI startup" "$SPROUT" --help
(cd "$FIXTURE" && "$SPROUT" clean >/dev/null 2>&1 || true)
measure "Cold compile" sh -c "cd '$FIXTURE' && '$SPROUT' compile"
measure "No-change compile" sh -c "cd '$FIXTURE' && '$SPROUT' compile"

#!/usr/bin/env bash
# Live progress for the running /graphify build. Root from $FIREFILES_ROOT or cwd.
R="${FIREFILES_ROOT:-$(pwd)}"
PY="$(cat "$R/graphify-out/.graphify_python" 2>/dev/null || echo python3)"
G="$R/graphify-out/graph.json"
while :; do
  echo "=== graphify $(date +%T) ==="
  for f in "$R"/graphify-out/.graphify_chunk_*.json; do
    [ -e "$f" ] && echo "  chunk $(basename "$f") $(stat -c%s "$f")B"
  done
  [ -e "$G" ] && "$PY" -c 'import json,sys
g = json.load(open(sys.argv[1]))
print("  graph:", len(g.get("nodes", [])), "nodes", len(g.get("links", [])), "edges")' "$G" 2>/dev/null
  sleep 3
done

#!/usr/bin/env bash
# FireFiles dev monitor - one GNU screen session, 4 stacked panes, one per subsystem.
#
#   scripts/splits.sh            tail-only view of all 4 logs + live graphify progress
#   scripts/splits.sh --dev      also START the desktop app (npm run tauri dev) in pane 0
#   scripts/splits.sh --kill     tear the session down
#
# Attach any time:  screen -r firefiles
# In-session keys:   C-a <n> jump to window n | C-a S split | C-a Tab next region | C-a d detach
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOGS="$ROOT/logs"
SESSION="firefiles"
RC="$ROOT/.graphify_tmp.firefiles.screenrc"
DEV=0
[ "${1:-}" = "--dev" ] && DEV=1

if [ "${1:-}" = "--kill" ]; then
  screen -S "$SESSION" -X quit 2>/dev/null && echo "killed $SESSION" || echo "no session $SESSION"
  rm -f "$RC"; exit 0
fi
command -v screen >/dev/null || { echo "screen not installed"; exit 1; }
if screen -ls "$SESSION" >/dev/null 2>&1; then
  echo "already running -> screen -r $SESSION"; exit 0
fi

mkdir -p "$LOGS"
for f in desktop android monitor graphify; do touch "$LOGS/$f.log"; done

# Pane 0: desktop (Tauri+Rust+React). Either run it, or follow its log.
if [ "$DEV" = 1 ]; then
  P0="cd $ROOT && npm run tauri dev 2>&1 | tee -a $LOGS/desktop.log"
else
  P0="echo 'desktop pane - start with: scripts/splits.sh --dev'; tail -F -n 200 $LOGS/desktop.log"
fi

# Pane 1: Android device logcat (needs a device on USB/adb)
P1="if command -v adb >/dev/null && adb get-state >/dev/null 2>&1; then adb logcat -v time -s BridgeService:V MainActivity:V MonitorActivity:V com.example.bridge:V 2>&1 | tee -a $LOGS/android.log; else tail -F -n 200 $LOGS/android.log; fi"

# Pane 2: cloud monitor. It is deployed on a remote host (files.endrisusanto.my.id);
# here it only tails whatever docker gives us locally.
P2="if command -v docker >/dev/null; then docker compose -f $ROOT/docker-compose.yml logs -f --tail=200 bridge-monitor 2>&1 | tee -a $LOGS/monitor.log; else tail -F -n 200 $LOGS/monitor.log; fi"

# Pane 3: graphify build progress (live node/edge counts + chunks landing in graphify-out).
GFWATCH="$ROOT/scripts/graphify_watch.sh"
FIREFILES_ROOT="$ROOT" tee "$GFWATCH" >/dev/null <<'GFWEOF'
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
GFWEOF
chmod +x "$GFWATCH"
P3="$GFWATCH 2>&1 | tee -a $LOGS/graphify.log"

{
  echo "startup_message off"
  echo "vbell off"
  echo "altscreen on"
  echo "defscrollback 5000"
  echo 'caption splitonly "%{.bW}%-w%{.rW}%n %t%{-}%+w%=%{.bW}%h"'
  echo "splitwindow"; echo "focus down"
  echo "splitwindow"; echo "focus down"
  echo "splitwindow"; echo "focus down"
  echo "screen -t desktop  0 bash -c \"$P0\""
  echo "screen -t android  1 bash -c \"$P1\""
  echo "screen -t monitor  2 bash -c \"$P2\""
  echo "screen -t graphify 3 bash -c \"$P3\""
} > "$RC"

# -dm = start detached. Without it screen tries to attach and dies with
# "Must be connected to a terminal." when the caller has no TTY.
screen -c "$RC" -dmS "$SESSION" -ln
sleep 1
if screen -ls "$SESSION" >/dev/null 2>&1; then
  echo "session $SESSION up - 4 panes (desktop/android/monitor/graphify), logs in $LOGS"
else
  echo "FAILED to start session $SESSION - run 'screen -L -Logfile $LOGS/desktop.log -S $SESSION' manually"; exit 1
fi
echo "attach:  screen -r $SESSION     detach: C-a d     teardown: scripts/splits.sh --kill"

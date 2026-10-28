#!/usr/bin/env bash
# v0.1.2 held push on the emulator (no controller, no tracked hands): stand-in ray + trigger / grip, stand-in hand.
#   DEV=emulator-5570 bash tools/hold_flow.sh      -> .scratch/shots/H1-parked.png, H2-held-ctl.png, H3-held-hand.png ...
# Runs with the emulator un-throttled (harness `fast on`) so every step gets frames between the edges.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools"
DEV=${DEV:-emulator-5560}; PKG=io.embodit.accesscheck
H="bash $ROOT/tools/harness/emu.sh"
SH="$ROOT/.scratch/shots"; mkdir -p "$SH/emu"
cmd() { MSYS_NO_PATHCONV=1 adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd '$*'" >/dev/null 2>&1; sleep ${GAP:-1.2}; }
cap() { before=$(ls -1 "$SH/emu" 2>/dev/null | wc -l); adb -s $DEV emu screenrecord screenshot "$(cygpath -w "$SH/emu")" >/dev/null 2>&1
  for i in $(seq 1 12); do sleep 0.5; after=$(ls -1 "$SH/emu" 2>/dev/null | wc -l); [ "$after" -gt "$before" ] && break; done; sleep 0.5
  newest=$(ls -1t "$SH/emu"/*.png 2>/dev/null | head -1); [ -n "$newest" ] && mv -f "$newest" "$SH/$1.png" && echo "OK $SH/$1.png" || echo "capture FAILED"; }
log() { adb -s $DEV logcat -d -s AccessCheck:V 2>/dev/null | grep -E "$1" | tail -${2:-3} | cut -c32-240; }
adb -s $DEV logcat -c
$H fast on >/dev/null 2>&1
cmd hand off; cmd ray off
# 1. place with the stand-in controller: ghost on the ray's floor point, trigger puts it down (parked)
cmd state S31; sleep 2
cmd aim_ahead 1.8 -0.3; sleep 2
cmd trigger 1; cmd trigger 0; sleep 3
log "PLACE|CHAIR mode" 4
cap H1-parked
# 2. controller: ray on the chair, trigger held, the controller moves 0.6 m ahead and 0.3 m right: the chair goes with it
cmd pose
chair=$(log "POSE hmd" 1 | sed -E 's/.*chair=\(([-0-9.]+), ([-0-9.]+)\).*/\1 \2/')
cmd aim $chair; sleep 1
cmd trigger 1; sleep 2
log "HOLD|PUSH|CHAIR mode" 3
for i in 1 2 3 4 5 6; do cmd move_ray 0.05 -0.1; done
sleep 2
cap H2-held-ctl
cmd pose; log "POSE hmd" 1
cmd trigger 0; sleep 3
log "HOLD end|PUSH end|CHAIR mode|SNAP" 3
cap H3-let-go
# 3. the grip button instead of the trigger: ray on the chair, grip held, pulled 0.4 m back
cmd pose
chair=$(log "POSE hmd" 1 | sed -E 's/.*chair=\(([-0-9.]+), ([-0-9.]+)\).*/\1 \2/')
cmd aim $chair; sleep 1
cmd grip 1; sleep 2
for i in 1 2 3 4; do cmd move_ray 0 0.1; done
sleep 2
cmd pose; log "HOLD|PUSH|POSE hmd" 3
cmd grip 0; cmd ray off; sleep 3
log "HOLD end|PUSH end" 2
# 4. hand: open at the right grip, close a fist, move 0.5 m ahead / 0.25 m left, open
cmd hand_handle open right; sleep 2
cmd hand_handle fist right; sleep 2
log "HOLD|PUSH start|CHAIR mode" 3
for i in 1 2 3 4 5; do cmd hand_move -0.05 -0.1; done
sleep 2
cap H4-held-hand
cmd pose; log "POSE hmd" 1
cmd hand off; sleep 3
log "HOLD end|PUSH end|CHAIR mode" 3
cap H5-hand-let-go
# 5. a pinch at the handle holds too
cmd hand_handle pinch right; sleep 2
log "HOLD start" 1
cmd hand off; sleep 2
$H fast off >/dev/null 2>&1
adb -s $DEV logcat -d -s AccessCheck:V 2>/dev/null | grep -cE "FATAL" | sed 's/^/FATAL lines: /'
echo ALLDONE

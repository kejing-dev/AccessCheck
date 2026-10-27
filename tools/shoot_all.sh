#!/usr/bin/env bash
# All 30 design states -> .scratch/shots/Sxx.png (tools/qa_shot.sh per state, with the resets each state needs).
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools"
DEV=${DEV:-emulator-5560}; PKG=io.embodit.accesscheck
cmd() { MSYS_NO_PATHCONV=1 adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd '$*'" >/dev/null 2>&1; sleep 0.5; }
shoot() { SETTLE=${SETTLE:-7} bash tools/qa_shot.sh "$@" 2>&1 | tail -1; }
only="${1:-}"
want() { [ -z "$only" ] || [[ " $only " == *" $1 "* ]]; }
if [ -z "$only" ]; then adb -s $DEV shell am force-stop $PKG; sleep 2; adb -s $DEV shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 14; fi
# QA framing on the emulator room: the chair lands 1.7 m ahead, 0.3 m to the left (free floor; the design's 1.2 m puts it under the viewer's nose)
cmd place_ahead ${AHEAD:-1.7} ${SIDE:--0.3}
want S1 && shoot S1 && cmd state S3
want S2 && shoot S2 && cmd state S3
want S3 && shoot S3
want S4 && shoot S4
want S5 && shoot S5 && cmd obstacles_clear
want S6 && shoot S6
want S7 && shoot S7
want S8 && shoot S8
want S9 && shoot S9 && cmd obstacles_clear
want S10 && shoot S10
want S11 && shoot S11
want S12 && shoot S12
want S13 && shoot S13
want S14 && shoot S14
want S15 && cmd eye 1.60 && shoot S15
want S16 && cmd eye 1.01 && shoot S16 && cmd eye off
want S17 && shoot S17
want S18 && shoot S18
want S19 && SETTLE=8 shoot S19
want S20 && shoot S20 && cmd block off
want S21 && shoot S21
want S22 && shoot S22
want S23 && shoot S23
want S24 && shoot S24 && cmd lost 0
want S25 && shoot S25
want S26 && shoot S26
want S27 && shoot S27 && cmd obstacles_clear
want S28 && shoot S28 && cmd obstacles_clear
want S29 && shoot S29 && cmd clear_now
want S30 && shoot S30 && cmd clear_now
cmd state S3
echo ALLDONE

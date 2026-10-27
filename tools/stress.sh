#!/usr/bin/env bash
# Panel stress (design: 30 x report / menu / language / push<->park, plus Stage re-entry): tools/stress.sh [N] [RELAUNCHES]
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
export PATH="$PATH:${LOCALAPPDATA}/Android/Sdk/platform-tools"
DEV=${DEV:-emulator-5560}; PKG=io.embodit.accesscheck; N=${1:-30}; R=${2:-10}
cmd() { MSYS_NO_PATHCONV=1 adb -s $DEV shell "am broadcast -a $PKG.DEBUG -p $PKG --es cmd '$*'" >/dev/null 2>&1; }
adb -s $DEV logcat -c
cmd place_ahead 1.7 -0.3; cmd state S6; sleep 1
for i in $(seq 1 $N); do cmd report; sleep 0.5; cmd report; sleep 0.3; done; echo "report x$N"
for i in $(seq 1 $N); do cmd menu; sleep 0.5; cmd menu; sleep 0.3; done; echo "menu x$N"
for i in $(seq 1 $N); do cmd lang zh; sleep 0.4; cmd lang en; sleep 0.4; done; cmd lang system; echo "lang x$N"
for i in $(seq 1 $N); do cmd state S4; sleep 0.6; cmd park; sleep 0.5; done; echo "push/park x$N"
for i in $(seq 1 $R); do adb -s $DEV shell am force-stop $PKG; sleep 1.5; adb -s $DEV shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 11; done; echo "relaunch x$R"
sleep 3
echo "crashes: $(adb -s $DEV logcat -d 2>/dev/null | grep -c -E "FATAL EXCEPTION|ANR in $PKG|am_anr.*$PKG")"
echo "anr dialogs: $(adb -s $DEV shell dumpsys window windows 2>/dev/null | grep -c 'Application Not Responding')"
adb -s $DEV logcat -d -s AccessCheck:V 2>/dev/null | grep -E "STATE |SCENE attached" | tail -3 | cut -c1-120

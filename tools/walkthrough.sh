#!/system/bin/sh
# Runs on the TV (pushed by tools/record_walkthrough.sh): records the screen while pressing remote keys in real time.
# Each key is one pre-built press+release written to the remote's input device in a single write; `input keyevent`
# starts a JVM per key (~2 s here) and sendevent needs four processes per key, both far slower than a person.
PKG=io.github.starktvos
OUT=/sdcard/stark_walk.mp4
KEYS=/data/local/tmp/stark_keys
DEV=$(getevent -pl 2>/dev/null | awk '/add device/ {d=$4} /KEY_SELECT/ && d {print d; exit}')

key() { cat "$KEYS/$1" > "$DEV"; sleep "${2:-0.3}"; }
state() { dumpsys activity $PKG/.HomeActivity 2>/dev/null | grep -q "stark\.$1"; }
abort() { kill -2 $rec 2>/dev/null; wait $rec; input keyevent HOME; exit "$1"; }

am force-stop $PKG
am start -n $PKG/.HomeActivity >/dev/null
sleep 4
rm -f $OUT
screenrecord --bit-rate 16000000 --time-limit 20 $OUT &
rec=$!
sleep 0.6
am start -n $PKG/.HomeActivity --ez io.github.starktvos.SPLASH true >/dev/null
# Any key during the animation only skips it, so wait for it to start and finish before the first press.
i=0; while state splash=idle; do i=$((i + 1)); [ $i -gt 15 ] && abort 3; sleep 0.2; done
i=0; until state splash=idle; do i=$((i + 1)); [ $i -gt 50 ] && abort 3; sleep 0.1; done
state focus=fav:com.google.android.youtube.tv || abort 4
key right 0.4; key right 0.45; key left 0.5
key down 0.5
key ok 1.1
key down 0.4; key up 0.5
key back 0.9
key down 0.4; key up 0.5
key back 1.5
dumpsys activity activities | grep mResumedActivity | grep -q "$PKG/.HomeActivity" || abort 5
state focus=act:network || abort 5
kill -2 $rec 2>/dev/null
wait $rec

#!/system/bin/sh
# Runs on the TV (pushed by tools/record_walkthrough.sh): records the screen while pressing remote keys in real time.
# Each key is one pre-built press+release written to the remote's input device in a single write; `input keyevent`
# starts a JVM per key (~2 s here) and sendevent needs four processes per key, both far slower than a person.
PKG=io.github.starktvos
OUT=/sdcard/stark_walk.mp4
KEYS=/data/local/tmp/stark_keys
DEV=$(getevent -pl 2>/dev/null | awk '/add device/ {d=$4} /KEY_SELECT/ && d {print d; exit}')

key() { cat "$KEYS/$1" > "$DEV"; sleep "${2:-0.3}"; }

am force-stop $PKG
am start -n $PKG/.HomeActivity >/dev/null
sleep 4
rm -f $OUT
screenrecord --bit-rate 16000000 --time-limit 20 $OUT &
rec=$!
sleep 0.6
am start -n $PKG/.HomeActivity --ez io.github.starktvos.SPLASH true >/dev/null
sleep 4.2
key right 0.5; key right 0.6
key down 0.6
key ok 0.9
key ok 1.2
key back 0.5
key back 1.5
kill -2 $rec 2>/dev/null
wait $rec

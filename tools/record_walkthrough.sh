#!/usr/bin/env bash
# Records a real-time walkthrough on the TV and encodes docs/walkthrough.mp4 and docs/walkthrough.gif.
# Usage: tools/record_walkthrough.sh <tv-ip>   (Stark-TV-OS installed, TV on the home screen)
set -euo pipefail
cd "$(dirname "$0")/.."

ip="${1:-${STARK_TV:-}}"
[[ "$ip" =~ ^[0-9]{1,3}(\.[0-9]{1,3}){3}$ ]] || { echo "Usage: $0 <tv-ip>"; exit 2; }
TV="$ip:5555"
tv() { adb -s "$TV" "$@"; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# One press + release + syncs per file: struct input_event on a 32-bit kernel is <timeval sec, usec, type, code, value>.
python3 - "$work" <<'PY'
import struct, sys, os
codes = {"up": 103, "left": 105, "right": 106, "down": 108, "ok": 353, "back": 158}
ev = lambda t, c, v: struct.pack("<IIHHi", 0, 0, t, c, v)
for name, code in codes.items():
    with open(os.path.join(sys.argv[1], name), "wb") as f:
        f.write(ev(1, code, 1) + ev(0, 0, 0) + ev(1, code, 0) + ev(0, 0, 0))
PY

adb connect "$TV" >/dev/null
tv shell 'mkdir -p /data/local/tmp/stark_keys'
tv push -q "$work"/up "$work"/left "$work"/right "$work"/down "$work"/ok "$work"/back /data/local/tmp/stark_keys/
tv push -q tools/walkthrough.sh /data/local/tmp/stark_walk.sh
rc=0
tv shell 'sh /data/local/tmp/stark_walk.sh >/dev/null 2>&1; echo "rc=$?"' | grep -q '^rc=0' || rc=1
tv pull -q /sdcard/stark_walk.mp4 "$work/raw.mp4" || rc=1
tv shell 'rm -rf /sdcard/stark_walk.mp4 /data/local/tmp/stark_walk.sh /data/local/tmp/stark_keys'
if [ $rc -ne 0 ]; then echo "The walkthrough went off script; docs/ left unchanged. Run it again."; exit 1; fi

# Start 0.5 s after the startup animation's first black frame, just before its light sweep begins.
black=$(ffmpeg -hide_banner -i "$work/raw.mp4" -vf "blackdetect=d=0.05:pix_th=0.06" -an -f null - 2>&1 |
  sed -n 's/.*black_start:\([0-9.]*\).*/\1/p' | head -1)
start=$(awk -v b="${black:-0.6}" 'BEGIN { print b + 0.5 }')

# The startup animation plays at 2x; from the home screen on (its first scene change after 3 s) it's real time.
speed=2
cut=$(ffmpeg -hide_banner -ss "$start" -i "$work/raw.mp4" -vf "select='gt(t,3)*gt(scene,0.02)',showinfo" -an -f null - 2>&1 |
  sed -n 's/.*pts_time:\([0-9.]*\).*/\1/p' | head -1)
cut="${cut:-4.2}"
len=11

# screenrecord only writes a frame when the screen changes, so hold the last one for the still ending.
mkdir -p docs
ffmpeg -v error -y -ss "$start" -i "$work/raw.mp4" -t "$len" \
  -filter_complex "[0:v]tpad=stop_mode=clone:stop_duration=4,fps=30,split[a][b];
    [a]trim=end=${cut},setpts=(PTS-STARTPTS)/${speed}[logo];[b]trim=start=${cut},setpts=PTS-STARTPTS[rest];
    [logo][rest]concat=n=2:v=1:a=0,fps=30,format=yuv420p" -c:v libx264 -preset slow -crf 18 -profile:v high -movflags +faststart -an \
  docs/walkthrough.mp4
ffmpeg -v error -y -i docs/walkthrough.mp4 \
  -vf "fps=15,scale=960:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=192:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
  -loop 0 docs/walkthrough.gif
ls -lh docs/walkthrough.mp4 docs/walkthrough.gif

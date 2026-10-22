#!/usr/bin/env python3
"""Renders the Stark-TV-OS startup animation to app/res/raw/stark_boot.mp4.

The eclipse ring sweeps in, "STARK" settles into place, "TV OS" fades in, then the logo holds while the light
breathes. 1920x1080, 30 fps, 4 s, silent H.264 High@4.1 so old TV chips decode it in hardware.

Needs numpy, Pillow and ffmpeg (with libx264):  python3 tools/render_boot.py
"""
import math
import os
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "res", "raw", "stark_boot.mp4")
FONT = os.path.join(ROOT, "app", "assets", "fonts", "Michroma-Regular.ttf")

W, H, FPS, SECONDS = 1920, 1080, 30, 4.0
CX, CY, R = 960.0, 540.0, 305.0

# Colour around the ring, clockwise from 3 o'clock (same stops as Art.ring in the app): (position, rgb, strength)
STOPS = [
    (0.00, 0xC2621F, 1.00), (0.10, 0x703510, 0.50), (0.22, 0x244A99, 0.19), (0.36, 0x2E6BFF, 1.00),
    (0.50, 0x6FB4FF, 1.00), (0.62, 0x3C7BFF, 1.00), (0.71, 0x1A2A55, 0.06), (0.78, 0xFF9A40, 1.00),
    (0.85, 0xFFE3BF, 1.00), (0.92, 0xFF8A2A, 1.00), (1.00, 0xC2621F, 1.00),
]
SWEEP_START = 0.70  # the ring draws itself in from the dark gap just left of 12 o'clock


def ease_out(t):
    t = min(max(t, 0.0), 1.0)
    return 1 - (1 - t) ** 3


def ease_in_out(t):
    t = min(max(t, 0.0), 1.0)
    return t * t * (3 - 2 * t)


def phase(t, start, end):
    return min(max((t - start) / (end - start), 0.0), 1.0)


def ring_layers():
    y, x = np.mgrid[0:H, 0:W].astype(np.float32)
    dx, dy = x - CX, y - CY
    dist = np.abs(np.sqrt(dx * dx + dy * dy) - R)
    pos = (np.arctan2(dy, dx) / (2 * math.pi)) % 1.0

    xs = [s[0] for s in STOPS]
    rgb = np.stack([np.interp(pos, xs, [((s[1] >> sh) & 0xFF) / 255.0 for s in STOPS]) for sh in (16, 8, 0)], -1)
    strength = np.interp(pos, xs, [s[2] for s in STOPS]).astype(np.float32)

    core = np.exp(-(dist / 1.7) ** 2)
    inner = np.exp(-(dist / 7.0) ** 2) * 0.55
    outer = np.exp(-(dist / 40.0) ** 2) * 0.30
    glow = (inner + outer)[..., None] * rgb * strength[..., None]
    line = core[..., None] * (rgb * 0.55 + 0.45) * strength[..., None]
    halo = np.exp(-(dist / 22.0) ** 2)
    return pos.astype(np.float32), glow.astype(np.float32), line.astype(np.float32), halo.astype(np.float32)


def wordmark_strokes(x0, top, h, letter_w=1.35, gap=0.75, r=0.24):
    """Polylines for "STARK": the A has no crossbar and a small triangle inside, as in the logo."""
    def arc(cx, cy, rad, a0, a1, n=14):
        return [(cx + rad * math.cos(math.radians(a0 + (a1 - a0) * i / n)),
                 cy + rad * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]

    w = letter_w
    lines, fills = [], []
    x = 0.0
    s = [(x + w, 0)] + arc(x + r, r, r, 270, 180) + arc(x + r, 0.5 - r, r, 180, 90)
    s += arc(x + w - r, 0.5 + r, r, 270, 360) + arc(x + w - r, 1 - r, r, 0, 90) + [(x, 1)]
    lines.append(s)
    x += w + gap
    lines += [[(x, 0), (x + w, 0)], [(x + w / 2, 0), (x + w / 2, 1)]]
    x += w + gap
    lines.append([(x, 1), (x + w / 2, 0), (x + w, 1)])
    fills.append([(x + w / 2, 0.60), (x + w / 2 - 0.13, 0.86), (x + w / 2 + 0.13, 0.86)])
    x += w + gap
    lines.append([(x, 1), (x, 0)] + arc(x + w - r, r, r, 270, 360) + arc(x + w - r, 0.5 - r, r, 0, 90) + [(x, 0.5)])
    lines.append([(x + w * 0.45, 0.5), (x + w, 1)])
    x += w + gap
    lines += [[(x, 0), (x, 1)], [(x + w, 0), (x + 0.1, 0.5), (x + w, 1)]]
    total = x + w

    def place(pts):
        return [(x0 + px * h, top + py * h) for px, py in pts]
    return [place(l) for l in lines], [place(f) for f in fills], total * h


def text_layer(tracking, word_alpha, sub_alpha, font, ss=2):
    """Alpha for "STARK" and "TV OS", supersampled for clean edges."""
    img = Image.new("L", (W * ss, H * ss), 0)
    d = ImageDraw.Draw(img)
    h = 64 * ss
    gap = 0.75 * tracking
    _, _, width = wordmark_strokes(0, 0, h, gap=gap)
    x0, top = (W * ss - width) / 2, (CY - 46) * ss
    lines, fills, _ = wordmark_strokes(x0, top, h, gap=gap)
    level = int(255 * word_alpha)
    if level:
        for l in lines:
            d.line(l, fill=level, width=int(0.155 * h), joint="curve")
        for f in fills:
            d.polygon(f, fill=level)
    level = int(235 * sub_alpha)
    if level:
        label, spacing = "TV OS", 0.62
        size = 30 * ss
        widths = [font.getlength(ch) if ch != " " else 0 for ch in label]
        total = sum(widths) + spacing * size * (len(label) - 2) + spacing * size * 1.3
        x, base = (W * ss - total) / 2, (CY + 98) * ss
        for ch, cw in zip(label, widths):
            if ch == " ":
                x += spacing * size * 1.3
                continue
            d.text((x, base), ch, font=font, fill=level, anchor="ls")
            x += cw + spacing * size
    return np.asarray(img.resize((W, H), Image.LANCZOS), dtype=np.float32) / 255.0


def text_shield():
    """A soft dark area behind the letters, so the ring passes behind them without a visible box."""
    img = Image.new("L", (W, H), 0)
    ImageDraw.Draw(img).rectangle([CX - 345, CY - 70, CX + 345, CY + 38], fill=255)
    return np.asarray(img.filter(ImageFilter.GaussianBlur(26)), dtype=np.float32) / 255.0


def main():
    font = ImageFont.truetype(FONT, 60)
    pos, glow, line, halo = ring_layers()
    shield = text_shield()[..., None]
    rel = (pos - SWEEP_START) % 1.0
    rng = np.random.default_rng(7)
    frames = int(FPS * SECONDS)

    cmd = ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS),
           "-i", "-", "-c:v", "libx264", "-preset", "slow", "-crf", "16", "-tune", "animation",
           "-profile:v", "high", "-level", "4.1", "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-an", OUT]
    enc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    cached_text = {}
    for i in range(frames):
        t = i / FPS
        sweep = ease_in_out(phase(t, 0.10, 1.45))
        edge = np.clip((sweep - rel) / 0.012, 0, 1) if sweep < 1 else 1.0
        if np.ndim(edge):
            edge = edge[..., None]
        behind = (sweep - rel) % 1.0
        flare = np.exp(-(behind / 0.05) ** 2) * (1 - phase(t, 1.35, 1.9))
        breathe = 1.0 + 0.10 * math.sin(max(0.0, t - 1.6) * 2.4) * phase(t, 1.6, 2.2)
        light = (glow * breathe + line) * edge
        light = light + (flare * halo)[..., None] * np.array([1.0, 0.72, 0.45], np.float32) * 0.55
        light = light * (1 - 0.93 * shield * phase(t, 0.9, 1.5))

        word = ease_out(phase(t, 1.05, 2.05))
        sub = ease_out(phase(t, 1.65, 2.45))
        tracking = 1.0 + 0.55 * (1 - word)
        key = (round(tracking, 3), round(word, 3), round(sub, 3))
        if key not in cached_text:
            cached_text = {key: text_layer(tracking, word, sub, font)}
        txt = cached_text[key][..., None]

        rgb = 1 - np.exp(-1.6 * light)
        rgb = rgb * (1 - txt) + txt * np.array([0.95, 0.96, 0.98], np.float32)
        rgb += (rng.random((H, W, 1), dtype=np.float32) - 0.5) / 255.0
        enc.stdin.write((np.clip(rgb, 0, 1) * 255 + 0.5).astype(np.uint8).tobytes())
        sys.stdout.write(f"\rframe {i + 1}/{frames}")
        sys.stdout.flush()
    enc.stdin.close()
    enc.wait()
    print(f"\n{OUT} ({os.path.getsize(OUT) // 1024} KB)")
    return enc.returncode


if __name__ == "__main__":
    sys.exit(main())

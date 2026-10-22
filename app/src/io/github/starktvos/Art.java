package io.github.starktvos;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;

/**
 * Everything glowing is rendered once, in software, into bitmaps: the eclipse ring and soft glows. Drawing them
 * later is a single textured quad, so the look costs nothing per frame on weak TV GPUs.
 */
final class Art {
    /** Inner rectangle of {@link #tileGlow()}, inside a padding of GLOW_PAD on each side. */
    static final float GLOW_W = 100, GLOW_H = 56, GLOW_PAD = 34;

    private static final Path WORDMARK = wordmarkPath();
    private static final float LETTER_W = 0.95f, LETTER_GAP = 0.58f;

    private Art() {}

    /** The boot animation's ring: ice blue on the left, white-hot amber at the top right. Ring radius = 0.34 size. */
    static Bitmap ring(int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        float cx = size / 2f, r = size * 0.34f;
        int[] colors = {0xFFC2621F, 0x80703510, 0x30244A99, 0xFF2E6BFF, 0xFF6FB4FF, 0xFF3C7BFF, 0x101A2A55,
                0xFFFF9A40, 0xFFFFE3BF, 0xFFFF8A2A, 0xFFC2621F};
        float[] pos = {0f, 0.10f, 0.22f, 0.36f, 0.50f, 0.62f, 0.71f, 0.78f, 0.85f, 0.92f, 1f};
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setShader(new SweepGradient(cx, cx, colors, pos));
        RectF o = new RectF(cx - r, cx - r, cx + r, cx + r);

        p.setStrokeWidth(size * 0.075f);
        p.setMaskFilter(new BlurMaskFilter(size * 0.06f, BlurMaskFilter.Blur.NORMAL));
        p.setAlpha(140);
        c.drawOval(o, p);

        p.setStrokeWidth(size * 0.022f);
        p.setMaskFilter(new BlurMaskFilter(size * 0.018f, BlurMaskFilter.Blur.NORMAL));
        p.setAlpha(230);
        c.drawOval(o, p);

        p.setMaskFilter(null);
        p.setStrokeWidth(Math.max(1.5f, size * 0.006f));
        p.setAlpha(255);
        c.drawOval(o, p);
        b.prepareToDraw();
        return b;
    }

    /** A soft round light, alpha only: drawn with a paint colour, it tints to anything. */
    static Bitmap radialGlow(int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float h = size / 2f;
        p.setShader(new RadialGradient(h, h, h, new int[]{0xFFFFFFFF, 0x66FFFFFF, 0x1AFFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.35f, 0.7f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, size, size, p);
        Bitmap alpha = b.extractAlpha();
        b.recycle();
        return alpha;
    }

    /** A blurred rounded rectangle, alpha only, for the light under a focused tile. */
    static Bitmap tileGlow() {
        int w = (int) (GLOW_W + 2 * GLOW_PAD), h = (int) (GLOW_H + 2 * GLOW_PAD);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFFFFFFFF);
        p.setMaskFilter(new BlurMaskFilter(GLOW_PAD * 0.6f, BlurMaskFilter.Blur.NORMAL));
        c.drawRoundRect(new RectF(GLOW_PAD, GLOW_PAD, GLOW_PAD + GLOW_W, GLOW_PAD + GLOW_H), 10, 10, p);
        Bitmap alpha = b.extractAlpha();
        b.recycle();
        return alpha;
    }

    /** Where to draw {@link #tileGlow()} so its inner rectangle covers the tile. */
    static void glowRect(RectF tile, RectF out) {
        float sx = tile.width() / GLOW_W, sy = tile.height() / GLOW_H;
        out.set(tile.left - GLOW_PAD * sx, tile.top - GLOW_PAD * sy, tile.right + GLOW_PAD * sx, tile.bottom + GLOW_PAD * sy);
    }

    static float wordmarkWidth(float height) {
        return (5 * LETTER_W + 4 * LETTER_GAP) * height;
    }

    /** "STARK" in the boot animation's lettering, drawn with a stroke paint (its width is set here). */
    static void wordmark(Canvas c, float x, float baseline, float height, Paint stroke) {
        c.save();
        c.translate(x, baseline - height);
        c.scale(height, height);
        float w = stroke.getStrokeWidth();
        stroke.setStrokeWidth(0.1f);
        c.drawPath(WORDMARK, stroke);
        stroke.setStrokeWidth(w);
        c.restore();
    }

    static Paint wordmarkPaint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStrokeJoin(Paint.Join.MITER);
        p.setColor(color);
        return p;
    }

    private static Path wordmarkPath() {
        Path p = new Path();
        float w = LETTER_W, r = 0.22f, x = 0;
        // S
        p.moveTo(x + w, 0);
        p.lineTo(x + r, 0);
        p.arcTo(new RectF(x, 0, x + 2 * r, 2 * r), 270, -90);
        p.lineTo(x, 0.5f - r);
        p.arcTo(new RectF(x, 0.5f - 2 * r, x + 2 * r, 0.5f), 180, -90);
        p.lineTo(x + w - r, 0.5f);
        p.arcTo(new RectF(x + w - 2 * r, 0.5f, x + w, 0.5f + 2 * r), 270, 90);
        p.lineTo(x + w, 1 - r);
        p.arcTo(new RectF(x + w - 2 * r, 1 - 2 * r, x + w, 1), 0, 90);
        p.lineTo(x, 1);
        // T
        x += w + LETTER_GAP;
        p.moveTo(x, 0);
        p.lineTo(x + w, 0);
        p.moveTo(x + w / 2, 0);
        p.lineTo(x + w / 2, 1);
        // A: no crossbar, a small triangle inside
        x += w + LETTER_GAP;
        p.moveTo(x, 1);
        p.lineTo(x + w / 2, 0);
        p.lineTo(x + w, 1);
        p.moveTo(x + w / 2, 0.62f);
        p.lineTo(x + w / 2 - 0.09f, 0.86f);
        p.lineTo(x + w / 2 + 0.09f, 0.86f);
        p.close();
        // R
        x += w + LETTER_GAP;
        p.moveTo(x, 1);
        p.lineTo(x, 0);
        p.lineTo(x + w - r, 0);
        p.arcTo(new RectF(x + w - 2 * r, 0, x + w, 2 * r), 270, 90);
        p.lineTo(x + w, 0.5f - r);
        p.arcTo(new RectF(x + w - 2 * r, 0.5f - 2 * r, x + w, 0.5f), 0, 90);
        p.lineTo(x, 0.5f);
        p.moveTo(x + w * 0.45f, 0.5f);
        p.lineTo(x + w, 1);
        // K
        x += w + LETTER_GAP;
        p.moveTo(x, 0);
        p.lineTo(x, 1);
        p.moveTo(x + w, 0);
        p.lineTo(x + 0.08f, 0.5f);
        p.lineTo(x + w, 1);
        return p;
    }
}

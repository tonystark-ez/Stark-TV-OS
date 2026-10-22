package io.github.starktvos;

import android.content.Context;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;

import java.util.Locale;

/**
 * "Eclipse": pure black, an ice-blue to amber light, wide geometric type. Every screen is drawn on a 1920x1080
 * design grid and scaled to the real display.
 */
final class Theme {
    static final float W = 1920f, H = 1080f, MARGIN = 96f;

    static final int BG = 0xFF000000;
    static final int SURFACE = 0xFF0B0D12;
    static final int SURFACE_HI = 0xFF151922;
    static final int BORDER = 0x17FFFFFF;
    static final int HIGHLIGHT = 0x24FFFFFF;
    static final int TEXT = 0xFFF4F6FA;
    static final int DIM = 0xFF7D8696;
    static final int FAINT = 0xFF3B414C;

    static final int BLUE = 0xFF2F6BFF;
    static final int ICE = 0xFF6FB4FF;
    static final int AMBER = 0xFFFFA24D;
    static final int ACCENT = ICE;
    static final int GOOD = 0xFF5BD69A;
    static final int WARN = 0xFFFFB55C;
    static final int BAD = 0xFFFF6B6B;

    static final Typeface THIN = Typeface.create("sans-serif-thin", Typeface.NORMAL);
    static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface REGULAR = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    /** Michroma (SIL OFL), the wide geometric face of the boot animation. Falls back to medium. */
    static Typeface display = MEDIUM;

    private Theme() {}

    static void init(Context c) {
        try {
            display = Typeface.createFromAsset(c.getAssets(), "fonts/Michroma-Regular.ttf");
        } catch (RuntimeException e) {
            display = MEDIUM;
        }
    }

    static TextPaint text(int color, float size, Typeface face) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setColor(color);
        p.setTextSize(size);
        p.setTypeface(face);
        return p;
    }

    /** Wide, letter-spaced caps for labels and section titles. */
    static TextPaint caps(int color, float size) {
        TextPaint p = text(color, size, MEDIUM);
        p.setLetterSpacing(0.24f);
        return p;
    }

    static Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        return p;
    }

    static Paint stroke(int color, float width) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(width);
        p.setColor(color);
        return p;
    }

    /** Blue to amber across a unit width; position it with setLocalMatrix. */
    static LinearGradient eclipseGradient() {
        return new LinearGradient(0, 0, 1, 0, new int[]{BLUE, ICE, 0xFFFFD2A0, AMBER},
                new float[]{0f, 0.4f, 0.75f, 1f}, Shader.TileMode.CLAMP);
    }

    /** Ease-out cubic. */
    static float ease(float t) {
        if (t <= 0) return 0;
        if (t >= 1) return 1;
        float u = 1 - t;
        return 1 - u * u * u;
    }

    static int lerpColor(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    static String fit(String s, TextPaint p, float width) {
        if (s == null) return "";
        if (p.measureText(s) <= width) return s;
        return TextUtils.ellipsize(s, p, width, TextUtils.TruncateAt.END).toString();
    }

    static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.getDefault());
    }

    static String bytes(long b) {
        if (b < 0) b = 0;
        if (b >= 1L << 30) return String.format(Locale.US, "%.1f GB", b / (double) (1L << 30));
        if (b >= 1L << 20) return (b >> 20) + " MB";
        return (b >> 10) + " KB";
    }
}

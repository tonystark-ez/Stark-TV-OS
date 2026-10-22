package io.github.starktvos;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Line icons drawn from paths on a 24x24 grid: no image assets, crisp at any size. */
final class Glyphs {
    static final int WIFI = 0, ETHERNET = 1, OFFLINE = 2, CAST = 3, SPARKLE = 4, GAUGE = 5, SHIELD = 6, GEAR = 7,
            PERSON = 8, INPUT = 9, GRID = 10, BAG = 11, TV = 12, GLOBE = 13, INFO = 14, CHIP = 15, POWER = 16,
            STAR = 17, DOWNLOAD = 18, HOME = 19, ARROWS = 20, TRASH = 21, MOON = 22, LOCK = 23, BOLT = 24,
            REFRESH = 25, COUNT = 26;

    private static final Path[] PATHS = new Path[COUNT];

    static {
        PATHS[WIFI] = wifi();
        PATHS[ETHERNET] = ethernet();
        Path off = wifi();
        off.moveTo(3, 3);
        off.lineTo(21, 21);
        PATHS[OFFLINE] = off;
        PATHS[CAST] = cast();
        PATHS[SPARKLE] = sparkle();
        PATHS[GAUGE] = gauge();
        PATHS[SHIELD] = shield();
        PATHS[GEAR] = gear();
        PATHS[PERSON] = person();
        PATHS[INPUT] = input();
        PATHS[GRID] = grid();
        PATHS[BAG] = bag();
        PATHS[TV] = tv();
        PATHS[GLOBE] = globe();
        PATHS[INFO] = info();
        PATHS[CHIP] = chip();
        PATHS[POWER] = power();
        PATHS[STAR] = star();
        PATHS[DOWNLOAD] = download();
        PATHS[HOME] = home();
        PATHS[ARROWS] = arrows();
        PATHS[TRASH] = trash();
        PATHS[MOON] = moon();
        PATHS[LOCK] = lock();
        PATHS[BOLT] = bolt();
        PATHS[REFRESH] = refresh();
    }

    private Glyphs() {}

    /** A stroke paint for glyphs; its width is in grid units (1.8 looks right at every size). */
    static Paint paint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.8f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        return p;
    }

    static void draw(Canvas c, int glyph, float cx, float cy, float size, Paint p) {
        if (glyph < 0 || glyph >= COUNT) return;
        float k = size / 24f;
        c.save();
        c.translate(cx - size / 2f, cy - size / 2f);
        c.scale(k, k);
        c.drawPath(PATHS[glyph], p);
        c.restore();
    }

    private static RectF oval(float cx, float cy, float r) {
        return new RectF(cx - r, cy - r, cx + r, cy + r);
    }

    private static Path wifi() {
        Path p = new Path();
        float[] radii = {4.5f, 9f, 13.5f};
        for (float r : radii) p.addArc(oval(12, 19.5f, r), 225, 90);
        p.addCircle(12, 19.5f, 0.9f, Path.Direction.CW);
        return p;
    }

    private static Path ethernet() {
        Path p = new Path();
        p.addRect(9, 2, 15, 8, Path.Direction.CW);
        p.moveTo(12, 8); p.lineTo(12, 16);
        p.moveTo(5, 12); p.lineTo(19, 12);
        p.moveTo(5, 12); p.lineTo(5, 16);
        p.moveTo(19, 12); p.lineTo(19, 16);
        p.addRect(2, 16, 8, 22, Path.Direction.CW);
        p.addRect(9, 16, 15, 22, Path.Direction.CW);
        p.addRect(16, 16, 22, 22, Path.Direction.CW);
        return p;
    }

    private static Path cast() {
        Path p = new Path();
        p.moveTo(2, 8); p.lineTo(2, 4); p.lineTo(22, 4); p.lineTo(22, 20); p.lineTo(14, 20);
        p.addArc(new RectF(-2, 16, 6, 24), 270, 90);
        p.addArc(new RectF(-6, 12, 10, 28), 270, 90);
        p.addCircle(2.6f, 19.4f, 0.8f, Path.Direction.CW);
        return p;
    }

    private static Path sparkle() {
        Path p = new Path();
        p.moveTo(11, 3);
        p.quadTo(12, 11, 20, 12);
        p.quadTo(12, 13, 11, 21);
        p.quadTo(10, 13, 2, 12);
        p.quadTo(10, 11, 11, 3);
        p.close();
        p.moveTo(19, 2);
        p.quadTo(19.4f, 4.6f, 22, 5);
        p.quadTo(19.4f, 5.4f, 19, 8);
        p.quadTo(18.6f, 5.4f, 16, 5);
        p.quadTo(18.6f, 4.6f, 19, 2);
        p.close();
        return p;
    }

    private static Path gauge() {
        Path p = new Path();
        p.addArc(new RectF(3, 5, 21, 23), 150, 240);
        p.moveTo(12, 14); p.lineTo(16.5f, 9);
        p.addCircle(12, 14, 1f, Path.Direction.CW);
        return p;
    }

    private static Path shield() {
        Path p = new Path();
        p.moveTo(12, 2); p.lineTo(20, 5); p.lineTo(20, 11);
        p.cubicTo(20, 16, 16.5f, 20, 12, 22);
        p.cubicTo(7.5f, 20, 4, 16, 4, 11);
        p.lineTo(4, 5); p.close();
        p.moveTo(8.5f, 12); p.lineTo(11, 14.5f); p.lineTo(15.5f, 9.5f);
        return p;
    }

    private static Path gear() {
        Path p = new Path();
        float r1 = 7.2f, r2 = 10f;
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45);
            double[] angles = {a - Math.toRadians(15), a - Math.toRadians(9), a + Math.toRadians(9), a + Math.toRadians(15)};
            float[] radii = {r1, r2, r2, r1};
            for (int j = 0; j < 4; j++) {
                float x = 12 + (float) (Math.cos(angles[j]) * radii[j]);
                float y = 12 + (float) (Math.sin(angles[j]) * radii[j]);
                if (i == 0 && j == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
        }
        p.close();
        p.addCircle(12, 12, 3f, Path.Direction.CW);
        return p;
    }

    private static Path person() {
        Path p = new Path();
        p.addCircle(12, 8, 4, Path.Direction.CW);
        p.moveTo(4, 21);
        p.cubicTo(4, 16, 8, 14, 12, 14);
        p.cubicTo(16, 14, 20, 16, 20, 21);
        return p;
    }

    private static Path input() {
        Path p = new Path();
        p.moveTo(3, 6); p.lineTo(21, 6); p.lineTo(21, 11); p.lineTo(18, 15); p.lineTo(6, 15); p.lineTo(3, 11); p.close();
        p.moveTo(12, 15); p.lineTo(12, 21);
        p.moveTo(8, 9.5f); p.lineTo(16, 9.5f);
        return p;
    }

    private static Path grid() {
        Path p = new Path();
        p.addRoundRect(new RectF(4, 4, 10, 10), 1.5f, 1.5f, Path.Direction.CW);
        p.addRoundRect(new RectF(14, 4, 20, 10), 1.5f, 1.5f, Path.Direction.CW);
        p.addRoundRect(new RectF(4, 14, 10, 20), 1.5f, 1.5f, Path.Direction.CW);
        p.addRoundRect(new RectF(14, 14, 20, 20), 1.5f, 1.5f, Path.Direction.CW);
        return p;
    }

    private static Path bag() {
        Path p = new Path();
        p.moveTo(5, 8); p.lineTo(19, 8); p.lineTo(18, 21); p.lineTo(6, 21); p.close();
        p.moveTo(9, 8); p.lineTo(9, 6.5f);
        p.cubicTo(9, 2.5f, 15, 2.5f, 15, 6.5f);
        p.lineTo(15, 8);
        return p;
    }

    private static Path tv() {
        Path p = new Path();
        p.addRoundRect(new RectF(2, 4, 22, 17), 2, 2, Path.Direction.CW);
        p.moveTo(8, 21); p.lineTo(16, 21);
        p.moveTo(12, 17); p.lineTo(12, 21);
        return p;
    }

    private static Path globe() {
        Path p = new Path();
        p.addCircle(12, 12, 9, Path.Direction.CW);
        p.addOval(new RectF(8, 3, 16, 21), Path.Direction.CW);
        p.moveTo(3, 12); p.lineTo(21, 12);
        return p;
    }

    private static Path info() {
        Path p = new Path();
        p.addCircle(12, 12, 9, Path.Direction.CW);
        p.moveTo(12, 11); p.lineTo(12, 16.5f);
        p.addCircle(12, 7.8f, 0.5f, Path.Direction.CW);
        return p;
    }

    private static Path chip() {
        Path p = new Path();
        p.addRoundRect(new RectF(6, 6, 18, 18), 1.5f, 1.5f, Path.Direction.CW);
        p.addRect(9.5f, 9.5f, 14.5f, 14.5f, Path.Direction.CW);
        float[] at = {9, 12, 15};
        for (float v : at) {
            p.moveTo(v, 2.5f); p.lineTo(v, 6);
            p.moveTo(v, 18); p.lineTo(v, 21.5f);
            p.moveTo(2.5f, v); p.lineTo(6, v);
            p.moveTo(18, v); p.lineTo(21.5f, v);
        }
        return p;
    }

    private static Path power() {
        Path p = new Path();
        p.addArc(new RectF(4, 5, 20, 21), -60, 300);
        p.moveTo(12, 2.5f); p.lineTo(12, 11);
        return p;
    }

    private static Path star() {
        Path p = new Path();
        for (int i = 0; i < 10; i++) {
            double a = Math.toRadians(-90 + i * 36);
            float r = i % 2 == 0 ? 9.5f : 4f;
            float x = 12 + (float) (Math.cos(a) * r), y = 12.8f + (float) (Math.sin(a) * r);
            if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        p.close();
        return p;
    }

    private static Path download() {
        Path p = new Path();
        p.moveTo(12, 3); p.lineTo(12, 15);
        p.moveTo(7, 10); p.lineTo(12, 15); p.lineTo(17, 10);
        p.moveTo(4, 20); p.lineTo(20, 20);
        return p;
    }

    private static Path home() {
        Path p = new Path();
        p.moveTo(3, 11); p.lineTo(12, 3); p.lineTo(21, 11);
        p.moveTo(5.5f, 9); p.lineTo(5.5f, 21); p.lineTo(18.5f, 21); p.lineTo(18.5f, 9);
        p.moveTo(10, 21); p.lineTo(10, 15); p.lineTo(14, 15); p.lineTo(14, 21);
        return p;
    }

    private static Path arrows() {
        Path p = new Path();
        p.moveTo(8, 7); p.lineTo(3, 12); p.lineTo(8, 17);
        p.moveTo(16, 7); p.lineTo(21, 12); p.lineTo(16, 17);
        p.moveTo(3, 12); p.lineTo(21, 12);
        return p;
    }

    private static Path trash() {
        Path p = new Path();
        p.moveTo(4, 6); p.lineTo(20, 6);
        p.moveTo(9, 6); p.lineTo(9, 3.5f); p.lineTo(15, 3.5f); p.lineTo(15, 6);
        p.moveTo(6, 6); p.lineTo(7, 21); p.lineTo(17, 21); p.lineTo(18, 6);
        p.moveTo(10, 10); p.lineTo(10, 17);
        p.moveTo(14, 10); p.lineTo(14, 17);
        return p;
    }

    private static Path moon() {
        Path a = new Path();
        a.addCircle(11, 13, 8.5f, Path.Direction.CW);
        Path b = new Path();
        b.addCircle(16.5f, 7.5f, 7f, Path.Direction.CW);
        a.op(b, Path.Op.DIFFERENCE);
        return a;
    }

    private static Path lock() {
        Path p = new Path();
        p.addRoundRect(new RectF(5, 10, 19, 21), 2, 2, Path.Direction.CW);
        p.moveTo(8, 10); p.lineTo(8, 7);
        p.cubicTo(8, 2.5f, 16, 2.5f, 16, 7);
        p.lineTo(16, 10);
        return p;
    }

    private static Path bolt() {
        Path p = new Path();
        p.moveTo(13, 2); p.lineTo(5, 13.5f); p.lineTo(11.5f, 13.5f); p.lineTo(10.5f, 22); p.lineTo(19, 10);
        p.lineTo(12.5f, 10); p.close();
        return p;
    }

    private static Path refresh() {
        Path p = new Path();
        p.addArc(new RectF(4, 4, 20, 20), -30, 300);
        p.moveTo(19.5f, 3.5f); p.lineTo(19.5f, 8.5f); p.lineTo(14.5f, 8.5f);
        return p;
    }
}

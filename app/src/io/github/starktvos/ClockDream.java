package io.github.starktvos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.service.dreams.DreamService;
import android.text.TextPaint;
import android.text.format.DateFormat;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * Stark Clock screensaver: a dim eclipse ring and clock on black that jump to a new spot every minute, so no
 * pixel stays lit. Redraws once a minute; no network, no animation.
 */
public final class ClockDream extends DreamService {
    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(false);
        setContentView(new ClockView(this));
    }

    private static final class ClockView extends View {
        private static final float RING_D = 520;
        private final TextPaint time = Theme.text(0xFF9AA3B0, 64, Theme.display);
        private final TextPaint date = Theme.caps(0xFF4E5662, 18);
        private final Paint ringPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final RectF r = new RectF();
        private final Random random = new Random();
        private final Runnable tick = this::moveAndRedraw;
        private Bitmap ring;
        private float x = Theme.W / 2, y = Theme.H / 2;

        ClockView(Context c) {
            super(c);
            time.setTextAlign(Paint.Align.CENTER);
            date.setTextAlign(Paint.Align.CENTER);
            ringPaint.setAlpha(110);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!Prefs.safeMode(getContext())) {
                final int px = Math.max(64, Math.round(RING_D * getResources().getDisplayMetrics().widthPixels / Theme.W));
                Bg.quick(() -> {
                    final Bitmap b = Art.ring(px);
                    Bg.ui(() -> {
                        ring = b;
                        invalidate();
                    });
                });
            }
            moveAndRedraw();
        }

        @Override
        protected void onDetachedFromWindow() {
            removeCallbacks(tick);
            super.onDetachedFromWindow();
        }

        private void moveAndRedraw() {
            x = 360 + random.nextFloat() * (Theme.W - 720);
            y = 300 + random.nextFloat() * (Theme.H - 600);
            invalidate();
            long now = System.currentTimeMillis();
            postDelayed(tick, 60_000 - now % 60_000 + 50);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(0xFF000000);
            float s = getWidth() / Theme.W;
            c.save();
            c.scale(s, s);
            if (ring != null) {
                float h = RING_D / 2;
                r.set(x - h, y - h, x + h, y + h);
                c.drawBitmap(ring, null, r, ringPaint);
            }
            Date now = new Date();
            String pattern = DateFormat.is24HourFormat(getContext()) ? "HH:mm" : "h:mm";
            c.drawText(new SimpleDateFormat(pattern, Locale.getDefault()).format(now), x, y + 22, time);
            c.drawText(new SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now).toUpperCase(Locale.getDefault()),
                    x, y + 72, date);
            c.restore();
        }
    }
}

package io.github.starktvos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

import java.util.ArrayList;

/** One Control Center page: a list on the left, help for the focused row on the right. Drawn on one Canvas. */
final class PanelView extends View {

    interface Host {
        void back();
    }

    static final class Item {
        final int glyph;
        final String label;
        String value;
        int valueColor = Theme.DIM;
        final String help;
        final Runnable action;
        boolean busy;

        Item(int glyph, String label, String value, String help, Runnable action) {
            this.glyph = glyph;
            this.label = label;
            this.value = value;
            this.help = help;
            this.action = action;
        }

        Item color(int c) {
            valueColor = c;
            return this;
        }
    }

    static final class Page {
        final String title, subtitle;
        final ArrayList<Item> items = new ArrayList<>();

        Page(String title, String subtitle) {
            this.title = title;
            this.subtitle = Theme.upper(subtitle);
        }

        Item add(Item i) {
            items.add(i);
            return i;
        }
    }

    private static final float LIST_TOP = 262, ITEM_H = 86, ITEM_GAP = 14, LIST_W = 1040, HELP_X = 1236, HELP_W = 588;
    private static final float RING_CX = 1700, RING_CY = 900, RING_D = 1000;
    private static final String HINT = "OK  SELECT      BACK  RETURN";

    private final Host host;
    private final AccessibilityManager a11y;
    private Page page;
    private int sel;
    private float scroll, scrollTarget;
    private long lastFrame;
    private String message;
    private int messageColor = Theme.ACCENT;
    private Bitmap ring, radial;

    private StaticLayout helpLayout, messageLayout;
    private Item helpFor;
    private String messageFor;

    private final RectF r = new RectF();
    private final Matrix ringMatrix = new Matrix();
    private final LinearGradient ringShader = Theme.eclipseGradient();
    private final Paint ringPaint = Theme.stroke(Theme.TEXT, 2.5f);
    private final Paint surface = Theme.fill(Theme.SURFACE), surfaceHi = Theme.fill(Theme.SURFACE_HI);
    private final Paint border = Theme.stroke(Theme.BORDER, 1.5f), highlight = Theme.stroke(Theme.HIGHLIGHT, 1.5f);
    private final Paint art = new Paint(Paint.FILTER_BITMAP_FLAG), glow = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint wordmark = Art.wordmarkPaint(Theme.DIM);
    private final Paint glyph = Glyphs.paint(Theme.DIM), glyphOn = Glyphs.paint(Theme.TEXT);
    private final TextPaint title = Theme.text(Theme.TEXT, 60, Theme.LIGHT);
    private final TextPaint subtitle = Theme.caps(Theme.DIM, 17);
    private final TextPaint label = Theme.text(Theme.TEXT, 29, Theme.REGULAR);
    private final TextPaint value = Theme.text(Theme.DIM, 25, Theme.REGULAR);
    private final TextPaint help = Theme.text(0xFFA9B1BF, 27, Theme.REGULAR);
    private final TextPaint msg = Theme.text(Theme.ACCENT, 27, Theme.MEDIUM);
    private final TextPaint hint = Theme.caps(Theme.FAINT, 14);

    PanelView(Context c, Host host) {
        super(c);
        this.host = host;
        this.a11y = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        value.setTextAlign(Paint.Align.RIGHT);
        hint.setTextAlign(Paint.Align.RIGHT);
        ringPaint.setShader(ringShader);
        art.setAlpha(150);
    }

    void setArt(Bitmap ring, Bitmap radial) {
        this.ring = ring;
        this.radial = radial;
        invalidate();
    }

    /** Shows a page. A refresh of the same page keeps the message line. */
    void show(Page p, int selection, boolean keepMessage) {
        boolean same = page != null && page.title.equals(p.title);
        page = p;
        sel = Math.max(0, Math.min(selection, p.items.size() - 1));
        if (!(keepMessage && same)) message = null;
        helpFor = null;
        ensureVisible(!same);
        invalidate();
        announce();
    }

    Page page() {
        return page;
    }

    int selection() {
        return sel;
    }

    void message(String m, int color) {
        message = m;
        messageColor = color;
        invalidate();
        if (a11y != null && a11y.isEnabled()) announceForAccessibility(m);
    }

    private void announce() {
        if (a11y == null || !a11y.isEnabled() || page == null || page.items.isEmpty()) return;
        Item it = page.items.get(sel);
        announceForAccessibility(it.value == null ? it.label : it.label + ", " + it.value);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (page == null) return super.onKeyDown(code, e);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                if (sel > 0) { sel--; ensureVisible(true); announce(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (sel < page.items.size() - 1) { sel++; ensureVisible(true); announce(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
                if (e.getRepeatCount() == 0 && !page.items.isEmpty()) {
                    Item it = page.items.get(sel);
                    if (it.action != null && !it.busy) it.action.run();
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                return true;
            default:
                return super.onKeyDown(code, e);
        }
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if ((code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) && !e.isCanceled()) {
            host.back();
            return true;
        }
        return super.onKeyUp(code, e);
    }

    private void ensureVisible(boolean animate) {
        float top = LIST_TOP + sel * (ITEM_H + ITEM_GAP);
        if (top + ITEM_H - scrollTarget > Theme.H - 70) scrollTarget = top + ITEM_H - (Theme.H - 70);
        if (top - scrollTarget < LIST_TOP) scrollTarget = top - LIST_TOP;
        scrollTarget = Math.max(0, scrollTarget);
        if (!animate) scroll = scrollTarget;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 16 : Math.min(48, now - lastFrame);
        float d = scrollTarget - scroll;
        boolean moving = Math.abs(d) >= 0.5f;
        scroll = moving ? scroll + d * (1f - (float) Math.exp(-dt / 70f)) : scrollTarget;
        lastFrame = moving ? now : 0;

        float s = getWidth() / Theme.W;
        c.drawColor(Theme.BG);
        c.save();
        c.scale(s, s);
        drawArt(c);
        Art.wordmark(c, Theme.MARGIN, 78, 16, wordmark);
        if (page == null) {
            c.restore();
            return;
        }

        c.save();
        c.clipRect(0, LIST_TOP - 24, HELP_X - 40, Theme.H);
        for (int i = 0; i < page.items.size(); i++) {
            float y = LIST_TOP + i * (ITEM_H + ITEM_GAP) - scroll;
            if (y > Theme.H || y + ITEM_H < LIST_TOP - 24) continue;
            drawItem(c, page.items.get(i), y, i == sel);
        }
        c.restore();

        c.drawText(page.title, Theme.MARGIN, 170, title);
        if (page.subtitle != null) c.drawText(page.subtitle, Theme.MARGIN, 212, subtitle);
        drawHelp(c);
        c.drawText(HINT, Theme.W - Theme.MARGIN, 78, hint);
        c.restore();

        if (moving) postInvalidateOnAnimation();
    }

    private void drawArt(Canvas c) {
        if (radial != null) {
            glow.setColor(Theme.BLUE);
            glow.setAlpha(40);
            r.set(-500, -560, 1100, 520);
            c.drawBitmap(radial, null, r, glow);
        }
        if (ring != null) {
            float h = RING_D / 2;
            r.set(RING_CX - h, RING_CY - h, RING_CX + h, RING_CY + h);
            c.drawBitmap(ring, null, r, art);
        }
    }

    private void drawItem(Canvas c, Item it, float y, boolean on) {
        r.set(Theme.MARGIN, y, Theme.MARGIN + LIST_W, y + ITEM_H);
        c.drawRoundRect(r, 16, 16, on ? surfaceHi : surface);
        if (on) {
            ringMatrix.setScale(r.width(), 1);
            ringMatrix.postTranslate(r.left, 0);
            ringShader.setLocalMatrix(ringMatrix);
            c.drawRoundRect(r, 16, 16, ringPaint);
        } else {
            c.drawRoundRect(r, 16, 16, border);
            c.drawLine(r.left + 16, r.top + 1, r.right - 16, r.top + 1, highlight);
        }
        float x = Theme.MARGIN + 32;
        if (it.glyph >= 0) {
            Glyphs.draw(c, it.glyph, x + 18, y + ITEM_H / 2, 34, on ? glyphOn : glyph);
            x += 64;
        }
        label.setColor(on ? Theme.TEXT : it.action != null || it.value == null ? 0xFFD5DAE2 : 0xFFAAB2BE);
        float valueW = it.value == null ? 0 : Math.min(value.measureText(it.value), LIST_W * 0.5f);
        c.drawText(Theme.fit(it.label, label, Theme.MARGIN + LIST_W - 48 - valueW - x), x, y + ITEM_H / 2 + 10, label);
        if (it.value != null) {
            value.setColor(it.busy ? Theme.ACCENT : it.valueColor);
            c.drawText(Theme.fit(it.value, value, LIST_W * 0.5f), Theme.MARGIN + LIST_W - 32, y + ITEM_H / 2 + 9, value);
        }
    }

    private void drawHelp(Canvas c) {
        if (page.items.isEmpty()) return;
        Item it = page.items.get(sel);
        if (helpFor != it) {
            helpFor = it;
            helpLayout = it.help == null ? null : layout(it.help, help);
        }
        if (message != null && !message.equals(messageFor)) {
            messageFor = message;
            messageLayout = layout(message, msg);
        }
        float y = LIST_TOP + 6;
        if (helpLayout != null) {
            c.save();
            c.translate(HELP_X, y);
            helpLayout.draw(c);
            c.restore();
            y += helpLayout.getHeight() + 36;
        }
        if (message != null && messageLayout != null) {
            msg.setColor(messageColor);
            c.save();
            c.translate(HELP_X, y);
            messageLayout.draw(c);
            c.restore();
        }
    }

    private static StaticLayout layout(String text, TextPaint p) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), p, (int) HELP_W)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(8, 1f)
                .build();
    }
}

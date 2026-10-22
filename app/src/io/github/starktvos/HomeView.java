package io.github.starktvos;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * The home screen, drawn by hand on one Canvas in 1920x1080 design units. It only redraws while something moves
 * and allocates nothing per frame. All glow is pre-rendered (see {@link Art}); text keeps a fixed size while tiles
 * grow, because re-rasterizing glyphs every frame is what makes old TV GPUs stutter.
 */
final class HomeView extends View {

    interface Host {
        void onOpen(Tile t);
        List<String> menuFor(Tile t);
        void onMenu(Tile t, String option);
    }

    static final class Tile {
        static final int APP = 0, ACTION = 1, GET = 2;
        final int type;
        final String id;
        final String label;
        final int glyph;
        final AppRepo.App app;
        final Object ref;
        /** One line under the title when focused. */
        String sub;
        /** Light colour when focused; apps use their banner's colour. */
        int color = Theme.ICE;
        private String fitted;
        private float fittedWidth;

        Tile(int type, String id, String label, int glyph, AppRepo.App app, Object ref) {
            this.type = type;
            this.id = id;
            this.label = label;
            this.glyph = glyph;
            this.app = app;
            this.ref = ref;
        }

        String fit(TextPaint p, float width) {
            if (fitted == null || fittedWidth != width) {
                fitted = Theme.fit(label, p, width);
                fittedWidth = width;
            }
            return fitted;
        }

        int light() {
            return app != null && app.color != 0 ? app.color : color;
        }
    }

    static final class Row {
        final String title;
        final String caps;
        final int type;
        final ArrayList<Tile> tiles = new ArrayList<>();
        float top, titleH, w, h, gap, height, sx, sxTarget;

        Row(String title, int type) {
            this.title = title;
            this.caps = Theme.upper(title);
            this.type = type;
        }
    }

    static final float APP_W = 288, APP_H = 162, RADIUS = 16, FOCUS_GROW = 0.10f;
    static final float RING_D = 360;
    private static final float APP_GAP = 30, ACT_W = 200, ACT_H = 116, ACT_GAP = 22, GET_H = 104;
    private static final float ROWS_TOP = 384, TITLE_H = 50;
    private static final float RING_CX = 1690, RING_CY = 196;
    private static final float HERO_X = Theme.MARGIN, HERO_Y = 252, HERO_W = 1060;
    private static final String GET_SUB = "GET ON PLAY STORE";
    private static final String HINT = "HOLD OK FOR OPTIONS";

    private final Host host;
    private final AccessibilityManager a11y;
    private final ArrayList<Row> rows = new ArrayList<>();
    private int fr, fc;
    private float scrollY, scrollYTarget;
    private Tile focusTile, prevTile;
    private float focusT = 1, prevT;
    private long lastFrame;
    private boolean longPressed;

    private String heroTitle, heroSub, heroPrevTitle, heroPrevSub;
    private float heroT = 1;
    private int lightFrom = Theme.ICE, lightTo = Theme.ICE;
    private float lightT = 1;

    private Bitmap ring, radial, tileGlow;

    private String clock = "", date = "";
    private int netGlyph = Glyphs.OFFLINE, netColor = Theme.DIM;
    private String netText = "";
    private boolean secureDns;

    private String toast;
    private long toastUntil;

    private List<String> menu;
    private Tile menuTile;
    private int menuSel;

    private final RectF r = new RectF(), r2 = new RectF();
    private final Matrix ringMatrix = new Matrix();
    private final LinearGradient ringShader = Theme.eclipseGradient();
    private final Paint ringPaint = Theme.stroke(Theme.TEXT, 3);
    private final Paint surface = Theme.fill(Theme.SURFACE), surfaceHi = Theme.fill(Theme.SURFACE_HI);
    private final Paint border = Theme.stroke(Theme.BORDER, 1.5f), highlight = Theme.stroke(Theme.HIGHLIGHT, 1.5f);
    private final Paint bg = Theme.fill(Theme.BG), dim = Theme.fill(Theme.BG), scrim = Theme.fill(0xCC000000);
    private final Paint faintRing = Theme.stroke(Theme.FAINT, 2);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint headerFade = new Paint(), bottomFade = new Paint();
    private final Paint wordmark = Art.wordmarkPaint(Theme.TEXT);
    private final Paint glyphHot = Glyphs.paint(Theme.TEXT), glyphDim = Glyphs.paint(Theme.DIM);
    private final Paint glyphStatus = Glyphs.paint(Theme.TEXT);
    private final TextPaint tvos = Theme.text(Theme.DIM, 15, Theme.display);
    private final TextPaint heroPaint = Theme.text(Theme.TEXT, 80, Theme.LIGHT);
    private final TextPaint subPaint = Theme.text(Theme.DIM, 28, Theme.REGULAR);
    private final TextPaint clockPaint = Theme.text(Theme.TEXT, 42, Theme.display);
    private final TextPaint datePaint = Theme.caps(Theme.DIM, 14);
    private final TextPaint rowTitle = Theme.caps(Theme.DIM, 17);
    private final TextPaint actLabel = Theme.text(Theme.DIM, 22, Theme.REGULAR);
    private final TextPaint placeholder = Theme.text(Theme.TEXT, 28, Theme.MEDIUM);
    private final TextPaint getName = Theme.text(Theme.TEXT, 27, Theme.MEDIUM);
    private final TextPaint getSub = Theme.caps(Theme.DIM, 13);
    private final TextPaint status = Theme.text(Theme.TEXT, 24, Theme.REGULAR);
    private final TextPaint statusDim = Theme.text(Theme.DIM, 22, Theme.REGULAR);
    private final TextPaint hint = Theme.caps(Theme.FAINT, 14);
    private final TextPaint toastPaint = Theme.text(Theme.TEXT, 25, Theme.REGULAR);
    private final TextPaint menuTitle = Theme.text(Theme.TEXT, 34, Theme.LIGHT);
    private final TextPaint menuItem = Theme.text(Theme.TEXT, 27, Theme.REGULAR);
    private final TextPaint emptyPaint = Theme.text(Theme.DIM, 28, Theme.REGULAR);

    HomeView(Context c, Host host) {
        super(c);
        this.host = host;
        this.a11y = (AccessibilityManager) c.getSystemService(Context.ACCESSIBILITY_SERVICE);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setDefaultFocusHighlightEnabled(false);
        tvos.setLetterSpacing(0.5f);
        actLabel.setTextAlign(Paint.Align.CENTER);
        placeholder.setTextAlign(Paint.Align.CENTER);
        getName.setTextAlign(Paint.Align.CENTER);
        getSub.setTextAlign(Paint.Align.CENTER);
        clockPaint.setTextAlign(Paint.Align.CENTER);
        datePaint.setTextAlign(Paint.Align.CENTER);
        status.setTextAlign(Paint.Align.RIGHT);
        statusDim.setTextAlign(Paint.Align.RIGHT);
        hint.setTextAlign(Paint.Align.CENTER);
        toastPaint.setTextAlign(Paint.Align.CENTER);
        emptyPaint.setTextAlign(Paint.Align.CENTER);
        ringPaint.setShader(ringShader);
        headerFade.setShader(new LinearGradient(0, ROWS_TOP - 44, 0, ROWS_TOP + 4,
                Theme.BG, Theme.BG & 0x00FFFFFF, Shader.TileMode.CLAMP));
        bottomFade.setShader(new LinearGradient(0, Theme.H - 120, 0, Theme.H,
                Theme.BG & 0x00FFFFFF, Theme.BG, Shader.TileMode.CLAMP));
    }

    // ---------- state from the activity ----------

    void setArt(Bitmap ring, Bitmap radial, Bitmap tileGlow) {
        this.ring = ring;
        this.radial = radial;
        this.tileGlow = tileGlow;
        invalidate();
    }

    void setRows(List<Row> newRows) {
        Tile was = focused();
        String keep = was == null ? null : was.id;
        rows.clear();
        rows.addAll(newRows);
        float y = ROWS_TOP;
        for (Row row : rows) {
            row.titleH = row.title != null ? TITLE_H : 0;
            if (row.type == Tile.ACTION) { row.w = ACT_W; row.h = ACT_H; row.gap = ACT_GAP; }
            else if (row.type == Tile.GET) { row.w = APP_W; row.h = GET_H; row.gap = APP_GAP; }
            else { row.w = APP_W; row.h = APP_H; row.gap = APP_GAP; }
            row.height = row.titleH + row.h + 40;
            row.top = y;
            y += row.height;
        }
        fr = 0;
        fc = 0;
        if (keep != null) {
            outer:
            for (int i = 0; i < rows.size(); i++) {
                ArrayList<Tile> ts = rows.get(i).tiles;
                for (int j = 0; j < ts.size(); j++) {
                    if (ts.get(j).id.equals(keep)) { fr = i; fc = j; break outer; }
                }
            }
        }
        focusTile = focused();
        prevTile = null;
        focusT = 1;
        setHero(focusTile, false);
        if (focusTile != null) lightFrom = lightTo = focusTile.light();
        lightT = 1;
        ensureVisible(false);
        invalidate();
    }

    void setClock(String clock, String date) {
        this.clock = clock;
        this.date = Theme.upper(date);
        invalidate();
    }

    void setNetwork(int glyph, String text, int color, boolean secureDns) {
        this.netGlyph = glyph;
        this.netText = text;
        this.netColor = color;
        this.secureDns = secureDns;
        invalidate();
    }

    /** Tile images arrived: the focused app's light colour may be known now. */
    void tilesUpdated() {
        if (focusTile != null && lightT >= 1 && focusTile.light() != lightTo) {
            lightFrom = lightTo;
            lightTo = focusTile.light();
            lightT = 0;
        }
        kick();
    }

    void toast(String message) {
        toast = message;
        toastUntil = SystemClock.uptimeMillis() + 4000;
        invalidate();
        postInvalidateDelayed(4050);
        if (a11y != null && a11y.isEnabled()) announceForAccessibility(message);
    }

    void goTop() {
        if (menu != null) { menu = null; invalidate(); return; }
        if (!rows.isEmpty()) setFocus(0, 0);
        for (Row row : rows) row.sxTarget = 0;
        scrollYTarget = 0;
        kick();
    }

    Tile focused() {
        if (fr < 0 || fr >= rows.size()) return null;
        ArrayList<Tile> ts = rows.get(fr).tiles;
        return fc >= 0 && fc < ts.size() ? ts.get(fc) : null;
    }

    boolean menuOpen() {
        return menu != null;
    }

    /** Plain-text state for `adb shell dumpsys activity`, used by the validation suite. */
    void dump(String prefix, PrintWriter w) {
        int apps = 0, loaded = 0;
        StringBuilder pkgs = new StringBuilder();
        for (Row row : rows) {
            for (Tile t : row.tiles) {
                if (t.type != Tile.APP) continue;
                apps++;
                if (t.app != null && t.app.tile != null) loaded++;
                if (t.app != null && t.id.startsWith("all:")) pkgs.append(pkgs.length() == 0 ? "" : ",").append(t.app.pkg);
            }
        }
        Tile f = focused();
        w.println(prefix + "stark.rows=" + rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            w.println(prefix + "stark.row." + i + "=" + (row.title == null ? "-" : row.title) + " tiles=" + row.tiles.size());
        }
        w.println(prefix + "stark.tiles=" + loaded + "/" + apps);
        w.println(prefix + "stark.apps=" + pkgs);
        w.println(prefix + "stark.focus=" + (f == null ? "none" : f.id));
        w.println(prefix + "stark.menu=" + (menu != null));
        w.println(prefix + "stark.art=" + (ring != null));
    }

    // ---------- navigation ----------

    private void move(int dr, int dc) {
        if (rows.isEmpty()) return;
        if (dc != 0) {
            int nc = fc + dc;
            if (nc < 0 || nc >= rows.get(fr).tiles.size()) return;
            setFocus(fr, nc);
            return;
        }
        int nr = fr + dr;
        if (nr < 0 || nr >= rows.size()) return;
        Row from = rows.get(fr), to = rows.get(nr);
        float cx = Theme.MARGIN + fc * (from.w + from.gap) + from.w / 2 - from.sxTarget;
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < to.tiles.size(); i++) {
            float tcx = Theme.MARGIN + i * (to.w + to.gap) + to.w / 2 - to.sxTarget;
            float d = Math.abs(tcx - cx);
            if (d < bestD) { bestD = d; best = i; }
        }
        setFocus(nr, best);
    }

    private void setFocus(int row, int col) {
        Tile old = focused();
        fr = row;
        fc = col;
        Tile now = focused();
        if (old != now) {
            prevTile = old;
            prevT = focusT;
            focusTile = now;
            focusT = 0;
            setHero(now, true);
            if (now != null) {
                lightFrom = currentLight();
                lightTo = now.light();
                lightT = 0;
                if (a11y != null && a11y.isEnabled()) {
                    announceForAccessibility(now.sub == null ? now.label : now.label + ", " + now.sub);
                }
            }
        }
        ensureVisible(true);
        kick();
    }

    private void setHero(Tile t, boolean animate) {
        heroPrevTitle = animate ? heroTitle : null;
        heroPrevSub = animate ? heroSub : null;
        heroTitle = t == null ? null : Theme.fit(t.label, heroPaint, HERO_W);
        heroSub = t == null || t.sub == null ? null : Theme.fit(t.sub, subPaint, HERO_W);
        heroT = animate ? 0 : 1;
    }

    private int currentLight() {
        return Theme.lerpColor(lightFrom, lightTo, Theme.ease(lightT));
    }

    private void ensureVisible(boolean animate) {
        if (rows.isEmpty()) { scrollY = scrollYTarget = 0; return; }
        Row row = rows.get(fr);
        float x = Theme.MARGIN + fc * (row.w + row.gap);
        if (x - row.sxTarget < Theme.MARGIN) row.sxTarget = x - Theme.MARGIN;
        else if (x + row.w - row.sxTarget > Theme.W - Theme.MARGIN) row.sxTarget = x + row.w - (Theme.W - Theme.MARGIN);
        row.sxTarget = Math.max(0, row.sxTarget);

        float top = row.top, bottom = row.top + row.height;
        if (top - scrollYTarget < ROWS_TOP) scrollYTarget = top - ROWS_TOP;
        if (bottom - scrollYTarget > Theme.H - 30) scrollYTarget = bottom - (Theme.H - 30);
        scrollYTarget = Math.max(0, scrollYTarget);
        if (!animate) {
            row.sx = row.sxTarget;
            scrollY = scrollYTarget;
        }
    }

    private void kick() {
        postInvalidateOnAnimation();
    }

    // ---------- keys ----------

    private static boolean isSelect(int code) {
        return code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (menu != null && menuKey(code, e)) return true;
        if (menu != null) return super.onKeyDown(code, e);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT: move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(0, 1); return true;
            case KeyEvent.KEYCODE_MENU:
                if (e.getRepeatCount() == 0) openMenu();
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                if (e.getRepeatCount() == 0) goTop();
                return true;
            default:
                if (isSelect(code)) {
                    if (e.getRepeatCount() == 0) {
                        e.startTracking();
                        longPressed = false;
                    }
                    return true;
                }
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public boolean onKeyLongPress(int code, KeyEvent e) {
        if (isSelect(code) && menu == null) {
            longPressed = true;
            openMenu();
            return true;
        }
        return super.onKeyLongPress(code, e);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (isSelect(code)) {
            if (menu == null && !longPressed && e.isTracking() && !e.isCanceled()) {
                Tile t = focused();
                if (t != null) host.onOpen(t);
            }
            longPressed = false;
            return true;
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) return true;
        return super.onKeyUp(code, e);
    }

    private void openMenu() {
        Tile t = focused();
        if (t == null) return;
        List<String> options = host.menuFor(t);
        if (options == null || options.isEmpty()) return;
        menu = options;
        menuTile = t;
        menuSel = 0;
        invalidate();
    }

    /** Keys while the long-press menu is open. Anything else (volume, mute, power) falls through. */
    private boolean menuKey(int code, KeyEvent e) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP: menuSel = Math.max(0, menuSel - 1); invalidate(); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: menuSel = Math.min(menu.size() - 1, menuSel + 1); invalidate(); return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_MENU:
                menu = null;
                invalidate();
                return true;
            default:
                if (isSelect(code)) {
                    if (e.getRepeatCount() == 0) {
                        String option = menu.get(menuSel);
                        Tile t = menuTile;
                        menu = null;
                        invalidate();
                        host.onMenu(t, option);
                    }
                    return true;
                }
                return false;
        }
    }

    // ---------- drawing ----------

    @Override
    protected void onDraw(Canvas c) {
        long now = SystemClock.uptimeMillis();
        float dt = lastFrame == 0 ? 16 : Math.min(48, now - lastFrame);
        boolean moving = step(dt);
        lastFrame = moving ? now : 0;

        float s = getWidth() / Theme.W;
        c.drawColor(Theme.BG);
        c.save();
        c.scale(s, s);
        drawRows(c);
        if (scrollY > 1) {
            c.drawRect(0, 0, Theme.W, ROWS_TOP - 44, bg);
            c.drawRect(0, ROWS_TOP - 44, Theme.W, ROWS_TOP + 4, headerFade);
        }
        drawLight(c);
        drawHeader(c);
        c.drawRect(0, Theme.H - 120, Theme.W, Theme.H, bottomFade);
        if (toast != null) {
            if (now < toastUntil) drawToast(c); else toast = null;
        }
        if (toast == null && menu == null && !rows.isEmpty()) c.drawText(HINT, Theme.W / 2, Theme.H - 34, hint);
        if (menu != null) drawMenu(c);
        c.restore();

        if (moving) postInvalidateOnAnimation();
    }

    private boolean step(float dt) {
        boolean moving = false;
        float k = 1f - (float) Math.exp(-dt / 70f);
        float d = scrollYTarget - scrollY;
        if (Math.abs(d) < 0.5f) scrollY = scrollYTarget; else { scrollY += d * k; moving = true; }
        for (Row row : rows) {
            float dx = row.sxTarget - row.sx;
            if (Math.abs(dx) < 0.5f) row.sx = row.sxTarget; else { row.sx += dx * k; moving = true; }
        }
        if (focusT < 1) { focusT = Math.min(1, focusT + dt / 150f); moving = true; }
        if (prevT > 0) { prevT = Math.max(0, prevT - dt / 150f); moving = true; }
        if (heroT < 1) { heroT = Math.min(1, heroT + dt / 220f); moving = true; }
        if (lightT < 1) { lightT = Math.min(1, lightT + dt / 450f); moving = true; }
        return moving;
    }

    /** The coloured light behind the header follows the focused app, like the glow of its poster. */
    private void drawLight(Canvas c) {
        if (radial == null) return;
        glowPaint.setColor(currentLight());
        glowPaint.setAlpha(62);
        r.set(-260, -420, 1360, 640);
        c.drawBitmap(radial, null, r, glowPaint);
        glowPaint.setColor(Theme.BLUE);
        glowPaint.setAlpha(46);
        r.set(RING_CX - 380, RING_CY - 380, RING_CX + 380, RING_CY + 380);
        c.drawBitmap(radial, null, r, glowPaint);
    }

    private void drawRows(Canvas c) {
        if (rows.isEmpty()) {
            c.drawText("Loading your apps…", Theme.W / 2, 640, emptyPaint);
            return;
        }
        for (int ri = 0; ri < rows.size(); ri++) {
            Row row = rows.get(ri);
            float y = row.top - scrollY;
            if (y > Theme.H || y + row.height < ROWS_TOP - 60) continue;
            if (row.caps != null) c.drawText(row.caps, Theme.MARGIN, y + 26, rowTitle);
            float ty = y + row.titleH;
            for (int i = 0; i < row.tiles.size(); i++) {
                float x = Theme.MARGIN + i * (row.w + row.gap) - row.sx;
                if (x > Theme.W || x + row.w < 0) continue;
                Tile t = row.tiles.get(i);
                float f = t == focusTile ? Theme.ease(focusT) : t == prevTile ? Theme.ease(prevT) : 0;
                drawTile(c, t, row, x, ty, f);
            }
        }
    }

    private void drawTile(Canvas c, Tile t, Row row, float x, float y, float f) {
        float grow = 1 + FOCUS_GROW * f;
        float w = row.w * grow, h = row.h * grow;
        float cx = x + row.w / 2, cy = y + row.h / 2;
        float rad = RADIUS * grow;
        r.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);

        if (f > 0 && tileGlow != null) {
            Art.glowRect(r, r2);
            glowPaint.setColor(t.light());
            glowPaint.setAlpha((int) (130 * f));
            c.drawBitmap(tileGlow, null, r2, glowPaint);
        }

        if (t.type == Tile.APP) {
            Bitmap b = t.app == null ? null : t.app.tile;
            if (b != null) {
                c.drawBitmap(b, null, r, bitmapPaint);
            } else {
                c.drawRoundRect(r, rad, rad, surfaceHi);
                c.drawText(t.fit(placeholder, row.w - 28), cx, cy + 10, placeholder);
            }
            if (f < 1) {
                dim.setAlpha((int) (92 * (1 - f)));
                c.drawRoundRect(r, rad, rad, dim);
            }
        } else {
            boolean hot = f > 0.5f;
            c.drawRoundRect(r, rad, rad, hot ? surfaceHi : surface);
            c.drawRoundRect(r, rad, rad, border);
            c.drawLine(r.left + rad, r.top + 1, r.right - rad, r.top + 1, highlight);
            if (t.type == Tile.ACTION) {
                Glyphs.draw(c, t.glyph, cx, cy - 14, 40, hot ? glyphHot : glyphDim);
                actLabel.setColor(hot ? Theme.TEXT : Theme.DIM);
                c.drawText(t.fit(actLabel, row.w - 20), cx, cy + 38, actLabel);
            } else {
                c.drawText(t.fit(getName, row.w - 28), cx, cy + 2, getName);
                c.drawText(GET_SUB, cx, cy + 32, getSub);
            }
        }

        if (f > 0) {
            r2.set(r);
            r2.inset(-6, -6);
            ringMatrix.setScale(r2.width(), 1);
            ringMatrix.postTranslate(r2.left, 0);
            ringShader.setLocalMatrix(ringMatrix);
            ringPaint.setAlpha((int) (255 * f));
            c.drawRoundRect(r2, rad + 5, rad + 5, ringPaint);
        }
    }

    private void drawHeader(Canvas c) {
        Art.wordmark(c, Theme.MARGIN, 96, 24, wordmark);
        c.drawText("TV OS", Theme.MARGIN + Art.wordmarkWidth(24) + 24, 96, tvos);

        drawHero(c);

        if (ring != null) {
            float half = RING_D / 2;
            r.set(RING_CX - half, RING_CY - half, RING_CX + half, RING_CY + half);
            c.drawBitmap(ring, null, r, bitmapPaint);
        } else {
            c.drawCircle(RING_CX, RING_CY, RING_D * 0.34f, faintRing);
        }
        c.drawText(clock, RING_CX, RING_CY + 14, clockPaint);
        c.drawText(date, RING_CX, RING_CY + 52, datePaint);

        float right = RING_CX - RING_D / 2 - 8;
        status.setColor(netColor);
        c.drawText(netText, right, 180, status);
        float tw = status.measureText(netText);
        glyphStatus.setColor(netColor);
        Glyphs.draw(c, netGlyph, right - tw - 24, 171, 28, glyphStatus);
        if (secureDns) {
            c.drawText("Private DNS", right, 220, statusDim);
            float dw = statusDim.measureText("Private DNS");
            glyphStatus.setColor(Theme.GOOD);
            Glyphs.draw(c, Glyphs.SHIELD, right - dw - 22, 212, 24, glyphStatus);
        }
    }

    private void drawHero(Canvas c) {
        float e = Theme.ease(heroT);
        if (heroPrevTitle != null && e < 1) {
            int a = (int) (255 * (1 - e));
            heroPaint.setAlpha(a);
            c.drawText(heroPrevTitle, HERO_X, HERO_Y - 14 * e, heroPaint);
            if (heroPrevSub != null) {
                subPaint.setAlpha(a);
                c.drawText(heroPrevSub, HERO_X, HERO_Y + 52 - 14 * e, subPaint);
            }
        }
        if (heroTitle != null) {
            int a = (int) (255 * e);
            heroPaint.setAlpha(a);
            c.drawText(heroTitle, HERO_X, HERO_Y + 14 * (1 - e), heroPaint);
            if (heroSub != null) {
                subPaint.setAlpha(a);
                c.drawText(heroSub, HERO_X, HERO_Y + 52 + 14 * (1 - e), subPaint);
            }
        }
        heroPaint.setAlpha(255);
        subPaint.setAlpha(255);
    }

    private void drawToast(Canvas c) {
        float w = toastPaint.measureText(toast) + 80;
        r.set((Theme.W - w) / 2, 976, (Theme.W + w) / 2, 1040);
        c.drawRoundRect(r, 32, 32, surfaceHi);
        c.drawRoundRect(r, 32, 32, border);
        c.drawText(toast, Theme.W / 2, 1017, toastPaint);
    }

    private void drawMenu(Canvas c) {
        c.drawRect(0, 0, Theme.W, Theme.H, scrim);
        float w = 660, itemH = 76, head = 116;
        float h = head + menu.size() * itemH + 28;
        float x = (Theme.W - w) / 2, y = (Theme.H - h) / 2;
        r.set(x, y, x + w, y + h);
        c.drawRoundRect(r, 26, 26, surface);
        c.drawRoundRect(r, 26, 26, border);
        c.drawLine(x + 26, y + 1, x + w - 26, y + 1, highlight);
        c.drawText(Theme.fit(menuTile.label, menuTitle, w - 80), x + 40, y + 72, menuTitle);
        for (int i = 0; i < menu.size(); i++) {
            float iy = y + head + i * itemH;
            if (i == menuSel) {
                r.set(x + 18, iy, x + w - 18, iy + itemH - 10);
                c.drawRoundRect(r, 16, 16, surfaceHi);
                ringMatrix.setScale(r.width(), 1);
                ringMatrix.postTranslate(r.left, 0);
                ringShader.setLocalMatrix(ringMatrix);
                ringPaint.setAlpha(255);
                c.drawRoundRect(r, 16, 16, ringPaint);
            }
            menuItem.setColor(i == menuSel ? Theme.TEXT : Theme.DIM);
            c.drawText(menu.get(i), x + 46, iy + 44, menuItem);
        }
    }
}

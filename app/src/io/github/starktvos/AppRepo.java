package io.github.starktvos;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Installed apps and their tile images. Everything here runs on a background thread. */
final class AppRepo {
    static final class App {
        final String pkg;
        final ComponentName component;
        final String label;
        final boolean system;
        final boolean leanback;
        volatile Bitmap tile;
        /** The banner's most vivid colour, for the light around the focused tile. 0 until rendered. */
        volatile int color;

        App(String pkg, ComponentName component, String label, boolean system, boolean leanback) {
            this.pkg = pkg;
            this.component = component;
            this.label = label;
            this.system = system;
            this.leanback = leanback;
        }
    }

    /** Kept off the home screen: reachable from Quick actions instead. */
    private static final String[] HIDDEN = {"com.android.tv.settings"};

    private static final Map<String, Bitmap> TILES = new ConcurrentHashMap<>();
    private static final Map<String, Integer> COLORS = new ConcurrentHashMap<>();
    /** Bumped whenever tiles are dropped, so a render pass that's still running stops instead of refilling. */
    private static final AtomicInteger GENERATION = new AtomicInteger();

    private AppRepo() {}

    static List<App> load(Context c) {
        PackageManager pm = c.getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();
        collect(c, pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, byPkg);
        collect(c, pm, Intent.CATEGORY_LAUNCHER, false, byPkg);
        List<App> apps = new ArrayList<>(byPkg.values());
        final Collator collator = Collator.getInstance();
        Collections.sort(apps, (a, b) -> collator.compare(a.label, b.label));
        for (App a : apps) {
            a.tile = TILES.get(a.pkg);
            Integer color = COLORS.get(a.pkg);
            if (color != null) a.color = color;
        }
        return apps;
    }

    private static void collect(Context c, PackageManager pm, String category, boolean leanback, Map<String, App> out) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
        for (ResolveInfo ri : list) {
            ActivityInfo ai = ri.activityInfo;
            String pkg = ai.packageName;
            if (pkg.equals(c.getPackageName()) || out.containsKey(pkg) || isHidden(pkg)) continue;
            CharSequence l = ri.loadLabel(pm);
            String label = l == null ? pkg : l.toString().trim();
            boolean system = (ai.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            out.put(pkg, new App(pkg, new ComponentName(pkg, ai.name), label, system, leanback));
        }
    }

    private static boolean isHidden(String pkg) {
        for (String h : HIDDEN) if (h.equals(pkg)) return true;
        return false;
    }

    static Intent launchIntent(App a) {
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(a.leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                .setComponent(a.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    static void forget(String pkg) {
        TILES.remove(pkg);
        COLORS.remove(pkg);
    }

    /** Frees every tile image; the home screen rebuilds them when it's shown again. */
    static void dropTiles(List<App> apps) {
        GENERATION.incrementAndGet();
        TILES.clear();
        if (apps != null) for (App a : apps) a.tile = null;
    }

    static int generation() {
        return GENERATION.get();
    }

    /**
     * Renders tiles for apps that have none yet, in the given order, at exactly the pixel size they're drawn at
     * when focused. Calls back after every few tiles so they appear progressively, and gives up if tiles are
     * dropped meanwhile.
     */
    static void renderTiles(Context c, List<App> ordered, int w, int h, float radius, int generation, Runnable progress) {
        PackageManager pm = c.getPackageManager();
        int done = 0;
        for (App a : ordered) {
            if (GENERATION.get() != generation) return;
            if (a.tile != null) continue;
            Bitmap b = TILES.get(a.pkg);
            if (b == null || b.getWidth() != w) {
                b = render(c, pm, a, w, h, radius);
                if (b == null) continue;
                if (GENERATION.get() != generation) return;
                TILES.put(a.pkg, b);
            }
            Integer color = COLORS.get(a.pkg);
            if (color != null) a.color = color;
            a.tile = b;
            if (++done % 4 == 0) progress.run();
        }
        progress.run();
    }

    private static Bitmap render(Context c, PackageManager pm, App a, int w, int h, float radius) {
        Drawable banner = null, icon = null;
        try {
            ActivityInfo ai = pm.getActivityInfo(a.component, 0);
            banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            if (banner == null) icon = ai.loadIcon(pm);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return null;
        }

        Bitmap content = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(content);
        cv.drawColor(Theme.SURFACE_HI);
        if (banner != null) {
            banner.setBounds(0, 0, w, h);
            banner.draw(cv);
        } else if (icon != null) {
            int s = Math.round(h * 0.44f);
            int left = (w - s) / 2, top = Math.round(h * 0.14f);
            icon.setBounds(left, top, left + s, top + s);
            icon.draw(cv);
            TextPaint tp = Theme.text(Theme.TEXT, h * 0.12f, Theme.MEDIUM);
            tp.setTextAlign(Paint.Align.CENTER);
            cv.drawText(Theme.fit(a.label, tp, w * 0.86f), w / 2f, h * 0.86f, tp);
        }
        int color = dominant(content);
        COLORS.put(a.pkg, color);
        a.color = color;

        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas oc = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        p.setShader(new BitmapShader(content, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        oc.drawRoundRect(new RectF(0, 0, w, h), radius, radius, p);
        content.recycle();
        out.prepareToDraw();
        return out;
    }

    /** The most vivid colour of an image, brightened so it works as light on black. Grey images give ice blue. */
    static int dominant(Bitmap b) {
        Bitmap small = Bitmap.createScaledBitmap(b, 12, 7, true);
        float[] hsv = new float[3];
        double sx = 0, sy = 0, weight = 0, sat = 0;
        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                Color.colorToHSV(small.getPixel(x, y), hsv);
                double wgt = hsv[1] * hsv[2] * hsv[2];
                double rad = Math.toRadians(hsv[0]);
                sx += Math.cos(rad) * wgt;
                sy += Math.sin(rad) * wgt;
                sat += hsv[1] * wgt;
                weight += wgt;
            }
        }
        if (small != b) small.recycle();
        if (weight < 1.2) return Theme.ICE;
        float hue = (float) Math.toDegrees(Math.atan2(sy, sx));
        if (hue < 0) hue += 360;
        float s = (float) Math.max(0.55, Math.min(0.85, sat / weight));
        return Color.HSVToColor(new float[]{hue, s, 1f});
    }
}

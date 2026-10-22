package io.github.starktvos;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/** Choices made on the TV, plus what `./stark` reported over ADB. */
final class Prefs {
    static final int SPLASH_OFF = 0, SPLASH_BOOT = 1, SPLASH_BOOT_AND_WAKE = 2;

    private static final long CRASH_WINDOW_MS = 10 * 60_000L;
    private static final int CRASHES_FOR_SAFE_MODE = 3;

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("stark", Context.MODE_PRIVATE);
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(",")) if (!part.trim().isEmpty()) out.add(part.trim());
        return out;
    }

    /** Favourite packages in order, or null while the person hasn't edited them (then defaults apply). */
    static List<String> favourites(Context c) {
        String s = sp(c).getString("favourites", null);
        return s == null ? null : split(s);
    }

    static void setFavourites(Context c, List<String> pkgs) {
        sp(c).edit().putString("favourites", TextUtils.join(",", pkgs)).apply();
    }

    static void resetFavourites(Context c) {
        sp(c).edit().remove("favourites").apply();
    }

    static boolean suggestions(Context c) {
        return sp(c).getBoolean("suggestions", true);
    }

    static void setSuggestions(Context c, boolean on) {
        sp(c).edit().putBoolean("suggestions", on).apply();
    }

    static List<String> disabledByStark(Context c) {
        return split(sp(c).getString("disabled", ""));
    }

    static void setDisabledByStark(Context c, String csv) {
        sp(c).edit().putString("disabled", csv == null ? "" : csv).apply();
    }

    static String originalScreensaver(Context c) {
        return sp(c).getString("orig_screensaver", null);
    }

    static void setOriginalScreensaver(Context c, String component) {
        if (component == null || component.isEmpty() || "null".equals(component)) return;
        if (component.startsWith(c.getPackageName())) return;
        sp(c).edit().putString("orig_screensaver", component).apply();
    }

    // ---------- startup animation ----------

    static int splashMode(Context c) {
        return sp(c).getInt("splash_mode", SPLASH_BOOT_AND_WAKE);
    }

    static void setSplashMode(Context c, int mode) {
        sp(c).edit().putInt("splash_mode", mode).apply();
    }

    static int lastBoot(Context c) {
        return sp(c).getInt("last_boot", Integer.MIN_VALUE);
    }

    static long lastSleep(Context c) {
        return sp(c).getLong("last_sleep", -1);
    }

    static void setPowerMarks(Context c, int boot, long sleep) {
        sp(c).edit().putInt("last_boot", boot).putLong("last_sleep", sleep).apply();
    }

    // ---------- crash loop protection ----------

    /** Synchronous: the process is about to die. */
    static void recordCrash(Context c) {
        long now = System.currentTimeMillis();
        StringBuilder kept = new StringBuilder();
        for (String t : split(sp(c).getString("crashes", ""))) {
            try {
                if (now - Long.parseLong(t) < CRASH_WINDOW_MS) kept.append(t).append(',');
            } catch (NumberFormatException ignored) {
            }
        }
        kept.append(now);
        sp(c).edit().putString("crashes", kept.toString()).commit();
    }

    /** Three crashes within ten minutes: start without the fancy parts until things calm down. */
    static boolean safeMode(Context c) {
        long now = System.currentTimeMillis();
        int recent = 0;
        for (String t : split(sp(c).getString("crashes", ""))) {
            try {
                if (now - Long.parseLong(t) < CRASH_WINDOW_MS) recent++;
            } catch (NumberFormatException ignored) {
            }
        }
        return recent >= CRASHES_FOR_SAFE_MODE;
    }

    static void clearCrashes(Context c) {
        sp(c).edit().remove("crashes").apply();
    }
}

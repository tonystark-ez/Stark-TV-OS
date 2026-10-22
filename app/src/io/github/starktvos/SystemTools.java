package io.github.starktvos;

import android.Manifest;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Environment;
import android.os.Process;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.provider.Settings;
import android.view.Display;
import android.view.WindowManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Memory, storage, caches and system settings. Blocking calls: run them on {@link Bg}. */
final class SystemTools {
    static final String TAG = "StarkTV";

    /** Background services that keep casting, the remote, voice and the keyboard working. */
    private static final Set<String> NEVER_CLOSE = new HashSet<>(java.util.Arrays.asList(
            "com.google.android.apps.mediashell", "com.google.android.gms", "com.google.android.gsf",
            "com.google.android.tv.remote.service", "com.google.android.katniss", "com.google.android.inputmethod.latin",
            "com.android.vending", "com.tcl.miracast", "com.tcl.MultiScreenInteraction_TV", "com.ionitech.airscreen"));

    private SystemTools() {}

    // ---------- permissions granted by ./stark ----------

    static boolean canWriteSecure(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasUsageAccess(Context c) {
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) return false;
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    // ---------- memory ----------

    static ActivityManager.MemoryInfo memory(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi;
    }

    /**
     * Closes apps that are sitting in the background (the streaming apps you watched earlier keep 100+ MB each).
     * Android only lets this close processes that aren't on screen, so nothing visible is interrupted.
     * Returns how much memory came back.
     */
    static long freeMemory(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        long before = memory(c).availMem;
        Set<String> media = new HashSet<>();
        for (Catalog.Entry e : Catalog.ALL) Collections.addAll(media, e.pkgs);
        PackageManager pm = c.getPackageManager();
        String self = c.getPackageName();
        String keyboard = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            String pkg = ai.packageName;
            if (pkg.equals(self) || NEVER_CLOSE.contains(pkg)) continue;
            if (keyboard != null && keyboard.startsWith(pkg + "/")) continue;
            if ((ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0) continue;
            boolean user = (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
            if (user || media.contains(pkg)) am.killBackgroundProcesses(pkg);
        }
        SystemClock.sleep(1200);
        return Math.max(0, memory(c).availMem - before);
    }

    // ---------- storage ----------

    static long[] storage() {
        StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
        return new long[]{fs.getAvailableBytes(), fs.getTotalBytes()};
    }

    /**
     * Asks Android for all the space that cached files take. The system makes room by deleting other apps' caches
     * (temporary files they rebuild on their own); app data, logins and downloads are not touched.
     */
    static long clearCaches(Context c) throws IOException {
        StorageManager sm = (StorageManager) c.getSystemService(Context.STORAGE_SERVICE);
        File dir = c.getFilesDir();
        UUID uuid = sm.getUuidForPath(dir);
        long before = dir.getUsableSpace();
        long allocatable = sm.getAllocatableBytes(uuid);
        if (allocatable > 0) sm.allocateBytes(uuid, allocatable);
        return Math.max(0, dir.getUsableSpace() - before);
    }

    static final class AppCache {
        final String pkg, label;
        final long cache;

        AppCache(String pkg, String label, long cache) {
            this.pkg = pkg;
            this.label = label;
            this.cache = cache;
        }
    }

    /** Cache size per app, biggest first. Needs usage access (granted by ./stark install). */
    static List<AppCache> caches(Context c) {
        List<AppCache> out = new ArrayList<>();
        if (!hasUsageAccess(c)) return out;
        StorageStatsManager ssm = (StorageStatsManager) c.getSystemService(Context.STORAGE_STATS_SERVICE);
        PackageManager pm = c.getPackageManager();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            try {
                StorageStats st = ssm.queryStatsForPackage(ai.storageUuid, ai.packageName, Process.myUserHandle());
                if (st.getCacheBytes() >= 1 << 20) {
                    out.add(new AppCache(ai.packageName, String.valueOf(ai.loadLabel(pm)), st.getCacheBytes()));
                }
            } catch (PackageManager.NameNotFoundException | IOException | SecurityException e) {
                // skip
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.cache, a.cache));
        return out;
    }

    // ---------- secure settings ----------

    static float animationScale(Context c) {
        return Settings.Global.getFloat(c.getContentResolver(), Settings.Global.WINDOW_ANIMATION_SCALE, 1f);
    }

    static boolean setAnimationScale(Context c, float scale) {
        try {
            ContentResolver r = c.getContentResolver();
            Settings.Global.putFloat(r, Settings.Global.WINDOW_ANIMATION_SCALE, scale);
            Settings.Global.putFloat(r, Settings.Global.TRANSITION_ANIMATION_SCALE, scale);
            Settings.Global.putFloat(r, Settings.Global.ANIMATOR_DURATION_SCALE, scale);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    static String dreamComponent(Context c) {
        return c.getPackageName() + "/" + ClockDream.class.getName();
    }

    static String screensaver(Context c) {
        try {
            return Settings.Secure.getString(c.getContentResolver(), "screensaver_components");
        } catch (SecurityException e) {
            return null;
        }
    }

    static boolean setStarkScreensaver(Context c, boolean stark) {
        try {
            ContentResolver r = c.getContentResolver();
            String current = screensaver(c);
            if (stark) Prefs.setOriginalScreensaver(c, current);
            String target = stark ? dreamComponent(c) : Prefs.originalScreensaver(c);
            if (target == null) target = "com.android.dreams.basic/com.android.dreams.basic.Colors";
            Settings.Secure.putString(r, "screensaver_components", target);
            Settings.Secure.putInt(r, "screensaver_enabled", 1);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    static boolean adbEnabled(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1;
    }

    static boolean disableAdb(Context c) {
        try {
            return Settings.Global.putInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0);
        } catch (SecurityException e) {
            return false;
        }
    }

    static String deviceName(Context c) {
        String n = Settings.Global.getString(c.getContentResolver(), Settings.Global.DEVICE_NAME);
        return n == null || n.isEmpty() ? Build.MODEL : n;
    }

    // ---------- home screen ----------

    static boolean isDefaultHome(Context c) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo ri = c.getPackageManager().resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && c.getPackageName().equals(ri.activityInfo.packageName);
    }

    /** Stops being the preferred home and opens the TV's own one. `./stark home` switches back. */
    @SuppressWarnings("deprecation")
    static boolean switchToOriginalHome(Context c) {
        PackageManager pm = c.getPackageManager();
        pm.clearPackagePreferredActivities(c.getPackageName());
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : pm.queryIntentActivities(home, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(c.getPackageName()) || ri.activityInfo.name.endsWith("FallbackHome")) continue;
            Intent i = new Intent(home).setClassName(pkg, ri.activityInfo.name);
            if (Intents.start(c, i)) return true;
        }
        return false;
    }

    /** User apps that didn't come from the Play Store. */
    @SuppressWarnings("deprecation")
    static List<String> sideloaded(Context c) {
        List<String> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 || ai.packageName.equals(c.getPackageName())) continue;
            String installer;
            try {
                installer = pm.getInstallerPackageName(ai.packageName);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!"com.android.vending".equals(installer)) out.add(ai.packageName);
        }
        return out;
    }

    // ---------- about ----------

    static List<String[]> about(Context c) {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Model", Build.MANUFACTURER + " " + Build.MODEL});
        rows.add(new String[]{"Android", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"});
        rows.add(new String[]{"Security patch", Build.VERSION.SECURITY_PATCH});
        String chip = Build.HARDWARE;
        if (Build.VERSION.SDK_INT >= 31 && !Build.SOC_MODEL.isEmpty()) chip = Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL;
        rows.add(new String[]{"Chip", chip.toUpperCase(Locale.ROOT) + ", " + Runtime.getRuntime().availableProcessors() + " cores, "
                + (Build.SUPPORTED_64_BIT_ABIS.length > 0 ? "64-bit" : "32-bit")});
        ActivityManager.MemoryInfo mi = memory(c);
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        rows.add(new String[]{"Memory", Theme.bytes(mi.availMem) + " free of " + Theme.bytes(mi.totalMem)
                + (am.isLowRamDevice() ? " (low-RAM device)" : "")});
        long[] st = storage();
        rows.add(new String[]{"Storage", Theme.bytes(st[0]) + " free of " + Theme.bytes(st[1])});
        WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        Display d = wm.getDefaultDisplay();
        Display.Mode m = d.getMode();
        rows.add(new String[]{"Display", m.getPhysicalWidth() + " x " + m.getPhysicalHeight() + " at "
                + Math.round(m.getRefreshRate()) + " Hz" + hdr(d)});
        rows.add(new String[]{"Up for", uptime(SystemClock.elapsedRealtime())});
        rows.add(new String[]{"Build", Build.DISPLAY});
        String version = "?";
        try {
            version = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        rows.add(new String[]{"Stark-TV-OS", version});
        return rows;
    }

    private static String hdr(Display d) {
        Display.HdrCapabilities caps = d.getHdrCapabilities();
        if (caps == null || caps.getSupportedHdrTypes().length == 0) return "";
        List<String> names = new ArrayList<>();
        for (int t : caps.getSupportedHdrTypes()) {
            if (t == Display.HdrCapabilities.HDR_TYPE_HDR10) names.add("HDR10");
            else if (t == Display.HdrCapabilities.HDR_TYPE_HLG) names.add("HLG");
            else if (t == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) names.add("Dolby Vision");
        }
        return names.isEmpty() ? "" : ", " + android.text.TextUtils.join(" / ", names);
    }

    static String uptime(long ms) {
        long h = ms / 3_600_000L, d = h / 24;
        return d > 0 ? d + " days " + (h % 24) + " h" : h + " h " + (ms / 60_000L % 60) + " min";
    }

    /** Months since the security patch, or -1 when unknown. */
    static int patchAgeMonths() {
        try {
            String[] p = Build.VERSION.SECURITY_PATCH.split("-");
            java.util.Calendar now = java.util.Calendar.getInstance();
            return (now.get(java.util.Calendar.YEAR) - Integer.parseInt(p[0])) * 12
                    + (now.get(java.util.Calendar.MONTH) + 1 - Integer.parseInt(p[1]));
        } catch (RuntimeException e) {
            return -1;
        }
    }
}

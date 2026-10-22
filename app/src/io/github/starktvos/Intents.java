package io.github.starktvos;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

/** Opening things: apps, system screens, Play Store pages. Each tries the TV's own screen, then fallbacks. */
final class Intents {
    static final String TCL_LIVE_TV = "com.tcl.tv";

    private Intents() {}

    static boolean start(Context c, Intent... tries) {
        for (Intent i : tries) {
            if (i == null) continue;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                c.startActivity(i);
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                // next
            }
        }
        return false;
    }

    static Intent launchFor(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }

    static boolean openPackage(Context c, String pkg) {
        return start(c, launchFor(c, pkg));
    }

    static boolean settings(Context c) {
        return start(c, new Intent(Settings.ACTION_SETTINGS));
    }

    static boolean googleAccount(Context c) {
        return start(c,
                new Intent(Settings.ACTION_ADD_ACCOUNT).putExtra(Settings.EXTRA_ACCOUNT_TYPES, new String[]{"com.google"}),
                new Intent(Settings.ACTION_SYNC_SETTINGS),
                new Intent(Settings.ACTION_SETTINGS));
    }

    static boolean wifi(Context c) {
        return start(c, new Intent(Settings.ACTION_WIFI_SETTINGS), new Intent(Settings.ACTION_SETTINGS));
    }

    static boolean storage(Context c) {
        return start(c, new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS), new Intent(Settings.ACTION_SETTINGS));
    }

    static boolean appInfo(Context c, String pkg) {
        return start(c, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null)));
    }

    static boolean uninstall(Context c, String pkg) {
        return start(c, new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null)));
    }

    static boolean unknownSources(Context c) {
        return start(c, new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES), new Intent(Settings.ACTION_SECURITY_SETTINGS));
    }

    static boolean adsPrivacy(Context c) {
        return start(c, new Intent("com.google.android.gms.settings.ADS_PRIVACY"), new Intent(Settings.ACTION_PRIVACY_SETTINGS));
    }

    static boolean allApps(Context c) {
        return start(c, new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS), new Intent(Settings.ACTION_APPLICATION_SETTINGS));
    }

    static boolean liveTv(Context c) {
        return openPackage(c, TCL_LIVE_TV)
                || start(c, new Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.Channels.CONTENT_URI));
    }

    /** The app's Play Store page when the package is known for sure, otherwise a search by name. */
    static boolean playStore(Context c, Catalog.Entry e) {
        Intent details = e.verified && e.pkgs.length > 0
                ? store(Uri.parse("market://details?id=" + e.pkgs[0])) : null;
        Intent search = store(Uri.parse("market://search?q=" + Uri.encode(Catalog.searchQuery(e)) + "&c=apps"));
        return start(c, details, search);
    }

    private static Intent store(Uri uri) {
        return new Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending");
    }
}

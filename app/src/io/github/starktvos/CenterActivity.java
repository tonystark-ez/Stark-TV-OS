package io.github.starktvos;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Stark Control: performance, network, mirroring, security, apps and device info, all on the TV. */
public final class CenterActivity extends Activity implements PanelView.Host {
    static final String EXTRA_PAGE = "page";

    private static final String NEEDS_SETUP = "Needs one-time setup";
    private static final String SETUP_HELP = "Run ./stark install from your computer once. It grants Stark the "
            + "permission to change this setting; nothing else on the TV changes.";

    private static final class Entry {
        final String page;
        int sel;

        Entry(String page) {
            this.page = page;
        }
    }

    private static Bitmap artRing, artRadial;

    private PanelView view;
    private final ArrayDeque<Entry> stack = new ArrayDeque<>();
    private long confirmUntil;
    private String confirmKey;
    /** Pages are built off the UI thread; only the newest request may show. */
    private int buildSeq;
    private final Runnable refreshLater = this::refresh;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        view = new PanelView(this, this);
        setContentView(view);
        getWindow().setBackgroundDrawable(null);
        view.requestFocus();
        stack.push(new Entry("main"));
        String start = getIntent().getStringExtra(EXTRA_PAGE);
        if (start != null) stack.push(new Entry(start));
        show();
        if (!Prefs.safeMode(this)) loadArt();
    }

    @Override
    protected void onDestroy() {
        Bg.MAIN.removeCallbacks(refreshLater);
        buildSeq++;
        super.onDestroy();
    }

    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter w, String[] args) {
        super.dump(prefix, fd, w, args);
        PanelView.Page p = view.page();
        Entry e = stack.peek();
        w.println(prefix + "stark.page=" + (e == null ? "none" : e.page));
        w.println(prefix + "stark.page.title=" + (p == null ? "loading" : p.title));
        w.println(prefix + "stark.page.items=" + (p == null ? 0 : p.items.size()));
    }

    private void loadArt() {
        if (artRing != null) {
            view.setArt(artRing, artRadial);
            return;
        }
        // Not on Bg.quick: rendering the ring takes long enough to hold up the first page behind it.
        Bg.run(() -> {
            final Bitmap ring = Art.ring(640);
            final Bitmap radial = Art.radialGlow(256);
            Bg.ui(() -> {
                artRing = ring;
                artRadial = radial;
                if (!isDestroyed()) view.setArt(ring, radial);
            });
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        stack.clear();
        stack.push(new Entry("main"));
        String start = intent.getStringExtra(EXTRA_PAGE);
        if (start != null) stack.push(new Entry(start));
        show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Values may have changed in system settings while we were away.
        refresh();
    }

    @Override
    public void back() {
        if (stack.size() > 1) {
            stack.pop();
            show();
        } else {
            finish();
        }
    }

    private void open(String page) {
        stack.peek().sel = view.selection();
        stack.push(new Entry(page));
        show();
    }

    private void show() {
        load(false);
    }

    private void refresh() {
        Entry e = stack.peek();
        if (e == null) return;
        if (view.page() != null) e.sel = view.selection();
        load(true);
    }

    private void load(final boolean keepMessage) {
        final Entry e = stack.peek();
        if (e == null) return;
        final int seq = ++buildSeq;
        Bg.quick(() -> {
            final PanelView.Page p = build(e.page);
            Bg.ui(() -> {
                if (seq != buildSeq || isDestroyed() || stack.peek() != e) return;
                view.show(p, e.sel, keepMessage);
            });
        });
    }

    private PanelView.Page build(String page) {
        switch (page) {
            case "performance": return performance();
            case "caches": return caches();
            case "network": return network();
            case "mirror": return mirror();
            case "security": return security();
            case "sideloaded": return sideloaded();
            case "apps": return apps();
            case "get": return getApps();
            case "home": return home();
            case "about": return about();
            default: return main();
        }
    }

    /** Runs work off the UI thread while the row shows "Working…", then shows the result. */
    private void work(final PanelView.Item item, final Task task) {
        if (testing()) return;
        item.busy = true;
        final String before = item.value;
        item.value = "Working…";
        view.invalidate();
        final Context ctx = getApplicationContext();
        Bg.run(() -> {
            String[] res;
            try {
                res = task.run(ctx);
            } catch (Exception ex) {
                res = new String[]{before, "Didn't work: " + ex.getMessage()};
            }
            final String[] out = res;
            Bg.ui(() -> {
                item.busy = false;
                item.value = out[0];
                if (out.length > 1 && out[1] != null) view.message(out[1], Theme.ACCENT);
                view.invalidate();
            });
        });
    }

    private interface Task {
        /** Returns {new value, message}. */
        String[] run(Context c) throws Exception;
    }

    /** Under an automated UI test (monkey) nothing that changes the TV's settings may run. */
    private boolean testing() {
        if (!ActivityManager.isUserAMonkey()) return false;
        view.message("Settings can't be changed during automated testing.", Theme.DIM);
        return true;
    }

    /** Destructive actions need a second press within 4 seconds. */
    private boolean confirmed(String key, String prompt) {
        if (testing()) return false;
        long now = SystemClock.uptimeMillis();
        if (key.equals(confirmKey) && now < confirmUntil) {
            confirmKey = null;
            return true;
        }
        confirmKey = key;
        confirmUntil = now + 4000;
        view.message(prompt, Theme.WARN);
        return false;
    }

    // ---------- pages ----------

    private PanelView.Page main() {
        PanelView.Page p = new PanelView.Page("Stark Control", "Your TV, under control");
        ActivityManager.MemoryInfo mi = SystemTools.memory(this);
        p.add(new PanelView.Item(Glyphs.GAUGE, "Performance", Theme.bytes(mi.availMem) + " memory free",
                "Free memory, clear caches and make menus snappier.", () -> open("performance")));
        NetTools.Status ns = NetTools.status(this);
        p.add(new PanelView.Item(ns.type == NetTools.ETHERNET ? Glyphs.ETHERNET : Glyphs.WIFI, "Network",
                ns.type == NetTools.NONE ? "Offline" : ns.ssid != null ? ns.label() + " · " + ns.ssid : ns.label(),
                "Connection details, speed test and encrypted DNS.", () -> open("network"))
                .color(ns.type == NetTools.NONE ? Theme.BAD : Theme.DIM));
        p.add(new PanelView.Item(Glyphs.CAST, "Screen mirroring", SystemTools.deviceName(this),
                "Show your phone or laptop on the TV: Chromecast, Miracast and AirPlay.", () -> open("mirror")));
        int issues = securityIssues(ns);
        p.add(new PanelView.Item(Glyphs.SHIELD, "Security & privacy", issues == 0 ? "Good" : issues + " to review",
                "Debugging access, encrypted DNS, updates, apps from outside the Play Store.", () -> open("security"))
                .color(issues == 0 ? Theme.GOOD : Theme.WARN));
        p.add(new PanelView.Item(Glyphs.BAG, "Apps", null,
                "Get popular Indian streaming apps from the Play Store, and manage installed ones.", () -> open("apps")));
        p.add(new PanelView.Item(Glyphs.HOME, "Home & screensaver", SystemTools.isDefaultHome(this) ? "Stark" : "Original",
                "Pick the home screen and a light screensaver.", () -> open("home")));
        p.add(new PanelView.Item(Glyphs.PERSON, "Google account", null,
                "Sign in to Google for the Play Store, YouTube and purchases. Opens the TV's own sign-in screen.",
                () -> Intents.googleAccount(this)));
        p.add(new PanelView.Item(Glyphs.GEAR, "TV settings", null,
                "Picture, sound, inputs, remote and everything else from TCL and Android.", () -> Intents.settings(this)));
        p.add(new PanelView.Item(Glyphs.INFO, "About this TV", Build.MANUFACTURER + " · Android " + Build.VERSION.RELEASE,
                "Model, chip, memory, display and versions.", () -> open("about")));
        return p;
    }

    private PanelView.Page performance() {
        PanelView.Page p = new PanelView.Page("Performance", "Keep the TV quick");
        final boolean secure = SystemTools.canWriteSecure(this);

        p.add(task(Glyphs.SPARKLE, "Clean up now", null,
                "Closes apps waiting in the background and clears temporary files. Your apps, sign-ins and downloads "
                        + "stay. Do this when the TV starts to feel slow.", c -> {
                    long ram = SystemTools.freeMemory(c);
                    long disk = SystemTools.clearCaches(c);
                    return new String[]{"Done", "Freed " + Theme.bytes(ram) + " of memory and " + Theme.bytes(disk) + " of storage."};
                }));

        ActivityManager.MemoryInfo mi = SystemTools.memory(this);
        p.add(task(Glyphs.CHIP, "Free memory", Theme.bytes(mi.availMem) + " free of " + Theme.bytes(mi.totalMem),
                "Closes streaming apps you've left in the background. They open from scratch next time, which takes a "
                        + "second longer but leaves memory for the app you're using.", c -> {
                    long freed = SystemTools.freeMemory(c);
                    ActivityManager.MemoryInfo m = SystemTools.memory(c);
                    return new String[]{Theme.bytes(m.availMem) + " free of " + Theme.bytes(m.totalMem), "Freed " + Theme.bytes(freed) + "."};
                }));

        long[] st = SystemTools.storage();
        p.add(task(Glyphs.SPARKLE, "Clear app caches", Theme.bytes(st[0]) + " free",
                "Deletes files apps keep to load faster and can always download again. Helps most when storage is "
                        + "nearly full: a full disk is one of the main reasons old TVs crawl.", c -> {
                    long freed = SystemTools.clearCaches(c);
                    return new String[]{Theme.bytes(SystemTools.storage()[0]) + " free", "Cleared " + Theme.bytes(freed) + " of caches."};
                }));

        if (SystemTools.hasUsageAccess(this)) {
            p.add(new PanelView.Item(Glyphs.GRID, "Biggest caches", null,
                    "Apps sorted by how much cache they keep. Open one to clear its cache or data.", () -> open("caches")));
        }

        float scale = SystemTools.animationScale(this);
        String anim = scale == 0 ? "Off" : scale <= 0.5f ? "Fast (0.5x)" : "Normal (1x)";
        p.add(new PanelView.Item(Glyphs.BOLT, "Animation speed", secure ? anim : NEEDS_SETUP,
                (secure ? "Fast halves every system transition so menus feel twice as quick. Off removes them; some "
                        + "apps look abrupt without them." : SETUP_HELP),
                secure ? () -> {
                    if (testing()) return;
                    float next = scale > 0.5f ? 0.5f : scale > 0 ? 0f : 1f;
                    SystemTools.setAnimationScale(this, next);
                    refresh();
                } : null));

        p.add(new PanelView.Item(Glyphs.CHIP, "Storage", Theme.bytes(st[0]) + " free of " + Theme.bytes(st[1]),
                "Opens the TV's storage settings.", () -> Intents.storage(this)));
        return p;
    }

    private PanelView.Page caches() {
        final PanelView.Page p = new PanelView.Page("Biggest caches", "Open an app to clear its cache");
        for (SystemTools.AppCache ac : SystemTools.caches(this)) {
            final String pkg = ac.pkg;
            p.add(new PanelView.Item(-1, ac.label, Theme.bytes(ac.cache),
                    "Opens App info, where Clear cache frees this space.", () -> Intents.appInfo(this, pkg)));
            if (p.items.size() >= 25) break;
        }
        if (p.items.isEmpty()) p.add(new PanelView.Item(-1, "No large caches", null, "Nothing to clear.", null));
        return p;
    }

    private PanelView.Page network() {
        final PanelView.Page p = new PanelView.Page("Network", null);
        final NetTools.Status s = NetTools.status(this);
        String state = s.type == NetTools.NONE ? "Not connected" : s.validated ? "Connected" : "Connected, no internet";
        p.add(new PanelView.Item(s.type == NetTools.ETHERNET ? Glyphs.ETHERNET : s.type == NetTools.NONE ? Glyphs.OFFLINE : Glyphs.WIFI,
                s.label() + (s.ssid != null ? " · " + s.ssid : ""), state,
                "Opens the TV's network settings to change networks or forget one.", () -> Intents.wifi(this))
                .color(s.type == NetTools.NONE ? Theme.BAD : s.validated ? Theme.GOOD : Theme.WARN));

        if (s.type == NetTools.WIFI && s.rssi != Integer.MIN_VALUE) {
            int bars = s.bars();
            String q = bars >= 4 ? "Excellent" : bars == 3 ? "Good" : bars == 2 ? "Fair" : "Weak";
            p.add(new PanelView.Item(-1, "Signal", q + " (" + s.rssi + " dBm)",
                    "Below Fair, 4K streams may buffer. Move the router closer or use Ethernet.", null)
                    .color(bars >= 3 ? Theme.GOOD : bars == 2 ? Theme.WARN : Theme.BAD));
            boolean is24 = s.freqMhz > 0 && s.freqMhz < 3000;
            p.add(new PanelView.Item(-1, "Band and link", (is24 ? "2.4 GHz" : "5 GHz") + " · " + s.linkMbps + " Mbps",
                    is24 ? "2.4 GHz is slower and crowded by neighbours. If your router has a 5 GHz network and the TV "
                            + "supports it, connect to that for smoother 4K." : "5 GHz: the fast band. Good for 4K.",
                    null).color(is24 ? Theme.WARN : Theme.GOOD));
        }

        p.add(task(Glyphs.GAUGE, "Speed test", "Run",
                "Measures delay and download speed against Cloudflare (about 10 seconds, ~50 MB). "
                        + "HD needs 5 Mbps, 4K needs 25 Mbps.", c -> {
                    int ms = NetTools.latencyMs();
                    if (ms < 0) return new String[]{"Failed", "Couldn't reach the internet."};
                    double mbps = NetTools.downloadMbps();
                    String v = ms + " ms · " + (mbps < 0 ? "?" : String.format(Locale.US, "%.0f Mbps", mbps));
                    String verdict = mbps >= 25 ? "Fast enough for 4K." : mbps >= 5 ? "Good for HD; 4K may buffer."
                            : mbps >= 0 ? "Slow: expect buffering, even in HD." : "Download test failed.";
                    return new String[]{v, verdict};
                }));

        boolean secure = SystemTools.canWriteSecure(this);
        int choice = NetTools.dnsChoice(s);
        p.add(dnsItem(secure, choice, s));

        p.add(new PanelView.Item(-1, "IP address", s.ip == null ? "-" : s.ip, null, null));
        p.add(new PanelView.Item(-1, "Router", s.gateway == null ? "-" : s.gateway, null, null));
        p.add(new PanelView.Item(-1, "DNS servers", s.dns == null || s.dns.isEmpty() ? "-" : s.dns,
                s.privateDns ? "DNS lookups are encrypted." : "DNS lookups go unencrypted to these servers.", null));

        p.add(task(Glyphs.REFRESH, "Refresh connection", null,
                "Makes Android re-check the connection and DNS. Try this when apps say there's no internet but "
                        + "other devices work.", c -> {
                    NetTools.refresh(c);
                    SystemClock.sleep(2500);
                    NetTools.Status now = NetTools.status(c);
                    return new String[]{now.validated ? "Online" : "Still offline",
                            now.validated ? "Connection is working." : "Still no internet. Try restarting the router."};
                }));
        return p;
    }

    private PanelView.Item task(int glyph, String label, String value, String help, Task t) {
        final PanelView.Item[] self = new PanelView.Item[1];
        self[0] = new PanelView.Item(glyph, label, value, help, () -> work(self[0], t));
        return self[0];
    }

    private PanelView.Item dnsItem(boolean secure, int choice, NetTools.Status s) {
        String help = secure
                ? "Encrypts the TV's DNS lookups so your ISP can't see or tamper with them. AdGuard also blocks most "
                + "ads and trackers in apps. If a provider doesn't work on your network, Stark switches back to "
                + "Automatic on its own. Press OK to cycle."
                : SETUP_HELP;
        String value = !secure ? NEEDS_SETUP : choice == 0 ? (s.privateDns ? "Automatic (encrypted)" : "Automatic")
                : NetTools.DNS_NAMES[choice].replaceAll(" \\(.*", "");
        final PanelView.Item[] holder = new PanelView.Item[1];
        holder[0] = new PanelView.Item(Glyphs.LOCK, "Encrypted DNS", value, help, !secure ? null : () -> {
            if (testing()) return;
            final int next = (choice + 1) % NetTools.DNS_HOSTS.length;
            work(holder[0], c -> {
                String err = NetTools.setDns(c, next);
                if (err != null) return new String[]{"Automatic", NetTools.DNS_NAMES[next] + ": " + err + "."};
                return new String[]{NetTools.DNS_NAMES[next].replaceAll(" \\(.*", ""),
                        "Now using " + NetTools.DNS_NAMES[next] + "."};
            });
            Bg.MAIN.removeCallbacks(refreshLater);
            Bg.MAIN.postDelayed(refreshLater, 12_000);
        });
        if (s.privateDns) holder[0].color(Theme.GOOD);
        return holder[0];
    }

    private PanelView.Page mirror() {
        PanelView.Page p = new PanelView.Page("Screen mirroring", "Your TV appears as \u201c" + SystemTools.deviceName(this) + "\u201d");
        boolean cast = enabled("com.google.android.apps.mediashell");
        p.add(new PanelView.Item(Glyphs.CAST, "Chromecast built-in", cast ? "Ready" : "Not available",
                "From an Android phone: Google Home app \u203a this TV \u203a Cast my screen, or the Cast button in "
                        + "YouTube, Netflix, Prime Video or JioHotstar (best quality: the TV streams directly).\n\n"
                        + "From a laptop: Chrome \u203a \u22ee \u203a Cast \u203a Cast tab or screen.", null)
                .color(cast ? Theme.GOOD : Theme.DIM));

        boolean miracast = enabled("com.tcl.miracast");
        p.add(new PanelView.Item(Glyphs.TV, "Miracast", miracast ? "Listening" : "Not available",
                "From Android phones (Samsung, OnePlus, Xiaomi\u2026): Quick Settings \u203a Smart View / Screen cast / "
                        + "Wireless display, then pick this TV.\n\nFrom Windows: press Win + K.", null)
                .color(miracast ? Theme.GOOD : Theme.DIM));

        if (Intents.launchFor(this, "com.tcl.MultiScreenInteraction_TV") != null) {
            p.add(new PanelView.Item(Glyphs.ARROWS, "T-Cast", "Open",
                    "TCL's phone app (T-Cast on Android and iPhone) sends photos and videos, and works as a remote.",
                    () -> Intents.openPackage(this, "com.tcl.MultiScreenInteraction_TV")));
        }

        boolean airScreen = Intents.launchFor(this, "com.ionitech.airscreen") != null;
        boolean airplay = Intents.launchFor(this, "com.tcl.airplay2") != null;
        String airHelp = "From iPhone, iPad or Mac: Control Center \u203a Screen Mirroring \u203a pick this TV.";
        if (airplay) {
            p.add(new PanelView.Item(Glyphs.CAST, "AirPlay", "Open", airHelp, () -> Intents.openPackage(this, "com.tcl.airplay2")));
        } else {
            p.add(new PanelView.Item(Glyphs.CAST, "AirPlay (AirScreen)", airScreen ? "Open" : "Get",
                    airHelp + (airScreen ? " Open AirScreen once after the TV starts so it's listening."
                            : " This TV needs the free AirScreen app for that."),
                    () -> {
                        if (airScreen) Intents.openPackage(this, "com.ionitech.airscreen");
                        else for (Catalog.Entry e : Catalog.ALL) if (e.name.equals("AirScreen")) Intents.playStore(this, e);
                    }).color(airScreen ? Theme.GOOD : Theme.ACCENT));
        }
        p.add(new PanelView.Item(Glyphs.GEAR, "Change the TV's name", null,
                "Opens settings: Device Preferences \u203a About \u203a Device name.", () -> Intents.settings(this)));
        return p;
    }

    private int securityIssues(NetTools.Status s) {
        int n = 0;
        if (SystemTools.adbEnabled(this)) n++;
        if (!s.privateDns && Build.VERSION.SDK_INT >= 28) n++;
        if (!SystemTools.sideloaded(this).isEmpty()) n++;
        return n;
    }

    private PanelView.Page security() {
        PanelView.Page p = new PanelView.Page("Security & privacy", null);
        final boolean secure = SystemTools.canWriteSecure(this);
        boolean adb = SystemTools.adbEnabled(this);
        p.add(new PanelView.Item(Glyphs.LOCK, "Debugging access (ADB)", adb ? "On" : "Off",
                adb ? "While this is on, any device on your Wi-Fi can ask to control the TV (you still have to accept "
                        + "on screen). Turn it off once setup is done; Developer options turns it back on."
                        + (secure ? " Press OK twice to turn it off." : "")
                        : "Off: nothing on your network can connect to the TV.",
                adb && secure ? () -> {
                    if (!confirmed("adb", "Press OK again to turn off debugging. ./stark stops working until you turn it back on.")) return;
                    view.message(SystemTools.disableAdb(this) ? "Debugging is off." : "Couldn't turn it off.", Theme.ACCENT);
                    refresh();
                } : null).color(adb ? Theme.WARN : Theme.GOOD));

        NetTools.Status s = NetTools.status(this);
        p.add(dnsItem(secure, NetTools.dnsChoice(s), s));

        int age = SystemTools.patchAgeMonths();
        String patch = Build.VERSION.SECURITY_PATCH + (age > 0 ? " · " + (age / 12 > 0 ? age / 12 + " yr " : "") + age % 12 + " mo old" : "");
        p.add(new PanelView.Item(Glyphs.SHIELD, "Security update", patch,
                age > 12 ? "TCL stopped updating this model's system. Stark can't patch Android itself, so: install "
                        + "apps only from the Play Store, keep encrypted DNS on, and turn debugging off."
                        : "Recent enough.", null).color(age > 12 ? Theme.WARN : Theme.GOOD));

        List<String> side = SystemTools.sideloaded(this);
        p.add(new PanelView.Item(Glyphs.DOWNLOAD, "Apps from outside the Play Store", side.isEmpty() ? "None" : String.valueOf(side.size()),
                "Apps installed from files skip Google's malware checks. Review them and remove any you don't trust.",
                side.isEmpty() ? null : () -> open("sideloaded")).color(side.isEmpty() ? Theme.GOOD : Theme.WARN));

        p.add(new PanelView.Item(Glyphs.GRID, "Install unknown apps", null,
                "Which apps are allowed to install other apps. Keep this off for everything you don't use for that.",
                () -> Intents.unknownSources(this)));

        List<String> off = Prefs.disabledByStark(this);
        p.add(new PanelView.Item(Glyphs.POWER, "TCL telemetry & promos", off.isEmpty() ? "Active" : off.size() + " apps off",
                off.isEmpty() ? "Run ./stark debloat from your computer to turn off TCL's analytics, promo messages, "
                        + "store demo and other background apps. Nothing is uninstalled; ./stark restore undoes it."
                        : "Turned off by ./stark debloat: " + android.text.TextUtils.join(", ", off)
                        + ". ./stark restore brings them back.", null)
                .color(off.isEmpty() ? Theme.WARN : Theme.GOOD));

        p.add(new PanelView.Item(Glyphs.PERSON, "Ads personalisation", null,
                "Opens Google's ad settings: reset or delete your advertising ID.", () -> Intents.adsPrivacy(this)));
        return p;
    }

    private PanelView.Page sideloaded() {
        PanelView.Page p = new PanelView.Page("Apps from outside the Play Store", "Open one to uninstall it");
        PackageManager pm = getPackageManager();
        for (String pkg : SystemTools.sideloaded(this)) {
            String label = pkg;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                label = String.valueOf(ai.loadLabel(pm));
            } catch (PackageManager.NameNotFoundException ignored) {
            }
            final String target = pkg;
            p.add(new PanelView.Item(-1, label, pkg, "Opens App info, where you can uninstall it.",
                    () -> Intents.appInfo(this, target)));
        }
        return p;
    }

    private PanelView.Page apps() {
        PanelView.Page p = new PanelView.Page("Apps", null);
        p.add(new PanelView.Item(Glyphs.DOWNLOAD, "Popular apps in India", null,
                "Netflix, Prime Video, JioHotstar, SonyLIV, ZEE5 and more. Installed ones are marked; the rest open "
                        + "in the Play Store.", () -> open("get")));
        p.add(new PanelView.Item(Glyphs.BAG, "Google Play Store", null,
                "Every app made for Android TV.", () -> Intents.openPackage(this, "com.android.vending")));
        boolean sugg = Prefs.suggestions(this);
        p.add(new PanelView.Item(Glyphs.STAR, "Suggestions on Home", sugg ? "On" : "Off",
                "The \u201cGet more apps\u201d row at the bottom of Home. Press OK to switch.", () -> {
                    if (testing()) return;
                    Prefs.setSuggestions(this, !sugg);
                    refresh();
                }));
        p.add(new PanelView.Item(Glyphs.REFRESH, "Reset favourites", null,
                "Goes back to the default favourites row: your installed streaming apps in a sensible order.", () -> {
                    if (testing()) return;
                    Prefs.resetFavourites(this);
                    view.message("Favourites reset. Go back to Home to see them.", Theme.ACCENT);
                }));
        p.add(new PanelView.Item(Glyphs.GEAR, "Manage all apps", null,
                "The TV's app settings: storage, permissions, force stop, uninstall.", () -> Intents.allApps(this)));
        return p;
    }

    private PanelView.Page getApps() {
        PanelView.Page p = new PanelView.Page("Popular apps in India", "Installed apps open; the rest open in the Play Store");
        List<AppRepo.App> apps = AppRepo.load(this);
        Map<String, AppRepo.App> byPkg = new HashMap<>();
        for (AppRepo.App a : apps) byPkg.put(a.pkg, a);
        for (final Catalog.Entry e : Catalog.ALL) {
            final AppRepo.App a = Catalog.find(e, byPkg, apps);
            p.add(new PanelView.Item(-1, e.name, a != null ? "Installed" : "Get",
                    e.blurb + (a != null ? ". Press OK to open." : ". Press OK to open its Play Store page."),
                    () -> {
                        if (a != null) Intents.start(this, AppRepo.launchIntent(a));
                        else Intents.playStore(this, e);
                    }).color(a != null ? Theme.GOOD : Theme.ACCENT));
        }
        return p;
    }

    private PanelView.Page home() {
        PanelView.Page p = new PanelView.Page("Home & screensaver", null);
        boolean isHome = SystemTools.isDefaultHome(this);
        p.add(new PanelView.Item(Glyphs.HOME, "Home screen", isHome ? "Stark" : "Original",
                isHome ? "Press OK twice to go back to the TV's original home screen. To return to Stark, run "
                        + "./stark home from your computer."
                        : "The TV's original home is active. Run ./stark home from your computer to make Stark the home "
                        + "screen again.",
                isHome ? () -> {
                    if (!confirmed("home", "Press OK again to switch to the original home screen.")) return;
                    if (!SystemTools.switchToOriginalHome(this)) {
                        view.message("The TV's own launcher is turned off while Stark is home (it would always take "
                                + "over). Run ./stark restore from your computer to bring it back.", Theme.WARN);
                    }
                } : null));

        final boolean secure = SystemTools.canWriteSecure(this);
        String saver = SystemTools.screensaver(this);
        boolean stark = saver != null && saver.startsWith(getPackageName());
        String saverName = stark ? "Stark Clock" : saver == null ? "System default"
                : saver.contains("backdrop") ? "Ambient Mode" : saver.replaceAll("/.*", "");
        p.add(new PanelView.Item(Glyphs.MOON, "Screensaver", secure ? saverName : NEEDS_SETUP,
                secure ? "Stark Clock: a dim clock that moves every minute to protect the panel and uses almost no "
                        + "memory or data. Ambient Mode downloads photos all day and keeps 100+ MB busy. Press OK to switch."
                        : SETUP_HELP,
                secure ? () -> {
                    if (testing()) return;
                    SystemTools.setStarkScreensaver(this, !stark);
                    refresh();
                } : null));

        final int mode = Prefs.splashMode(this);
        String modeName = mode == Prefs.SPLASH_BOOT_AND_WAKE ? "Start and wake" : mode == Prefs.SPLASH_BOOT ? "Start only" : "Off";
        p.add(new PanelView.Item(Glyphs.SPARKLE, "Startup animation", modeName,
                "The Stark-TV-OS animation when the TV turns on. \u201cStart and wake\u201d also plays it when the TV "
                        + "comes back from standby. Any button skips it. Press OK to switch.", () -> {
                    if (testing()) return;
                    int next = mode == Prefs.SPLASH_BOOT_AND_WAKE ? Prefs.SPLASH_BOOT
                            : mode == Prefs.SPLASH_BOOT ? Prefs.SPLASH_OFF : Prefs.SPLASH_BOOT_AND_WAKE;
                    Prefs.setSplashMode(this, next);
                    refresh();
                }));
        p.add(new PanelView.Item(Glyphs.TV, "Play the animation", null,
                "Plays it now on the home screen.", () -> Intents.start(this,
                new Intent(this, HomeActivity.class).putExtra(HomeActivity.EXTRA_SPLASH, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))));
        return p;
    }

    private PanelView.Page about() {
        PanelView.Page p = new PanelView.Page("About this TV", null);
        for (String[] row : SystemTools.about(this)) p.add(new PanelView.Item(-1, row[0], row[1], null, null));
        p.add(new PanelView.Item(Glyphs.INFO, "Stark-TV-OS", "Open source",
                "A fast, minimal home for Android TVs. No accounts, no tracking, no ads. Everything it changes, "
                        + "./stark restore undoes.", null));
        return p;
    }

    private boolean enabled(String pkg) {
        try {
            return getPackageManager().getApplicationInfo(pkg, 0).enabled;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}

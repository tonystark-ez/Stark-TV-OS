package io.github.starktvos;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.widget.FrameLayout;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The launcher. Lists apps and inputs, keeps the clock and connection status current, and stays out of the way. */
public final class HomeActivity extends Activity implements HomeView.Host, SplashView.Listener {

    /** Plays the startup animation now (preview from Control, and the validation suite). */
    static final String EXTRA_SPLASH = "io.github.starktvos.SPLASH";

    private static final String A_MIRROR = "mirror", A_CLEAN = "clean", A_NETWORK = "network", A_ACCOUNT = "account",
            A_SETTINGS = "settings", A_CONTROL = "control", A_APPS = "apps", A_LIVE_TV = "livetv";

    private static final String M_ADD_FAV = "Add to favourites", M_REMOVE_FAV = "Remove from favourites",
            M_LEFT = "Move left", M_RIGHT = "Move right", M_INFO = "App info", M_UNINSTALL = "Uninstall",
            M_STORE = "Open in Play Store", M_HIDE_SUGGESTIONS = "Hide suggestions";

    /** Suspend longer than this counts as the TV having been switched off. */
    private static final long WAKE_SLEEP_MS = 30_000;
    /** ...and the animation only plays if the screen came on this recently. */
    private static final long WAKE_RECENT_MS = 20_000;

    private static Bitmap artRing, artRadial, artTileGlow;

    private FrameLayout root;
    private HomeView view;
    private SplashView splash;
    private boolean safeMode;
    private long screenOnAt;
    private String lastSplashReason = "none";

    private List<AppRepo.App> apps = new ArrayList<>();
    private Map<String, AppRepo.App> byPkg = new HashMap<>();
    private List<Inputs.Input> inputs = new ArrayList<>();
    private boolean hasLiveTv;
    private boolean dirty = true;
    private boolean cleaning;
    private int tileW, tileH;
    private float tileRadius, scale;

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (i.getData() != null) AppRepo.forget(i.getData().getSchemeSpecificPart());
            dirty = true;
            if (hasWindowFocus()) reload();
        }
    };

    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            updateClock();
        }
    };

    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            screenOnAt = SystemClock.elapsedRealtime();
        }
    };

    private final ConnectivityManager.NetworkCallback netCallback = new ConnectivityManager.NetworkCallback() {
        @Override public void onAvailable(Network n) { postNetwork(); }
        @Override public void onLost(Network n) { postNetwork(); }
        @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) { postNetwork(); }
        @Override public void onLinkPropertiesChanged(Network n, LinkProperties lp) { postNetwork(); }
    };

    private final Runnable stable = () -> Prefs.clearCrashes(this);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        safeMode = Prefs.safeMode(this);
        root = new FrameLayout(this);
        view = new HomeView(this, this);
        root.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        getWindow().setBackgroundDrawable(null);
        view.requestFocus();

        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        scale = Math.max(dm.widthPixels, dm.heightPixels) / Theme.W;
        tileW = Math.round(HomeView.APP_W * (1 + HomeView.FOCUS_GROW) * scale);
        tileH = Math.round(HomeView.APP_H * (1 + HomeView.FOCUS_GROW) * scale);
        tileRadius = HomeView.RADIUS * (1 + HomeView.FOCUS_GROW) * scale;

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf, Context.RECEIVER_EXPORTED);

        IntentFilter tf = new IntentFilter();
        tf.addAction(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(time, tf, Context.RECEIVER_EXPORTED);
        registerReceiver(screen, new IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_EXPORTED);

        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        cm.registerDefaultNetworkCallback(netCallback);

        if (!safeMode) loadArt();
        view.postDelayed(stable, 60_000);
        updateClock();
        postNetwork();
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_SPLASH, false)) playSplash("intent");
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateClock();
        checkPower();
        if (dirty || needsTiles()) reload();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (splash != null) splash.finishNow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent.getBooleanExtra(EXTRA_SPLASH, false)) {
            playSplash("intent");
            return;
        }
        // Home pressed while already home: back to the top, like every launcher.
        if (hasWindowFocus()) view.goTop();
    }

    @Override
    public void onBackPressed() {
        view.goTop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (splash != null && !splash.isFinished() && !isSystemKey(e.getKeyCode())) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) splash.finish();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private static boolean isSystemKey(int code) {
        return code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_POWER
                || code == KeyEvent.KEYCODE_HOME;
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // While a streaming app is in front, give our tile images back to it; they're rebuilt in ~100 ms on return.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
                || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
                || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            if (!hasWindowFocus()) AppRepo.dropTiles(apps);
        }
    }

    @Override
    protected void onDestroy() {
        view.removeCallbacks(stable);
        unregisterReceiver(packages);
        unregisterReceiver(time);
        unregisterReceiver(screen);
        try {
            ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCallback);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }

    @Override
    public void dump(String prefix, FileDescriptor fd, PrintWriter w, String[] args) {
        super.dump(prefix, fd, w, args);
        view.dump(prefix, w);
        w.println(prefix + "stark.splash=" + (splash == null ? "idle" : splash.hasStarted() ? "playing" : "starting"));
        w.println(prefix + "stark.splash.last=" + lastSplashReason);
        w.println(prefix + "stark.safeMode=" + safeMode);
        w.println(prefix + "stark.home=" + SystemTools.isDefaultHome(this));
    }

    // ---------- startup animation ----------

    /**
     * Boot is detected by Android's boot counter. Wake from standby (this TV suspends to RAM instead of
     * shutting down) by the time the system spent suspended, which uptimeMillis excludes and elapsedRealtime counts.
     */
    private void checkPower() {
        int boot = Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        long now = SystemClock.elapsedRealtime();
        long slept = now - SystemClock.uptimeMillis();
        int lastBoot = Prefs.lastBoot(this);
        long lastSlept = Prefs.lastSleep(this);
        Prefs.setPowerMarks(this, boot, slept);

        int mode = Prefs.splashMode(this);
        if (mode == Prefs.SPLASH_OFF || safeMode) return;
        boolean booted = boot != lastBoot && now < 5 * 60_000L;
        long since = screenOnAt > 0 ? now - screenOnAt : now - Process.getStartElapsedRealtime();
        boolean woke = !booted && lastSlept >= 0 && slept - lastSlept > WAKE_SLEEP_MS && since < WAKE_RECENT_MS;
        if (booted) playSplash("boot");
        else if (woke && mode == Prefs.SPLASH_BOOT_AND_WAKE) playSplash("wake");
    }

    private void playSplash(String reason) {
        if (splash != null) return;
        lastSplashReason = reason;
        view.goTop();
        splash = new SplashView(this, this);
        root.addView(splash, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    @Override
    public void onSplashDone() {
        splash = null;
        view.requestFocus();
    }

    // ---------- loading ----------

    private void loadArt() {
        if (artRing != null) {
            view.setArt(artRing, artRadial, artTileGlow);
            return;
        }
        final int ringPx = Math.round(HomeView.RING_D * scale);
        Bg.quick(() -> {
            final Bitmap ring = Art.ring(ringPx);
            final Bitmap radial = Art.radialGlow(256);
            final Bitmap glow = Art.tileGlow();
            Bg.ui(() -> {
                artRing = ring;
                artRadial = radial;
                artTileGlow = glow;
                if (!isDestroyed()) view.setArt(ring, radial, glow);
            });
        });
    }

    private boolean needsTiles() {
        for (AppRepo.App a : apps) if (a.tile == null) return true;
        return false;
    }

    private void reload() {
        dirty = false;
        final Context ctx = getApplicationContext();
        Bg.quick(() -> {
            final List<AppRepo.App> loaded = AppRepo.load(ctx);
            final List<Inputs.Input> ins = Inputs.list(ctx);
            final boolean liveTv = Intents.launchFor(ctx, Intents.TCL_LIVE_TV) != null;
            Bg.ui(() -> {
                if (isDestroyed()) return;
                apps = loaded;
                byPkg = new HashMap<>();
                for (AppRepo.App a : loaded) byPkg.put(a.pkg, a);
                inputs = ins;
                hasLiveTv = liveTv;
                rebuild();
                renderTiles();
            });
        });
    }

    private void renderTiles() {
        if (safeMode) return;
        final List<AppRepo.App> ordered = new ArrayList<>();
        for (String pkg : favourites()) {
            AppRepo.App a = byPkg.get(pkg);
            if (a != null) ordered.add(a);
        }
        for (AppRepo.App a : apps) if (!ordered.contains(a)) ordered.add(a);
        final Context ctx = getApplicationContext();
        final int w = tileW, h = tileH, gen = AppRepo.generation();
        final float radius = tileRadius;
        Bg.run(() -> AppRepo.renderTiles(ctx, ordered, w, h, radius, gen, () -> Bg.ui(view::tilesUpdated)));
    }

    private List<String> favourites() {
        List<String> favs = Prefs.favourites(this);
        return favs != null ? favs : Catalog.defaultFavourites(byPkg, apps);
    }

    private void rebuild() {
        List<HomeView.Row> rows = new ArrayList<>();

        HomeView.Row fav = new HomeView.Row("Favourites", HomeView.Tile.APP);
        for (String pkg : favourites()) {
            AppRepo.App a = byPkg.get(pkg);
            if (a != null) fav.tiles.add(app("fav:", a));
        }
        if (!fav.tiles.isEmpty()) rows.add(fav);

        HomeView.Row quick = new HomeView.Row("Quick actions", HomeView.Tile.ACTION);
        quick.tiles.add(action(A_MIRROR, "Mirror", Glyphs.CAST, "Cast from Android, iPhone, iPad or a laptop"));
        quick.tiles.add(action(A_CLEAN, "Clean up", Glyphs.SPARKLE, "Free memory and clear app caches"));
        quick.tiles.add(action(A_NETWORK, "Network", Glyphs.WIFI, "Speed test, private DNS and Wi-Fi"));
        quick.tiles.add(action(A_CONTROL, "Control", Glyphs.GAUGE, "Performance, security and apps"));
        quick.tiles.add(action(A_APPS, "Get apps", Glyphs.BAG, "Indian OTT apps and more from Google Play"));
        quick.tiles.add(action(A_ACCOUNT, "Account", Glyphs.PERSON, "Sign in with your Google account"));
        quick.tiles.add(action(A_SETTINGS, "Settings", Glyphs.GEAR, "Android TV settings"));
        rows.add(quick);

        if (hasLiveTv || !inputs.isEmpty()) {
            HomeView.Row in = new HomeView.Row("Inputs", HomeView.Tile.ACTION);
            if (hasLiveTv) in.tiles.add(action(A_LIVE_TV, "Live TV", Glyphs.TV, "Antenna and cable channels"));
            for (Inputs.Input i : inputs) {
                HomeView.Tile t = new HomeView.Tile(HomeView.Tile.ACTION, "in:" + i.id, i.label, Glyphs.INPUT, null, i);
                t.sub = "Switch to this input";
                in.tiles.add(t);
            }
            rows.add(in);
        }

        final int perRow = 5;
        for (int i = 0; i < apps.size(); i += perRow) {
            HomeView.Row row = new HomeView.Row(i == 0 ? "All apps" : null, HomeView.Tile.APP);
            for (int j = i; j < Math.min(apps.size(), i + perRow); j++) row.tiles.add(app("all:", apps.get(j)));
            rows.add(row);
        }

        if (Prefs.suggestions(this)) {
            HomeView.Row get = new HomeView.Row("Get more apps", HomeView.Tile.GET);
            for (Catalog.Entry e : Catalog.missing(byPkg, apps)) {
                HomeView.Tile t = new HomeView.Tile(HomeView.Tile.GET, "get:" + e.name, e.name, Glyphs.DOWNLOAD, null, e);
                t.sub = e.blurb + " · free on Google Play";
                t.color = Theme.AMBER;
                get.tiles.add(t);
            }
            if (!get.tiles.isEmpty()) rows.add(get);
        }

        view.setRows(rows);
    }

    private static HomeView.Tile app(String prefix, AppRepo.App a) {
        HomeView.Tile t = new HomeView.Tile(HomeView.Tile.APP, prefix + a.pkg, a.label, -1, a, null);
        t.sub = Catalog.blurbFor(a);
        return t;
    }

    private static HomeView.Tile action(String id, String label, int glyph, String sub) {
        HomeView.Tile t = new HomeView.Tile(HomeView.Tile.ACTION, "act:" + id, label, glyph, null, id);
        t.sub = sub;
        return t;
    }

    // ---------- clock and network ----------

    private void updateClock() {
        Date now = new Date();
        String pattern = DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm";
        String clock = new java.text.SimpleDateFormat(pattern, Locale.getDefault()).format(now);
        String date = new java.text.SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(now);
        view.setClock(clock, date);
    }

    private void postNetwork() {
        final Context ctx = getApplicationContext();
        Bg.quick(() -> {
            final NetTools.Status s = NetTools.status(ctx);
            Bg.ui(() -> {
                int glyph, color = Theme.TEXT;
                String text = s.label();
                if (s.type == NetTools.WIFI) glyph = Glyphs.WIFI;
                else if (s.type == NetTools.ETHERNET) glyph = Glyphs.ETHERNET;
                else glyph = s.type == NetTools.NONE ? Glyphs.OFFLINE : Glyphs.GLOBE;
                if (s.type == NetTools.NONE) color = Theme.BAD;
                else if (!s.validated) { color = Theme.WARN; text = text + " · no internet"; }
                else if (s.type == NetTools.WIFI && s.bars() <= 1) { color = Theme.WARN; text = text + " · weak"; }
                view.setNetwork(glyph, text, color, s.privateDns && s.privateDnsHost != null);
            });
        });
    }

    // ---------- actions ----------

    @Override
    public void onOpen(HomeView.Tile t) {
        if (t.type == HomeView.Tile.APP) {
            if (!Intents.start(this, AppRepo.launchIntent(t.app))) view.toast("Can't open " + t.label);
        } else if (t.type == HomeView.Tile.GET) {
            if (!Intents.playStore(this, (Catalog.Entry) t.ref)) view.toast("Play Store isn't available");
        } else if (t.ref instanceof Inputs.Input) {
            if (!Inputs.open(this, (Inputs.Input) t.ref) && !Intents.liveTv(this)) view.toast("Can't switch to " + t.label);
        } else {
            onAction((String) t.ref);
        }
    }

    private void onAction(String id) {
        switch (id) {
            case A_MIRROR: center("mirror"); break;
            case A_NETWORK: center("network"); break;
            case A_CONTROL: center(null); break;
            case A_APPS: center("get"); break;
            case A_ACCOUNT:
                if (!Intents.googleAccount(this)) view.toast("Account settings aren't available");
                break;
            case A_SETTINGS: Intents.settings(this); break;
            case A_LIVE_TV:
                if (!Intents.liveTv(this)) view.toast("Live TV isn't available");
                break;
            case A_CLEAN: cleanUp(); break;
            default: break;
        }
    }

    private void center(String page) {
        Intent i = new Intent(this, CenterActivity.class);
        if (page != null) i.putExtra(CenterActivity.EXTRA_PAGE, page);
        Intents.start(this, i);
    }

    private void cleanUp() {
        if (cleaning) return;
        cleaning = true;
        view.toast("Cleaning up…");
        final Context ctx = getApplicationContext();
        Bg.run(() -> {
            long ram = SystemTools.freeMemory(ctx);
            long disk;
            try {
                disk = SystemTools.clearCaches(ctx);
            } catch (IOException | RuntimeException e) {
                disk = 0;
            }
            final String msg = "Done · memory +" + Theme.bytes(ram) + " · storage +" + Theme.bytes(disk);
            Bg.ui(() -> {
                cleaning = false;
                view.toast(msg);
            });
        });
    }

    // ---------- long-press menu ----------

    @Override
    public List<String> menuFor(HomeView.Tile t) {
        List<String> out = new ArrayList<>();
        if (t.type == HomeView.Tile.APP) {
            List<String> favs = favourites();
            boolean isFav = favs.contains(t.app.pkg);
            out.add(isFav ? M_REMOVE_FAV : M_ADD_FAV);
            if (t.id.startsWith("fav:")) {
                int i = favs.indexOf(t.app.pkg);
                if (i > 0) out.add(M_LEFT);
                if (i >= 0 && i < favs.size() - 1) out.add(M_RIGHT);
            }
            out.add(M_INFO);
            if (!t.app.system) out.add(M_UNINSTALL);
        } else if (t.type == HomeView.Tile.GET) {
            out.add(M_STORE);
            out.add(M_HIDE_SUGGESTIONS);
        }
        return out;
    }

    @Override
    public void onMenu(HomeView.Tile t, String option) {
        if (ActivityManager.isUserAMonkey()) {
            view.toast("Home can't be changed during automated testing");
            return;
        }
        List<String> favs = new ArrayList<>(favourites());
        switch (option) {
            case M_ADD_FAV:
                if (!favs.contains(t.app.pkg)) favs.add(t.app.pkg);
                Prefs.setFavourites(this, favs);
                rebuild();
                view.toast(t.label + " added to favourites");
                break;
            case M_REMOVE_FAV:
                favs.remove(t.app.pkg);
                Prefs.setFavourites(this, favs);
                rebuild();
                break;
            case M_LEFT:
            case M_RIGHT: {
                int i = favs.indexOf(t.app.pkg);
                int j = option.equals(M_LEFT) ? i - 1 : i + 1;
                if (i >= 0 && j >= 0 && j < favs.size()) {
                    favs.set(i, favs.get(j));
                    favs.set(j, t.app.pkg);
                    Prefs.setFavourites(this, favs);
                    rebuild();
                }
                break;
            }
            case M_INFO:
                if (!Intents.appInfo(this, t.app.pkg)) view.toast("App info isn't available");
                break;
            case M_UNINSTALL:
                Intents.uninstall(this, t.app.pkg);
                break;
            case M_STORE:
                Intents.playStore(this, (Catalog.Entry) t.ref);
                break;
            case M_HIDE_SUGGESTIONS:
                Prefs.setSuggestions(this, false);
                rebuild();
                view.toast("Suggestions hidden · turn them back on in Control › Apps");
                break;
            default:
                break;
        }
    }
}

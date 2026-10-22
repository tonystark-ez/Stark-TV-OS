package io.github.starktvos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Apps people in India watch on TV. Installed ones become the default favourites; missing ones show up in
 * "Get more apps" and open in the Play Store (the only safe source: OTT apps need Play-certified DRM anyway).
 */
final class Catalog {
    static final class Entry {
        final String name;
        final String blurb;
        /** Known TV package names; the first is used for the Play Store page when {@link #verified}. */
        final String[] pkgs;
        /** Exact app labels to match when the package differs (regional or renamed builds). */
        final String[] labels;
        final boolean verified;
        final boolean favourite;

        Entry(String name, String blurb, boolean verified, boolean favourite, String[] pkgs, String... labels) {
            this.name = name;
            this.blurb = blurb;
            this.verified = verified;
            this.favourite = favourite;
            this.pkgs = pkgs;
            this.labels = labels;
        }
    }

    private static String[] p(String... pkgs) {
        return pkgs;
    }

    static final Entry[] ALL = {
        new Entry("YouTube", "Free videos", true, true, p("com.google.android.youtube.tv"), "YouTube"),
        new Entry("Netflix", "Movies & series", true, true, p("com.netflix.ninja"), "Netflix"),
        new Entry("Prime Video", "Amazon originals", true, true, p("com.amazon.amazonvideo.livingroom"), "Prime Video", "Amazon Prime Video"),
        new Entry("JioHotstar", "Cricket, Disney, HBO", true, true, p("in.startv.hotstar", "in.startv.hotstartvonly"), "JioHotstar", "Hotstar", "Disney+ Hotstar"),
        new Entry("JioCinema", "Jio originals", false, true, p("com.jio.media.stb.ondemand", "com.jio.media.ondemand"), "JioCinema"),
        new Entry("SonyLIV", "Sports & Sony shows", true, true, p("com.sonyliv"), "SonyLIV", "Sony LIV"),
        new Entry("ZEE5", "Zee shows & films", true, true, p("com.graymatrix.did"), "ZEE5"),
        new Entry("Amazon MX Player", "Free movies & series", false, true, p("com.mxtech.videoplayer.television", "com.mxtech.videoplayer.ad"), "MX Player", "Amazon MX Player"),
        new Entry("Sun NXT", "South Indian films", false, true, p("com.suntv.sunnxt"), "Sun NXT", "SunNXT"),
        new Entry("Aha", "Telugu & Tamil", false, false, p(), "aha"),
        new Entry("Hoichoi", "Bengali originals", false, false, p("com.viewlift.hoichoi"), "hoichoi", "Hoichoi"),
        new Entry("WAVES", "Prasar Bharati, DD channels", true, true, p("com.ddnational.prasarbharati"), "WAVES", "Waves"),
        new Entry("Apple TV", "Apple originals", true, true, p("com.apple.atve.androidtv.appletv"), "Apple TV"),
        new Entry("Discovery+", "Documentaries", false, false, p(), "discovery+"),
        new Entry("Lionsgate Play", "Hollywood films", false, false, p(), "Lionsgate Play"),
        new Entry("YouTube Music", "Music", true, true, p("com.google.android.youtube.tvmusic"), "YouTube Music"),
        new Entry("Spotify", "Music & podcasts", true, true, p("com.spotify.tv.android"), "Spotify"),
        new Entry("YouTube Kids", "Safe videos for children", true, false, p("com.google.android.youtube.tvkids"), "YouTube Kids"),
        new Entry("AirScreen", "AirPlay from iPhone & Mac", true, false, p("com.ionitech.airscreen"), "AirScreen"),
        new Entry("VLC", "Plays any video file", true, false, p("org.videolan.vlc"), "VLC"),
        new Entry("Jellyfin", "Your own media server", true, true, p("org.jellyfin.androidtv"), "Jellyfin"),
        new Entry("Plex", "Your own media server", true, false, p("com.plexapp.android"), "Plex"),
        new Entry("TV Bro", "Web browser for TV", true, false, p("com.phlox.tvwebbrowser"), "TV Bro"),
        new Entry("Send Files to TV", "Phone-to-TV file transfer", true, false, p("com.yablio.sendfilestotv"), "Send files to TV", "Send Files To TV"),
        new Entry("Google Play Store", "All apps", true, true, p("com.android.vending"), "Google Play Store", "Play Store"),
    };

    private Catalog() {}

    /** The installed app for this entry: by package first, then by exact label. */
    static AppRepo.App find(Entry e, Map<String, AppRepo.App> byPkg, List<AppRepo.App> apps) {
        for (String pkg : e.pkgs) {
            AppRepo.App a = byPkg.get(pkg);
            if (a != null) return a;
        }
        for (AppRepo.App a : apps) {
            for (String l : e.labels) if (a.label.equalsIgnoreCase(l)) return a;
        }
        return null;
    }

    static List<String> defaultFavourites(Map<String, AppRepo.App> byPkg, List<AppRepo.App> apps) {
        List<String> out = new ArrayList<>();
        for (Entry e : ALL) {
            if (!e.favourite) continue;
            AppRepo.App a = find(e, byPkg, apps);
            if (a != null && !out.contains(a.pkg)) out.add(a.pkg);
        }
        return out;
    }

    static List<Entry> missing(Map<String, AppRepo.App> byPkg, List<AppRepo.App> apps) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : ALL) if (find(e, byPkg, apps) == null) out.add(e);
        return out;
    }

    /** The catalog's one-line description of an installed app, or null if it isn't in the catalog. */
    static String blurbFor(AppRepo.App a) {
        for (Entry e : ALL) {
            for (String pkg : e.pkgs) if (pkg.equals(a.pkg)) return e.blurb;
            for (String l : e.labels) if (a.label.equalsIgnoreCase(l)) return e.blurb;
        }
        return null;
    }

    static String searchQuery(Entry e) {
        return e.name.toLowerCase(Locale.ROOT);
    }
}

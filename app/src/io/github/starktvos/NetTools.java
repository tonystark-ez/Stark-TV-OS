package io.github.starktvos;

import android.content.ContentResolver;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

/** Connection status, speed test and encrypted DNS. */
final class NetTools {
    static final int NONE = 0, WIFI = 1, ETHERNET = 2, OTHER = 3;

    /** Encrypted DNS (DNS-over-TLS) providers. Index 0 is Android's own automatic mode. */
    static final String[] DNS_NAMES = {"Automatic", "Cloudflare", "AdGuard (blocks ads & trackers)", "Quad9 (blocks malware)", "Google"};
    static final String[] DNS_HOSTS = {null, "one.one.one.one", "dns.adguard-dns.com", "dns.quad9.net", "dns.google"};

    static final class Status {
        int type = NONE;
        boolean validated;
        String ip, gateway, dns, ssid;
        int rssi = Integer.MIN_VALUE, linkMbps = -1, freqMhz = -1;
        boolean privateDns;
        String privateDnsHost;

        String label() {
            switch (type) {
                case WIFI: return "Wi-Fi";
                case ETHERNET: return "Ethernet";
                case OTHER: return "Connected";
                default: return "Offline";
            }
        }

        /** 0..4 bars from RSSI. */
        int bars() {
            if (rssi == Integer.MIN_VALUE) return 0;
            return WifiManager.calculateSignalLevel(rssi, 5);
        }
    }

    private NetTools() {}

    static Status status(Context c) {
        Status s = new Status();
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm.getActiveNetwork();
        if (n == null) return s;
        NetworkCapabilities nc = cm.getNetworkCapabilities(n);
        LinkProperties lp = cm.getLinkProperties(n);
        if (nc == null) return s;
        if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) s.type = WIFI;
        else if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) s.type = ETHERNET;
        else s.type = OTHER;
        s.validated = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        if (lp != null) {
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address) s.ip = la.getAddress().getHostAddress();
            }
            for (RouteInfo r : lp.getRoutes()) {
                if (r.isDefaultRoute() && r.getGateway() instanceof Inet4Address) s.gateway = r.getGateway().getHostAddress();
            }
            List<String> dns = new ArrayList<>();
            for (InetAddress a : lp.getDnsServers()) dns.add(a.getHostAddress());
            s.dns = android.text.TextUtils.join(", ", dns);
            if (Build.VERSION.SDK_INT >= 28) {
                s.privateDns = lp.isPrivateDnsActive();
                s.privateDnsHost = lp.getPrivateDnsServerName();
            }
        }
        if (s.type == WIFI) {
            WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            @SuppressWarnings("deprecation")
            WifiInfo wi = wm == null ? null : wm.getConnectionInfo();
            if (wi != null) {
                s.rssi = wi.getRssi();
                s.linkMbps = wi.getLinkSpeed();
                s.freqMhz = wi.getFrequency();
                String ssid = wi.getSSID();
                if (ssid != null && !ssid.contains("unknown ssid")) s.ssid = ssid.replace("\"", "");
            }
        }
        return s;
    }

    /** Index into {@link #DNS_HOSTS} for what the network uses now. */
    static int dnsChoice(Status s) {
        if (s.privateDnsHost == null) return 0;
        int i = Arrays.asList(DNS_HOSTS).indexOf(s.privateDnsHost);
        return i < 0 ? 0 : i;
    }

    /**
     * Switches encrypted DNS. If the network can't reach the server within a few seconds (some ISPs block port 853),
     * it goes back to automatic so the TV never ends up offline. Returns null on success or the reason it failed.
     */
    static String setDns(Context c, int choice) {
        ContentResolver r = c.getContentResolver();
        String host = DNS_HOSTS[choice];
        try {
            if (host == null) {
                Settings.Global.putString(r, "private_dns_mode", "opportunistic");
                return null;
            }
            Settings.Global.putString(r, "private_dns_specifier", host);
            Settings.Global.putString(r, "private_dns_mode", "hostname");
        } catch (SecurityException e) {
            return "needs ./stark install";
        }
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        long deadline = SystemClock.elapsedRealtime() + 10_000;
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(700);
            Network n = cm.getActiveNetwork();
            if (n == null) continue;
            LinkProperties lp = cm.getLinkProperties(n);
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (lp == null || nc == null) continue;
            if (host.equals(lp.getPrivateDnsServerName()) && lp.isPrivateDnsActive()
                    && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                if (resolves()) return null;
            }
        }
        Settings.Global.putString(r, "private_dns_mode", "opportunistic");
        return "your network blocks it; back to automatic";
    }

    private static boolean resolves() {
        try {
            InetAddress.getByName("www.google.com");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Median TCP connect time to Cloudflare in ms, or -1 when unreachable. */
    static int latencyMs() {
        long[] samples = new long[5];
        int ok = 0;
        for (int i = 0; i < samples.length; i++) {
            long t0 = SystemClock.elapsedRealtimeNanos();
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("1.1.1.1", 443), 3000);
                samples[ok++] = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L;
            } catch (IOException e) {
                // count as lost
            }
        }
        if (ok == 0) return -1;
        long[] got = Arrays.copyOf(samples, ok);
        Arrays.sort(got);
        return (int) got[ok / 2];
    }

    /** Download speed in Mbps from Cloudflare's speed test, at most ~8 seconds. -1 on failure. */
    static double downloadMbps() {
        HttpsURLConnection conn = null;
        try {
            conn = (HttpsURLConnection) new URL("https://speed.cloudflare.com/__down?bytes=50000000").openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Stark-TV-OS");
            byte[] buf = new byte[64 * 1024];
            long bytes = 0, start = 0, end;
            try (InputStream in = conn.getInputStream()) {
                long first = SystemClock.elapsedRealtime();
                int n;
                while ((n = in.read(buf)) > 0) {
                    long now = SystemClock.elapsedRealtime();
                    // Skip the first half second: TCP is still ramping up.
                    if (start == 0 && now - first > 500) start = now;
                    if (start != 0) bytes += n;
                    if (now - first > 8000) break;
                }
                end = SystemClock.elapsedRealtime();
            }
            if (start == 0 || end <= start) return -1;
            return bytes * 8.0 / ((end - start) / 1000.0) / 1_000_000.0;
        } catch (IOException | RuntimeException e) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** Asks Android to re-check the connection (re-validates and refreshes DNS). */
    static void refresh(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm.getActiveNetwork();
        if (n != null) cm.reportNetworkConnectivity(n, false);
    }
}

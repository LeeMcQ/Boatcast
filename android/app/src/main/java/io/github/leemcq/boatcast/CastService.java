package io.github.leemcq.boatcast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Keeps one broadcast alive with the screen off: the phone's own hotspot
 * (LocalOnlyHotspot), Wi-Fi and CPU locks, and the bundled Rust server process.
 *
 * Host mode runs "boatcast serve"; repeater mode runs "boatcast relay --upstream ...",
 * which follows the host over Wi-Fi and re-serves the slides on this phone's hotspot.
 */
public class CastService extends Service {
    static final String ACTION_HOST = "io.github.leemcq.boatcast.HOST";
    static final String ACTION_RELAY = "io.github.leemcq.boatcast.RELAY";
    static final String EXTRA_UPSTREAM = "upstream";
    static final int PORT = 8080;
    private static final String TAG = "boatcast";
    private static final String CHANNEL = "cast";

    interface Listener {
        void onStatus(String status, boolean running, String consoleUrl);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile String status = "Ready: tap Host or Repeater.";
    private static volatile boolean running = false;
    private static volatile String consoleUrl = null;
    private static Listener listener;

    static void setListener(Listener l) {
        listener = l;
        if (l != null) l.onStatus(status, running, consoleUrl);
    }

    static boolean isRunning() {
        return running;
    }

    private static void publish(String s, boolean r, String url) {
        status = s;
        running = r;
        consoleUrl = url;
        MAIN.post(() -> {
            Listener l = listener;
            if (l != null) l.onStatus(status, running, consoleUrl);
        });
    }

    private WifiManager wifi;
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private WifiManager.WifiLock highPerfLock;
    private WifiManager.WifiLock lowLatencyLock;
    private PowerManager.WakeLock wakeLock;
    private volatile Process proc;
    private volatile boolean stopping;
    private volatile String error;
    private boolean started;
    private String mode;
    private String upstream;

    @Override
    public void onCreate() {
        super.onCreate();
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Always go foreground first: Android kills a service started with
        // startForegroundService() that does not do this within a few seconds.
        startForeground(1, notification(started ? "Broadcasting" : "Starting…"));
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (started) return START_NOT_STICKY; // already broadcasting
        started = true;
        mode = action;
        upstream = intent.getStringExtra(EXTRA_UPSTREAM);
        acquireLocks();
        startHotspot();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        Process p = proc;
        proc = null;
        if (p != null) p.destroy();
        if (reservation != null) {
            reservation.close();
            reservation = null;
        }
        release(highPerfLock);
        release(lowLatencyLock);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        publish(error != null ? error : "Stopped", false, null);
        super.onDestroy();
    }

    private boolean isRelay() {
        return ACTION_RELAY.equals(mode);
    }

    // ---------------------------------------------------------------- hotspot

    private void startHotspot() {
        publish("Starting this phone's hotspot…", true, null);
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                    if (stopping) {
                        r.close();
                        return;
                    }
                    reservation = r;
                    final String[] cred = credentials(r);
                    new Thread(() -> launch(cred[0], cred[1]), "boatcast-launch").start();
                }

                @Override
                public void onStopped() {
                    fail("Android stopped the hotspot.");
                }

                @Override
                public void onFailed(int reason) {
                    fail(hotspotError(reason));
                }
            }, MAIN);
        } catch (RuntimeException e) {
            // SecurityException (permission or Location off) or IllegalStateException
            fail("Could not start the hotspot: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private static String[] credentials(WifiManager.LocalOnlyHotspotReservation r) {
        String ssid = null, pass = null;
        if (Build.VERSION.SDK_INT >= 30) {
            SoftApConfiguration c = r.getSoftApConfiguration();
            if (c != null) {
                ssid = c.getSsid();
                pass = c.getPassphrase();
            }
        } else {
            WifiConfiguration c = r.getWifiConfiguration();
            if (c != null) {
                ssid = c.SSID;
                pass = c.preSharedKey;
            }
        }
        return new String[] {unquote(ssid), unquote(pass)};
    }

    private static String unquote(String s) {
        if (s != null && s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static String hotspotError(int reason) {
        switch (reason) {
            case WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE:
                return "Hotspot refused: turn off the phone's normal hotspot (and tethering) first.";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED:
                return "Hotspot refused: this phone does not allow hotspots (work profile or carrier lock).";
            case WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL:
                return "Hotspot refused: no free Wi-Fi channel. Try again.";
            default:
                return "Hotspot could not start (code " + reason + "). Try again.";
        }
    }

    // ----------------------------------------------------------------- server

    private void launch(String ssid, String pass) {
        String ip = waitForHotspotIp(8000);
        if (ip == null) {
            fail("The hotspot started but has no address. Stop and try again.");
            return;
        }
        File bin = new File(getApplicationInfo().nativeLibraryDir, "libboatcast.so");
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.getAbsolutePath());
        if (isRelay()) {
            cmd.add("relay");
            cmd.add("--upstream");
            cmd.add(upstream);
        } else {
            cmd.add("serve");
            cmd.add("--deck");
            cmd.add(MainActivity.deckDir(this).getAbsolutePath());
        }
        cmd.addAll(Arrays.asList("--port", String.valueOf(PORT), "--host-ip", ip));
        if (ssid != null) {
            cmd.add("--wifi-ssid");
            cmd.add(ssid);
        }
        if (pass != null) {
            cmd.add("--wifi-pass");
            cmd.add(pass);
        }
        Process p;
        try {
            p = new ProcessBuilder(cmd).directory(getFilesDir()).redirectErrorStream(true).start();
        } catch (IOException e) {
            fail("Could not start the server: " + e.getMessage());
            return;
        }
        if (stopping) {
            p.destroy();
            return;
        }
        proc = p;

        String join = "http://" + ip + ":" + PORT + "/";
        String what = isRelay() ? "Repeating " + upstream : "Hosting";
        String line1 = what + "\nWi-Fi: " + (ssid == null ? "?" : ssid) + "\nPhones open: " + join;
        publish(line1, true, "http://127.0.0.1:" + PORT + "/console.html");
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1, notification(what + " · Wi-Fi " + ssid));

        // Pump the server's log to logcat; if the stream ends, the server has exited.
        String last = "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                Log.i(TAG, line);
                if (!line.trim().isEmpty()) last = line.trim();
            }
        } catch (IOException ignored) {
            // stream closed on stop
        }
        if (!stopping) fail("The server stopped: " + last);
    }

    /** The hotspot gets its address a moment after onStarted; wait for it. */
    private String waitForHotspotIp(long ms) {
        String sta = stationIp();
        long start = SystemClock.elapsedRealtime(), end = start + ms;
        while (SystemClock.elapsedRealtime() < end && !stopping) {
            // Wi-Fi-named interfaces first; anything else only near the end of the wait,
            // so a stray interface (emulator eth0, a vendor modem) never beats the hotspot.
            boolean late = SystemClock.elapsedRealtime() - start > ms * 3 / 4;
            String ip = hotspotIp(sta, late);
            if (ip != null) return ip;
            SystemClock.sleep(250);
        }
        return null;
    }

    /** This phone's address on the Wi-Fi it has joined (the upstream), or null. */
    @SuppressWarnings("deprecation")
    private String stationIp() {
        WifiInfo info = wifi.getConnectionInfo();
        int i = info == null ? 0 : info.getIpAddress();
        if (i == 0) return null;
        return (i & 0xff) + "." + (i >> 8 & 0xff) + "." + (i >> 16 & 0xff) + "." + (i >> 24 & 0xff);
    }

    /**
     * The hotspot interface's IPv4 address. Names differ by maker (ap0, swlan0, wlan1,
     * softap0, sometimes wlan0), so: known hotspot names first, then other Wi-Fi names,
     * then (only if allowed) any other private address. Never the station address and
     * never a mobile-data interface.
     */
    static String hotspotIp(String skip, boolean allowOther) {
        String wifiLike = null, other = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                if (isMobileData(n)) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    if (ip == null || ip.equals(skip)) continue;
                    if (n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap")
                            || n.equals("wlan1") || n.equals("wlan2")) {
                        return ip;
                    }
                    if (n.startsWith("wlan") || n.startsWith("wl") || n.startsWith("wifi")) {
                        if (wifiLike == null) wifiLike = ip;
                    } else if (other == null) {
                        other = ip;
                    }
                }
            }
        } catch (SocketException ignored) {
            // no interfaces yet
        }
        if (wifiLike != null) return wifiLike;
        return allowOther ? other : null;
    }

    private static boolean isMobileData(String n) {
        for (String p : new String[] {"rmnet", "r_rmnet", "rev_rmnet", "ccmni", "seth", "ccinet", "pdp",
                "ppp", "wwan", "tun", "dummy", "p2p", "rndis", "usb", "v4-", "clat"}) {
            if (n.startsWith(p)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ misc

    private void fail(String message) {
        Log.w(TAG, message);
        MAIN.post(() -> {
            if (stopping) return;
            error = message;
            stopSelf();
        });
    }

    @SuppressWarnings("deprecation")
    private void acquireLocks() {
        // Keep the radio out of power save: each Wi-Fi hop otherwise adds up to a few
        // hundred ms while the chip sleeps between beacons.
        highPerfLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "boatcast:wifi");
        highPerfLock.setReferenceCounted(false);
        highPerfLock.acquire();
        if (Build.VERSION.SDK_INT >= 29) {
            lowLatencyLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "boatcast:lowlatency");
            lowLatencyLock.setReferenceCounted(false);
            lowLatencyLock.acquire();
        }
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "boatcast:cpu");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private static void release(WifiManager.WifiLock lock) {
        if (lock != null && lock.isHeld()) lock.release();
    }

    private Notification notification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                    new NotificationChannel(CHANNEL, "Broadcasting", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Boatcast is on")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .build();
    }
}

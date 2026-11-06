package io.github.leemcq.boatcast;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps a broadcast alive with the screen off.
 *
 * HOST: this phone's hotspot + the Rust server ("serve"), advertised over Bluetooth so
 * phones in Join mode find it without scanning a code.
 *
 * JOIN (mesh): this phone finds the best nearby host or repeater (Bluetooth), joins its
 * Wi-Fi by itself, shows the slides, and, when the mesh needs room and the phone can,
 * becomes a repeater (own hotspot + "boatcast relay"). Every few seconds it re-checks:
 * a dead parent is replaced, a much better one is taken, a low battery hands over.
 */
public class CastService extends Service {
    static final String ACTION_HOST = "io.github.leemcq.boatcast.HOST";
    static final String ACTION_JOIN = "io.github.leemcq.boatcast.JOIN";
    static final int PORT = 8080;
    private static final String TAG = "boatcast";
    private static final String CHANNEL = "cast";
    private static final long TICK_MS = 4000;

    interface Listener {
        /** pageUrl: the page to show (console for a host, slides for a joined phone), or null. */
        void onStatus(String status, boolean running, String pageUrl);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile String status = "Ready: tap Host or Join.";
    private static volatile boolean running = false;
    private static volatile String pageUrl = null;
    private static Listener listener;

    static void setListener(Listener l) {
        listener = l;
        if (l != null) l.onStatus(status, running, pageUrl);
    }

    static boolean isRunning() {
        return running;
    }

    static volatile boolean hosting;

    private static void publish(String s, boolean r, String url) {
        status = s;
        running = r;
        pageUrl = url;
        MAIN.post(() -> {
            Listener l = listener;
            if (l != null) l.onStatus(status, running, pageUrl);
        });
    }

    // ------------------------------------------------------------- state

    private HandlerThread thread;
    private Handler bg;
    private WifiManager wifi;
    private ConnectivityManager cm;
    private MeshRadio radio;
    private ServerProc server;
    private WifiManager.LocalOnlyHotspotReservation reservation;
    private WifiManager.WifiLock highPerfLock, lowLatencyLock;
    private PowerManager.WakeLock wakeLock;
    private final List<Integer> addedNetworks = new ArrayList<>();
    private volatile boolean stopping;
    private boolean started;
    private String mode;
    private int myId;
    private int myMesh;

    // hotspot (host, or repeater)
    private boolean hotspotStarting;
    private String apSsid, apPass, apIp;
    private final List<Long> hotspotRestarts = new ArrayList<>();

    // join mode
    private String parentUrl;          // http://ip:port of the node we follow
    private int parentId = -1;         // its Bluetooth id, -1 when found through the Wi-Fi gateway
    private int parentDepth = MeshPlanner.SEEKING;
    private int parentFree = MeshPlanner.CAPACITY;
    private int parentFails;
    private boolean joining;
    private long lastSwitch = -MeshPlanner.MIN_SWITCH_GAP_MS;
    private long orphanSince;
    private int oldDepth = MeshPlanner.SEEKING;
    private final Map<Integer, Long> avoid = new HashMap<>();
    private final Map<String, Long> avoidUrl = new HashMap<>();
    private boolean repeating;
    private String relayUpstream;
    private long cannotRepeatUntil;
    private String shownPage;

    @Override
    public void onCreate() {
        super.onCreate();
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        thread = new HandlerThread("boatcast-mesh");
        thread.start();
        bg = new Handler(thread.getLooper());
        radio = new MeshRadio(this, bg);
        SharedPreferences prefs = getSharedPreferences("boatcast", MODE_PRIVATE);
        myId = prefs.getInt("node", 0);
        if (myId == 0) {
            myId = 1 + new SecureRandom().nextInt(65534);
            prefs.edit().putInt("node", myId).apply();
        }
        File bin = new File(getApplicationInfo().nativeLibraryDir, "libboatcast.so");
        server = new ServerProc(bin.getAbsolutePath(), getFilesDir(), PORT, new ServerProc.Listener() {
            @Override
            public void onReady() {
                bg.post(CastService.this::onServerReady);
            }

            @Override
            public void onGaveUp(String lastLine) {
                bg.post(() -> {
                    if (ACTION_HOST.equals(mode)) fail("The server keeps stopping: " + lastLine);
                    else stopRepeating("server keeps stopping");
                });
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Always go foreground first: Android kills a service started with
        // startForegroundService() that does not do this within a few seconds.
        startForeground(1, notification(started ? "On" : "Starting…"));
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (started) return START_NOT_STICKY;
        started = true;
        mode = action;
        acquireLocks();
        hosting = ACTION_HOST.equals(mode);
        if (ACTION_HOST.equals(mode)) {
            myMesh = 1 + new SecureRandom().nextInt(65534);
            bg.post(this::startHotspot);
        } else {
            publish("Looking for a Boatcast show nearby…", true, null);
            bg.post(this::tick);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopping = true;
        hosting = false;
        server.stop();
        bg.removeCallbacksAndMessages(null);
        bg.post(() -> {
            radio.stop();
            closeHotspot();
            for (int id : addedNetworks) {
                try {
                    wifi.removeNetwork(id);
                } catch (RuntimeException ignored) {
                    // not ours any more
                }
            }
        });
        thread.quitSafely();
        try {
            cm.bindProcessToNetwork(null);
        } catch (RuntimeException ignored) {
            // nothing bound
        }
        release(highPerfLock);
        release(lowLatencyLock);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (status == null || !status.startsWith("Stopped:")) publish("Stopped", false, null);
        super.onDestroy();
    }

    private boolean isHost() {
        return ACTION_HOST.equals(mode);
    }

    // ------------------------------------------------------------ hotspot

    private void startHotspot() {
        if (stopping || hotspotStarting || reservation != null) return;
        hotspotStarting = true;
        if (isHost()) publish("Starting this phone's hotspot…", true, null);
        try {
            wifi.startLocalOnlyHotspot(new WifiManager.LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
                    hotspotStarting = false;
                    if (stopping) {
                        r.close();
                        return;
                    }
                    reservation = r;
                    String[] cred = credentials(r);
                    apSsid = cred[0];
                    apPass = cred[1];
                    apIp = waitForHotspotIp(8000);
                    if (apIp == null) {
                        closeHotspot();
                        onHotspotLost("the hotspot got no address");
                        return;
                    }
                    startServer();
                }

                @Override
                public void onStopped() {
                    reservation = null;
                    onHotspotLost("Android stopped the hotspot");
                }

                @Override
                public void onFailed(int reason) {
                    hotspotStarting = false;
                    onHotspotFailed(reason);
                }
            }, bg);
        } catch (RuntimeException e) {
            // SecurityException (permission or Location off) or IllegalStateException
            hotspotStarting = false;
            if (isHost()) fail("Could not start the hotspot: " + e.getMessage());
            else cannotRepeatUntil = SystemClock.elapsedRealtime() + 600_000;
        }
    }

    private void onHotspotFailed(int reason) {
        if (isHost()) {
            fail(hotspotError(reason));
        } else {
            // Not fatal in Join mode: this phone just does not repeat for a while.
            Log.w(TAG, "mesh: hotspot refused (" + reason + "); not repeating for 10 min");
            cannotRepeatUntil = SystemClock.elapsedRealtime() + 600_000;
        }
    }

    /** The hotspot went away. Host: bring it back (a few times). Repeater: stand down. */
    private void onHotspotLost(String why) {
        if (stopping) return;
        server.stop();
        radio.stopServingCredentials();
        radio.stopAdvertising();
        if (!isHost()) {
            repeating = false;
            relayUpstream = null;
            Log.w(TAG, "mesh: " + why);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        hotspotRestarts.removeIf(t -> now - t > 300_000);
        hotspotRestarts.add(now);
        if (hotspotRestarts.size() > 3) {
            fail(why + ", again. Stop and start Host.");
            return;
        }
        publish(why + ". Restarting it…", true, null);
        bg.postDelayed(this::startHotspot, 3000);
    }

    private void closeHotspot() {
        if (reservation != null) {
            try {
                reservation.close();
            } catch (RuntimeException ignored) {
                // already gone
            }
            reservation = null;
        }
    }

    private void startServer() {
        List<String> args = new ArrayList<>();
        if (isHost()) {
            args.add("serve");
            args.add("--deck");
            args.add(MainActivity.deckDir(this).getAbsolutePath());
            args.add("--mesh-id");
            args.add(String.valueOf(myMesh));
        } else {
            if (parentUrl == null) { // lost our parent while the hotspot was starting
                closeHotspot();
                return;
            }
            args.add("relay");
            args.add("--upstream");
            args.add(parentUrl);
            relayUpstream = parentUrl;
        }
        args.addAll(Arrays.asList("--port", String.valueOf(PORT), "--host-ip", apIp));
        if (apSsid != null) {
            args.add("--wifi-ssid");
            args.add(apSsid);
        }
        if (apPass != null) {
            args.add("--wifi-pass");
            args.add(apPass);
        }
        server.start(args);
    }

    /** Server answers: show the console (host), start advertising, serve credentials. */
    private void onServerReady() {
        if (stopping) return;
        try {
            JSONObject j = new JSONObject();
            j.put("ssid", apSsid);
            j.put("pass", apPass);
            j.put("ip", apIp);
            j.put("port", PORT);
            radio.serveCredentials(j.toString());
        } catch (JSONException ignored) {
            // cannot happen with strings and ints
        }
        String join = "http://" + apIp + ":" + PORT + "/";
        if (isHost()) {
            hotspotRestarts.clear();
            publish("Hosting\nWi-Fi: " + apSsid + "\nPhones open: " + join, true,
                    "http://127.0.0.1:" + PORT + "/console.html");
            notifyText("Hosting · Wi-Fi " + apSsid);
            bg.removeCallbacks(hostTick);
            bg.post(hostTick);
        } else {
            repeating = true;
            Log.i(TAG, "mesh: repeating on " + apSsid + " " + join);
        }
    }

    /** Host: keep the Bluetooth advert current (room left, battery). */
    private final Runnable hostTick = new Runnable() {
        @Override
        public void run() {
            if (stopping) return;
            int clients = localClients();
            int free = MeshPlanner.CAPACITY - Math.max(0, clients);
            int[] bat = battery();
            if (server.running()) {
                radio.advertise(MeshPlanner.encode(myMesh, myId, 0, free, bat[0], bat[1] == 1, free > 0), true);
            }
            bg.postDelayed(this, 5000);
        }
    };

    // --------------------------------------------------------- join / mesh

    private void tick() {
        if (stopping) return;
        try {
            step();
        } catch (RuntimeException e) {
            Log.w(TAG, "mesh: tick failed", e);
        }
        bg.postDelayed(this::tick, TICK_MS);
    }

    private void step() {
        long now = SystemClock.elapsedRealtime();
        radio.scan(parentUrl == null);
        bindToWifi();
        if (joining) return;

        // 1. Is our parent still there, and still connected to the host?
        if (parentUrl != null) {
            JSONObject st = statusOf(parentUrl);
            Integer depth = st == null ? null : optDepth(st);
            if (depth != null) {
                parentFails = 0;
                parentDepth = depth;
                parentFree = MeshPlanner.CAPACITY - st.optInt("clients", 0);
                int mesh = st.optInt("mesh", 0);
                if (mesh != 0) myMesh = mesh;
            } else if (++parentFails >= 3) {
                Log.w(TAG, "mesh: lost parent " + parentUrl);
                if (parentId >= 0) avoid.put(parentId, now + 60_000);
                avoidUrl.put(parentUrl, now + 60_000);
                oldDepth = parentDepth + 1;
                orphanSince = now;
                parentUrl = null;
                parentId = -1;
                parentDepth = MeshPlanner.SEEKING;
            }
        }

        // 2. No parent: the Wi-Fi we are on (joined by QR code), else the best node heard.
        if (parentUrl == null) {
            String gw = gatewayBoatcast(now);
            if (gw != null) {
                adopt(gw, -1, now);
            } else {
                int maxDepth = MeshPlanner.maxParentDepth(repeating && localClients() > 0, true, oldDepth, now - orphanSince);
                MeshPlanner.Node best = MeshPlanner.choose(radio.nodes(), myMesh, myId, maxDepth, avoid, now);
                if (best != null) {
                    joinNode(best);
                    return;
                }
                // Cut off: a repeater stops attracting phones (loop guard); a lone phone says it is looking.
                if (repeating) radio.stopAdvertising();
                else radio.advertise(MeshPlanner.encode(myMesh, myId, MeshPlanner.SEEKING, 0, battery()[0], false, false), false);
                publish(radio.available()
                        ? "Looking for a Boatcast show nearby…\nOr scan a Wi-Fi code from the host or a repeater."
                        : "Turn Bluetooth on to find the show by itself,\nor scan the Wi-Fi code from the host or a repeater.",
                        true, repeating ? "http://127.0.0.1:" + PORT + "/" : null);
                return;
            }
        }

        // 3. Connected. Move to a much better parent? (only to a shallower one when serving phones)
        int myDepth = parentDepth + 1;
        int children = repeating ? localClients() : 0;
        MeshPlanner.Node current = null;
        for (MeshPlanner.Node n : radio.nodes()) if (n.id == parentId) current = n;
        if (current == null) {
            current = new MeshPlanner.Node(myMesh, parentId, parentDepth, parentFree, 100, true, true, wifiRssi(), now);
        }
        int maxDepth = MeshPlanner.maxParentDepth(children > 0, false, myDepth, 0);
        MeshPlanner.Node best = MeshPlanner.choose(radio.nodes(), myMesh, myId, maxDepth, avoid, now);
        if (MeshPlanner.shouldSwitch(current, best, now - lastSwitch)) {
            Log.i(TAG, "mesh: moving to " + best + " from " + current);
            joinNode(best);
            return;
        }

        // 4. Repeat? (needs Wi-Fi + hotspot at once, and Wi-Fi as the phone's main network)
        int room = 0, seekers = 0;
        for (MeshPlanner.Node n : radio.nodes()) {
            if (n.id == myId) continue;
            if (n.seeking()) {
                if (n.rssi >= MeshPlanner.MIN_RSSI) seekers++;
            } else if (n.accepting && n.free > 0 && n.rssi > -75 && (myMesh == 0 || n.mesh == myMesh)) {
                room++;
            }
        }
        int[] bat = battery();
        boolean capable = canRepeat(now);
        boolean should = MeshPlanner.shouldRepeat(capable, bat[0], bat[1] == 1, repeating, children, room, seekers);
        if (should && !repeating && !hotspotStarting && reservation == null) {
            Log.i(TAG, "mesh: becoming a repeater (" + seekers + " looking, " + room + " with room)");
            startHotspot();
        } else if (!should && (repeating || reservation != null)) {
            stopRepeating(bat[0] < 30 && bat[1] == 0 ? "battery low" : "enough repeaters nearby");
        }

        // 5. Keep the relay on our current parent, and our advert current.
        if (repeating) {
            if (!parentUrl.equals(relayUpstream) && server.control("{\"action\":\"upstream\",\"url\":\"" + parentUrl + "\"}")) {
                relayUpstream = parentUrl;
            }
            JSONObject local = statusOf("http://127.0.0.1:" + PORT);
            Integer d = local == null ? null : optDepth(local);
            int free = MeshPlanner.CAPACITY - children;
            if (d != null) radio.advertise(MeshPlanner.encode(myMesh, myId, d, free, bat[0], bat[1] == 1, free > 0), true);
            else radio.stopAdvertising(); // cut off from the host: do not attract phones
        } else {
            radio.stopAdvertising();
        }

        String page = repeating ? "http://127.0.0.1:" + PORT + "/" : parentUrl + "/";
        String role = repeating ? "Watching and repeating (" + children + " phone" + (children == 1 ? "" : "s") + " on this one)"
                : "Watching";
        String hint = repeating ? "\nOthers can scan the codes on the host screen or ask this phone's owner." : "";
        if (!capable && !isWifiDefault()) hint = "\nTurn mobile data off so this phone can also repeat.";
        publish(role + " · " + (myDepth == 1 ? "1 hop" : myDepth + " hops") + " from the host" + hint, true, page);
        if (!page.equals(shownPage)) {
            shownPage = page;
            notifyText(role);
        }
    }

    private void adopt(String url, int id, long now) {
        parentUrl = url;
        parentId = id;
        parentFails = 0;
        lastSwitch = now;
        Log.i(TAG, "mesh: following " + url);
    }

    /** Fetch the node's hotspot credentials over Bluetooth, join its Wi-Fi, check it answers. */
    private void joinNode(MeshPlanner.Node n) {
        joining = true;
        publish("Joining a " + (n.depth == 0 ? "host" : "repeater") + " nearby…", true, pageUrl);
        radio.fetchCredentials(n.id, json -> {
            if (json == null) {
                avoid.put(n.id, SystemClock.elapsedRealtime() + 30_000);
                joining = false;
                return;
            }
            new Thread(() -> {
                String url = null;
                try {
                    JSONObject c = new JSONObject(json);
                    String ip = c.getString("ip");
                    String candidate = "http://" + ip + ":" + c.optInt("port", PORT);
                    if (joinWifi(c.getString("ssid"), c.getString("pass"), candidate)) url = candidate;
                } catch (JSONException e) {
                    Log.w(TAG, "mesh: bad credentials from node " + n.id);
                }
                final String joined = url;
                bg.post(() -> {
                    long now = SystemClock.elapsedRealtime();
                    if (joined != null) adopt(joined, n.id, now);
                    else avoid.put(n.id, now + 60_000);
                    joining = false;
                });
            }, "boatcast-join").start();
        });
    }

    /** Join a Wi-Fi network by name and password, and wait until the node at url answers. */
    @SuppressWarnings("deprecation")
    private boolean joinWifi(String ssid, String pass, String url) {
        String quoted = "\"" + ssid + "\"";
        WifiInfo info = wifi.getConnectionInfo();
        if (info == null || !quoted.equals(info.getSSID())) {
            WifiConfiguration c = new WifiConfiguration();
            c.SSID = quoted;
            c.preSharedKey = "\"" + pass + "\"";
            int id = wifi.addNetwork(c);
            if (id < 0) {
                List<WifiConfiguration> known = wifi.getConfiguredNetworks();
                if (known != null) for (WifiConfiguration k : known) if (quoted.equals(k.SSID)) id = k.networkId;
            } else {
                addedNetworks.add(id);
            }
            if (id < 0 || !wifi.enableNetwork(id, true)) {
                Log.w(TAG, "mesh: Android refused to join " + ssid);
                return false;
            }
        }
        long end = SystemClock.elapsedRealtime() + 20_000;
        while (SystemClock.elapsedRealtime() < end && !stopping) {
            SystemClock.sleep(700);
            bindToWifi();
            JSONObject st = statusOf(url);
            if (st != null && "boatcast".equals(st.optString("app"))) return true;
        }
        Log.w(TAG, "mesh: joined " + ssid + " but " + url + " did not answer");
        return false;
    }

    /** The node at the Wi-Fi gateway, if this phone is on a Boatcast hotspot (joined by QR code). */
    @SuppressWarnings("deprecation")
    private String gatewayBoatcast(long now) {
        DhcpInfo d = wifi.getDhcpInfo();
        int g = d == null ? 0 : d.gateway;
        if (g == 0) return null;
        String gw = (g & 0xff) + "." + (g >> 8 & 0xff) + "." + (g >> 16 & 0xff) + "." + (g >> 24 & 0xff);
        if (gw.equals(apIp)) return null;
        for (int port : new int[] {PORT, 80}) {
            String url = "http://" + gw + (port == 80 ? "" : ":" + port);
            Long until = avoidUrl.get(url);
            if (until != null && until > now) continue;
            JSONObject st = statusOf(url);
            if (st != null && "boatcast".equals(st.optString("app")) && optDepth(st) != null) return url;
        }
        return null;
    }

    private void stopRepeating(String why) {
        if (!repeating && reservation == null) return;
        Log.i(TAG, "mesh: stop repeating: " + why);
        server.stop();
        radio.stopServingCredentials();
        radio.stopAdvertising();
        closeHotspot();
        repeating = false;
        relayUpstream = null;
    }

    private boolean canRepeat(long now) {
        if (now < cannotRepeatUntil) return false;
        if (Build.VERSION.SDK_INT < 30 || !wifi.isStaApConcurrencySupported()) return false;
        return isWifiDefault(); // the relay must reach its parent over Wi-Fi
    }

    private boolean isWifiDefault() {
        Network n = cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }

    /** Send this app's own traffic (status checks, the slide view) over Wi-Fi even if mobile data is on. */
    private void bindToWifi() {
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    if (!n.equals(cm.getBoundNetworkForProcess())) cm.bindProcessToNetwork(n);
                    return;
                }
            }
        } catch (RuntimeException ignored) {
            // no permission or network gone
        }
    }

    // ------------------------------------------------------------- helpers

    private static JSONObject statusOf(String base) {
        String body = ServerProc.get(base + "/status", 1500);
        if (body == null) return null;
        try {
            return new JSONObject(body);
        } catch (JSONException e) {
            return null;
        }
    }

    /** Depth from a /status reply; null when that node is cut off from the host. */
    private static Integer optDepth(JSONObject st) {
        if (!"boatcast".equals(st.optString("app"))) return null;
        if (!st.has("depth") || st.isNull("depth")) return null;
        return st.optInt("depth");
    }

    private int localClients() {
        JSONObject st = statusOf("http://127.0.0.1:" + PORT);
        return st == null ? 0 : st.optInt("clients", 0);
    }

    @SuppressWarnings("deprecation")
    private int wifiRssi() {
        WifiInfo i = wifi.getConnectionInfo();
        return i == null ? -70 : i.getRssi();
    }

    /** {percent, charging 1/0} */
    private int[] battery() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return new int[] {100, 1};
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 100);
        int scale = Math.max(1, b.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        return new int[] {level * 100 / scale, plugged != 0 ? 1 : 0};
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

    /** The hotspot gets its address a moment after onStarted; wait for it. */
    private String waitForHotspotIp(long ms) {
        String sta = stationIp();
        long start = SystemClock.elapsedRealtime(), end = start + ms;
        while (SystemClock.elapsedRealtime() < end && !stopping) {
            boolean late = SystemClock.elapsedRealtime() - start > ms * 3 / 4;
            String ip = hotspotIp(sta, late);
            if (ip != null) return ip;
            SystemClock.sleep(250);
        }
        return null;
    }

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

    private void fail(String message) {
        Log.w(TAG, message);
        MAIN.post(() -> {
            if (stopping) return;
            publish("Stopped: " + message, false, null);
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

    private void notifyText(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1, notification(text));
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

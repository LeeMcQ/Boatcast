package io.github.leemcq.boatcast;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One screen: Host / Repeater / Pick slides / Stop, a status line, and the presenter
 * console (served by the bundled Rust server) in a WebView once broadcasting.
 */
public class MainActivity extends Activity implements CastService.Listener {
    private static final int REQ_LOCATION = 1;
    private static final int REQ_PICK = 2;

    private TextView status;
    private Button hostBtn, relayBtn, slidesBtn, stopBtn;
    private WebView web;
    private String shownUrl;
    private Runnable afterPermission;

    static File deckDir(Context c) {
        File d = new File(c.getFilesDir(), "deck");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    // ------------------------------------------------------------------ UI

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(17, 19, 24));
        int pad = dp(10);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextColor(Color.rgb(230, 232, 236));
        status.setTextSize(15);
        status.setPadding(dp(4), 0, dp(4), dp(8));
        root.addView(status);

        hostBtn = button("Host", v -> withPermission(this::startHost));
        relayBtn = button("Repeater", v -> withPermission(this::startRepeater));
        root.addView(row(hostBtn, relayBtn));

        slidesBtn = button("Pick slides", v -> pickSlides());
        stopBtn = button("Stop", v -> stopService(new Intent(this, CastService.class)));
        root.addView(row(slidesBtn, stopBtn));

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(8);
        root.addView(web, lp);

        setContentView(root);
        if (saved == null) selfCheck();
    }

    /** Make sure this phone can run the bundled server before anyone relies on it. */
    private void selfCheck() {
        new Thread(() -> {
            File bin = new File(getApplicationInfo().nativeLibraryDir, "libboatcast.so");
            String problem = null;
            try {
                Process p = new ProcessBuilder(bin.getAbsolutePath(), "-h").redirectErrorStream(true).start();
                String out = readAll(p.getInputStream(), 4096);
                p.waitFor();
                if (!out.contains("boatcast")) problem = "unexpected output";
            } catch (IOException | InterruptedException e) {
                problem = e.getMessage();
            }
            if (problem == null) {
                android.util.Log.i("boatcast", "self-check ok: " + bin);
            } else {
                android.util.Log.e("boatcast", "self-check failed: " + problem);
                say("This phone cannot run the Boatcast server (" + problem + ").");
            }
        }, "boatcast-selfcheck").start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        CastService.setListener(this);
    }

    @Override
    protected void onPause() {
        CastService.setListener(null);
        super.onPause();
    }

    @Override
    public void onStatus(String text, boolean running, String consoleUrl) {
        status.setText(text);
        hostBtn.setEnabled(!running);
        relayBtn.setEnabled(!running);
        stopBtn.setEnabled(running);
        if (consoleUrl != null) {
            if (!consoleUrl.equals(shownUrl)) {
                shownUrl = consoleUrl;
                // the server needs a moment to bind its port
                web.postDelayed(() -> web.loadUrl(consoleUrl), 600);
            }
        } else if (shownUrl != null || web.getUrl() == null) {
            shownUrl = null;
            web.loadDataWithBaseURL(null, HELP, "text/html", "utf-8", null);
        }
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout row(View a, View b) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        r.addView(a, p);
        r.addView(b, new LinearLayout.LayoutParams(p));
        return r;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void say(String s) {
        runOnUiThread(() -> status.setText(s));
    }

    // ------------------------------------------------------- permissions

    /** Android only starts a hotspot for apps with location permission and Location on. */
    private void withPermission(Runnable action) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            afterPermission = action;
            say("Allow location: Android requires it before an app can start a hotspot.");
            requestPermissions(new String[] {
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION
            }, REQ_LOCATION);
            return;
        }
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        boolean on = Build.VERSION.SDK_INT >= 28 ? lm.isLocationEnabled()
                : lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        if (!on) {
            say("Turn Location on (Android needs it for hotspots), then come back and tap again.");
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            return;
        }
        action.run();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_LOCATION) return;
        Runnable next = afterPermission;
        afterPermission = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && next != null) {
            withPermission(next);
        } else {
            say("Location permission is needed to start a hotspot.");
        }
    }

    // ---------------------------------------------------------------- host

    private void startHost() {
        ensureStarterDeck();
        startForegroundService(new Intent(this, CastService.class).setAction(CastService.ACTION_HOST));
    }

    private void ensureStarterDeck() {
        File dir = deckDir(this);
        String[] names = dir.list();
        if (names != null && names.length > 0) return;
        String text = "# Welcome\nSlides will appear here.\n\n"
                + "# Join\nScan the Wi-Fi code, then the page code.\n";
        try (OutputStream o = new FileOutputStream(new File(dir, "slides.txt"))) {
            o.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            say("Could not write the starter slides: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ repeater

    @SuppressWarnings("deprecation")
    private void startRepeater() {
        WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (Build.VERSION.SDK_INT >= 30 && !wifi.isStaApConcurrencySupported()) {
            say("This phone cannot stay on Wi-Fi while running its own hotspot, so it cannot repeat. "
                    + "It can still watch, or be the host.");
            return;
        }
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        Network active = cm.getActiveNetwork();
        NetworkCapabilities caps = active == null ? null : cm.getNetworkCapabilities(active);
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            say("First join the host's Wi-Fi (scan its Wi-Fi code) and turn mobile data off, "
                    + "so this phone talks to the host over Wi-Fi. Then tap Repeater.");
            return;
        }
        DhcpInfo dhcp = wifi.getDhcpInfo();
        int g = dhcp == null ? 0 : dhcp.gateway;
        if (g == 0) {
            askForHost("Could not find the host on this Wi-Fi.");
            return;
        }
        String gw = (g & 0xff) + "." + (g >> 8 & 0xff) + "." + (g >> 16 & 0xff) + "." + (g >> 24 & 0xff);
        say("Looking for Boatcast at " + gw + "…");
        new Thread(() -> {
            String found = null;
            for (int port : new int[] {CastService.PORT, 80}) {
                String base = "http://" + gw + (port == 80 ? "" : ":" + port);
                if (isBoatcast(base)) {
                    found = base;
                    break;
                }
            }
            final String up = found;
            runOnUiThread(() -> {
                if (up == null) askForHost("No Boatcast answered at " + gw + ".");
                else startRelay(up);
            });
        }, "boatcast-probe").start();
    }

    private void askForHost(String why) {
        EditText input = new EditText(this);
        input.setHint("192.168.43.1:8080");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        new AlertDialog.Builder(this)
                .setTitle("Host address")
                .setMessage(why + " Type the address shown under the host's page code.")
                .setView(input)
                .setPositiveButton("Repeat it", (d, w) -> {
                    String a = input.getText().toString().trim();
                    if (a.startsWith("http://")) a = a.substring(7);
                    while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
                    if (a.isEmpty()) return;
                    final String base = "http://" + a;
                    new Thread(() -> {
                        boolean ok = isBoatcast(base);
                        runOnUiThread(() -> {
                            if (ok) startRelay(base);
                            else say("No Boatcast answered at " + base + ".");
                        });
                    }, "boatcast-probe").start();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void startRelay(String upstream) {
        startForegroundService(new Intent(this, CastService.class)
                .setAction(CastService.ACTION_RELAY)
                .putExtra(CastService.EXTRA_UPSTREAM, upstream));
    }

    /** GET /status and check it is a Boatcast server. Runs off the UI thread. */
    private static boolean isBoatcast(String base) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base + "/status").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            if (c.getResponseCode() != 200) return false;
            try (InputStream in = c.getInputStream()) {
                return readAll(in, 8192).contains("\"app\":\"boatcast\"");
            }
        } catch (IOException e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[2048];
        int n;
        while (out.size() < max && (n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------- slides

    private void pickSlides() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[] {"image/*", "text/plain", "application/json"});
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int k = 0; k < clip.getItemCount(); k++) uris.add(clip.getItemAt(k).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        say("Copying " + uris.size() + " file(s)…");
        new Thread(() -> {
            String msg = copySlides(uris);
            if (CastService.isRunning()) msg += reloadDeck();
            say(msg);
        }, "boatcast-slides").start();
    }

    /** Replace the deck folder with the picked files (images, slides.txt or deck.json). */
    private String copySlides(List<Uri> uris) {
        File dir = deckDir(this);
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        int n = 0;
        for (Uri u : uris) {
            String name = displayName(u);
            String lower = name.toLowerCase();
            String type = getContentResolver().getType(u);
            if (lower.endsWith(".json") || "application/json".equals(type)) name = "deck.json";
            else if (lower.endsWith(".txt") || "text/plain".equals(type)) name = "slides.txt";
            try (InputStream in = getContentResolver().openInputStream(u);
                 OutputStream out = new FileOutputStream(new File(dir, name))) {
                if (in == null) continue;
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                n++;
            } catch (IOException e) {
                return "Could not copy " + name + ": " + e.getMessage();
            }
        }
        return "Loaded " + n + " file(s) as the slides.";
    }

    private String displayName(Uri u) {
        String name = null;
        try (Cursor c = getContentResolver().query(u, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) name = c.getString(0);
        } catch (RuntimeException ignored) {
            // provider without a display name
        }
        if (name == null || name.isEmpty()) name = "file-" + Math.abs(u.hashCode());
        return name.replace('/', '_').replace('\\', '_');
    }

    /** Ask the running host to re-read the deck folder (loopback needs no PIN). */
    private static String reloadDeck() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + CastService.PORT + "/control").openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(1500);
            c.setReadTimeout(3000);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) {
                o.write("{\"action\":\"reload\"}".getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            return code == 200 || code == 204 ? " Phones now have them." : " (reload failed: HTTP " + code + ")";
        } catch (IOException e) {
            return " (reload failed: " + e.getMessage() + ")";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static final String HELP = "<!doctype html><meta name=viewport content='width=device-width'>"
            + "<style>body{font:16px/1.45 system-ui,sans-serif;background:#111318;color:#e6e8ec;margin:0;padding:4px 6px}"
            + "h3{margin:14px 0 4px;font-size:17px}ol{padding-left:22px;margin:4px 0}li{margin:3px 0}"
            + "p{margin:6px 0;color:#aab}</style>"
            + "<h3>Host</h3><ol><li>Tap <b>Host</b>. This phone starts its own hotspot.</li>"
            + "<li>Show the two codes on screen: phones scan the Wi-Fi code, then the page code.</li>"
            + "<li>Change slides from the console that opens here.</li></ol>"
            + "<h3>Repeater (more than 10 phones)</h3><ol>"
            + "<li>Scan the host's Wi-Fi code (or another repeater's) to join it.</li>"
            + "<li>Turn mobile data off.</li><li>Tap <b>Repeater</b>. This phone shows its own two codes; "
            + "about 10 more phones can join it. Repeaters can chain.</li></ol>"
            + "<h3>Slides</h3><p>Tap <b>Pick slides</b> and choose images (PowerPoint: File, Export, PNG), "
            + "a slides.txt (blank line between slides, # for a title) or a deck.json. "
            + "You can also type slides in the console.</p>"
            + "<p>Keep Location on and leave the notification running. Viewers need only a browser; "
            + "iPhones can watch but cannot repeat.</p>";
}

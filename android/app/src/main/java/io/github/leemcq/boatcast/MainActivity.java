package io.github.leemcq.boatcast;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
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
 * One screen: Host / Join / Pick slides / Stop, a status line, and the presenter
 * console (served by the bundled Rust server) in a WebView once broadcasting.
 */
public class MainActivity extends Activity implements CastService.Listener {
    private static final int REQ_LOCATION = 1;
    private static final int REQ_PICK = 2;

    private TextView status;
    private Button hostBtn, relayBtn, slidesBtn, stopBtn;
    private WebView web;
    private LinearLayout tabs;
    private Button tabMain, tabPhones, tabShare;
    private String pageFromService;
    /** 0 = controls (or slides when joined), 1 = what phones see, 2 = share */
    private int tab;
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
        relayBtn = button("Join", v -> withPermission(this::startJoin));
        root.addView(row(hostBtn, relayBtn));

        slidesBtn = button("Pick slides", v -> pickSlides());
        stopBtn = button("Stop", v -> stopService(new Intent(this, CastService.class)));
        root.addView(row(slidesBtn, stopBtn));

        tabMain = button("Controls", v -> showTab(0));
        tabPhones = button("Phones see", v -> showTab(1));
        tabShare = button("Share", v -> showTab(2));
        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tabs.addView(tabMain, tp);
        tabs.addView(tabPhones, new LinearLayout.LayoutParams(tp));
        tabs.addView(tabShare, new LinearLayout.LayoutParams(tp));
        tabs.setVisibility(View.GONE);
        tabMain.setEnabled(false);
        root.addView(tabs);

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if ("boatcast:share".equals(url)) {
                    shareJoinDetails();
                    return true;
                }
                return false;
            }
        });
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
    public void onStatus(String text, boolean running, String pageUrl) {
        status.setText(text);
        hostBtn.setEnabled(!running);
        relayBtn.setEnabled(!running);
        stopBtn.setEnabled(running);
        pageFromService = pageUrl;
        boolean sharing = CastService.shareUrl != null;
        tabs.setVisibility(sharing ? View.VISIBLE : View.GONE);
        tabMain.setText(CastService.hosting ? "Controls" : "Slides");
        if (!sharing) tab = 0;
        if (tab != 0) return; // the other tabs stay as they are until tapped
        if (pageUrl != null) {
            if (!pageUrl.equals(shownUrl)) {
                shownUrl = pageUrl;
                web.loadUrl(pageUrl); // the service only reports it once the server answers
            }
        } else if (shownUrl != null || web.getUrl() == null || !running) {
            shownUrl = null;
            web.loadDataWithBaseURL(null, HELP, "text/html", "utf-8", null);
        }
    }

    /** Controls (or the slides), exactly what the phones see, or the codes to share. */
    private void showTab(int t) {
        tab = t;
        tabMain.setEnabled(t != 0);
        tabPhones.setEnabled(t != 1);
        tabShare.setEnabled(t != 2);
        String local = "http://127.0.0.1:" + CastService.PORT + "/";
        if (t == 0) {
            shownUrl = pageFromService;
            if (pageFromService != null) web.loadUrl(pageFromService);
        } else if (t == 1) {
            shownUrl = local;
            // The same page as on every phone; "observer" keeps this preview out of the phone count.
            web.loadUrl(local + "?observer=1");
        } else {
            shownUrl = "share";
            web.loadDataWithBaseURL(local, shareHtml(), "text/html", "utf-8", null);
        }
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String shareHtml() {
        String ssid = esc(CastService.shareSsid), pass = esc(CastService.sharePass), url = esc(CastService.shareUrl);
        long t = System.currentTimeMillis();
        return "<!doctype html><meta name=viewport content='width=device-width'>"
                + "<style>body{font:17px/1.4 system-ui,sans-serif;background:#111318;color:#e6e8ec;margin:0;padding:8px;text-align:center}"
                + "h3{margin:14px 0 6px}img{width:72%;max-width:320px;background:#fff;padding:10px;border-radius:12px}"
                + ".big{font-size:22px;font-weight:600;word-break:break-all;margin:6px 0}.q{color:#aab}"
                + "a.b{display:block;margin:18px auto 8px;max-width:340px;padding:14px;border-radius:12px;background:#2f6fe0;"
                + "color:#fff;text-decoration:none;font-weight:600}</style>"
                + "<h3>1 · Join the Wi-Fi</h3><img src='/qr/wifi.svg?t=" + t + "' alt='Wi-Fi code'>"
                + "<div class=q>Wi-Fi</div><div class=big>" + ssid + "</div>"
                + "<div class=q>Password</div><div class=big>" + pass + "</div>"
                + "<h3>2 · Open the slides</h3><img src='/qr/url.svg?t=" + t + "' alt='Page code'>"
                + "<div class=big>" + url + "</div>"
                + "<p class=q>Turn mobile data off if the page does not open.</p>"
                + "<a class=b href='boatcast:share'>Send these details</a>";
    }

    /** Android share sheet: WhatsApp, SMS, Bluetooth, nearby share… */
    private void shareJoinDetails() {
        if (CastService.shareUrl == null) return;
        String text = "Join Boatcast\n1. Wi-Fi: " + CastService.shareSsid
                + "\n   Password: " + CastService.sharePass
                + "\n2. Open: " + CastService.shareUrl
                + "\n(Turn mobile data off if the page does not open.)";
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, "Send join details"));
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

    // ---------------------------------------------------------------- join

    /** Join mode: find the show (Bluetooth or the Wi-Fi this phone is on), watch, repeat when useful. */
    private void startJoin() {
        android.bluetooth.BluetoothAdapter bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
        if (bt != null && !bt.isEnabled()) {
            try {
                startActivity(new Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE));
            } catch (RuntimeException ignored) {
                // no Bluetooth settings screen; Join still works through a Wi-Fi code
            }
        }
        startForegroundService(new Intent(this, CastService.class).setAction(CastService.ACTION_JOIN));
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
            if (CastService.hosting) msg += reloadDeck();
            say(msg);
        }, "boatcast-slides").start();
    }

    /**
     * Replace the deck with the picked files (images, slides.txt or deck.json). Files are
     * copied into a new folder first; the old deck is only replaced once every copy worked.
     */
    private String copySlides(List<Uri> uris) {
        File dir = deckDir(this);
        File fresh = new File(getFilesDir(), "deck.new");
        deleteTree(fresh);
        //noinspection ResultOfMethodCallIgnored
        fresh.mkdirs();
        int n = 0;
        for (Uri u : uris) {
            String name = displayName(u);
            String lower = name.toLowerCase();
            String type = getContentResolver().getType(u);
            if (lower.endsWith(".json") || "application/json".equals(type)) name = "deck.json";
            else if (lower.endsWith(".txt") || "text/plain".equals(type)) name = "slides.txt";
            else if (type != null && !type.startsWith("image/")) continue; // not a slide
            try (InputStream in = getContentResolver().openInputStream(u);
                 OutputStream out = new FileOutputStream(new File(fresh, name))) {
                if (in == null) continue;
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                n++;
            } catch (IOException e) {
                deleteTree(fresh);
                return "Could not copy " + name + " (" + e.getMessage() + "). Your old slides are unchanged.";
            }
        }
        if (n == 0) {
            deleteTree(fresh);
            return "None of those files are slides (images, slides.txt or deck.json). Your old slides are unchanged.";
        }
        File old = new File(getFilesDir(), "deck.old");
        deleteTree(old);
        if (dir.exists() && !dir.renameTo(old)) {
            deleteTree(fresh);
            return "Could not replace the slides. Your old slides are unchanged.";
        }
        if (!fresh.renameTo(dir)) {
            //noinspection ResultOfMethodCallIgnored
            old.renameTo(dir);
            return "Could not replace the slides. Your old slides are unchanged.";
        }
        deleteTree(old);
        return "Loaded " + n + " file(s) as the slides.";
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
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
            + "<h3>Join (builds the mesh by itself)</h3><ol>"
            + "<li>Turn Bluetooth on and mobile data off.</li>"
            + "<li>Tap <b>Join</b>. The phone finds the nearest host or repeater, joins its Wi-Fi and shows the slides.</li>"
            + "<li>If phones nearby need a way in and this phone can, it also becomes a repeater by itself, "
            + "and hands over when its battery runs low.</li></ol>"
            + "<p>Phones without the app can still scan the Wi-Fi code and page code shown by the host.</p>"
            + "<h3>Slides</h3><p>Tap <b>Pick slides</b> and choose images (PowerPoint: File, Export, PNG), "
            + "a slides.txt (blank line between slides, # for a title) or a deck.json. "
            + "You can also type slides in the console.</p>"
            + "<p>Keep Location on and leave the notification running. Viewers need only a browser; "
            + "iPhones can watch but cannot repeat.</p>";
}

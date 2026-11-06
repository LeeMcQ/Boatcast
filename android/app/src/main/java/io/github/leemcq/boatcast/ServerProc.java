package io.github.leemcq.boatcast;

import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The bundled Rust server as a child process: start it, wait until it answers, restart it
 * if it dies (a few times, with growing pauses), and talk to it on 127.0.0.1.
 */
final class ServerProc {
    interface Listener {
        /** The server is up and answering (first start or after a restart). */
        void onReady();

        /** It kept dying; giving up. */
        void onGaveUp(String lastLine);
    }

    private static final String TAG = "boatcast";
    private static final int MAX_RESTARTS = 5;
    private static final long RESTART_WINDOW_MS = 120_000;

    private final String binary;
    private final java.io.File workDir;
    private final int port;
    private final Listener listener;
    private final List<Long> restarts = new ArrayList<>();
    private volatile List<String> args;
    private volatile Process proc;
    private volatile boolean stopping;
    private volatile String lastLine = "";

    ServerProc(String binary, java.io.File workDir, int port, Listener listener) {
        this.binary = binary;
        this.workDir = workDir;
        this.port = port;
        this.listener = listener;
    }

    /** Start (or restart with new arguments) on a background thread. */
    synchronized void start(List<String> newArgs) {
        stopping = false;
        args = new ArrayList<>(newArgs);
        Process old = proc;
        proc = null;
        if (old != null) old.destroy();
        new Thread(this::supervise, "boatcast-server").start();
    }

    boolean running() {
        Process p = proc;
        return p != null && isAlive(p);
    }

    synchronized void stop() {
        stopping = true;
        Process p = proc;
        proc = null;
        if (p != null) p.destroy();
    }

    private void supervise() {
        List<String> myArgs = args;
        while (!stopping && myArgs == args) {
            List<String> cmd = new ArrayList<>();
            cmd.add(binary);
            cmd.addAll(myArgs);
            Process p;
            try {
                p = new ProcessBuilder(cmd).directory(workDir).redirectErrorStream(true).start();
            } catch (IOException e) {
                lastLine = e.getMessage();
                if (!backOff()) return;
                continue;
            }
            synchronized (this) {
                if (stopping || myArgs != args) {
                    p.destroy();
                    return;
                }
                proc = p;
            }
            Thread pump = new Thread(() -> pump(p.getInputStream()), "boatcast-log");
            pump.start();
            if (waitReady(p, 6000)) listener.onReady();
            try {
                p.waitFor();
            } catch (InterruptedException e) {
                return;
            }
            if (stopping || myArgs != args) return;
            Log.w(TAG, "server exited: " + lastLine);
            if (!backOff()) return;
        }
    }

    /** Pause before a restart; false (and a give-up report) after too many restarts. */
    private boolean backOff() {
        long now = SystemClock.elapsedRealtime();
        restarts.removeIf(t -> now - t > RESTART_WINDOW_MS);
        restarts.add(now);
        if (restarts.size() > MAX_RESTARTS) {
            listener.onGaveUp(lastLine);
            return false;
        }
        SystemClock.sleep(1000L * restarts.size());
        return !stopping;
    }

    private void pump(InputStream in) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = r.readLine()) != null) {
                Log.i(TAG, line);
                if (!line.trim().isEmpty()) lastLine = line.trim();
            }
        } catch (IOException ignored) {
            // closed on stop
        }
    }

    private boolean waitReady(Process p, long ms) {
        long end = SystemClock.elapsedRealtime() + ms;
        while (SystemClock.elapsedRealtime() < end && !stopping) {
            if (!isAlive(p)) return false;
            if (status() != null) return true;
            SystemClock.sleep(150);
        }
        return false;
    }

    private static boolean isAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    /** GET /status from our own server, or null. */
    String status() {
        return get("http://127.0.0.1:" + port + "/status", 1500);
    }

    /** POST /control to our own server (loopback needs no PIN). True on HTTP 200. */
    boolean control(String json) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:" + port + "/control").openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(1500);
            c.setReadTimeout(3000);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) {
                o.write(json.getBytes(StandardCharsets.UTF_8));
            }
            return c.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Plain GET with short timeouts; the body as text, or null. */
    static String get(String url, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while (out.size() < 65536 && (n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}

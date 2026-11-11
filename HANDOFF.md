# Boatcast: handoff for the next AI model

This file holds everything needed to understand, rebuild and extend Boatcast and its Android APK. Read all of it before changing anything. Every fact here was true of the repository at the commit that added this file.

- **Repository:** https://github.com/LeeMcQ/Boatcast (public; default branch `main`)
- **Website:** https://leemcq.github.io/Boatcast/ (GitHub Pages serves `main` from the repository root)
- **APK link:** https://leemcq.github.io/Boatcast/boatcast.apk (the same link for every update)

---

## 1. What the owner wants (requirements, in priority order)

1. **No internet and no router.** One phone or laptop sends live slides and words to many phones nearby over Wi-Fi.
2. **Viewers need only a browser.** They scan a QR code to join the Wi-Fi, and a second QR code to open the page.
3. **Real time.** Slide changes must reach phones within 1–2 s at most.
4. **As efficient and low-level as possible.** That's why the server is lean Rust with no web framework.
5. **The host can be a phone** (Android hotspot) when there's no laptop.
6. **More than about 10 phones.** A phone hotspot takes about 10. Other Android phones act as repeaters and form a self-organising mesh that keeps adjusting, like a cell network.
7. **It must also work as a website and an installable web app (PWA).** A PWA install needs HTTPS (see §9).
8. **The host sees what viewers see,** and the app shows the QR codes and address so they can be shared.
9. **One stable APK link** on the website that every update replaces.

**Owner preferences:** concrete, implementation-ready results. Avoid back-and-forth debugging: test in CI before handing anything over. The owner works mostly from a phone, so keep instructions phone-friendly.

---

## 2. Architecture in one picture

```
 Host (phone app "Host" or laptop CLI)        Repeater (phone app, Join mode)       Viewer
 ┌──────────────────────────────┐   Wi-Fi    ┌─────────────────────────────┐ Wi-Fi ┌──────────┐
 │ LocalOnlyHotspot (phone)     │◄──────────►│ STA joined to host's AP     │◄─────►│ browser  │
 │ boatcast serve (Rust)        │  SSE /events│ + own LocalOnlyHotspot      │       │ index.html│
 │  - serves page, deck, images │            │ boatcast relay --upstream … │       │ EventSource│
 │  - SSE push of state         │            │  (mirrors deck, re-serves)  │       └──────────┘
 │ BLE advert + GATT creds      │◄──BLE──────│ BLE scan / advert / GATT    │
 └──────────────────────────────┘            └─────────────────────────────┘
```

- **Transport:** plain HTTP/1.1 and Server-Sent Events (SSE). Each phone downloads the deck once, then holds one SSE stream and gets about 200 bytes per slide change. WebSockets, WebRTC and multicast aren't used; multicast can't be used from browsers.
- **Discovery between app phones:** Bluetooth LE adverts, plus a GATT read that hands over the hotspot's Wi-Fi name and password. Slides always travel over Wi-Fi.
- **Mesh shape:** a tree rooted at the host. Each node knows its depth, meaning its hops from the host.

---

## 3. Repository map

| Path | What it is |
|---|---|
| `Cargo.toml`, `Cargo.lock` | Rust crate `boatcast` v0.2.0, edition 2024. Optional feature `tls`. |
| `src/main.rs` | Entry: parse config; build the tokio runtime (current_thread if threads ≤ 1, else multi_thread); accept loops (plain and TLS); spawn `relay::run` in relay mode. |
| `src/config.rs` | CLI parsing, `HELP` text, `local_ip()` (picks the Wi-Fi interface and skips the upstream's subnet in relay mode), `page_url()`, `wifi_qr_text()`, `qr_svg()`, `pub fn parse_upstream()`. |
| `src/http.rs` | Minimal HTTP/1.1: `read_request` (16 KB header cap, 256 KB body cap; keeps the query string in `Request.query`), `respond` (one write when the body is ≤ 32 KB), plain `get()` and `post()` clients for relays. |
| `src/deck.rs` | Deck model; loads from `deck.json`, `slides.txt` or images; natural sort (Slide10 after Slide9); content-hashed assets with gzip copies; `natural_key` with a unit test. |
| `src/hub.rs` | Show state, 150 ms throttle, 15 s keepalive tick, adaptive `policy()`, `control()` actions, relay state (`upstream`, `upstream_changed` Notify, `depth`, `mesh`, `relays`). |
| `src/relay.rs` | Relay loop: follow upstream `/events`, mirror the deck, re-broadcast. Switches upstream at runtime. Learns depth and mesh id from the upstream's `/status`. Reports no depth when cut off. |
| `src/server.rs` | Routing, SSE handler, auth, captive-portal answers, `/status`, QR endpoints, embedded web files. |
| `web/index.html` | Viewer page: auto-fit text, image slides, live-text banner, swipe back and "Back to live", text-size and theme menu, wake lock (HTTPS only), service-worker registration (secure context only). |
| `web/console.html` | Presenter console: prev, next, blank, slide list, now/next/notes, live text, paste slides, QR codes, full-screen join card, phone count. Polls `/status` every 2 s. |
| `web/sw.js`, `web/manifest.webmanifest`, `web/icon*` | PWA files. |
| `examples/demo-deck/` | Four-slide sample deck. |
| `tests/smoke.sh` | 46 end-to-end checks against the real binary (§10). |
| `tests/portable.py` | Core host and relay checks, Python standard library only. Runs in CI on Windows, macOS and Linux (§10). |
| `tests/browsers.py` | Playwright: the viewer page in Chromium, Firefox and WebKit at phone sizes, plus console checks in Chromium (§10). |
| `tests/android-emulator.sh` | Emulator test run in CI (§10). |
| `android/` | Android app: plain Java, no AndroidX, no libraries (§6). |
| `.github/workflows/build.yml` | All builds, tests, APK publishing and releases (§7). |
| `index.html` (root) | The website: the link to `boatcast.apk`, a note on laptop downloads and a link to `FIELD-TEST.md`. |
| `boatcast.apk` (root) | Latest signed APK, committed by CI after each passing build. Don't edit it by hand. |
| `README.md` | User-facing guide. |
| `FIELD-TEST.md` | Six-stage test sheet for real phones (§14). |

---

## 4. The Rust server in detail

### 4.1 CLI

Run `boatcast -h`. The modes:
- `boatcast [serve]`: host.
- `boatcast relay --upstream http://IP:PORT`: repeater. `--upstream` implies relay and must be `http://`; a URL with no port means port 80.

| Option | Meaning | Default |
|---|---|---|
| `--port` | HTTP port | 8080 |
| `--bind` | listen address | 0.0.0.0 |
| `--deck DIR` | deck folder | built-in demo deck |
| `--pin` | PIN for control from other devices | none |
| `--wifi-ssid`, `--wifi-pass` | Wi-Fi name and password for the Wi-Fi QR code | none |
| `--public-url` | URL printed in the page QR code | none |
| `--host-ip` | this device's address, overriding auto-detection | auto |
| `--captive` | answer phone connectivity checks so the page pops up on join | off |
| `--max-clients` | viewers beyond this are refused | 2000 |
| `--mesh-id N` | host's mesh id (u16); relays inherit it | none |
| `--threads N\|auto` | worker threads (`auto` = CPU cores, max 8) | 1 |
| `--tls-cert`, `--tls-key`, `--https-port` | HTTPS; needs a `--features tls` build | port 8443 |

The app always passes `--host-ip`, because Android hotspot interface names vary by maker.

**Double-click start (Windows and macOS).** Running the program with no options now:
- creates and uses a folder called `slides` next to the program (images, `slides.txt` or `deck.json`; an empty folder shows the sample slides);
- opens the presenter console in the browser;
- on Windows, prints a reminder: when the firewall asks, tick both Private and Public networks and allow access;
- keeps the window open with a message if it could not start (usually because Boatcast is already running).

Options still work as before from a terminal.

### 4.2 HTTP routes

| Route | Notes |
|---|---|
| `GET /` | Viewer page |
| `GET /console` and `/console.html` | Presenter console. **Both must exist**: the app opens `/console.html`, and a missing alias was a real bug once. |
| `GET /sw.js`, `/manifest.webmanifest`, `/icon.svg`, `/icon-192.png`, `/icon-512.png` | PWA files. Icons cached for a day; the rest `no-cache`. |
| `GET /deck.json` | Deck without presenter notes. ETag; gzip when the browser accepts it. |
| `GET /a/<fnv64>.<ext>` | Slide assets, named by content hash. `Cache-Control: immutable`, one year. |
| `GET /api/deck-full` | Deck with notes. Needs auth. |
| `GET /events` | SSE stream (§4.3). The header `X-Boatcast-Relay: 1` marks a child relay. `?observer=1` marks a viewer the server leaves out of `clients` and which does not answer test signals. |
| `GET /status` | JSON (§4.4) |
| `GET /qr/url.svg`, `/qr/wifi.svg` | QR codes as SVG |
| `POST /control` (alias `POST /next`) | Change the show. Needs auth. On a relay, returns 409 except for `upstream` from loopback. |
| `GET /api/test` | Delivery-test results (§4.7). Same auth as control. On a relay, returns 409. |
| `POST /api/ack` | A viewer's answer to a test signal: `{"run","n","id","rc"}`. No auth; small and bounded. |
| `OPTIONS *` | CORS preflight |
| Captive probes | `/generate_204`, `/gen_204`, `/hotspot-detect.html`, `/library/test/success.html`, `/connecttest.txt`, `/ncsi.txt`, `/success.txt`, `/canonical.html`. Before a phone has joined, any foreign Host header gets a 302 to the page. |

**Auth** is any one of: a loopback peer; an `X-Pin` header equal to `--pin`; or `"pin"` in the JSON body. A LAN peer with no PIN gets 403.

**Control actions** take a JSON body `{"action": …}`:

| Action | Fields | Effect |
|---|---|---|
| `next`, `prev`, `first` | — | move through the slides |
| `goto` | `slide` | jump to a slide |
| `blank` | `on` (optional; toggles if absent) | blank the screens |
| `text` | `text` (empty clears it) | live text line |
| `load_text` | `title`, `text` (slides.txt format) | replace the slides |
| `reload` | — | re-read `--deck` (the console button **Reload slides from folder**) |
| `wifi` | `ssid`, `pass` | set the Wi-Fi name and password used for the Wi-Fi QR code, instead of `--wifi-ssid` and `--wifi-pass`. An empty `ssid` clears it. `/status` `ssid` and `wifi_qr` reflect it. |
| `test` | `count` (1–100), `gap_ms` (300–10000) | start a delivery test (§4.7) |
| `test_stop` | — | stop the running delivery test |
| `upstream` | `url` | relay only, loopback only: switch the upstream at once |

### 4.3 The SSE protocol

- **First on connect:** a `retry:` line, then a `policy` event with `{"preload":"all"|"near","spread":<ms>}`, then a snapshot `state` event. The retry delay travels in the `retry:` line. The names `spread_ms` and `retry_ms` appear only in `/status` `policy`, not on the wire.
- **State events:** `id: <seq>\nevent: state\ndata: {"boot","seq","deck","slide","slides","blank","text"}\n\n`. "Latest state wins": phones never need history. `boot` is random and changes each time the host starts. The viewer compares `(boot, seq)`, so a restarted host is not mistaken for stale updates.
- **Probe events:** `event: probe` with `{"run","n","of"}`, sent during a delivery test (§4.7).
- **Keepalive:** `event: ping\ndata: 1\n\n` every 15 s. The viewer has a 25 s watchdog and reconnects on visibilitychange.
- **Throttle:** the first change goes out immediately. Changes within 150 ms after it merge into one trailing send.
- **Adaptive policy by phone count n:**
  - 0–49: preload all slides, no spread, retry 1 s.
  - 50–200: preload nearby slides, spread n×100 ms, retry 3 s.
  - Above 200: preload nearby slides, spread min(n×100, 60 000) ms, retry 5 s.
- **Relays** forward the upstream's `state` bytes unchanged, so `boot` and `seq` are the same across the whole mesh. They also forward `probe` events down, and answers up.

### 4.4 `/status` fields

- **Show:** `app` (`"boatcast"`), `version`, `mode` (`host` or `relay`), `seq`, `slide`, `slides`, `blank`, `text`, `title`, `deck`.
- **Load:** `clients` (all SSE streams, including child relays, but not observer streams opened with `?observer=1`, such as the host's own "Phones see" tab), `relays` (child relays only), `policy` (here the names are `spread_ms` and `retry_ms`), `threads`.
- **Joining:** `url`, `ssid` (the Wi-Fi password is never exposed here; it follows what was set by `--wifi-ssid` or the `wifi` action), `wifi_qr` (bool).
- **Server:** `uptime_s`, `auth`.
- **Mesh:**
  - `depth`: 0 on a host; upstream + 1 on a relay; **`null` when the relay is cut off from the host**.
  - `mesh`: the mesh id.
  - `upstream`: `{"connected": bool, "url": "http://ip:port"}` on relays.

### 4.5 Relay behaviour

- **Following:** it runs `GET /events` with `X-Boatcast-Relay: 1` on the current upstream. A version change in a `state` event makes it mirror `/deck.json` and every `/a/*` image.
- **Retry:** backoff from 500 ms up to 8 s, plus jitter. 40 s of silence counts as a dead link.
- **Depth:** on connect and on every `ping`, it GETs the upstream's `/status`. It sets `depth = upstream.depth + 1` and copies the mesh id; if the upstream's depth is `null`, its own becomes `null`.
- **Cut off:** when the link drops, depth becomes `u32::MAX`, reported as `null`. This is the **loop guard ("poisoning")**.
- **Switching upstream:** a loopback `POST /control {"action":"upstream","url":…}` swaps the upstream and notifies the loop, which drops the old link at once.

### 4.6 Build and size

| Build | Command | Size |
|---|---|---|
| Lean | `cargo build --release` | 1.1 MB |
| With HTTPS | `cargo build --release --features tls` | 2.3 MB |

- **Crates:** tokio, bytes, serde_json, flate2 (miniz_oxide, pure Rust), qrcode (svg only), if-addrs, optional tokio-rustls (ring).
- **Release profile:** `lto`, `codegen-units=1`, `strip`, panic=unwind. Unwind is deliberate, so one bad connection can't kill the server.

### 4.7 The delivery test

- **Start:** `POST /control {"action":"test","count":1-100,"gap_ms":300-10000}`. The console's **Run test** sends 20 signals 1.5 s apart. `{"action":"test_stop"}` ends it.
- **Signals:** each is an SSE `probe` event `{"run","n","of"}` on the live connection, so the slides on screen don't change.
- **Answers:** the viewer page answers each with `POST /api/ack {"run","n","id","rc"}` (`rc` feeds the reconnect count shown in the results).
- **Relays:** forward probes down and answers up, adding 1 to `hops`.
- **Results:** `GET /api/test` (same auth as control; 409 on a relay). The console shows, per phone: device type, hops, how many of the 20 arrived, typical and worst time in ms, and reconnects. Times are "there and back", so real delay is lower.
- **Verdict:** green when nothing was lost and 95% of answers took 1 s or less. **Copy results** copies the table as CSV.
- **Observers:** pages opened with `?observer=1` (the host's own "Phones see" preview) don't answer.
- **Older pages:** phones that loaded the page before this feature must reload once.
- **Console UI:** it was being added separately when this section was written. Check `web/console.html` for what exists.

---

## 5. Measured performance (lab: one Linux machine, simulated clients)

- **5,000 phones:** about 31.5 MB of memory in total (about 6 KB per connection). The last phone got each slide change in about 45 ms (median of 30 changes), and none were lost.
- **Relay overhead:** about 0.15 ms per hop.

| Phones per tier | Host | Through 1 relay | Through 2 relays |
|---|---|---|---|
| 10 | 0.33 ms | 0.50 ms | 0.50 ms |
| 1,000 | 9.3 ms | 9.2 ms | 9.0 ms |
| 5,000 | 45.6 ms | — | 44.2 ms |

- **Language benchmark:** at 5,000 clients, the median time for a slide change to reach the last client was:
  - C: 31–33 ms
  - lean Rust: 36–37 ms
  - Python: 35–37 ms
  - Node: 40–43 ms
  - Rust with axum: 42–43 ms
  - Go: 44–45 ms

  Lean Rust was chosen for its tiny memory use and single static binary.
- **Headless Chrome:** a slide change appeared in the page in 104 ms. The service worker registered and there were no page errors.

**Not measured:** real phones, real Wi-Fi, battery drain, and the true 100- or 1,000-phone capacity. Say this whenever capacity comes up.

---

## 6. The Android app

### 6.1 Build settings (`android/app/build.gradle`)

| Setting | Value |
|---|---|
| AGP | 8.7.3, with Gradle 8.11.1 (no Gradle wrapper in the repo; CI uses `gradle/actions/setup-gradle@v4` with `gradle-version: 8.11.1`) |
| JDK | 17; Java source and target 11 |
| `namespace` / `applicationId` | `io.github.leemcq.boatcast` |
| SDK levels | `compileSdk 34`, `minSdk 26`, **`targetSdk 28`** |
| Version | `versionCode` = `GITHUB_RUN_NUMBER` (1 locally); `versionName '0.2.0'` |
| Native libs | `packaging.jniLibs.useLegacyPackaging true`, so the Rust binary is extracted to `nativeLibraryDir` and can run as a program |
| Lint | `checkReleaseBuilds false`, `abortOnError false` |
| Dependencies | none at runtime; `testImplementation 'junit:junit:4.13.2'`; `testOptions.unitTests.returnDefaultValues = true` |
| Signing | `release` uses env `BOATCAST_KEYSTORE`, `BOATCAST_KEYSTORE_PASSWORD`, `BOATCAST_KEY_ALIAS` (default `boatcast`); the key password equals the store password (PKCS12). With no env, it falls back to the auto-generated debug key. |

**Why `targetSdk 28` (deliberate; don't raise it without redesigning):**
- **Joining Wi-Fi without a dialog:** `WifiManager.addNetwork()` and `enableNetwork()` let the app join a hotspot silently. They always fail for apps targeting API 29+, which would need `WifiNetworkSpecifier` and a user dialog for every join.
- **Simple permissions:** Bluetooth uses the legacy BLUETOOTH and BLUETOOTH_ADMIN permissions, and location is the only runtime permission. The local-only hotspot needs location permission plus Location switched on.
- **Fewer Android 14+ restrictions:** no foreground-service type and no runtime notification-permission handling are required.
- **The catch:** Play Protect may warn that the app is "built for an older version of Android". That's expected for a sideloaded app; the user taps *Install anyway*.

### 6.2 Manifest

- **Permissions:** INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, WAKE_LOCK, FOREGROUND_SERVICE, POST_NOTIFICATIONS, BLUETOOTH, BLUETOOTH_ADMIN.
- **Features:** `android.hardware.wifi` required; `bluetooth_le` not required.
- **Application:** `usesCleartextTraffic="true"` (the WebView and HttpURLConnection talk plain HTTP to 127.0.0.1 and LAN IPs), `allowBackup=false`, theme `@android:style/Theme.Material.NoActionBar`.
- **Activity `.MainActivity`:** exported, `launchMode=singleTask`, with configChanges for orientation and screen size so rotating doesn't reload the WebView.
- **Service `.CastService`:** not exported.
- **Icons:** `res/mipmap-*/ic_launcher.png` at 48, 72, 96, 144 and 192 px, made from `web/icon-512.png`.

### 6.3 The Rust binary inside the APK

- **Build:** CI compiles the server for `aarch64-linux-android`, `armv7-linux-androideabi` and `x86_64-linux-android` with the NDK clang linkers:
  - `aarch64-linux-android26-clang`
  - `armv7a-linux-androideabi26-clang`
  - `x86_64-linux-android26-clang`

  The linker is set through `CARGO_TARGET_<TRIPLE>_LINKER`, with the NDK from `$ANDROID_NDK_LATEST_HOME`.
- **Placement:** each build is copied to `android/app/src/main/jniLibs/<abi>/libboatcast.so`. That folder is gitignored and filled only in CI.
- **Running it:** `ProcessBuilder(nativeLibraryDir + "/libboatcast.so", args…)`. Running a program from `nativeLibraryDir` is allowed; running one from the app's data dir is not (W^X).
- **Self-check:** on first launch, `MainActivity.selfCheck()` runs `libboatcast.so -h` and logs `self-check ok` (the emulator test checks for it).

### 6.4 Classes

**`MainActivity`** (UI built in code, no XML layouts)
- **Layout:** a status line; buttons **Host**, **Join**, **Pick slides** and **Stop**; a tab row; a WebView.
- **Tab row** (shown only while this phone has its own hotspot, i.e. hosting or repeating):
  - **Controls** (labelled **Slides** when joined): the page `CastService` reports — `/console.html` for a host, the viewer page for a joined phone.
  - **Phones see:** `http://127.0.0.1:8080/?observer=1`, the same viewer page every phone gets. The `observer` flag keeps this preview out of the phone count and out of the delivery test.
  - **Share:** a local HTML page loaded with base URL `http://127.0.0.1:8080/`. It shows `/qr/wifi.svg`, the Wi-Fi name, the password, `/qr/url.svg` and the address. A link to `boatcast:share` is intercepted in `shouldOverrideUrlLoading` and opens the Android share sheet (`ACTION_SEND` text).
- **Permissions flow:** `withPermission()` asks for location, then checks that Location is on (otherwise it opens the settings screen). **Join** also asks for Bluetooth to be enabled.
- **Pick slides:** `ACTION_OPEN_DOCUMENT` with multi-select (images, txt, json). Files are copied into `files/deck.new`. Only after every copy succeeds is the swap made (`deck` → `deck.old`, `deck.new` → `deck`, delete `deck.old`), so a failed import leaves the old deck untouched. Files named `.txt` become `slides.txt` and `.json` becomes `deck.json`; other non-image types are skipped. If hosting, it POSTs `{"action":"reload"}`.
- **Starter deck:** `ensureStarterDeck()` writes a two-slide `slides.txt` when the deck folder is empty.

**`CastService`** (foreground service; all logic runs on a `HandlerThread` called `boatcast-mesh`)
- **Starting up:** `startForeground` is called first in every `onStartCommand`.
- **Keeping awake:** it holds `WIFI_MODE_FULL_HIGH_PERF` and (API 29+) `WIFI_MODE_FULL_LOW_LATENCY` Wi-Fi locks, which keep the radio out of power save (a latency win of up to hundreds of ms per hop), plus a `PARTIAL_WAKE_LOCK`.
- **Node id:** a random u16 kept in SharedPreferences `boatcast`/`node`. The mesh id is a random u16 per hosting session.
- **HOST mode:**
  1. `startLocalOnlyHotspot`, then read the SSID and passphrase (`getSoftApConfiguration()` on API 30+, `getWifiConfiguration()` below).
  2. Find the hotspot IP with `hotspotIp()`.
  3. Start the server: `ServerProc.start(serve --deck … --mesh-id … --port 8080 --host-ip IP --wifi-ssid … --wifi-pass …)`.
  4. On ready: serve the credentials over GATT, publish the console URL, and start `hostTick` every 5 s, which refreshes the BLE advert (depth 0, free slots = 8 − clients).
- **Hotspot loss:** in host mode it retries every 3 s, up to 3 times in 5 minutes. In join mode it stops repeating.
- **JOIN mode:** `tick()` runs every 4 s (§8).
- **Wi-Fi joining:** `joinWifi()` uses deprecated `addNetwork` and `enableNetwork(id, true)`, then waits up to 20 s for the parent's `/status`. Network ids it added are removed in `onDestroy`.
- **Process binding:** `bindToWifi()` binds the app process (Java HTTP and the WebView) to the Wi-Fi network, so viewing works even with mobile data on. **The Rust child process is not bound**, so a repeater needs Wi-Fi as the default network, meaning mobile data off. `canRepeat()` checks this.
- **For the Share tab:** static `shareSsid`, `sharePass`, `shareUrl`.
- **For the UI:** `hosting`, `isRunning()`, `setListener()` and `publish(status, running, pageUrl)`.

**`ServerProc`**
- Supervises the child process: starts it, then waits up to 6 s for `/status` to answer before calling `onReady`.
- **Restarts:** after an unexpected exit it restarts with growing pauses (1 s × count), up to 5 restarts in 2 minutes, then calls `onGaveUp`.
- **Logging:** forwards the server's output to logcat with tag `boatcast`.
- **Helpers:** `status()`, `control(json)` and the static `get(url, timeout)`.

**`MeshRadio`** (BLE)
- **Advert:** a 16-bit service UUID `0000b0a7-0000-1000-8000-00805f9b34fb`, both as a service UUID (used by the scan filter) and as service data carrying the 8-byte payload. Device name and TX power are left out, to stay within 31 bytes.
- **Payload (8 bytes):** `[mesh hi, mesh lo, id hi, id lo, depth (255 = seeking), free slots, battery %, flags (bit0 accepting, bit1 charging)]`. See `MeshPlanner.encode` and `decode`.
- **GATT:** service `6b6f6174-6361-7374-0001-000000000001`, characteristic `…0002` (read-only). It returns `{"ssid","pass","ip","port"}` and handles long reads by offset. The client requests MTU 247 and has a 10 s timeout.
- **Scanning:** low-latency while seeking, low-power once connected. Hosts and repeaters advertise connectable; a seeking phone advertises non-connectable with depth 255.

**`MeshPlanner`** (pure Java, unit-tested)

The constants:

| Constant | Value |
|---|---|
| `CAPACITY` | 8 |
| `SEEKING` | 255 |
| `STALE_MS` | 15 000 |
| `MIN_RSSI` | −85 |
| `HYSTERESIS` | 60 |
| `MIN_SWITCH_GAP_MS` | 60 000 |
| `POISON_WAIT_MS` | 36 000 |

The methods:
- **`score(n)`:** `depth×100 + (8 − free)×8 + max(0, −55 − rssi)×2 + 150 if battery < 30 and not charging`. Lower is better.
- **`choose(nodes, myMesh, myId, maxDepth, avoid, now)`:** the lowest-score node that is accepting, has free > 0, is fresh, has rssi ≥ −85, has depth < maxDepth, is in the same mesh (if a mesh is set) and isn't being avoided.
- **`maxParentDepth(hasChildren, orphan, myDepth, msSinceOrphaned)`:**
  - no children: anything goes;
  - connected with children: only shallower than itself;
  - orphan with children: shallower for 36 s, then anything (by then its subtree has been poisoned).
- **`shouldSwitch(cur, best, gap)`:** only if the gap is ≥ 60 s and `score(best) + 60 < score(cur)`.
- **`shouldRepeat(capable, battery, charging, repeating, children, roomNearby, seekers)`:**
  - **No:** if the phone isn't capable, or if not charging and battery < 30 (< 20 if it's already repeating).
  - **Yes:** if it's repeating with children, or if any phone nearby is seeking.
  - **Otherwise it depends on spare repeaters in reach:** start when fewer than 2; keep going while fewer than 4.

---

## 7. CI/CD (`.github/workflows/build.yml`)

**Triggers:** push to `main`, pull_request, workflow_dispatch, and `release: published`.

| Job | What it does |
|---|---|
| `test` | `cargo test --release`, `cargo build --release`, `bash tests/smoke.sh` |
| `linux` (matrix) | musl static builds for x86_64, aarch64 and armv7hf with `cargo zigbuild` (pip `ziglang` and `cargo-zigbuild` under setup-python 3.12) |
| `desktop` (matrix) | Windows x86_64 msvc, macOS aarch64 and Linux x86_64 gnu, all with `--features tls` |
| `android` | Rust for 3 ABIs, then the jniLibs; `gradle -p android testReleaseUnitTest`; signing-key step; `assembleRelease`; copy to `dist/boatcast.apk`; check the APK (`aapt2 dump badging`, `apksigner verify`); upload artifact `boatcast-android` |
| `android-emulator` | KVM udev rule; `reactivecircus/android-emulator-runner@v2` (API 33, x86_64, google_apis); runs `tests/android-emulator.sh`, **retrying once** because the emulator's Google services sometimes crash and take the app down with them |
| `website-apk` | Push to main only. Needs `android` and `android-emulator`. Downloads the artifact, refuses a throwaway-signed APK, commits `boatcast.apk` to the repo root as `github-actions[bot]` with `[skip ci]`, and pushes with pull --rebase retries. Pages then serves it. |
| `release` | Only on `release: published`. Downloads every artifact, refuses a throwaway-signed APK, attaches everything to that release with `softprops/action-gh-release@v2`. |

**Signing-key step:**
- **Secrets:** `ANDROID_KEYSTORE_B64` (a base64 PKCS12 keystore, alias `boatcast`) and `ANDROID_KEYSTORE_PASSWORD`.
- **Tolerant parsing:** the owner pasted the whole instruction file into the secret once, so:
  - the key is taken as the first whitespace-separated run of 1,000+ characters (or failing that, every base64 character);
  - the password is taken as the line after `Value:` under `ANDROID_KEYSTORE_PASSWORD` if the file was pasted (or the trimmed value otherwise).
- **Check:** it validates with `keytool -list`, and on failure gives a clear error that mentions the character count. The real key is 4,584 base64 characters.
- **No secrets:** it writes the `dist/THROWAWAY-KEY` marker, and nothing gets published.

---

## 8. The mesh, step by step (`CastService.step()`, every 4 s in JOIN mode)

1. Scan BLE (eagerly if there's no parent) and bind the process to Wi-Fi. While a join is in progress, do nothing else.
2. **Parent health:** GET the parent's `/status`.
   - A valid depth resets the fail count and updates the parent's depth, free slots and mesh id.
   - A missing reply or a `null` depth counts as a failure. Three failures in a row mean the parent is lost:
     - avoid that node id and URL for 60 s;
     - become an orphan (`oldDepth`, `orphanSince`).
3. **No parent:**
   - First try the Wi-Fi gateway (the phone may have joined by QR code): `http://gw:8080`, then `http://gw:80`, which must be a Boatcast with a depth.
   - Otherwise `choose()` among BLE nodes, then `joinNode()`: GATT credentials → `joinWifi` → verify `/status` → adopt it.
   - Otherwise advertise "seeking" (or stop advertising, if repeating while cut off) and show "Looking…".
4. **Connected:**
   - `myDepth = parentDepth + 1`.
   - Build the current parent's node, from BLE if heard, else from `/status` plus the Wi-Fi RSSI.
   - Call `choose(maxDepth = maxParentDepth(children > 0, false, myDepth))`; if `shouldSwitch` says yes, call `joinNode(best)`.
5. **Repeat decision:**
   - Count nearby repeaters with room (rssi > −75) and seeking phones (rssi ≥ −85).
   - `canRepeat` requires API 30+, `isStaApConcurrencySupported()`, Wi-Fi as the default network, and no recent hotspot refusal (which blocks it for 10 minutes).
   - Then `shouldRepeat` decides: start the local-only hotspot and `relay --upstream parentUrl`, or call `stopRepeating`.
6. **While repeating:**
   - Keep the relay's upstream equal to `parentUrl`, using loopback `POST /control upstream`.
   - Advertise its own depth from local `/status`, and stop advertising if that depth is `null` (loop guard).
7. **Publish** the role and hop count; the page is the local viewer if repeating, else the parent's viewer.

**Simulation** (`MeshPlannerTest`): signal is modelled as `−40 − 25·log10(d)`; 70% of phones are capable (60% in the 20-room run); battery is 40–100%. All pass:

| Scenario | Result |
|---|---|
| 100 phones | depth ≤ 3 |
| 1,000 phones | depth ≤ 5 |
| 120 phones, 5 busy repeaters removed | healed |
| A repeater's battery drops to 15% | it hands over |
| 20 random rooms of 150 phones, up to 6 repeaters removed in each | all healed |
| 240 m hall (beyond one hotspot's range) | needs ≥ 2 hops, everyone connects |

What every scenario checks: everyone has a path to the host, no hotspot has more than 8 children, and there are no loops.

---

## 9. Website and PWA truths (don't promise otherwise)

- **Plain HTTP:** viewer pages are always plain websites served by the host. A PWA install needs HTTPS or localhost.
- **HTTPS install:** only possible with a laptop host using `--features tls`, a real certificate for a domain the owner controls, and local DNS (dnsmasq) pointing that name at the laptop.
- **Phone hosts and repeaters:** plain HTTP, so no install and no wake lock.
- **GitHub Pages can't serve the live show:** there's no internet at the venue, and an HTTPS page can't read a plain-HTTP LAN server (mixed content; Chrome 142+ also has Local Network Access prompts).
- **Web pages can't repeat:** browsers can't run hotspots or accept connections, so repeaters must be the native app (or Termux plus the binary).
- **iPhones** can watch but can never repeat.
- **Captive mode:** `--port 80 --captive` plus `dnsmasq --address=/#/HOST_IP --address=/mask.icloud.com/`. The second rule returns NXDOMAIN for `mask.icloud.com`, so iCloud Private Relay steps aside.

---

## 10. Tests

| Test | Covers |
|---|---|
| `tests/smoke.sh` (46 checks; needs a built `target/release/boatcast` and curl) | gzip, 304 responses, pages incl. `/console.html`, deck and assets, SSE stream, throttle, errors, PIN/auth from the LAN IP, QR codes, `load_text`, reload, relay follow, depth 0/1, relay counting, upstream switch (403 from the LAN, 200 from loopback), mesh-id inheritance, `null` depth after the upstream dies, captive mode; and the newer ones: delivery test through a relay, observer count, Wi-Fi code set at runtime |
| `tests/portable.py BINARY` (Python standard library only) | core host and relay checks. Runs in CI on Windows, macOS and Linux against each laptop build (the `desktop` job) |
| `tests/browsers.py BINARY` (Playwright) | the viewer page in Chromium, Firefox and WebKit at phone sizes: first slide, slide change under 1 s, live text, blank, reconnect, host restart, delivery test, observer. Console checks in Chromium only. WebKit is the engine Safari uses, but it is not Safari on a real iPhone. Runs in the `browsers` CI job |
| `cargo test` | natural sort |
| `MeshPlannerTest` | the rules and the simulation (§8) |
| `tests/android-emulator.sh APK FALLBACK_BIN` | see the list below |

What `tests/android-emulator.sh` checks:
- **Install and binary:** the APK installs; the binary is extracted. The lib dir is parsed with `sed`, because the path contains base64 with `=`, so don't use `cut -d=`.
- **Server on Android:** the binary runs; a host and a relay on the device follow each other, and a slide change reaches the relay.
- **App:**
  - its buttons exist;
  - the self-check passes;
  - tapping Host starts a hotspot and the server;
  - `/console.html` is served, and the console is visible;
  - the Controls, Phones see and Share tabs exist, and Share shows the codes;
  - Join runs without crashing.
- **CI wiring:** `website-apk` and `release` need the `browsers` job as well as `android` and `android-emulator`.
- **Last confirmed:** all hard checks passed on the emulator. It got a real local-only hotspot with an address like `192.168.x.y`.

**Local Java check without the Android SDK:** compile against an `android.jar` with `javac --release 11 -cp android-30.jar`. Run `MeshPlannerTest` with a tiny JUnit stub (`org.junit.Test` and `Assert`) and a reflection runner.

---

## 11. Gotchas already hit (don't repeat them)

1. **The app opened `/console.html` but only `/console` existed.** Both are now served, and the test covers it.
2. **The APK signing key was committed publicly in 0.1.0.** It's removed and replaced with secrets. The old key is still in git history, so 0.1.0 is untrusted, and 0.1.0 installs must be removed before installing 0.2.x.
3. **The owner pasted the whole secrets file into the secret.** The parsing is now tolerant (§7).
4. **The emulator's Google services can die and kill the app** ("depends on provider … in dying proc"). It's handled by retrying once; it isn't an app bug.
5. **`legacyNativeLibraryDir` paths contain `=`.** Parse them with `sed`.
6. **Hotspot IP detection:** prefer `ap*`, `swlan*`, `softap*`, `wlan1`/`wlan2`; then other `wlan*`/`wl*`/`wifi*`; and only late in the wait any other private address. Skip mobile-data interfaces (`rmnet`, `ccmni`, `seth`, `ccinet`, `pdp`, `ppp`, `wwan`, `tun`, `dummy`, `p2p`, `rndis`, `usb`, `v4-`, `clat`). The emulator's `eth0` (10.0.2.15) used to win before this ordering.
7. **Android 11+ gives the hotspot a random subnet each session.** Always read the IP fresh, and pass `--host-ip`.
8. **A repeater has two interfaces.** Its page URL must be the hotspot IP, not the upstream subnet. `config.rs` skips the upstream subnet.
9. **Mobile data on makes Android route around an internet-less Wi-Fi.** Viewers must turn mobile data off; repeaters require it (the child process isn't network-bound).
10. **PowerPoint exports `Slide10` before `Slide2` in text order.** Natural sort fixes it.
11. **Restrictions that apply to a Claude session (not to the owner):**
    - **Blocked:** pushing tags, creating releases, setting secrets and rerunning workflows (403).
    - **Allowed:** pushing to `main`.
    - **Work-around:** publishing goes through `website-apk` (and `release` when the owner publishes one).
    - **Can't reach from the sandbox:** github.io and artifact blob downloads. Verify through `gh api repos/LeeMcQ/Boatcast/contents/boatcast.apk` and CI logs (`gh run view --job ID --log`) instead.
12. **The "Phones see" tab used to count as a phone.** It now loads `/?observer=1`, so the viewer uses `/events?observer=1`, which the server leaves out of `clients` and which does not answer test signals. Any other preview of the viewer page should use the same flag, or it will add 1 to `clients` and lower the advertised free slots by 1.
13. **Phones stayed stuck on the old slide after the host restarted.** `seq` starts again at a low number on a new host, and the viewer ignored any `seq` not above the last one it had seen, so phones showed the old slide until `seq` passed the old value. Fixed: every `state` event carries `boot` (random per host start), and the viewer compares `(boot, seq)`. `tests/browsers.py` covers the host restart.

---

## 12. How to rebuild the APK from scratch

**The normal way:** push to `main`. CI builds, tests, signs and publishes to https://leemcq.github.io/Boatcast/boatcast.apk within about 10 minutes.

**On a machine with the Android SDK and NDK, JDK 17, Rust and Gradle 8.11.1:**

```sh
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
NDK_BIN=$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/linux-x86_64/bin   # darwin-x86_64 on macOS
CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$NDK_BIN/aarch64-linux-android26-clang \
  cargo build --release --target aarch64-linux-android
CARGO_TARGET_ARMV7_LINUX_ANDROIDEABI_LINKER=$NDK_BIN/armv7a-linux-androideabi26-clang \
  cargo build --release --target armv7-linux-androideabi
CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER=$NDK_BIN/x86_64-linux-android26-clang \
  cargo build --release --target x86_64-linux-android
for p in "aarch64-linux-android arm64-v8a" "armv7-linux-androideabi armeabi-v7a" "x86_64-linux-android x86_64"; do
  set -- $p; mkdir -p android/app/src/main/jniLibs/$2
  cp target/$1/release/boatcast android/app/src/main/jniLibs/$2/libboatcast.so
done
export BOATCAST_KEYSTORE=/path/release.jks BOATCAST_KEYSTORE_PASSWORD=…   # omit for a debug-signed test build
gradle -p android testReleaseUnitTest assembleRelease
# output: android/app/build/outputs/apk/release/app-release.apk
```

Keep the same keystore forever, or installed apps can't update. If the key changes, every user must uninstall first.

---

## 13. Open work, in suggested order

1. **Real-phone field test:** follow `FIELD-TEST.md` (six stages; each must pass before the next). It uses the delivery test (§4.7) and also covers the real hotspot limit, failover time when a repeater is switched off, and battery use over 60 minutes for a host, a repeater and a viewer. Write the results into the README. Capacity and battery claims stay unverified until then.
2. **Real per-hotspot limit:** read it from the device (it's not public API; keep 8) and confirm how many clients each make actually accepts.
3. **A repeater on API < 30** is currently never allowed, because `isStaApConcurrencySupported` doesn't exist there. Maybe allow it after a trial: start the hotspot, then check the station link is still up.
4. **Encrypt or PIN-gate the GATT credentials** if the Wi-Fi password should not be readable by anyone nearby. It's low risk today: the same password is shown as a QR code on screen.
5. **Bring the doc-only extras into the app:** PIN-protected remote console from another phone, the presenter notes view.
6. **Raise `targetSdk` only with a redesign:** it would need `WifiNetworkSpecifier`, a dialog for each join and the new Bluetooth permissions (§6.1).

---

## 14. Release tiers and the delivery test

The product is released in tiers. Each tier must pass its field test (`FIELD-TEST.md`) before the next is relied on.

- **Tier 1 (the release):** one host, phones on its hotspot, any browser (Android, iPhone, Windows, Mac). Everything in CI covers this tier.
- **Tier 2:** repeaters through Join mode (Android app only). Simulation-tested and emulator-tested. Treat it as a bonus until the field test passes on the owner's phones.
- **The access point and the host don't have to be the same device.** Any phone's ordinary hotspot can be the Wi-Fi, with a laptop that joined it running Boatcast. This is the recommended way to use a Windows laptop: Windows usually refuses to start its own Mobile Hotspot when it has no internet connection to share, and its hotspot takes 8 devices. Whether phones on a given hotspot can reach each other depends on the phone that makes the hotspot. Don't promise it; the field test (Stage 3) checks it.
- **iPhones are viewers only, for good.** iOS gives apps no way to run a hotspot or a server for other devices in this way. Tips for iPhone viewers: turn mobile data off, or tap "Keep trying Wi-Fi" or "Use without internet" if asked; open the page from the QR code so the address stays `http://`.
- **The delivery test** (§4.7) is how each stage is measured. Nothing has been run on real phones yet.

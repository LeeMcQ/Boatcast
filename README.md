# Boatcast

Live slides and words from one phone or laptop to many phones over Wi-Fi. There's no internet and no router. Viewers only need a browser: they scan a QR code to join the Wi-Fi, then another to open the page.

- **Host**: one small Rust program, 1.1 MB. It runs on a laptop, in an Android app, in Termux, or on a router.
- **Viewers**: any phone browser. A slide change reaches every phone in under 50 ms at 5,000 phones in a lab test. On real Wi-Fi it's typically 0.1–0.3 s.
- **More than 10 phones**: a phone's hotspot takes about 10. In **Join** mode, Android phones with the app build a **mesh** by themselves: each finds the best nearby host or repeater, joins it, and becomes a repeater when phones nearby need a way in. The mesh re-arranges itself when phones leave or run low on battery.

```
                 Host phone or laptop  (boatcast serve)
                 hotspot: ~10 slots (phone), 8 (Windows), 30+ (Linux laptop)
            ┌──────────────┬──────────┴───┬──────────────┐
       Repeater 1     Repeater 2     Repeater 3      phones…
     (boatcast relay)     …              …
      own hotspot
   ┌────┬────┬─────┐
 phone phone … Repeater 1a  (chains: ~10 → ~100 → ~1,000)
```

## Quick start

### Phone as the host (Android app)

1. Download `boatcast.apk` from **Releases** and install it. Android will ask you to allow installing apps from your browser.
2. Open Boatcast and tap **Host**. Allow Location when asked; Android needs it before an app can start a hotspot.
3. The console opens with two QR codes. Viewers scan the **Wi-Fi code**, then the **page code**.
4. To use your own slides, tap **Pick slides** and choose images, a `slides.txt` or a `deck.json`. For images from PowerPoint, use File → Export → PNG. You can also type or paste slides in the console.

### Laptop as the host

**Windows or Mac: double-click.**

1. Download the program for your laptop from **Releases**.
2. Double-click it. It does four things:
    - It creates a folder called `slides` next to the program and uses it. Put slide images, a `slides.txt` or a `deck.json` there. While the folder is empty, you see the sample slides.
    - It opens the presenter console in your browser.
    - On Windows, it reminds you about the firewall. When Windows asks, tick **both Private and Public** networks, then allow access.
    - If it can't start, it keeps its window open with a message. The usual cause is that Boatcast is already running.
3. In the console, type the Wi-Fi name and password into the **Wi-Fi name** and **password** fields, then press **Show Wi-Fi code**. Viewers scan that code, then the page code.
4. To pick up new files in `slides`, press **Reload slides from folder**.

The program isn't signed with a paid developer certificate, so each system asks once before running it (these two notes are from how the systems normally behave, not from a test on real laptops):
- **Windows:** if "Windows protected your PC" appears, choose **More info**, then **Run anyway**.
- **Mac:** a downloaded file must first be made runnable. In Terminal, run `chmod +x` followed by the file, then right-click the file in Finder and choose **Open**.

**From a terminal.** The options still work as before:

```sh
boatcast --deck my-slides --wifi-ssid "Hall WiFi" --wifi-pass "secret123"
```

Then turn on the laptop's hotspot with that name and password. Open `http://localhost:8080/console.html` on the laptop and put the join card on the projector.

**Recommended for a Windows laptop: let a phone make the hotspot.**
1. On an Android phone, turn on its ordinary hotspot.
2. Join the laptop to that hotspot.
3. Start Boatcast on the laptop (double-click).
4. Type the hotspot's name and password into the console and press **Show Wi-Fi code**.

Windows usually refuses to start its own Mobile Hotspot when the laptop has no internet connection to share. Its hotspot also takes only 8 devices. The Wi-Fi and the host don't have to be the same device.

Whether phones on a given hotspot can reach each other depends on the phone that makes the hotspot. Check it with the delivery test (below and in [FIELD-TEST.md](FIELD-TEST.md), Stage 3).

How many phones a laptop's own hotspot takes depends on the system:
- **Windows Mobile Hotspot**: up to 8 phones.
- **Linux with `hostapd`**: usually 30 or more, depending on the Wi-Fi card.

On either one, add repeaters for more phones.

## Check it works: the delivery test

Run this before the day, with the phones you will use.

1. Open the presenter console.
2. Find the **Delivery test** panel and press **Run test**.
3. The host sends 20 numbered test signals, 1.5 seconds apart, down the same live connection the slides use. The slides on screen don't change.
4. Every phone's page answers each one. The console shows, per phone: device type, hops (repeaters between host and phone), how many of the 20 arrived, typical and worst time in ms, and how many times the page had to reconnect.
5. The times are "there and back", so the real delay is lower.
6. The verdict line is green when nothing was lost and 95% of answers took 1 second or less.
7. **Copy results** copies the table as CSV.

Phones that loaded the page before this update must reload it once.

The full step-by-step sheet for real phones is in [FIELD-TEST.md](FIELD-TEST.md). Nothing here has been tested on real phones yet.

## Release tiers

The product is released in tiers. Each tier must pass its field test before the next one is relied on.

- **Tier 1 (the release):** one host, with phones on its hotspot, in any browser (Android, iPhone, Windows, Mac). Everything in the automated tests covers this tier.
- **Tier 2:** repeaters through Join mode (Android app only). It is tested in simulation and on an emulator. Treat it as a bonus until the field test passes on real phones.
- **The access point and the host can be different devices.** Any phone's ordinary hotspot can be the Wi-Fi, with a laptop that joined it running Boatcast.
- **iPhones are viewers only.** iOS gives apps no way to run a hotspot or a server for other devices in this way. iPhone tips: turn mobile data off, or tap **Keep trying Wi-Fi** or **Use without internet** if asked. Open the page from the QR code so the address stays `http://`.

## Join mode: the self-building mesh

Tap **Join** on any Android phone with the app. With no codes to scan, the phone:

1. **Finds the show.** Hosts and repeaters announce themselves over Bluetooth LE: mesh, depth (hops from the host), room left and battery. It also uses the Wi-Fi it's already on, if you scanned a code.
2. **Picks the best way in.** Fewest hops first, then the most room, then the strongest signal, avoiding phones with low battery.
3. **Joins by itself.** It reads that hotspot's Wi-Fi name and password over a short Bluetooth connection, joins it, and shows the slides.
4. **Repeats when useful.** If phones nearby are still looking, or fewer than two repeaters with room are in reach, it starts its own hotspot and relays. This needs a phone that can keep Wi-Fi and hotspot on together, battery at 30% or more (or charging), and mobile data off.
5. **Keeps adjusting** every 4 seconds:
    - If its parent stops answering, it moves to the next best one.
    - If a clearly better parent appears, it moves, at most once a minute.
    - Below 20% battery it stops repeating, and its phones move elsewhere.
    - Idle repeaters stand down when there are plenty nearby.

**No loops.** A repeater that loses its link to the host stops advertising and reports no depth, and so does everything below it. It then rejoins a node that still has a path to the host.

**Tested so far.** The rules are unit-tested, and a simulation checks rooms of 100, 150, 200 and 1,000 phones. Every phone connects, no hotspot goes over 8 phones, there are no loops, and the mesh heals when repeaters leave or run low. The simulation covers 20 random rooms and a 240 m hall beyond one hotspot's range.

The simulation models radio in a basic way, with signal falling off with distance. It shows the rules work, not that real phones and Wi-Fi will. Capacity and battery life on real phones are **not measured yet**.

**What it can't do**
- Phones without the app, including all iPhones, can still watch, but they join by scanning a code and don't move by themselves if their repeater leaves.
- iOS doesn't let apps run a hotspot, so iPhones never repeat.
- A web page or PWA can't repeat. Browsers can't start a hotspot or accept connections.

## Repeaters: more than 10 phones

A repeater is an Android phone that does three things at once:
1. Stays joined to the host's Wi-Fi.
2. Runs its own hotspot.
3. Runs `boatcast relay`.

The relay follows the host's live stream, copies the slides once, and serves them to the phones on its own hotspot. Each phone on the repeater gets its own QR codes from the repeater's screen.

**Steps on the repeater phone**
1. Scan the host's Wi-Fi code to join it. You can also join another repeater, which builds a chain.
2. Turn **mobile data off**. Otherwise Android sends traffic over mobile data instead of the host's Wi-Fi.
3. Open Boatcast and tap **Join**. It finds the host on its own and repeats when phones nearby need it.
4. The phones near this repeater scan the repeater's two codes.

**What a repeater needs**
- An Android phone that can be on Wi-Fi and run a hotspot at the same time ("STA + AP concurrency"). Most phones from about 2020 on can. Samsung calls this "Wi-Fi sharing". The app checks for it and says if the phone can't.
- iPhones can watch from any layer, but iOS doesn't let apps run a hotspot, so they can't be repeaters.

**Why it can't be a web page or a PWA**

Browsers aren't allowed to start a hotspot or accept connections from other phones. A repeater has to be a native program: the Android app, or the same binary in Termux.

**How much delay each repeater adds**

Measured on one machine at 10, 1,000 and 5,000 phones per tier: a relay adds about 0.15 ms of processing per hop. On real Wi-Fi each hop adds one more radio trip.

The app holds a high-performance Wi-Fi lock, so the repeater's radio doesn't doze between beacons. Each hop should then add a few ms to tens of ms, and two tiers stay well within the 1–2 s target. This hasn't been measured on real phones yet.

| Phones per tier | Time to last phone: host | via 1 repeater | via 2 repeaters |
|---|---|---|---|
| 10 | 0.33 ms | 0.50 ms | 0.50 ms |
| 1,000 | 9.3 ms | 9.2 ms | 9.0 ms |
| 5,000 | 45.6 ms | — | 44.2 ms |

**Repeaters without the app (Termux)**

On the repeater phone, install Termux from F-Droid, then run:

```sh
termux-wake-lock
curl -LO https://github.com/LeeMcQ/Boatcast/releases/latest/download/boatcast-aarch64-linux-android
chmod +x boatcast-aarch64-linux-android
./boatcast-aarch64-linux-android relay --upstream http://HOST_IP:8080 --wifi-ssid "REPEATER_HOTSPOT" --wifi-pass "ITS_PASSWORD"
```

Then turn on the phone's own hotspot alongside Wi-Fi. On Samsung that's Settings → Mobile Hotspot → Wi-Fi sharing.

## Website and installable app (PWA)

Every viewer page is a normal website, so it works in any phone browser over plain `http://`.

Installing it as an app needs HTTPS, which means a laptop host with a real certificate:
1. Build with `--features tls`.
2. Run with `--tls-cert`, `--tls-key` and `--public-url https://show.example.com:8443/`.
3. Point that name at the laptop with a local DNS server, such as `dnsmasq` in captive mode.

Once installed, the page opens full screen with no browser bar. The phone keeps the slide images it has already loaded.

When a phone is the host, the page is plain HTTP and can't be installed. It works the same in the browser.

## Running modes

| Mode | Command |
|---|---|
| Laptop host (double-click) | run the program with no options; it uses a `slides` folder next to it and opens the console |
| Laptop host (terminal) | `boatcast --deck DIR --wifi-ssid NAME --wifi-pass PASS` |
| Laptop host with control from a phone | add `--pin 4321`, then open `/console.html` on the phone and enter the PIN |
| Captive portal (page pops up on join) | `sudo boatcast --port 80 --captive` plus a DNS wildcard: `dnsmasq --address=/#/HOST_IP --address=/mask.icloud.com/` |
| HTTPS / PWA install | `boatcast --tls-cert fullchain.pem --tls-key key.pem --public-url https://show.example.com:8443/` |
| Repeater | `boatcast relay --upstream http://HOST_IP:8080` |
| Big host (many cores) | `--threads auto` |

Run `boatcast -h` to see every option.

## Slides

Put one of these in a folder and pass `--deck FOLDER`, or pick the files in the app:

- **Images**: `Slide1.png`, `Slide2.png`, … shown in number order (Slide10 comes after Slide9). PNG, JPEG, WebP, GIF and SVG all work.
- **`slides.txt`**: one slide per block, with a blank line between slides. A line starting with `# ` is the title.
- **`deck.json`**: `{"title": "...", "slides": [{"title": "...", "text": "...", "image": "file.webp", "notes": "presenter only"}]}`

From the console you can also go to any slide, blank the screens, send a live text line, paste new slides, or press **Reload slides from folder**. Phones can swipe back through earlier slides and tap **Back to live**.

## How it stays light

- **Content**: each phone downloads the slides once. The files have content-hashed names, so phones cache them forever.
- **Live channel**: after that, each phone keeps one open Server-Sent Events stream and receives about 200 bytes per slide change.
- **Fast clicking**: rapid clicks merge. The first goes out at once, and the latest within 150 ms follows it.
- **Adapts to crowd size**: with more phones it preloads fewer slides at once and spreads reconnects out. `--threads auto` uses more CPU cores.
- **Measured cost**: about 3 MB at idle and about 6 KB per connected phone. At 5,000 phones the host uses 45 ms of CPU per slide change.

## API

| Request | What it does |
|---|---|
| `GET /events` | live stream (`policy`, `state`, `ping` and `probe` events). Add `?observer=1` for a viewer that should not be counted as a phone |
| `GET /deck.json` | slides without notes |
| `GET /status` | mode, phones connected, slide, upstream, join URLs |
| `POST /control` `{"action": "next" \| "prev" \| "first" \| "goto" \| "blank" \| "text" \| "load_text" \| "reload" \| "wifi" \| "test" \| "test_stop"}` | changes the show. Works from localhost, or from elsewhere with `X-Pin` |
| `POST /control` `{"action":"wifi","ssid":"…","pass":"…"}` | sets the Wi-Fi name and password for the Wi-Fi code. An empty `ssid` clears it. `/status` then shows it in `ssid` and `wifi_qr` |
| `POST /control` `{"action":"test","count":1-100,"gap_ms":300-10000}` | starts a delivery test. `{"action":"test_stop"}` ends it |
| `GET /api/test` | results of the delivery test (same auth as control). A relay answers 409 |
| `POST /api/ack` `{"run","n","id","rc"}` | how a viewer page answers a test signal |
| `GET /api/deck-full` | slides with presenter notes (same auth as control) |

## Building

```sh
cargo build --release                  # lean, 1.1 MB
cargo build --release --features tls   # with HTTPS, 2.3 MB
cargo test --release && bash tests/smoke.sh
python tests/portable.py target/release/boatcast
python tests/browsers.py target/release/boatcast   # needs Playwright
```

The tests:
- `cargo test`: unit tests.
- `tests/smoke.sh`: 46 end-to-end checks against the real binary. They include the delivery test through a relay, the observer count and the Wi-Fi code set while running.
- `tests/portable.py`: core host and relay checks, using only the Python standard library. It runs the same on Windows, macOS and Linux.
- `tests/browsers.py`: uses Playwright to open the viewer page in Chromium, Firefox and WebKit at phone sizes. It checks the first slide, a slide change in under 1 s, live text, blank, reconnect, a host restart, the delivery test and the observer. It also checks the console in Chromium. WebKit is the engine Safari uses, but it is not Safari on a real iPhone.
- `tests/android-emulator.sh`: the Android emulator test.

GitHub Actions tests and builds everything on each push, including a `browsers` job and an Android emulator test:
- Linux musl static binaries for x86_64, ARM64 and ARMv7.
- Windows, macOS and Linux builds with HTTPS. Each is checked with `tests/portable.py`.
- Android binaries and the APK.

To publish downloads, create a release on GitHub (Releases → Draft a new release → tag such as `v0.1.0` → Publish). The build attaches the APK and every binary to it within about 10 minutes. Both the website APK update and the release wait for the `browsers` job.

The Android app is in `android/`: plain Java with no libraries. It bundles the Rust server as `lib/<abi>/libboatcast.so` and runs it as a program. The mesh rules and the simulation are in `MeshPlanner.java` and `MeshPlannerTest.java`.

**Signing.** The APK is signed with a private release key that the repository never holds. You add it once as two GitHub secrets, `ANDROID_KEYSTORE_B64` and `ANDROID_KEYSTORE_PASSWORD` (Settings → Secrets and variables → Actions). If they're missing, the build uses a throwaway key and the release step refuses to publish the APK.

Version 0.1.0 was signed with a key that was public in this repository. Delete that release, and uninstall 0.1.0 from any phone before installing a newer version.

## Limits to know

- **Phone hotspot size**: about 10 phones, set by the maker. Apps can't raise it, so use repeaters. The mesh counts on 8 per hotspot.
- **Capacity and battery not measured on real phones**: figures like 100 or 1,000 phones come from the simulation and the server load test, not from a real room of phones.
- **Viewers and mobile data**: viewers must turn mobile data off if the page doesn't load. Android prefers mobile data when the Wi-Fi has no internet.
- **Hotspot address changes**: Android 11 and later give the hotspot a new address each session. The app reads it fresh every time, so always use the codes on screen.
- **Not tested on real phones**: the automated tests use desktop browsers and an emulator. Follow [FIELD-TEST.md](FIELD-TEST.md) to find out. On an Android 13 emulator in CI, the app installs, runs its server, starts a local-only hotspot when you tap Host, and a relay follows a host. Hotspot behaviour and STA + AP support vary by maker, so try three to five phones before the day.

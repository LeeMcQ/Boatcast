# Boatcast

Live slides and words from one phone or laptop to many phones over Wi-Fi. There's no internet and no router. Viewers only need a browser: they scan a QR code to join the Wi-Fi, then another to open the page.

- **Host**: one small Rust program, 1.1 MB. It runs on a laptop, in an Android app, in Termux, or on a router.
- **Viewers**: any phone browser. A slide change reaches every phone in under 50 ms at 5,000 phones in a lab test. On real Wi-Fi it's typically 0.1–0.3 s.
- **More than 10 phones**: a phone's hotspot takes about 10. Android phones can act as **repeaters**, each adding about 10 more, and repeaters can chain.

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

```sh
boatcast --deck my-slides --wifi-ssid "Hall WiFi" --wifi-pass "secret123"
```

Then turn on the laptop's hotspot with that name and password. Open `http://localhost:8080/console.html` on the laptop and put the join card on the projector.

How many phones a laptop hotspot takes depends on the system:
- **Windows Mobile Hotspot**: up to 8 phones.
- **Linux with `hostapd`**: usually 30 or more, depending on the Wi-Fi card.

On either one, add repeaters for more phones.

## Repeaters: more than 10 phones

A repeater is an Android phone that does three things at once:
1. Stays joined to the host's Wi-Fi.
2. Runs its own hotspot.
3. Runs `boatcast relay`.

The relay follows the host's live stream, copies the slides once, and serves them to the phones on its own hotspot. Each phone on the repeater gets its own QR codes from the repeater's screen.

**Steps on the repeater phone**
1. Scan the host's Wi-Fi code to join it. You can also join another repeater, which builds a chain.
2. Turn **mobile data off**. Otherwise Android sends traffic over mobile data instead of the host's Wi-Fi.
3. Open Boatcast and tap **Repeater**. It finds the host on its own. If it can't, type the address shown under the host's page code.
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
| Laptop host | `boatcast --deck DIR --wifi-ssid NAME --wifi-pass PASS` |
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

From the console you can also go to any slide, blank the screens, send a live text line, or paste new slides. Phones can swipe back through earlier slides and tap **Back to live**.

## How it stays light

- **Content**: each phone downloads the slides once. The files have content-hashed names, so phones cache them forever.
- **Live channel**: after that, each phone keeps one open Server-Sent Events stream and receives about 200 bytes per slide change.
- **Fast clicking**: rapid clicks merge. The first goes out at once, and the latest within 150 ms follows it.
- **Adapts to crowd size**: with more phones it preloads fewer slides at once and spreads reconnects out. `--threads auto` uses more CPU cores.
- **Measured cost**: about 3 MB at idle and about 6 KB per connected phone. At 5,000 phones the host uses 45 ms of CPU per slide change.

## API

| Request | What it does |
|---|---|
| `GET /events` | live stream (`policy`, `state`, `ping` events) |
| `GET /deck.json` | slides without notes |
| `GET /status` | mode, phones connected, slide, upstream, join URLs |
| `POST /control` `{"action": "next" \| "prev" \| "first" \| "goto" \| "blank" \| "text" \| "load_text" \| "reload"}` | changes the show. Works from localhost, or from elsewhere with `X-Pin` |
| `GET /api/deck-full` | slides with presenter notes (same auth as control) |

## Building

```sh
cargo build --release                  # lean, 1.1 MB
cargo build --release --features tls   # with HTTPS, 2.3 MB
cargo test --release && bash tests/smoke.sh
```

GitHub Actions builds everything on each push:
- Linux musl static binaries for x86_64, ARM64 and ARMv7.
- Windows, macOS and Linux builds with HTTPS.
- Android binaries and the APK.

Push a tag such as `v0.1.1` to publish a release.

The Android app is in `android/`: plain Java with no libraries. It bundles the Rust server as `lib/<abi>/libboatcast.so` and runs it as a program. The APK is signed with a shared development key that's checked into the repo, so new builds install over old ones. Replace it with your own key before distributing widely.

## Limits to know

- **Phone hotspot size**: about 10 phones, set by the maker. Apps can't raise it, so use repeaters.
- **Viewers and mobile data**: viewers must turn mobile data off if the page doesn't load. Android prefers mobile data when the Wi-Fi has no internet.
- **Hotspot address changes**: Android 11 and later give the hotspot a new address each session. The app reads it fresh every time, so always use the codes on screen.
- **Not yet tested on real phones**: the repeater app has been built and checked here, but not yet run on a phone. Hotspot behaviour varies by maker.

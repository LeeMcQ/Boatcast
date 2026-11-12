"""
Boatcast Live end-to-end test: the presenter page in Chromium, three phone pages, a direct
browser-to-browser link with no server in between (only a static file server for the pages).

Usage: python tests/live.py [chromium|firefox|webkit ...]   (engines for the phones; default chromium)
Env:   BOATCAST_CHROME=/path/to/chrome  use an installed Chrome instead of Playwright's Chromium
       LIVE_CAMERA=0                     skip the camera test (needs ffmpeg and Chromium)

Checks, for each phone engine:
  - 3 phones pair from the presenter's code (the way a phone's camera app opens it)
  - every slide change, live text line and blank reaches all 3 phones within 1 s
  - typed slides and picture slides (3 pictures) reach every phone
  - a slide change is not held up by pictures still downloading
  - the presenter sees each phone's delay, nothing missed, no page errors
  - a phone whose reply carries no address at all still connects (as when a phone hides its address)
Then, Chromium only: the full camera path, both ways, with QR images fed in as a fake camera.
"""
import io, os, random, shutil, subprocess, sys, tempfile, time

from playwright.sync_api import sync_playwright

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PORT = 19410
BASE = f"http://127.0.0.1:{PORT}/live/"
CHROME = os.environ.get("BOATCAST_CHROME")
fails = []


def check(name, ok, detail=""):
    print(("PASS: " if ok else "FAIL: ") + name + (f" ({detail})" if detail else ""))
    if not ok:
        fails.append(name)


def pictures(n, size):
    """n random-noise JPEGs of about `size` pixels wide (noise compresses badly: big files)."""
    from PIL import Image
    out = []
    for i in range(n):
        im = Image.effect_noise((size, size * 9 // 16), 80 + i).convert("RGB")
        b = io.BytesIO()
        im.save(b, "JPEG", quality=95)
        out.append({"name": f"Slide{i + 1}.jpg", "mimeType": "image/jpeg", "buffer": b.getvalue()})
    return out


def launch(p, engine, extra_args=None):
    if engine == "chromium":
        args = ["--use-fake-ui-for-media-stream", "--use-fake-device-for-media-stream"] + (extra_args or [])
        return p.chromium.launch(executable_path=CHROME, args=args) if CHROME else p.chromium.launch(args=args)
    return getattr(p, engine).launch()


def host_page(browser):
    ctx = browser.new_context(viewport={"width": 1280, "height": 860}, permissions=["camera"])
    page = ctx.new_page()
    errs = []
    page.on("pageerror", lambda e: errs.append(str(e)))
    page.goto(BASE + "host.html")
    page.wait_for_function("window.__host && __host.code()", timeout=20000)
    return page, errs


def phone_page(p, browser, engine):
    dev = {"chromium": "Pixel 5", "webkit": "iPhone 13"}.get(engine)
    ctx = browser.new_context(**p.devices[dev]) if dev else browser.new_context(viewport={"width": 390, "height": 800})
    page = ctx.new_page()
    errs = []
    page.on("pageerror", lambda e: errs.append(str(e)))
    return page, errs


def pair(host, phone, strip_addresses=False):
    host.wait_for_function("__host.url()", timeout=10000)
    phone.goto(host.evaluate("__host.url()"))
    phone.wait_for_function("window.__viewer && __viewer.code()", timeout=20000)
    code = phone.evaluate("__viewer.code()")
    if strip_addresses:
        f = code.split(",")
        f[7] = ""
        code = ",".join(f)
    host.evaluate("c => __host.reply(c)", code)
    phone.wait_for_function("__viewer.state().open", timeout=20000)
    return code


def everyone(phones, cond, timeout=1000):
    t0 = time.time()
    for ph in phones:
        left = max(50, timeout - (time.time() - t0) * 1000)
        ph.wait_for_function(cond, timeout=left)
    return (time.time() - t0) * 1000


def run_engine(p, engine):
    tag = f"[{engine}]"
    hb = launch(p, "chromium")
    pb = hb if engine == "chromium" else launch(p, engine)
    try:
        host, herr = host_page(hb)
        phones, perrs = [], []
        for i in range(3):
            ph, pe = phone_page(p, pb, engine)
            try:
                pair(host, ph)
                phones.append(ph)
                perrs.append(pe)
            except Exception as e:
                check(f"{tag} phone {i + 1} pairs with the presenter", False, str(e).splitlines()[0])
                return
        check(f"{tag} 3 phones paired with the presenter", len(phones) == 3)

        worst = 0
        for k in range(1, 6):
            host.evaluate(f"__host.go({k % 2})")
            try:
                worst = max(worst, everyone(phones, f"__viewer.state().slide == {k % 2}"))
            except Exception:
                worst = 99999
        check(f"{tag} 5 slide changes each reach all 3 phones within 1 s", worst < 1000, f"slowest {worst:.0f} ms incl. test polling")

        host.evaluate("__host.text('Hello phones')")
        ok = True
        try:
            everyone(phones, "__viewer.state().live == 'Hello phones'")
        except Exception:
            ok = False
        check(f"{tag} live text reaches all 3 phones", ok)
        host.evaluate("__host.blank(true)")
        try:
            everyone(phones, "__viewer.state().blank")
            host.evaluate("__host.blank(false)")
            everyone(phones, "!__viewer.state().blank")
            ok = True
        except Exception:
            ok = False
        check(f"{tag} blank and un-blank reach all 3 phones", ok)

        host.evaluate("__host.typed('# A\\none\\n\\n# B\\ntwo\\n\\n# C\\nthree\\n\\n# D\\nfour')")
        try:
            everyone(phones, "__viewer.state().slides == 4", 3000)
            ok = True
        except Exception:
            ok = False
        check(f"{tag} typed slides reach all 3 phones", ok)

        # Big pictures, then change slides while they are still downloading.
        host.set_input_files("#files", pictures(3, 1920))
        host.wait_for_function("__host.show().slides == 3 && __host.phones().some(function(p){return p.picsOf==3})", timeout=20000)
        host.evaluate("__host.go(2)")
        try:
            t = everyone(phones, "__viewer.state().slide == 2")
            ok = True
        except Exception:
            ok, t = False, 0
        busy = host.evaluate("__host.phones().some(function(p){return p.picsOf==3 && p.pics<3})")
        check(f"{tag} slide change is not held up by pictures downloading", ok,
              f"{t:.0f} ms; pictures still downloading at the time: {busy}")
        try:
            everyone(phones, "__viewer.state().assets >= 3", 30000)
            ok = True
        except Exception:
            ok = False
        check(f"{tag} all 3 pictures reach all 3 phones", ok)
        img_ok = all(ph.evaluate("(function(i){return !!i && i.complete && i.naturalWidth>0})(document.querySelector('#slide img'))") for ph in phones)
        check(f"{tag} the picture slide is shown on every phone", img_ok)

        time.sleep(5)
        stats = host.evaluate("__host.phones()")
        conn = [s for s in stats if s["status"] == "Connected"]
        rtts = [r for s in conn for r in s["rtt"]]
        check(f"{tag} presenter shows 3 connected phones with delay checks, none missed",
              len(conn) == 3 and all(s["rtt"] for s in conn) and not any(s["missed"] for s in conn),
              f"typical {sorted(rtts)[len(rtts) // 2]:.0f} ms, worst {max(rtts):.0f} ms" if rtts else "no delay data")

        # A phone whose reply has no address (as when a phone hides its address and the
        # computer can't look it up): the computer's own address must be enough.
        ph4, pe4 = phone_page(p, pb, engine)
        try:
            pair(host, ph4, strip_addresses=True)
            host.evaluate("__host.go(1)")
            ph4.wait_for_function("__viewer.state().slide == 1", timeout=2000)
            ok = True
        except Exception:
            ok = False
        check(f"{tag} a phone that sends no address still connects", ok)

        allerr = herr + [e for es in perrs for e in es] + pe4
        check(f"{tag} no page errors", not allerr, "; ".join(allerr)[:300])
        host.screenshot(path=f"/tmp/live-host-{engine}.png", full_page=True)
        phones[0].screenshot(path=f"/tmp/live-phone-{engine}.png")
    finally:
        if pb is not hb:
            pb.close()
        hb.close()


def y4m(png_bytes, path):
    src = path + ".png"
    with open(src, "wb") as f:
        f.write(png_bytes)
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-loop", "1", "-i", src, "-t", "2", "-r", "10",
                    "-vf", "scale=640:480:force_original_aspect_ratio=decrease,pad=640:480:(ow-iw)/2:(oh-ih)/2:color=white",
                    "-pix_fmt", "yuv420p", path], check=True)


def run_camera(p):
    """Both QR codes read through a (fake) camera, as on real devices."""
    tmp = tempfile.mkdtemp()
    host_cam = os.path.join(tmp, "host.y4m")
    phone_cam = os.path.join(tmp, "phone.y4m")
    from PIL import Image
    blank = io.BytesIO()
    Image.new("RGB", (640, 480), "white").save(blank, "PNG")
    y4m(blank.getvalue(), host_cam)
    hb = launch(p, "chromium", [f"--use-file-for-fake-video-capture={host_cam}"])
    try:
        host, herr = host_page(hb)
        y4m(host.locator("#qrBox").screenshot(), phone_cam)
        pb = launch(p, "chromium", [f"--use-file-for-fake-video-capture={phone_cam}"])
        try:
            ctx = pb.new_context(**p.devices["Pixel 5"], permissions=["camera"])
            ph = ctx.new_page()
            perr = []
            ph.on("pageerror", lambda e: perr.append(str(e)))
            ph.goto(BASE)
            ph.click("#scanBtn")
            try:
                ph.wait_for_function("window.__viewer && __viewer.code()", timeout=20000)
                ok = True
            except Exception:
                ok = False
            check("[camera] phone reads the presenter's code with its camera", ok)
            if not ok:
                return
            ph.wait_for_selector("#replyQr svg")
            y4m(ph.locator("#replyQr").screenshot(), host_cam)
            host.evaluate("__host.restartCamera()")
            try:
                ph.wait_for_function("__viewer.state().open", timeout=20000)
                ok = True
            except Exception:
                ok = False
            check("[camera] presenter's camera reads the phone's reply and they connect", ok)
            if ok:
                host.evaluate("__host.go(1)")
                ph.wait_for_function("__viewer.state().slide == 1", timeout=2000)
            check("[camera] no page errors", not (herr + perr), "; ".join(herr + perr)[:300])
        finally:
            pb.close()
    finally:
        hb.close()
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    engines = sys.argv[1:] or ["chromium"]
    srv = subprocess.Popen([sys.executable, "-m", "http.server", str(PORT), "--bind", "127.0.0.1", "-d", ROOT],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    time.sleep(1)
    try:
        with sync_playwright() as p:
            for e in engines:
                try:
                    run_engine(p, e)
                except Exception as ex:
                    check(f"[{e}] run", False, str(ex).splitlines()[0])
            if os.environ.get("LIVE_CAMERA", "1") != "0" and shutil.which("ffmpeg"):
                try:
                    run_camera(p)
                except Exception as ex:
                    check("[camera] run", False, str(ex).splitlines()[0])
    finally:
        srv.kill()
    print("ALL PASSED" if not fails else f"{len(fails)} FAILED")
    sys.exit(1 if fails else 0)


if __name__ == "__main__":
    main()

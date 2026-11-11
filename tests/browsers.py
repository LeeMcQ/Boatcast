#!/usr/bin/env python3
"""Drive the viewer page (web/index.html) in real browser engines with Playwright (sync API).

Usage: python tests/browsers.py PATH_TO_BINARY [chromium|firefox|webkit ...]   (default: all three)
Env:   BOATCAST_CHROME=/usr/bin/google-chrome  launches chromium from that executable (local use).

Reconnect check (e): we use context.set_offline(True/False) while the host keeps running and its
slide changes twice. This is supported by all three engines; whether or not the engine actually
severs the open EventSource, the pass condition is the same: the page shows the latest slide
within 30 s without a reload (a JS marker on window proves no reload happened). Note: in Chromium the
open stream kept delivering while 'offline', so this check is weak there. A server-restart check (host restarts, seq goes back
to 0) runs after it. Also: delivery test, observer page, and (chromium) console panels at two sizes.
"""
import json
import os
import subprocess
import sys
import time
import urllib.request

from playwright.sync_api import sync_playwright

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE_PORT = 19110
FAILED = []


def check(engine, name, ok, detail=""):
    print(("PASS: " if ok else "FAIL: ") + f"[{engine}] {name}" + (f" ({detail})" if detail else ""), flush=True)
    if not ok:
        FAILED.append((engine, name))
    return ok


def post(port, **body):
    req = urllib.request.Request(f"http://127.0.0.1:{port}/control", data=json.dumps(body).encode(), method="POST")
    with urllib.request.urlopen(req, timeout=5) as r:
        return r.status


def start_server(binary, port):
    p = subprocess.Popen([binary, "--port", str(port), "--deck", "examples/demo-deck"], cwd=ROOT,
                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    end = time.time() + 10
    while time.time() < end:
        try:
            urllib.request.urlopen(f"http://127.0.0.1:{port}/status", timeout=1).read()
            return p
        except Exception:
            time.sleep(0.1)
    p.kill()
    raise RuntimeError("server did not start")


def get_json(port, path):
    with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=5) as r:
        return json.loads(r.read())


def console_checks(browser, binary, port):
    """Console panels at laptop and phone width (separate server so counts are exact)."""
    for i, (name, vp, shot) in enumerate((("wide", {"width": 1280, "height": 860}, "/tmp/console-wide.png"),
                               ("narrow", {"width": 360, "height": 740}, "/tmp/console-narrow.png"))):
        server = start_server(binary, port + i)
        try:
            ctx = browser.new_context(viewport=vp)
            p = port + i
            try:
                viewer = ctx.new_page()
                viewer.goto(f"http://127.0.0.1:{p}/")
                shown(viewer, f"{POS}('1 / 4')", 8000)
                con = ctx.new_page()
                errs = []
                con.on("pageerror", lambda e: errs.append(str(e)))
                con.goto(f"http://127.0.0.1:{p}/console.html")
                con.click("#testBtn")
                ok = shown(con, "/\\b1 phone|The phone/.test(document.getElementById('verdict').textContent)", 8000)
                verdict = con.inner_text("#verdict") if con.locator("#verdict").is_visible() else ""
                check("chromium", f"console {name}: delivery verdict mentions 1 phone", ok is not None, verdict)
                con.fill("#wifiName", "BoatNet")
                con.fill("#wifiPass", "s3cretPassw0rd")
                con.click("#wifiBtn")
                ok = shown(con, "(function(i){return i.complete && i.naturalWidth>0})(document.getElementById('qrWifi'))", 8000)
                body = con.evaluate("document.body.innerText")
                check("chromium", f"console {name}: Wi-Fi QR loads and password not shown",
                      ok is not None and "s3cretPassw0rd" not in body and con.input_value("#wifiPass") == "")
                time.sleep(2.5)  # let the test finish and the table render
                con.screenshot(path=shot, full_page=True)
                check("chromium", f"console {name}: no uncaught page errors", not errs, "; ".join(errs))
            finally:
                ctx.close()
        finally:
            server.kill()


def shown(page, js_cond, timeout_ms):
    """Wait until js_cond is true in the page. Returns elapsed ms, or None on timeout."""
    t0 = time.perf_counter()
    try:
        page.wait_for_function(js_cond, polling="raf", timeout=timeout_ms)
    except Exception:
        return None
    return (time.perf_counter() - t0) * 1000


# Page state in terms of the viewer's DOM: #pos "N / total", #title, #text, #live, #img.
POS = "document.getElementById('pos').textContent.startsWith"
TITLE = "document.getElementById('title').textContent"
TEXT = "document.getElementById('text').textContent"
LIVE = "document.getElementById('live')"


def test_engine(pw, engine, binary, port):
    server = start_server(binary, port)
    browser = None
    try:
        if engine == "chromium":
            kw = {}
            if os.environ.get("BOATCAST_CHROME"):
                kw["executable_path"] = os.environ["BOATCAST_CHROME"]
            browser = pw.chromium.launch(**kw)
            ctx = browser.new_context(**pw.devices["Pixel 5"])
        elif engine == "webkit":
            browser = pw.webkit.launch()
            ctx = browser.new_context(**pw.devices["iPhone 13"])
        else:
            browser = pw.firefox.launch()
            ctx = browser.new_context(viewport={"width": 390, "height": 800})
        page = ctx.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.goto(f"http://127.0.0.1:{port}/")

        # a. first slide visible
        ms = shown(page, f"{TITLE} === 'Welcome' && {TEXT}.includes('Good morning')", 8000)
        vis = page.locator("#text").is_visible() and page.locator("#title").is_visible()
        check(engine, "first slide text visible", ms is not None and vis,
              f"{ms:.0f} ms" if ms else "timeout")

        # b. next -> slide 2 within 1000 ms of the POST returning
        post(port, action="next")
        ms = shown(page, f"{TITLE} === 'Opening song' && {TEXT}.includes('Verse 1')", 1000)
        check(engine, "next shows slide 2 within 1000 ms", ms is not None, f"{ms:.0f} ms" if ms else "timeout")

        # c. live text
        post(port, action="text", text="Hello phones")
        ms = shown(page, f"{LIVE}.style.display !== 'none' && {LIVE}.textContent === 'Hello phones'", 1500)
        check(engine, "live text appears", ms is not None and page.locator("#live").is_visible(),
              f"{ms:.0f} ms" if ms else "timeout")
        post(port, action="text", text="")
        ms = shown(page, f"{LIVE}.style.display === 'none'", 1500)
        check(engine, "empty text clears live text", ms is not None and not page.locator("#live").is_visible())

        # d. blank / un-blank (blank empties the slide text; un-blank restores it)
        post(port, action="blank", on=True)
        ms = shown(page, f"{TEXT} === ''", 1500)
        check(engine, "blank hides slide content", ms is not None)
        post(port, action="blank", on=False)
        ms = shown(page, f"{TEXT}.includes('Verse 1')", 1500)
        check(engine, "un-blank restores slide content", ms is not None)

        # e. reconnect without reload
        page.evaluate("window.__noReload = 42")
        ctx.set_offline(True)
        time.sleep(1)
        post(port, action="goto", slide=3)
        post(port, action="goto", slide=0)
        post(port, action="goto", slide=3)
        ctx.set_offline(False)
        ms = shown(page, f"{TITLE} === 'Notices' && {POS}('4 / 4')", 30000)
        same_page = page.evaluate("window.__noReload") == 42
        ok = check(engine, "reconnect: latest slide shown within 30 s after offline/online, no reload",
                   ms is not None and same_page, f"{ms:.0f} ms" if ms else "timeout")
        # Server restart on the same port (seq restarts at 0, new boot id): the page must still catch up.
        server.kill(); server.wait()
        server = start_server(binary, port)
        post(port, action="goto", slide=2)
        ms = shown(page, f"{POS}('3 / 4')", 30000)
        check(engine, "server restart: page catches up after goto 3 within 30 s, no reload",
              ms is not None and page.evaluate("window.__noReload") == 42, f"{ms:.0f} ms" if ms else "timeout")

        # g. delivery test through the real stream
        post(port, action="test", count=3, gap_ms=400)
        time.sleep(2.5)
        t = get_json(port, "/api/test")
        devs = t.get("devices", [])
        d0 = devs[0] if devs else {}
        check(engine, "delivery test: 1 device, got 3, lost 0",
              len(devs) == 1 and d0.get("got") == 3 and d0.get("lost") == 0,
              f"median {d0.get('median_ms')} ms" if devs else json.dumps(t))

        # h. observer page is not counted as a phone and still shows the slide
        before = get_json(port, "/status")["clients"]
        opage = ctx.new_page()
        opage.goto(f"http://127.0.0.1:{port}/?observer=1")
        ms = shown(opage, f"{POS}('3 / 4')", 8000)
        time.sleep(1)
        after = get_json(port, "/status")["clients"]
        check(engine, "observer: clients stays at 1 and slide content shown",
              before == 1 and after == 1 and ms is not None, f"before {before}, after {after}")
        opage.close()

        if engine == "chromium":
            console_checks(browser, binary, port + 50)

        # f. no uncaught page errors
        check(engine, "no uncaught page errors", not errors, "; ".join(errors))
    finally:
        if browser:
            try:
                browser.close()
            except Exception:
                pass
        server.kill()


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    binary = os.path.abspath(sys.argv[1])
    engines = sys.argv[2:] or ["chromium", "firefox", "webkit"]
    with sync_playwright() as pw:
        for i, engine in enumerate(engines):
            try:
                test_engine(pw, engine, binary, BASE_PORT + i)
            except Exception as e:
                check(engine, f"engine run completed: {e!r}", False)
    print(f"{'FAILED' if FAILED else 'ALL PASSED'}: {len(FAILED)} failure(s)")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())

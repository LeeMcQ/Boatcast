#!/usr/bin/env python3
"""Cross-platform end-to-end checks for the boatcast binary (standard library only).

Usage: python tests/portable.py PATH_TO_BINARY
Runs on Linux, macOS and Windows. Everything binds/connects on 127.0.0.1.
"""
import json
import os
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
HOST_PORT, RELAY_PORT = 19101, 19102
FAILED = []
PROCS = []


def check(name, ok, detail=""):
    print(("PASS: " if ok else "FAIL: ") + name + (f" ({detail})" if detail else ""), flush=True)
    if not ok:
        FAILED.append(name)
    return ok


def http(port, path, body=None, timeout=5):
    """Return (status, headers, bytes). HTTP errors are returned, not raised."""
    url = f"http://127.0.0.1:{port}{path}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method="POST" if data is not None else "GET")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.headers, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read()


def get_json(port, path):
    st, _, body = http(port, path)
    return json.loads(body) if st == 200 else None


def control(port, **body):
    return http(port, "/control", body)[0]


def start(binary, *args):
    p = subprocess.Popen([binary, *args], cwd=ROOT, stdin=subprocess.DEVNULL,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    PROCS.append(p)
    return p


def wait_for(fn, timeout, interval=0.1):
    end = time.time() + timeout
    while time.time() < end:
        try:
            v = fn()
            if v:
                return v
        except Exception:
            pass
        time.sleep(interval)
    return None


class Sse:
    """Raw-socket SSE client; a thread collects (monotonic time, event, data) tuples."""

    def __init__(self, port):
        self.events = []
        self.lock = threading.Lock()
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=5)
        self.sock.sendall(b"GET /events HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: text/event-stream\r\n\r\n")
        self.sock.settimeout(0.2)
        self.alive = True
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        buf = b""
        while self.alive:
            try:
                chunk = self.sock.recv(65536)
                if not chunk:
                    return
            except socket.timeout:
                continue
            except OSError:
                return
            buf += chunk.replace(b"\r\n", b"\n")
            while b"\n\n" in buf:
                block, buf = buf.split(b"\n\n", 1)
                ev, data = "message", ""
                for line in block.decode("utf-8", "replace").split("\n"):
                    if line.startswith("event:"):
                        ev = line[6:].strip()
                    elif line.startswith("data:"):
                        data += line[5:].strip()
                if ev == "state":
                    with self.lock:
                        self.events.append((time.monotonic(), json.loads(data)))

    def states(self):
        with self.lock:
            return list(self.events)

    def wait_state(self, pred, timeout, start_index=0):
        """Wait for a state event (after start_index) satisfying pred; return (time, state) or None."""
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            for t, s in self.states()[start_index:]:
                if pred(s):
                    return t, s
            time.sleep(0.005)
        return None

    def close(self):
        self.alive = False
        try:
            self.sock.close()
        except OSError:
            pass


def run(binary):
    # 1. help text
    out = subprocess.run([binary, "-h"], cwd=ROOT, stdin=subprocess.DEVNULL, capture_output=True,
                         timeout=10)
    text = (out.stdout + out.stderr).decode("utf-8", "replace").lower()
    check("-h prints usage mentioning boatcast", "boatcast" in text)

    # 2. host starts and /status is right
    host = start(binary, "--port", str(HOST_PORT), "--deck", "examples/demo-deck")
    st = wait_for(lambda: get_json(HOST_PORT, "/status"), 10)
    if not check("host answers /status within 10 s", st is not None):
        return
    check("status: app, mode, depth, slides",
          st.get("app") == "boatcast" and st.get("mode") == "host" and st.get("depth") == 0
          and st.get("slides") == 4, json.dumps({k: st.get(k) for k in ("app", "mode", "depth", "slides")}))

    # 3. pages and assets
    code, hdr, body = http(HOST_PORT, "/")
    check("GET / is 200 HTML", code == 200 and "html" in hdr.get("Content-Type", "").lower())
    code, _, body = http(HOST_PORT, "/console.html")
    check("GET /console.html contains 'Presenter console'", code == 200 and b"Presenter console" in body)
    deck = get_json(HOST_PORT, "/deck.json")
    check("GET /deck.json has 4 slides", deck is not None and len(deck["slides"]) == 4)
    img = next((s["image"] for s in (deck or {}).get("slides", []) if "image" in s), None)
    if img:
        code, hdr, _ = http(HOST_PORT, img)
        check(f"image asset {img} is 200 with image/* type",
              code == 200 and hdr.get("Content-Type", "").startswith("image/"), hdr.get("Content-Type", ""))
    else:
        check("deck has an image slide", False)

    # 4. SSE push latency
    sse = Sse(HOST_PORT)
    first = sse.wait_state(lambda s: True, 3)
    check("SSE sends initial state", first is not None and first[1]["slide"] == 0)
    n = len(sse.states())
    t0 = time.monotonic()
    code = control(HOST_PORT, action="next")
    got = sse.wait_state(lambda s: s["slide"] == 1, 1.0, n)
    ms = (got[0] - t0) * 1000 if got else None
    check("next -> state slide 1 within 1000 ms", code == 200 and got is not None,
          f"{ms:.1f} ms" if ms is not None else "no event")

    # 5. burst throttle (5 posts, expect <= 2 state events, final state == /status)
    time.sleep(0.4)
    n = len(sse.states())
    for a in ("next", "prev", "next", "prev", "next"):
        control(HOST_PORT, action=a)
    time.sleep(0.6)
    new = sse.states()[n:]
    final = get_json(HOST_PORT, "/status")
    check("burst of 5 posts -> at most 2 state events", 1 <= len(new) <= 2, f"{len(new)} events")
    check("last streamed state matches /status",
          bool(new) and new[-1][1]["slide"] == final["slide"] and new[-1][1]["seq"] == final["seq"],
          f"stream slide={new[-1][1]['slide'] if new else None} status slide={final['slide']}")

    # 6. relay
    relay = start(binary, "relay", "--upstream", f"http://127.0.0.1:{HOST_PORT}", "--port", str(RELAY_PORT))

    def relay_ready():
        s = get_json(RELAY_PORT, "/status")
        return s if s and s.get("mode") == "relay" and (s.get("upstream") or {}).get("connected") and s.get("depth") == 1 else None
    rs = wait_for(relay_ready, 5)
    check("relay status: mode=relay, upstream.connected, depth=1", rs is not None)
    host_deck = get_json(HOST_PORT, "/deck.json")
    relay_deck = wait_for(lambda: get_json(RELAY_PORT, "/deck.json"), 5)
    check("relay /deck.json equals host's", relay_deck == host_deck)
    rsse = Sse(RELAY_PORT)
    rsse.wait_state(lambda s: True, 3)
    n = len(rsse.states())
    target = 0 if get_json(HOST_PORT, "/status")["slide"] != 0 else 3
    t0 = time.monotonic()
    control(HOST_PORT, action="goto", slide=target)
    got = rsse.wait_state(lambda s: s["slide"] == target, 1.0, n)
    check("host slide change reaches relay SSE client within 1000 ms", got is not None,
          f"{(got[0] - t0) * 1000:.1f} ms" if got else "no event")
    rsse.close()
    sse.close()

    # 7. control auth and validation
    check("control from loopback without PIN -> 200", control(HOST_PORT, action="first") == 200)
    check("unknown action -> 400", control(HOST_PORT, action="no-such-action") == 400)

    # 8. host dies -> relay becomes cut off (depth null) within 60 s
    host.kill()
    host.wait()
    t0 = time.monotonic()
    cut = wait_for(lambda: (lambda s: s is not None and s.get("depth") is None)(get_json(RELAY_PORT, "/status")), 60, 0.5)
    check("relay depth becomes null after host dies (<= 60 s)", bool(cut), f"{time.monotonic() - t0:.1f} s")
    relay.kill()


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    binary = os.path.abspath(sys.argv[1])
    try:
        run(binary)
    except Exception as e:  # any crash is a failure, but still clean up
        check(f"test run completed without exception: {e!r}", False)
    finally:
        for p in PROCS:
            try:
                p.kill()
            except OSError:
                pass
    print(f"{'FAILED' if FAILED else 'ALL PASSED'}: {len(FAILED)} failure(s)")
    return 1 if FAILED else 0


if __name__ == "__main__":
    sys.exit(main())

// Service worker: makes the viewer an installable, offline-capable app when served over HTTPS.
// Live data (/events, /control, /status, /qr) always goes to the network; images are cached forever.
const SHELL = "boatcast-shell-v1";
const ASSETS = "boatcast-assets-v1";
const SHELL_FILES = ["/", "/manifest.webmanifest", "/icon.svg", "/icon-192.png", "/icon-512.png"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(SHELL).then((c) => c.addAll(SHELL_FILES)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== SHELL && k !== ASSETS).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== self.location.origin) return;
  const p = url.pathname;
  if (p === "/events" || p === "/status" || p.startsWith("/qr/") || p.startsWith("/api/")) return;

  if (p.startsWith("/a/")) {
    // Content-hashed images: cache first, they never change.
    e.respondWith(
      caches.open(ASSETS).then((c) =>
        c.match(e.request).then((hit) => hit || fetch(e.request).then((r) => { if (r.ok) c.put(e.request, r.clone()); return r; }))
      )
    );
    return;
  }
  // Pages, deck.json and icons: network first so the show stays current, cache as fallback offline.
  e.respondWith(
    fetch(e.request)
      .then((r) => {
        if (r.ok) { const copy = r.clone(); caches.open(SHELL).then((c) => c.put(e.request, copy)); }
        return r;
      })
      .catch(() => caches.match(e.request).then((hit) => hit || caches.match("/")))
  );
});

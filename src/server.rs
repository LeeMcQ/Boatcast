//! Connections and routes.

use crate::{
    config::qr_svg,
    deck::{Asset, asset},
    http::{self, Request, respond},
    hub::Hub,
};
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    io,
    net::{IpAddr, SocketAddr},
    sync::{Arc, OnceLock, atomic::Ordering::SeqCst},
    time::Duration,
};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

static WEB: OnceLock<HashMap<&'static str, Asset>> = OnceLock::new();

fn web() -> &'static HashMap<&'static str, Asset> {
    WEB.get_or_init(|| {
        let mut m = HashMap::new();
        let viewer = asset("text/html; charset=utf-8", include_str!("../web/index.html"));
        m.insert("/", viewer);
        m.insert("/console", asset("text/html; charset=utf-8", include_str!("../web/console.html")));
        m.insert("/console.html", asset("text/html; charset=utf-8", include_str!("../web/console.html")));
        m.insert("/sw.js", asset("text/javascript; charset=utf-8", include_str!("../web/sw.js")));
        m.insert("/manifest.webmanifest", asset("application/manifest+json", include_str!("../web/manifest.webmanifest")));
        m.insert("/icon.svg", asset("image/svg+xml", include_str!("../web/icon.svg")));
        m.insert("/icon-192.png", asset("image/png", &include_bytes!("../web/icon-192.png")[..]));
        m.insert("/icon-512.png", asset("image/png", &include_bytes!("../web/icon-512.png")[..]));
        m
    })
}

pub async fn conn<S>(hub: Arc<Hub>, mut stream: S, peer: SocketAddr)
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let mut buf = Vec::with_capacity(1024);
    let mut first = true;
    loop {
        // Slow or idle sockets must not pin file descriptors on a phone host.
        let wait = if first { Duration::from_secs(10) } else { Duration::from_secs(30) };
        let req = match tokio::time::timeout(wait, http::read_request(&mut stream, &mut buf)).await {
            Ok(Ok(Some(r))) => r,
            _ => return,
        };
        first = false;
        if req.method == "GET" && req.path == "/events" && !foreign_host(&hub, &req) {
            let is_relay = req.header("x-boatcast-relay").is_some();
            // The host's own preview of the viewer page is not one of the phones.
            let observer = req.query.split('&').any(|q| q == "observer=1" || q == "observer");
            // Free the request buffers: a phone may hold this stream for hours.
            drop(req);
            drop(buf);
            return sse(hub, stream, peer, is_relay, observer).await;
        }
        match route(&hub, &req, &mut stream, peer).await {
            Ok(true) if !req.wants_close() => continue,
            _ => return,
        }
    }
}

/// One phone's live stream: policy, snapshot, then every state change and a keepalive.
async fn sse<S>(hub: Arc<Hub>, stream: S, peer: SocketAddr, is_relay: bool, observer: bool)
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    struct Count(Arc<Hub>, bool, bool);
    impl Drop for Count {
        fn drop(&mut self) {
            if !self.2 {
                self.0.clients.fetch_sub(1, SeqCst);
            }
            if self.1 {
                self.0.relays.fetch_sub(1, SeqCst);
            }
        }
    }
    let n = if observer { hub.clients.load(SeqCst) } else { hub.clients.fetch_add(1, SeqCst) + 1 };
    if is_relay {
        hub.relays.fetch_add(1, SeqCst);
    }
    let _count = Count(hub.clone(), is_relay, observer);
    let (mut rd, mut wr) = tokio::io::split(stream);
    if n > hub.cfg.max_clients {
        let _ = respond(&mut wr, 503, &[("Retry-After", String::from("10"))], b"full", false).await;
        return;
    }
    if hub.cfg.captive {
        hub.joined.lock().unwrap().insert(peer.ip());
    }
    let (mut rx, mut tick, mut probe) = hub.subscribe();
    probe.borrow_and_update(); // a probe sent before this phone connected is not for it
    let p = hub.policy();
    let snapshot = rx.borrow_and_update().clone();
    tick.borrow_and_update();
    let mut head = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\n\
         X-Accel-Buffering: no\r\nAccess-Control-Allow-Origin: *\r\n\r\n\
         retry: {}\nevent: policy\ndata: {{\"preload\":\"{}\",\"spread\":{}}}\n\n",
        p.retry_ms, p.preload, p.spread_ms
    )
    .into_bytes();
    head.extend_from_slice(&snapshot);
    if !write_to(&mut wr, &head).await {
        return;
    }
    let mut junk = [0u8; 64];
    loop {
        tokio::select! {
            r = rx.changed() => {
                if r.is_err() { break; }
                let b = rx.borrow_and_update().clone();
                if !write_to(&mut wr, &b).await { break; }
            }
            r = tick.changed() => {
                if r.is_err() { break; }
                if !write_to(&mut wr, b"event: ping\ndata: 1\n\n").await { break; }
            }
            r = probe.changed() => {
                if r.is_err() { break; }
                let b = probe.borrow_and_update().clone();
                if !write_to(&mut wr, &b).await { break; }
            }
            r = rd.read(&mut junk) => {
                if matches!(r, Ok(0) | Err(_)) { break; }
            }
        }
    }
}

/// A stalled phone only blocks its own task; after 30 s it is dropped.
async fn write_to<W: AsyncWrite + Unpin>(wr: &mut W, b: &[u8]) -> bool {
    matches!(tokio::time::timeout(Duration::from_secs(30), wr.write_all(b)).await, Ok(Ok(())))
}

fn is_loopback(peer: &SocketAddr) -> bool {
    match peer.ip() {
        IpAddr::V4(a) => a.is_loopback(),
        IpAddr::V6(a) => a.is_loopback() || a.to_ipv4_mapped().is_some_and(|v| v.is_loopback()),
    }
}

fn authorised(hub: &Hub, req: &Request, peer: &SocketAddr, body: &Value) -> bool {
    if is_loopback(peer) {
        return true;
    }
    let Some(pin) = &hub.cfg.pin else { return false };
    req.header("x-pin") == Some(pin.as_str()) || body["pin"].as_str() == Some(pin.as_str())
}

/// In captive mode, any hostname that is not us (connectivity checks, typed sites) gets the portal.
fn foreign_host(hub: &Hub, req: &Request) -> bool {
    if !hub.cfg.captive {
        return false;
    }
    let h = req.host();
    if h.is_empty() || h.parse::<IpAddr>().is_ok() || h == "localhost" {
        return false;
    }
    let public = hub.cfg.public_url.as_deref().unwrap_or("");
    !public.contains(&format!("//{h}"))
}

async fn send_asset<W: AsyncWrite + Unpin>(w: &mut W, req: &Request, a: &Asset, cache: &str) -> io::Result<bool> {
    let etag = format!("\"{}\"", a.etag);
    if req.header("if-none-match").is_some_and(|v| v.contains(&a.etag)) {
        respond(w, 304, &[("ETag", etag), ("Cache-Control", cache.to_string())], b"", true).await?;
        return Ok(true);
    }
    let gz = a.gz.as_ref().filter(|_| req.accepts_gzip());
    let mut headers = vec![
        ("Content-Type", a.ctype.to_string()),
        ("Cache-Control", cache.to_string()),
        ("ETag", etag),
    ];
    if a.gz.is_some() {
        headers.push(("Vary", String::from("Accept-Encoding")));
    }
    if gz.is_some() {
        headers.push(("Content-Encoding", String::from("gzip")));
    }
    let body = gz.unwrap_or(&a.body);
    respond(w, 200, &headers, body, req.method == "HEAD").await?;
    Ok(true)
}

async fn send_json<W: AsyncWrite + Unpin>(w: &mut W, code: u16, v: &Value) -> io::Result<bool> {
    let body = v.to_string();
    let h = [
        ("Content-Type", String::from("application/json")),
        ("Cache-Control", String::from("no-store")),
    ];
    respond(w, code, &h, body.as_bytes(), false).await?;
    Ok(true)
}

async fn captive<W: AsyncWrite + Unpin>(hub: &Hub, req: &Request, w: &mut W, peer: SocketAddr) -> io::Result<bool> {
    let joined = hub.joined.lock().unwrap().contains(&peer.ip());
    let text = |ct: &str, b: &'static str| (ct.to_string(), b);
    let success = match req.path.as_str() {
        "/generate_204" | "/gen_204" => None,
        "/hotspot-detect.html" | "/library/test/success.html" => {
            Some(text("text/html", "<HTML><HEAD><TITLE>Success</TITLE></HEAD><BODY>Success</BODY></HTML>"))
        }
        "/connecttest.txt" => Some(text("text/plain", "Microsoft Connect Test")),
        "/ncsi.txt" => Some(text("text/plain", "Microsoft NCSI")),
        "/success.txt" | "/canonical.html" => Some(text("text/plain", "success\n")),
        _ => {
            respond(w, 302, &[("Location", hub.cfg.page_url()), ("Cache-Control", String::from("no-store"))], b"", false).await?;
            return Ok(false);
        }
    };
    if !joined {
        respond(w, 302, &[("Location", hub.cfg.page_url()), ("Cache-Control", String::from("no-store"))], b"", false).await?;
        return Ok(false);
    }
    match success {
        None => respond(w, 204, &[("Cache-Control", String::from("no-store"))], b"", false).await?,
        Some((ct, b)) => respond(w, 200, &[("Content-Type", ct), ("Cache-Control", String::from("no-store"))], b.as_bytes(), false).await?,
    }
    Ok(true)
}

pub async fn route<W: AsyncWrite + Unpin>(hub: &Arc<Hub>, req: &Request, w: &mut W, peer: SocketAddr) -> io::Result<bool> {
    if foreign_host(hub, req) {
        return captive(hub, req, w, peer).await;
    }
    let get = req.method == "GET" || req.method == "HEAD";
    let path = if req.path == "/index.html" { "/" } else { req.path.as_str() };
    if get {
        if let Some(a) = web().get(path) {
            let cache = if path.ends_with(".png") || path.ends_with(".svg") { "public, max-age=86400" } else { "no-cache" };
            return send_asset(w, req, a, cache).await;
        }
        if path == "/deck.json" {
            let d = hub.deck();
            return send_asset(w, req, &d.public_json, "no-cache").await;
        }
        if let Some(rest) = path.strip_prefix("/a/") {
            let d = hub.deck();
            return match d.assets.get(&format!("/a/{rest}")) {
                Some(a) => send_asset(w, req, a, "public, max-age=31536000, immutable").await,
                None => send_json(w, 404, &json!({"error": "no such asset"})).await,
            };
        }
        if path == "/api/deck-full" {
            if !authorised(hub, req, &peer, &Value::Null) {
                return send_json(w, 403, &json!({"error": "pin required"})).await;
            }
            let d = hub.deck();
            return send_asset(w, req, &d.full_json, "no-cache").await;
        }
        if path == "/api/test" {
            if hub.cfg.relay {
                return send_json(w, 409, &json!({"error": "this is a relay; test results are on the host console"})).await;
            }
            if !authorised(hub, req, &peer, &Value::Null) {
                return send_json(w, 403, &json!({"error": "pin required"})).await;
            }
            return send_json(w, 200, &hub.test_results()).await;
        }
        if path == "/status" {
            return send_json(w, 200, &status(hub, req, &peer)).await;
        }
        if path == "/qr/url.svg" || path == "/qr/wifi.svg" {
            let data = if path == "/qr/url.svg" {
                Some(hub.cfg.page_url())
            } else {
                hub.wifi.lock().unwrap().as_ref().map(|(s, p)| crate::config::wifi_qr_text(s, p.as_deref()))
            };
            return match data.and_then(|d| qr_svg(&d)) {
                Some(svg) => {
                    let h = [("Content-Type", String::from("image/svg+xml")), ("Cache-Control", String::from("no-store"))];
                    respond(w, 200, &h, svg.as_bytes(), req.method == "HEAD").await.map(|_| true)
                }
                None => send_json(w, 404, &json!({"error": "set --wifi-ssid (and --wifi-pass) for the Wi-Fi code"})).await,
            };
        }
    }
    if req.method == "POST" && path == "/api/ack" {
        // A viewer page answering a delivery-test probe. Tiny, unauthenticated, bounded.
        let mut body: Value = serde_json::from_slice(&req.body).unwrap_or(Value::Null);
        if body.is_object() {
            if hub.cfg.relay {
                // Pass it up towards the host, one hop further away, without making the phone wait.
                if body["ua"].as_str().is_none() {
                    body["ua"] = json!(req.header("user-agent").unwrap_or(""));
                }
                body["hops"] = json!(body["hops"].as_u64().unwrap_or(0) + 1);
                if let Some((host, port)) = hub.upstream.lock().unwrap().clone() {
                    tokio::spawn(async move {
                        let _ = crate::http::post(&host, port, "/api/ack", body.to_string().as_bytes()).await;
                    });
                }
            } else {
                hub.test_ack(&body, peer.ip(), req.header("user-agent"));
            }
        }
        respond(w, 204, &[("Cache-Control", String::from("no-store"))], b"", false).await?;
        return Ok(true);
    }
    if req.method == "POST" && (path == "/control" || path == "/next") {
        let body: Value = if path == "/next" {
            json!({"action": "next"})
        } else {
            serde_json::from_slice(&req.body).unwrap_or(Value::Null)
        };
        if !authorised(hub, req, &peer, &body) {
            return send_json(w, 403, &json!({"error": "pin required"})).await;
        }
        if hub.cfg.relay {
            // The only thing a relay takes: a new upstream, from the app on the same phone.
            if body["action"] == "upstream" && is_loopback(&peer) {
                return match body["url"].as_str().map(crate::config::parse_upstream) {
                    Some(Ok(up)) => {
                        eprintln!("relay: switching upstream to http://{}:{}/", up.0, up.1);
                        *hub.upstream.lock().unwrap() = Some(up);
                        hub.upstream_changed.notify_one();
                        send_json(w, 200, &json!({"ok": true})).await
                    }
                    Some(Err(e)) => send_json(w, 400, &json!({"error": e})).await,
                    None => send_json(w, 400, &json!({"error": "upstream needs \"url\""})).await,
                };
            }
            return send_json(w, 409, &json!({"error": "this is a relay; control the show from the host console"})).await;
        }
        return match hub.control(&body) {
            Ok(()) => send_json(w, 200, &json!({"ok": true})).await,
            Err(e) => send_json(w, 400, &json!({"error": e})).await,
        };
    }
    if req.method == "OPTIONS" {
        let h = [
            ("Access-Control-Allow-Origin", String::from("*")),
            ("Access-Control-Allow-Headers", String::from("Content-Type, X-Pin")),
            ("Access-Control-Allow-Methods", String::from("GET, POST")),
        ];
        respond(w, 204, &h, b"", false).await?;
        return Ok(true);
    }
    send_json(w, 404, &json!({"error": "not found"})).await
}

fn status(hub: &Hub, req: &Request, peer: &SocketAddr) -> Value {
    let (seq, slide, slides, blank, text, title, ver) = {
        let s = hub.show.lock().unwrap();
        (s.seq, s.slide, s.deck.slides.len(), s.blank, s.text.clone(), s.deck.title.clone(), s.deck.version.clone())
    };
    let p = hub.policy();
    let auth = if is_loopback(peer) {
        "local"
    } else if hub.cfg.pin.is_none() {
        "local-only"
    } else if req.header("x-pin") == hub.cfg.pin.as_deref() {
        "pin-ok"
    } else {
        "pin"
    };
    json!({
        "app": "boatcast",
        "version": env!("CARGO_PKG_VERSION"),
        "mode": if hub.cfg.relay { "relay" } else { "host" },
        "clients": hub.clients.load(SeqCst),
        "seq": seq,
        "slide": slide,
        "slides": slides,
        "blank": blank,
        "text": text,
        "title": title,
        "deck": ver,
        "uptime_s": hub.started.elapsed().as_secs(),
        "url": hub.cfg.page_url(),
        "ssid": hub.wifi.lock().unwrap().as_ref().map(|(s, _)| s.clone()),
        "wifi_qr": hub.wifi.lock().unwrap().is_some(),
        "upstream": hub.upstream.lock().unwrap().as_ref().map(|(h, p)| json!({"url": format!("http://{h}:{p}"), "connected": hub.upstream_ok.load(SeqCst)})),
        "depth": match hub.depth.load(SeqCst) { u32::MAX => Value::Null, d => json!(d) },
        "mesh": hub.mesh.load(SeqCst),
        "relays": hub.relays.load(SeqCst),
        "policy": {"preload": p.preload, "spread_ms": p.spread_ms, "retry_ms": p.retry_ms},
        "threads": hub.cfg.threads,
        "auth": auth,
    })
}

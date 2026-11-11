//! Relay mode: a repeater phone follows an upstream boatcast and re-serves it to its own hotspot.
//! It mirrors the deck once per change, so the upstream sends images to each relay, not to every phone.

use crate::{
    deck::{Asset, Deck, asset, ctype_for},
    http,
    hub::Hub,
};
use std::{
    collections::HashMap,
    io,
    sync::{Arc, atomic::Ordering::SeqCst},
    time::Duration,
};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

pub async fn run(hub: Arc<Hub>) {
    let mut backoff = Duration::from_millis(500);
    loop {
        let (host, port) = hub.upstream.lock().unwrap().clone().expect("relay needs upstream");
        // A new upstream from the app (mesh re-parenting) drops the old link at once.
        let switched = tokio::select! {
            r = follow(&hub, &host, port) => {
                match r {
                    Ok(()) => eprintln!("relay: upstream closed the stream"),
                    Err(e) => eprintln!("relay: {e}"),
                }
                false
            }
            _ = hub.upstream_changed.notified() => true,
        };
        // Cut off from the host: report no depth, so no phone below us is picked as our parent.
        hub.depth.store(u32::MAX, SeqCst);
        if hub.upstream_ok.swap(false, SeqCst) || switched {
            backoff = Duration::from_millis(500); // had a good session, or a fresh upstream: retry quickly
        }
        if switched {
            continue;
        }
        let jitter = Duration::from_millis((crate::deck::fnv(&std::process::id().to_le_bytes()) % 400) as u64);
        tokio::select! {
            _ = tokio::time::sleep(backoff + jitter) => {}
            _ = hub.upstream_changed.notified() => { backoff = Duration::from_millis(500); continue; }
        }
        backoff = (backoff * 2).min(Duration::from_secs(8));
    }
}

/// Learn our place in the mesh from the upstream: depth + 1 and its mesh id.
async fn learn_depth(hub: &Arc<Hub>, host: &str, port: u16) {
    if let Ok((200, body)) = http::get(host, port, "/status").await {
        if let Ok(v) = serde_json::from_slice::<serde_json::Value>(&body) {
            match v["depth"].as_u64() {
                Some(d) => hub.depth.store(d as u32 + 1, SeqCst),
                None => hub.depth.store(u32::MAX, SeqCst), // our upstream is cut off too
            }
            if let Some(m) = v["mesh"].as_u64() {
                hub.mesh.store(m as u32, SeqCst);
            }
        }
    }
}

async fn follow(hub: &Arc<Hub>, host: &str, port: u16) -> io::Result<()> {
    let mut s = tokio::net::TcpStream::connect((host, port)).await?;
    s.set_nodelay(true).ok();
    let req = format!("GET /events HTTP/1.1\r\nHost: {host}\r\nAccept: text/event-stream\r\nX-Boatcast-Relay: 1\r\n\r\n");
    s.write_all(req.as_bytes()).await?;
    let mut r = BufReader::new(s);
    let mut line = String::new();
    // Response head.
    loop {
        line.clear();
        if r.read_line(&mut line).await? == 0 {
            return Err(io::Error::other("upstream closed during headers"));
        }
        if line.starts_with("HTTP/") && !line.contains(" 200") {
            return Err(io::Error::other(format!("upstream said {}", line.trim())));
        }
        if line == "\r\n" || line == "\n" {
            break;
        }
    }
    hub.upstream_ok.store(true, SeqCst);
    eprintln!("relay: following http://{host}:{port}/");
    learn_depth(hub, host, port).await;
    let (mut event, mut data) = (String::new(), String::new());
    loop {
        line.clear();
        // Upstream sends a keepalive every 15 s; 40 s of silence means the link is dead.
        let n = tokio::time::timeout(Duration::from_secs(40), r.read_line(&mut line))
            .await
            .map_err(|_| io::Error::other("upstream silent for 40 s"))??;
        if n == 0 {
            return Ok(());
        }
        let l = line.trim_end_matches(['\r', '\n']);
        if l.is_empty() {
            if event == "ping" {
                learn_depth(hub, host, port).await; // depth can change when an ancestor re-parents
            }
            if event == "probe" && !data.is_empty() {
                hub.relay_probe(&data);
            }
            if event == "state" && !data.is_empty() {
                on_state(hub, host, port, &data).await;
            }
            event.clear();
            data.clear();
        } else if let Some(v) = l.strip_prefix("event:") {
            event = v.trim().to_string();
        } else if let Some(v) = l.strip_prefix("data:") {
            data.push_str(v.trim_start());
        }
    }
}

async fn on_state(hub: &Arc<Hub>, host: &str, port: u16, data: &str) {
    let v: serde_json::Value = match serde_json::from_str(data) {
        Ok(v) => v,
        Err(_) => return,
    };
    let want = v["deck"].as_str().unwrap_or("");
    let deck = if hub.deck().version != want {
        match mirror(host, port).await {
            Ok(d) => {
                eprintln!("relay: mirrored deck {} ({} slides, {} images)", d.version, d.slides.len(), d.assets.len());
                Some(Arc::new(d))
            }
            Err(e) => {
                eprintln!("relay: deck mirror failed: {e}");
                None
            }
        }
    } else {
        None
    };
    hub.relay_publish(data, deck);
}

async fn mirror(host: &str, port: u16) -> io::Result<Deck> {
    let (code, json) = http::get(host, port, "/deck.json").await?;
    if code != 200 {
        return Err(io::Error::other(format!("deck.json: HTTP {code}")));
    }
    let v: serde_json::Value = serde_json::from_slice(&json).map_err(io::Error::other)?;
    let mut assets: HashMap<String, Asset> = HashMap::new();
    for s in v["slides"].as_array().into_iter().flatten() {
        if let Some(url) = s["image"].as_str() {
            if url.starts_with("/a/") && !assets.contains_key(url) {
                let (c, body) = http::get(host, port, url).await?;
                if c == 200 {
                    assets.insert(url.to_string(), asset(ctype_for(url), body));
                }
            }
        }
    }
    Deck::from_mirror(json, assets).map_err(io::Error::other)
}

//! The broadcast hub: one current state, latest-state-wins fan-out, leading-edge throttle.

use crate::{config::Config, deck::Deck};
use bytes::Bytes;
use serde_json::{Value, json};
use std::{
    collections::HashSet,
    net::IpAddr,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU32, AtomicUsize, Ordering::SeqCst},
    },
    time::{Duration, Instant},
};
use tokio::sync::watch;

/// Further changes within this window after a send merge into one trailing update.
const THROTTLE: Duration = Duration::from_millis(150);
/// Keepalive so phones (and proxies) see the stream is alive; viewers reconnect after 25 s of silence.
const KEEPALIVE: Duration = Duration::from_secs(15);

pub struct Show {
    pub deck: Arc<Deck>,
    pub slide: usize,
    pub blank: bool,
    pub text: Option<String>,
    pub seq: u64,
}

pub struct Hub {
    pub cfg: Arc<Config>,
    pub show: Mutex<Show>,
    tx: watch::Sender<Bytes>,
    tick: watch::Sender<u64>,
    pub clients: AtomicUsize,
    last_pub: Mutex<Option<Instant>>,
    trailing: AtomicBool,
    pub joined: Mutex<HashSet<IpAddr>>,
    pub started: Instant,
    pub upstream_ok: AtomicBool,
    /// Relay: the boatcast we follow. The phone app can change it at runtime (mesh re-parenting).
    pub upstream: Mutex<Option<(String, u16)>>,
    /// Woken when `upstream` changes so the relay drops the old link at once.
    pub upstream_changed: tokio::sync::Notify,
    /// Hops from the host (host = 0); relays learn it from their upstream's /status.
    pub depth: AtomicU32,
    /// Mesh id: chosen by the host, inherited by relays; lets phones tell meshes apart.
    pub mesh: AtomicU32,
    /// Live streams opened by other relays (child repeaters), a subset of `clients`.
    pub relays: AtomicUsize,
}

pub struct Policy {
    pub preload: &'static str,
    pub spread_ms: u64,
    pub retry_ms: u64,
}

fn event_bytes(seq: u64, data: &str) -> Bytes {
    Bytes::from(format!("id: {seq}\nevent: state\ndata: {data}\n\n"))
}

fn state_json(s: &Show) -> String {
    json!({
        "seq": s.seq,
        "deck": s.deck.version,
        "slide": s.slide,
        "slides": s.deck.slides.len(),
        "blank": s.blank,
        "text": s.text,
    })
    .to_string()
}

impl Hub {
    pub fn new(cfg: Arc<Config>, deck: Deck) -> Arc<Hub> {
        let show = Show { deck: Arc::new(deck), slide: 0, blank: false, text: None, seq: 0 };
        let first = event_bytes(0, &state_json(&show));
        let (tx, _) = watch::channel(first);
        let (tick, _) = watch::channel(0u64);
        let (cfg_upstream, relay, mesh_id) = (cfg.upstream.clone(), cfg.relay, cfg.mesh_id.unwrap_or(0) as u32);
        let hub = Arc::new(Hub {
            cfg,
            show: Mutex::new(show),
            tx,
            tick,
            clients: AtomicUsize::new(0),
            last_pub: Mutex::new(None),
            trailing: AtomicBool::new(false),
            joined: Mutex::new(HashSet::new()),
            started: Instant::now(),
            upstream_ok: AtomicBool::new(false),
            upstream: Mutex::new(cfg_upstream),
            upstream_changed: tokio::sync::Notify::new(),
            depth: AtomicU32::new(if relay { u32::MAX } else { 0 }),
            mesh: AtomicU32::new(mesh_id),
            relays: AtomicUsize::new(0),
        });
        let h = hub.clone();
        tokio::spawn(async move {
            let mut iv = tokio::time::interval(KEEPALIVE);
            iv.tick().await;
            loop {
                iv.tick().await;
                h.tick.send_modify(|t| *t += 1);
            }
        });
        hub
    }

    pub fn subscribe(&self) -> (watch::Receiver<Bytes>, watch::Receiver<u64>) {
        (self.tx.subscribe(), self.tick.subscribe())
    }

    pub fn deck(&self) -> Arc<Deck> {
        self.show.lock().unwrap().deck.clone()
    }

    /// Adapts preload and reconnect behaviour to how many phones are connected right now.
    pub fn policy(&self) -> Policy {
        let n = self.clients.load(SeqCst) as u64;
        match n {
            0..=49 => Policy { preload: "all", spread_ms: 0, retry_ms: 1000 },
            50..=200 => Policy { preload: "near", spread_ms: n * 100, retry_ms: 3000 },
            _ => Policy { preload: "near", spread_ms: (n * 100).min(60_000), retry_ms: 5000 },
        }
    }

    /// Applies a presenter action. Returns an error message for bad input.
    pub fn control(self: &Arc<Self>, cmd: &Value) -> Result<(), String> {
        let action = cmd["action"].as_str().unwrap_or("");
        {
            let mut s = self.show.lock().unwrap();
            let n = s.deck.slides.len();
            match action {
                "next" => s.slide = (s.slide + 1).min(n.saturating_sub(1)),
                "prev" => s.slide = s.slide.saturating_sub(1),
                "first" => s.slide = 0,
                "goto" => {
                    let i = cmd["slide"].as_u64().ok_or("goto needs \"slide\"")? as usize;
                    if i >= n {
                        return Err(format!("slide {i} out of range (0..{n})"));
                    }
                    s.slide = i;
                }
                "blank" => s.blank = cmd["on"].as_bool().unwrap_or(!s.blank),
                "text" => {
                    s.text = cmd["text"].as_str().map(|t| t.trim().to_string()).filter(|t| !t.is_empty());
                }
                "load_text" => {
                    let title = cmd["title"].as_str().unwrap_or("Slides");
                    let text = cmd["text"].as_str().ok_or("load_text needs \"text\"")?;
                    s.deck = Arc::new(Deck::from_text(title, text));
                    s.slide = 0;
                    s.blank = false;
                }
                "reload" => {
                    let dir = self.cfg.deck_dir.as_ref().ok_or("no --deck folder to reload")?;
                    s.deck = Arc::new(Deck::from_dir(dir)?);
                    s.slide = s.slide.min(s.deck.slides.len().saturating_sub(1));
                }
                other => return Err(format!("unknown action {other:?}")),
            }
        }
        self.schedule_publish();
        Ok(())
    }

    /// Leading edge goes out at once; anything within THROTTLE after it merges into one trailing send.
    fn schedule_publish(self: &Arc<Self>) {
        let now = Instant::now();
        let due = {
            let mut last = self.last_pub.lock().unwrap();
            let due = last.map(|t| t + THROTTLE).unwrap_or(now);
            if now >= due && !self.trailing.load(SeqCst) {
                *last = Some(now);
                None
            } else {
                Some(due.max(now))
            }
        };
        let Some(at) = due else {
            self.publish_now();
            return;
        };
        if self.trailing.swap(true, SeqCst) {
            return; // a trailing send is already scheduled; it will carry the latest state
        }
        let hub = self.clone();
        tokio::spawn(async move {
            tokio::time::sleep_until(tokio::time::Instant::from_std(at)).await;
            *hub.last_pub.lock().unwrap() = Some(Instant::now());
            hub.trailing.store(false, SeqCst);
            hub.publish_now();
        });
    }

    fn publish_now(&self) {
        let bytes = {
            let mut s = self.show.lock().unwrap();
            s.seq += 1;
            event_bytes(s.seq, &state_json(&s))
        };
        self.tx.send_replace(bytes);
    }

    /// Relay mode: adopt the upstream's state (and deck, if it changed) and re-broadcast it unchanged.
    pub fn relay_publish(&self, data: &str, deck: Option<Arc<Deck>>) {
        let v: Value = serde_json::from_str(data).unwrap_or(Value::Null);
        let seq = v["seq"].as_u64().unwrap_or(0);
        {
            let mut s = self.show.lock().unwrap();
            if let Some(d) = deck {
                s.deck = d;
            }
            s.seq = seq;
            s.slide = v["slide"].as_u64().unwrap_or(0) as usize;
            s.blank = v["blank"].as_bool().unwrap_or(false);
            s.text = v["text"].as_str().map(String::from);
        }
        self.tx.send_replace(event_bytes(seq, data));
    }
}

//! The broadcast hub: one current state, latest-state-wins fan-out, leading-edge throttle.

use crate::{config::Config, deck::Deck};
use bytes::Bytes;
use serde_json::{Value, json};
use std::{
    collections::{HashMap, HashSet},
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
    /// Random per start of the host. `seq` restarts at 0 with a new host, so viewers
    /// use (boot, seq) to tell a restarted host from a stale update.
    pub boot: u32,
}

/// One phone's replies in a delivery test.
pub struct Device {
    pub ua: String,
    pub ip: String,
    pub hops: u32,
    pub reconnects: u32,
    /// Milliseconds from sending probe n to its reply arriving back (there and back), by probe.
    pub delays: Vec<Option<u32>>,
}

/// A delivery test: the host sends numbered probes down the live stream and every viewer
/// page answers each one, so the host can time real delivery to every phone. Probes never
/// touch the show itself.
#[derive(Default)]
pub struct TestRun {
    pub run: u32,
    pub of: u32,
    pub gap_ms: u64,
    pub running: bool,
    pub clients_at_start: usize,
    pub sent: Vec<Instant>,
    pub devices: HashMap<String, Device>,
}

const MAX_TEST_DEVICES: usize = 2000;

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
    /// Delivery-test probes; a separate channel so a test never disturbs the slides.
    probe: watch::Sender<Bytes>,
    pub test: Mutex<TestRun>,
    /// Wi-Fi name and password shown as the join code; settable from the console at runtime.
    pub wifi: Mutex<Option<(String, Option<String>)>>,
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
        "boot": s.boot,
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
        let nanos = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
        let boot = (crate::deck::fnv(&[&nanos.to_le_bytes()[..], &std::process::id().to_le_bytes()[..]].concat()) as u32).max(1);
        let show = Show { deck: Arc::new(deck), slide: 0, blank: false, text: None, seq: 0, boot };
        let first = event_bytes(0, &state_json(&show));
        let (tx, _) = watch::channel(first);
        let (tick, _) = watch::channel(0u64);
        let (probe, _) = watch::channel(Bytes::new());
        let wifi = cfg.wifi_ssid.clone().map(|s| (s, cfg.wifi_pass.clone()));
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
            probe,
            test: Mutex::new(TestRun::default()),
            wifi: Mutex::new(wifi),
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

    pub fn subscribe(&self) -> (watch::Receiver<Bytes>, watch::Receiver<u64>, watch::Receiver<Bytes>) {
        (self.tx.subscribe(), self.tick.subscribe(), self.probe.subscribe())
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
        match action {
            "test" => {
                let count = cmd["count"].as_u64().unwrap_or(20).clamp(1, 100) as u32;
                let gap = cmd["gap_ms"].as_u64().unwrap_or(1500).clamp(300, 10_000);
                self.start_test(count, gap);
                return Ok(());
            }
            "test_stop" => {
                self.test.lock().unwrap().running = false;
                return Ok(());
            }
            "wifi" => {
                let ssid = cmd["ssid"].as_str().map(str::trim).filter(|s| !s.is_empty());
                let pass = cmd["pass"].as_str().filter(|s| !s.is_empty());
                if ssid.is_some_and(|s| s.len() > 32) || pass.is_some_and(|p| p.len() > 63) {
                    return Err(String::from("Wi-Fi name is at most 32 characters, password at most 63"));
                }
                *self.wifi.lock().unwrap() = ssid.map(|s| (s.to_string(), pass.map(String::from)));
                return Ok(());
            }
            _ => {}
        }
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

    /// Starts a delivery test: `count` probes, `gap_ms` apart. A new test replaces the old one.
    fn start_test(self: &Arc<Self>, count: u32, gap_ms: u64) {
        let run = {
            let mut t = self.test.lock().unwrap();
            *t = TestRun {
                run: t.run + 1,
                of: count,
                gap_ms,
                running: true,
                clients_at_start: self.clients.load(SeqCst),
                sent: Vec::with_capacity(count as usize),
                devices: HashMap::new(),
            };
            t.run
        };
        let hub = self.clone();
        tokio::spawn(async move {
            for n in 1..=count {
                {
                    let mut t = hub.test.lock().unwrap();
                    if t.run != run || !t.running {
                        return;
                    }
                    t.sent.push(Instant::now());
                }
                hub.probe.send_replace(probe_bytes(&json!({"run": run, "n": n, "of": count}).to_string()));
                tokio::time::sleep(Duration::from_millis(gap_ms)).await;
            }
            // Leave time for the last replies, then mark the test finished.
            tokio::time::sleep(Duration::from_secs(3)).await;
            let mut t = hub.test.lock().unwrap();
            if t.run == run {
                t.running = false;
            }
        });
    }

    /// Relay mode: pass a probe from upstream on to our own phones unchanged.
    pub fn relay_probe(&self, data: &str) {
        self.probe.send_replace(probe_bytes(data));
    }

    /// Records one phone's reply to a probe. Unknown runs and probes are ignored.
    pub fn test_ack(&self, v: &Value, ip: IpAddr, header_ua: Option<&str>) {
        let now = Instant::now();
        let mut t = self.test.lock().unwrap();
        let n = v["n"].as_u64().unwrap_or(0) as usize;
        if v["run"].as_u64() != Some(t.run as u64) || n == 0 || n > t.sent.len() {
            return;
        }
        let Some(id) = v["id"].as_str().filter(|s| !s.is_empty() && s.len() <= 16) else { return };
        let ms = now.duration_since(t.sent[n - 1]).as_millis().min(u32::MAX as u128) as u32;
        let of = t.of as usize;
        if !t.devices.contains_key(id) && t.devices.len() >= MAX_TEST_DEVICES {
            return;
        }
        let d = t.devices.entry(id.to_string()).or_insert_with(|| Device {
            ua: String::new(),
            ip: ip.to_string(),
            hops: 0,
            reconnects: 0,
            delays: vec![None; of],
        });
        if d.ua.is_empty() {
            d.ua = v["ua"].as_str().or(header_ua).unwrap_or("").chars().take(160).collect();
        }
        d.hops = v["hops"].as_u64().unwrap_or(0).min(64) as u32;
        d.reconnects = v["rc"].as_u64().unwrap_or(0).min(u32::MAX as u64) as u32;
        if d.delays[n - 1].is_none() {
            d.delays[n - 1] = Some(ms);
        }
    }

    /// The current (or last) delivery test as JSON for the console.
    pub fn test_results(&self) -> Value {
        let t = self.test.lock().unwrap();
        let sent = t.sent.len();
        let mut all: Vec<u32> = Vec::new();
        let (mut expected, mut got_total) = (0usize, 0usize);
        let mut devices: Vec<Value> = t
            .devices
            .iter()
            .map(|(id, d)| {
                let mut got: Vec<u32> = d.delays.iter().take(sent).flatten().copied().collect();
                got.sort_unstable();
                all.extend(&got);
                expected += sent;
                got_total += got.len();
                json!({
                    "id": id, "ua": d.ua, "ip": d.ip, "hops": d.hops, "reconnects": d.reconnects,
                    "got": got.len(), "lost": sent - got.len(),
                    "min_ms": got.first(), "median_ms": pick(&got, 50), "max_ms": got.last(),
                })
            })
            .collect();
        devices.sort_by(|a, b| b["median_ms"].as_u64().cmp(&a["median_ms"].as_u64()));
        all.sort_unstable();
        json!({
            "run": t.run, "running": t.running, "sent": sent, "of": t.of, "gap_ms": t.gap_ms,
            "clients_at_start": t.clients_at_start, "clients_now": self.clients.load(SeqCst),
            "summary": {
                "devices": t.devices.len(),
                "median_ms": pick(&all, 50), "p95_ms": pick(&all, 95), "max_ms": all.last(),
                "lost": expected - got_total,
                "lost_pct": if expected == 0 { 0.0 } else { ((expected - got_total) as f64 * 1000.0 / expected as f64).round() / 10.0 },
            },
            "devices": devices,
        })
    }
}

fn probe_bytes(data: &str) -> Bytes {
    Bytes::from(format!("event: probe\ndata: {data}\n\n"))
}

/// Percentile of a sorted list (nearest rank); None when empty.
fn pick(sorted: &[u32], pct: usize) -> Option<u32> {
    if sorted.is_empty() {
        return None;
    }
    let rank = (sorted.len() * pct).div_ceil(100).max(1);
    Some(sorted[rank - 1])
}

#[cfg(test)]
mod tests {
    #[test]
    fn percentiles() {
        assert_eq!(super::pick(&[], 50), None);
        assert_eq!(super::pick(&[7], 95), Some(7));
        assert_eq!(super::pick(&[1, 2, 3, 4], 50), Some(2));
        assert_eq!(super::pick(&(1..=100).collect::<Vec<u32>>(), 95), Some(95));
    }
}

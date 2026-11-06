//! Command line, network addresses and QR codes.

use std::{net::IpAddr, path::PathBuf};

pub const HELP: &str = "\
boatcast — broadcast live slides and words to phones over Wi-Fi, no internet needed.

USAGE
  boatcast [serve] [options]                 host: runs the show
  boatcast relay --upstream http://IP:PORT   repeater: follows another boatcast and re-serves it

OPTIONS
  --port N            HTTP port (default 8080; use 80 for the captive portal)
  --bind ADDR         listen address (default 0.0.0.0)
  --deck DIR          folder with deck.json, slides.txt or slide images (default: built-in demo)
  --pin PIN           allow the console from other devices with this PIN (localhost never needs it)
  --wifi-ssid NAME    Wi-Fi name, for the Wi-Fi QR code
  --wifi-pass PASS    Wi-Fi password, for the Wi-Fi QR code
  --public-url URL    address printed in the page QR (e.g. https://show.example.com/)
  --host-ip IP        this device's address, if auto-detection picks the wrong one
  --captive           answer phone connectivity checks so the page pops up on join (needs DNS wildcard)
  --max-clients N     refuse viewers beyond N (default 2000)
  --threads N|auto    worker threads (default 1; auto = CPU cores, max 8)
  --upstream URL      relay mode: the boatcast to follow
  --tls-cert FILE     HTTPS certificate chain (PEM)   [build with --features tls]
  --tls-key FILE      HTTPS private key (PEM)
  --https-port N      HTTPS port (default 8443)
  -h, --help          this text
";

pub struct Config {
    pub relay: bool,
    pub port: u16,
    pub bind: String,
    pub deck_dir: Option<PathBuf>,
    pub pin: Option<String>,
    pub wifi_ssid: Option<String>,
    pub wifi_pass: Option<String>,
    pub public_url: Option<String>,
    pub host_ip: Option<IpAddr>,
    pub captive: bool,
    pub max_clients: usize,
    pub threads: usize,
    pub upstream: Option<(String, u16)>,
    pub upstream_url: Option<String>,
    pub tls_cert: Option<PathBuf>,
    pub tls_key: Option<PathBuf>,
    pub https_port: u16,
}

fn parse_upstream(u: &str) -> Result<(String, u16), String> {
    let rest = u.strip_prefix("http://").ok_or("--upstream must start with http:// (relays talk plain HTTP on the LAN)")?;
    let hostport = rest.split('/').next().unwrap_or(rest);
    match hostport.rsplit_once(':') {
        Some((h, p)) => Ok((h.to_string(), p.parse().map_err(|_| "bad upstream port")?)),
        None => Ok((hostport.to_string(), 80)),
    }
}

impl Config {
    pub fn from_args(args: Vec<String>) -> Result<Config, String> {
        let mut c = Config {
            relay: false,
            port: 8080,
            bind: String::from("0.0.0.0"),
            deck_dir: None,
            pin: None,
            wifi_ssid: None,
            wifi_pass: None,
            public_url: None,
            host_ip: None,
            captive: false,
            max_clients: 2000,
            threads: 1,
            upstream: None,
            upstream_url: None,
            tls_cert: None,
            tls_key: None,
            https_port: 8443,
        };
        let mut it = args.into_iter().peekable();
        while let Some(a) = it.next() {
            let mut val = |name: &str| it.next().ok_or(format!("{name} needs a value"));
            match a.as_str() {
                "serve" => {}
                "relay" => c.relay = true,
                "-h" | "--help" => return Err(String::new()),
                "--port" => c.port = val("--port")?.parse().map_err(|_| "bad --port")?,
                "--bind" => c.bind = val("--bind")?,
                "--deck" => c.deck_dir = Some(PathBuf::from(val("--deck")?)),
                "--pin" => c.pin = Some(val("--pin")?),
                "--wifi-ssid" => c.wifi_ssid = Some(val("--wifi-ssid")?),
                "--wifi-pass" => c.wifi_pass = Some(val("--wifi-pass")?),
                "--public-url" => c.public_url = Some(val("--public-url")?),
                "--host-ip" => c.host_ip = Some(val("--host-ip")?.parse().map_err(|_| "bad --host-ip")?),
                "--captive" => c.captive = true,
                "--max-clients" => c.max_clients = val("--max-clients")?.parse().map_err(|_| "bad --max-clients")?,
                "--threads" => {
                    let v = val("--threads")?;
                    c.threads = if v == "auto" {
                        std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1).min(8)
                    } else {
                        v.parse().map_err(|_| "bad --threads")?
                    };
                }
                "--upstream" => {
                    let u = val("--upstream")?;
                    c.upstream = Some(parse_upstream(&u)?);
                    c.upstream_url = Some(u);
                    c.relay = true;
                }
                "--tls-cert" => c.tls_cert = Some(PathBuf::from(val("--tls-cert")?)),
                "--tls-key" => c.tls_key = Some(PathBuf::from(val("--tls-key")?)),
                "--https-port" => c.https_port = val("--https-port")?.parse().map_err(|_| "bad --https-port")?,
                other => return Err(format!("unknown option {other}")),
            }
        }
        if c.relay && c.upstream.is_none() {
            return Err(String::from("relay mode needs --upstream http://IP:PORT"));
        }
        if c.tls_cert.is_some() != c.tls_key.is_some() {
            return Err(String::from("--tls-cert and --tls-key go together"));
        }
        Ok(c)
    }

    /// Best guess at the address phones reach us on. Re-read on every call: Android changes it per hotspot session.
    pub fn local_ip(&self) -> Option<IpAddr> {
        if let Some(ip) = self.host_ip {
            return Some(ip);
        }
        let ifs = if_addrs::get_if_addrs().ok()?;
        // A repeater sits on two networks: the upstream Wi-Fi (station) and its own
        // hotspot. Phones on the hotspot must be sent to the hotspot address, so skip
        // any interface that shares a subnet with the upstream host.
        let upstream_v4 = self
            .upstream
            .as_ref()
            .and_then(|(h, _)| h.parse::<std::net::Ipv4Addr>().ok());
        let same_net = |i: &if_addrs::Interface| match (&i.addr, upstream_v4) {
            (if_addrs::IfAddr::V4(a), Some(u)) => {
                let m = u32::from(a.netmask);
                u32::from(a.ip) & m == u32::from(u) & m
            }
            _ => false,
        };
        let v4: Vec<_> = ifs
            .iter()
            .filter(|i| !i.is_loopback() && i.ip().is_ipv4() && !same_net(i))
            .collect();
        let private = |ip: IpAddr| match ip {
            IpAddr::V4(a) => a.is_private(),
            _ => false,
        };
        let wifi = |n: &str| ["wlan", "ap", "wl", "swlan", "softap", "wifi"].iter().any(|p| n.starts_with(p));
        v4.iter()
            .find(|i| wifi(&i.name) && private(i.ip()))
            .or_else(|| v4.iter().find(|i| private(i.ip())))
            .or_else(|| v4.first())
            .map(|i| i.ip())
    }

    pub fn page_url(&self) -> String {
        if let Some(u) = &self.public_url {
            return u.clone();
        }
        let ip = self.local_ip().map(|i| i.to_string()).unwrap_or_else(|| String::from("127.0.0.1"));
        if self.port == 80 { format!("http://{ip}/") } else { format!("http://{ip}:{}/", self.port) }
    }

    pub fn wifi_qr_text(&self) -> Option<String> {
        let esc = |s: &str| {
            s.chars()
                .flat_map(|c| if "\\;,:\"".contains(c) { vec!['\\', c] } else { vec![c] })
                .collect::<String>()
        };
        let ssid = self.wifi_ssid.as_ref()?;
        Some(match &self.wifi_pass {
            Some(p) => format!("WIFI:T:WPA;S:{};P:{};;", esc(ssid), esc(p)),
            None => format!("WIFI:T:nopass;S:{};;", esc(ssid)),
        })
    }
}

pub fn qr_svg(data: &str) -> Option<String> {
    use qrcode::{EcLevel, QrCode, render::svg};
    let code = QrCode::with_error_correction_level(data.as_bytes(), EcLevel::M).ok()?;
    Some(
        code.render::<svg::Color>()
            .min_dimensions(320, 320)
            .dark_color(svg::Color("#000000"))
            .light_color(svg::Color("#ffffff"))
            .build(),
    )
}

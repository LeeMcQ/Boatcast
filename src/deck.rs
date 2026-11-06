//! Decks: loaded once, served from memory. Images get content-hashed URLs so phones cache them forever.

use bytes::Bytes;
use flate2::{Compression, write::GzEncoder};
use serde::Deserialize;
use serde_json::json;
use std::{collections::HashMap, io::Write, path::Path};

/// A file served from memory, with a gzip copy for text and an ETag.
pub struct Asset {
    pub ctype: &'static str,
    pub body: Bytes,
    pub gz: Option<Bytes>,
    pub etag: String,
}

pub fn fnv(data: &[u8]) -> u64 {
    let mut h: u64 = 0xcbf29ce484222325;
    for b in data {
        h ^= *b as u64;
        h = h.wrapping_mul(0x100000001b3);
    }
    h
}

fn gzip(data: &[u8]) -> Bytes {
    let mut e = GzEncoder::new(Vec::new(), Compression::best());
    e.write_all(data).ok();
    Bytes::from(e.finish().unwrap_or_default())
}

pub fn asset(ctype: &'static str, body: impl Into<Bytes>) -> Asset {
    let body: Bytes = body.into();
    let texty = ctype.starts_with("text/") || ctype.contains("json") || ctype.contains("javascript") || ctype.contains("svg");
    let gz = (texty && body.len() > 512).then(|| gzip(&body));
    Asset { ctype, etag: format!("{:016x}", fnv(&body)), body, gz }
}

pub fn ctype_for(name: &str) -> &'static str {
    let ext = name.rsplit('.').next().unwrap_or("").to_ascii_lowercase();
    match ext.as_str() {
        "webp" => "image/webp",
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "gif" => "image/gif",
        "svg" => "image/svg+xml",
        "avif" => "image/avif",
        "json" => "application/json",
        _ => "application/octet-stream",
    }
}

#[derive(Deserialize)]
struct DeckFile {
    title: Option<String>,
    slides: Vec<SlideIn>,
}

#[derive(Deserialize)]
struct SlideIn {
    title: Option<String>,
    text: Option<String>,
    image: Option<String>,
    notes: Option<String>,
}

#[derive(Clone, Default)]
pub struct Slide {
    pub title: Option<String>,
    pub text: Option<String>,
    pub image: Option<String>, // URL, e.g. /a/3f2c...webp
    pub notes: Option<String>,
}

pub struct Deck {
    pub version: String,
    pub title: String,
    pub slides: Vec<Slide>,
    /// What phones download: no speaker notes.
    pub public_json: Asset,
    /// What the presenter console downloads: with notes.
    pub full_json: Asset,
    /// Images by URL path.
    pub assets: HashMap<String, Asset>,
}

impl Deck {
    pub fn build(title: String, slides: Vec<Slide>, assets: HashMap<String, Asset>) -> Deck {
        let view = |notes: bool| {
            slides
                .iter()
                .map(|s| {
                    let mut o = json!({});
                    if let Some(t) = &s.title { o["title"] = json!(t); }
                    if let Some(t) = &s.text { o["text"] = json!(t); }
                    if let Some(t) = &s.image { o["image"] = json!(t); }
                    if notes { if let Some(t) = &s.notes { o["notes"] = json!(t); } }
                    o
                })
                .collect::<Vec<_>>()
        };
        let body_no_ver = serde_json::to_vec(&json!({"title": title, "slides": view(false)})).unwrap_or_default();
        let version = format!("{:012x}", fnv(&body_no_ver) & 0xffff_ffff_ffff);
        let public = serde_json::to_vec(&json!({"version": version, "title": title, "slides": view(false)})).unwrap_or_default();
        let full = serde_json::to_vec(&json!({"version": version, "title": title, "slides": view(true)})).unwrap_or_default();
        Deck {
            version,
            title,
            slides,
            public_json: asset("application/json", public),
            full_json: asset("application/json", full),
            assets,
        }
    }

    /// Text slides: blocks separated by blank lines. A block starting with "# " gets that line as its title.
    pub fn from_text(title: &str, text: &str) -> Deck {
        let norm = text.replace("\r\n", "\n");
        let mut slides = Vec::new();
        for block in norm.split("\n\n") {
            let block = block.trim_matches('\n');
            if block.trim().is_empty() {
                continue;
            }
            let (t, body) = match block.strip_prefix("# ") {
                Some(rest) => {
                    let (h, b) = rest.split_once('\n').unwrap_or((rest, ""));
                    (Some(h.trim().to_string()), b.to_string())
                }
                None => (None, block.to_string()),
            };
            slides.push(Slide { title: t, text: (!body.trim().is_empty()).then_some(body), ..Default::default() });
        }
        if slides.is_empty() {
            slides.push(Slide { text: Some(String::from("…")), ..Default::default() });
        }
        Deck::build(title.to_string(), slides, HashMap::new())
    }

    pub fn demo() -> Deck {
        Deck::from_text(
            "Welcome",
            "# Welcome\nScan the Wi-Fi code, then the page code.\nThis page follows the presenter live.\n\n\
             # How it works\nThe deck loads once.\nEach slide change is one tiny message.\n\n\
             # Your settings\nTap Aa to change text size or colours.\nSwipe to look back; tap Back to live to return.",
        )
    }

    /// A folder with deck.json, or slides.txt, or just images (sorted by name).
    pub fn from_dir(dir: &Path) -> Result<Deck, String> {
        let mut assets = HashMap::new();
        let mut add_image = |name: &str| -> Result<String, String> {
            let p = dir.join(name);
            let data = std::fs::read(&p).map_err(|e| format!("{}: {e}", p.display()))?;
            let ext = name.rsplit('.').next().unwrap_or("bin").to_ascii_lowercase();
            let url = format!("/a/{:016x}.{ext}", fnv(&data));
            assets.insert(url.clone(), asset(ctype_for(name), data));
            Ok(url)
        };
        let deck_json = dir.join("deck.json");
        if deck_json.exists() {
            let raw = std::fs::read_to_string(&deck_json).map_err(|e| e.to_string())?;
            let df: DeckFile = serde_json::from_str(&raw).map_err(|e| format!("deck.json: {e}"))?;
            let mut slides = Vec::new();
            for s in df.slides {
                let image = match s.image {
                    Some(name) => Some(add_image(&name)?),
                    None => None,
                };
                slides.push(Slide { title: s.title, text: s.text, image, notes: s.notes });
            }
            let title = df.title.unwrap_or_else(|| String::from("Slides"));
            return Ok(Deck::build(title, slides, assets));
        }
        let txt = dir.join("slides.txt");
        if txt.exists() {
            let raw = std::fs::read_to_string(&txt).map_err(|e| e.to_string())?;
            return Ok(Deck::from_text("Slides", &raw));
        }
        let mut names: Vec<String> = std::fs::read_dir(dir)
            .map_err(|e| format!("{}: {e}", dir.display()))?
            .filter_map(|e| e.ok())
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .filter(|n| ctype_for(n).starts_with("image/"))
            .collect();
        // PowerPoint exports Slide1.PNG … Slide10.PNG: sort numbers by value, not by text.
        names.sort_by_cached_key(|n| natural_key(n));
        if names.is_empty() {
            return Err(format!("{}: no deck.json, slides.txt or images", dir.display()));
        }
        let mut slides = Vec::new();
        for n in &names {
            slides.push(Slide { image: Some(add_image(n)?), ..Default::default() });
        }
        Ok(Deck::build(String::from("Slides"), slides, assets))
    }

    /// Relay mode: rebuild the deck from the upstream's public JSON and images, keeping its version.
    pub fn from_mirror(json_bytes: Vec<u8>, assets: HashMap<String, Asset>) -> Result<Deck, String> {
        let v: serde_json::Value = serde_json::from_slice(&json_bytes).map_err(|e| e.to_string())?;
        let version = v["version"].as_str().unwrap_or("").to_string();
        let title = v["title"].as_str().unwrap_or("Slides").to_string();
        let slides = v["slides"]
            .as_array()
            .map(|a| {
                a.iter()
                    .map(|s| Slide {
                        title: s["title"].as_str().map(String::from),
                        text: s["text"].as_str().map(String::from),
                        image: s["image"].as_str().map(String::from),
                        notes: None,
                    })
                    .collect()
            })
            .unwrap_or_default();
        let public = asset("application/json", json_bytes);
        let full = asset("application/json", public.body.clone());
        Ok(Deck { version, title, slides, public_json: public, full_json: full, assets })
    }
}

/// "Slide10.png" sorts after "Slide9.png": runs of digits compare as numbers.
fn natural_key(name: &str) -> Vec<(u8, u64, String)> {
    let mut out = Vec::new();
    let mut chars = name.chars().peekable();
    while let Some(&c) = chars.peek() {
        if c.is_ascii_digit() {
            let mut n: u64 = 0;
            while let Some(&d) = chars.peek().filter(|d| d.is_ascii_digit()) {
                n = n.saturating_mul(10).saturating_add(u64::from(d as u8 - b'0'));
                chars.next();
            }
            out.push((0, n, String::new()));
        } else {
            let mut t = String::new();
            while let Some(&d) = chars.peek().filter(|d| !d.is_ascii_digit()) {
                t.extend(d.to_lowercase());
                chars.next();
            }
            out.push((1, 0, t));
        }
    }
    out
}

#[cfg(test)]
mod tests {
    #[test]
    fn natural_order() {
        let mut v = vec!["Slide10.PNG", "Slide2.PNG", "slide1.png", "Slide9.PNG"];
        v.sort_by_cached_key(|n| super::natural_key(n));
        assert_eq!(v, ["slide1.png", "Slide2.PNG", "Slide9.PNG", "Slide10.PNG"]);
    }
}

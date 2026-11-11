//! Minimal HTTP/1.1: just enough to serve phones and the console. Keep-alive, no chunking.

use std::io;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

const MAX_HEAD: usize = 16 * 1024;
const MAX_BODY: usize = 256 * 1024;

pub struct Request {
    pub method: String,
    pub path: String,
    /// Text after `?` in the request target ("" if none).
    pub query: String,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

impl Request {
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(k, _)| k == name).map(|(_, v)| v.as_str())
    }
    pub fn accepts_gzip(&self) -> bool {
        self.header("accept-encoding").is_some_and(|v| v.contains("gzip"))
    }
    pub fn wants_close(&self) -> bool {
        self.header("connection").is_some_and(|v| v.eq_ignore_ascii_case("close"))
    }
    /// Host header without port.
    pub fn host(&self) -> &str {
        let h = self.header("host").unwrap_or("");
        if h.starts_with('[') {
            return h.split(']').next().map(|s| &s[1..]).unwrap_or(h);
        }
        h.rsplit_once(':').map(|(a, _)| a).unwrap_or(h)
    }
}

fn find_head_end(buf: &[u8]) -> Option<usize> {
    buf.windows(4).position(|w| w == b"\r\n\r\n")
}

/// Reads one request. `buf` keeps bytes that belong to the next request on this connection.
pub async fn read_request<R: AsyncRead + Unpin>(r: &mut R, buf: &mut Vec<u8>) -> io::Result<Option<Request>> {
    let mut tmp = [0u8; 4096];
    let end = loop {
        if let Some(p) = find_head_end(buf) {
            break p;
        }
        if buf.len() > MAX_HEAD {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "header too large"));
        }
        let n = r.read(&mut tmp).await?;
        if n == 0 {
            return Ok(None);
        }
        buf.extend_from_slice(&tmp[..n]);
    };
    let head = String::from_utf8_lossy(&buf[..end]).into_owned();
    let mut lines = head.split("\r\n");
    let first = lines.next().unwrap_or("");
    let mut parts = first.split(' ');
    let method = parts.next().unwrap_or("").to_string();
    let target = parts.next().unwrap_or("/");
    let (path, query) = target.split_once('?').unwrap_or((target, ""));
    let query = query.to_string();
    let headers: Vec<(String, String)> = lines
        .filter_map(|l| l.split_once(':'))
        .map(|(k, v)| (k.trim().to_ascii_lowercase(), v.trim().to_string()))
        .collect();
    let len: usize = headers
        .iter()
        .find(|(k, _)| k == "content-length")
        .and_then(|(_, v)| v.parse().ok())
        .unwrap_or(0);
    if len > MAX_BODY {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "body too large"));
    }
    let need = end + 4 + len;
    while buf.len() < need {
        let n = r.read(&mut tmp).await?;
        if n == 0 {
            return Ok(None);
        }
        buf.extend_from_slice(&tmp[..n]);
    }
    let body = buf[end + 4..need].to_vec();
    buf.drain(..need);
    Ok(Some(Request {
        method,
        path: path.to_string(),
        query,
        headers,
        body,
    }))
}

pub fn status_text(code: u16) -> &'static str {
    match code {
        200 => "OK",
        204 => "No Content",
        302 => "Found",
        304 => "Not Modified",
        400 => "Bad Request",
        403 => "Forbidden",
        404 => "Not Found",
        405 => "Method Not Allowed",
        409 => "Conflict",
        503 => "Service Unavailable",
        _ => "OK",
    }
}

/// Writes a complete response in one write when small, so it leaves in as few packets as possible.
pub async fn respond<W: AsyncWrite + Unpin>(
    w: &mut W,
    code: u16,
    headers: &[(&str, String)],
    body: &[u8],
    head_only: bool,
) -> io::Result<()> {
    let mut out = format!("HTTP/1.1 {} {}\r\n", code, status_text(code));
    for (k, v) in headers {
        out.push_str(k);
        out.push_str(": ");
        out.push_str(v);
        out.push_str("\r\n");
    }
    if code != 204 && code != 304 {
        out.push_str(&format!("Content-Length: {}\r\n", body.len()));
    }
    out.push_str("\r\n");
    let mut bytes = out.into_bytes();
    if head_only || code == 204 || code == 304 {
        return w.write_all(&bytes).await;
    }
    if body.len() <= 32 * 1024 {
        bytes.extend_from_slice(body);
        w.write_all(&bytes).await
    } else {
        w.write_all(&bytes).await?;
        w.write_all(body).await
    }
}

/// Plain GET used by relay mode to talk to its upstream (HTTP on the LAN).
pub async fn get(host: &str, port: u16, path: &str) -> io::Result<(u16, Vec<u8>)> {
    let mut s = tokio::net::TcpStream::connect((host, port)).await?;
    s.set_nodelay(true).ok();
    let req = format!("GET {path} HTTP/1.1\r\nHost: {host}\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n");
    s.write_all(req.as_bytes()).await?;
    let mut all = Vec::new();
    s.read_to_end(&mut all).await?;
    let end = find_head_end(&all).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "bad response"))?;
    let head = String::from_utf8_lossy(&all[..end]);
    let code = head.split(' ').nth(1).and_then(|c| c.parse().ok()).unwrap_or(0);
    Ok((code, all[end + 4..].to_vec()))
}

/// Plain JSON POST used by relays to pass a phone's test reply up to the host. The reply is not read.
pub async fn post(host: &str, port: u16, path: &str, body: &[u8]) -> io::Result<()> {
    let mut s = tokio::net::TcpStream::connect((host, port)).await?;
    s.set_nodelay(true).ok();
    let head = format!(
        "POST {path} HTTP/1.1\r\nHost: {host}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
        body.len()
    );
    s.write_all(head.as_bytes()).await?;
    s.write_all(body).await?;
    let mut sink = [0u8; 256];
    let _ = s.read(&mut sink).await;
    Ok(())
}

//! boatcast: broadcast live slides and words to phones over local Wi-Fi.
//! One small binary; runs on a laptop, an Android phone (Termux) or an OpenWrt router.

mod config;
mod deck;
mod http;
mod hub;
mod relay;
mod server;

use config::{Config, HELP};
use deck::Deck;
use hub::Hub;
use std::{sync::Arc, time::Duration};
use tokio::net::TcpListener;

fn main() {
    let cfg = match Config::from_args(std::env::args().skip(1).collect()) {
        Ok(c) => c,
        Err(e) => {
            if e.is_empty() {
                print!("{HELP}");
                return;
            }
            eprintln!("error: {e}\n\n{HELP}");
            std::process::exit(2);
        }
    };
    let rt = if cfg.threads <= 1 {
        tokio::runtime::Builder::new_current_thread().enable_all().build()
    } else {
        tokio::runtime::Builder::new_multi_thread().worker_threads(cfg.threads).enable_all().build()
    }
    .expect("runtime");
    if let Err(e) = rt.block_on(run(cfg)) {
        eprintln!("error: {e}");
        std::process::exit(1);
    }
}

async fn run(cfg: Config) -> Result<(), String> {
    let deck = match &cfg.deck_dir {
        Some(dir) => Deck::from_dir(dir)?,
        None => Deck::demo(),
    };
    let cfg = Arc::new(cfg);
    let hub = Hub::new(cfg.clone(), deck);

    let addr = format!("{}:{}", cfg.bind, cfg.port);
    let listener = TcpListener::bind(&addr).await.map_err(|e| format!("cannot listen on {addr}: {e}"))?;
    tokio::spawn(accept_plain(listener, hub.clone()));

    #[cfg(feature = "tls")]
    if let (Some(cert), Some(key)) = (&cfg.tls_cert, &cfg.tls_key) {
        let acceptor = tls::acceptor(cert, key)?;
        let haddr = format!("{}:{}", cfg.bind, cfg.https_port);
        let l = TcpListener::bind(&haddr).await.map_err(|e| format!("cannot listen on {haddr}: {e}"))?;
        tokio::spawn(tls::accept(l, acceptor, hub.clone()));
        eprintln!("HTTPS on port {} (installable app enabled for the public URL)", cfg.https_port);
    }
    #[cfg(not(feature = "tls"))]
    if cfg.tls_cert.is_some() {
        return Err(String::from("this build has no HTTPS; rebuild with: cargo build --release --features tls"));
    }

    {
        let d = hub.deck();
        eprintln!(
            "boatcast {} — {} mode, deck \"{}\" ({} slides)",
            env!("CARGO_PKG_VERSION"),
            if cfg.relay { "relay" } else { "host" },
            d.title,
            d.slides.len()
        );
    }
    eprintln!("  phones open:   {}", cfg.page_url());
    eprintln!("  console:       http://127.0.0.1:{}/console", cfg.port);
    if cfg.wifi_ssid.is_none() {
        eprintln!("  (add --wifi-ssid and --wifi-pass to show the Wi-Fi QR code in the console)");
    }
    if cfg.relay {
        tokio::spawn(relay::run(hub.clone()));
    }
    std::future::pending::<()>().await;
    Ok(())
}

async fn accept_plain(listener: TcpListener, hub: Arc<Hub>) {
    loop {
        match listener.accept().await {
            Ok((s, peer)) => {
                s.set_nodelay(true).ok();
                tokio::spawn(server::conn(hub.clone(), s, peer));
            }
            Err(e) => {
                // Usually "too many open files": back off instead of spinning.
                eprintln!("accept: {e}");
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        }
    }
}

#[cfg(feature = "tls")]
mod tls {
    use super::*;
    use std::path::Path;
    use tokio_rustls::{
        TlsAcceptor,
        rustls::{
            ServerConfig,
            pki_types::{CertificateDer, PrivateKeyDer, pem::PemObject},
        },
    };

    pub fn acceptor(cert: &Path, key: &Path) -> Result<TlsAcceptor, String> {
        let certs: Vec<CertificateDer<'static>> = CertificateDer::pem_file_iter(cert)
            .map_err(|e| format!("{}: {e}", cert.display()))?
            .collect::<Result<_, _>>()
            .map_err(|e| format!("{}: {e}", cert.display()))?;
        let key = PrivateKeyDer::from_pem_file(key).map_err(|e| format!("{}: {e}", key.display()))?;
        let mut sc = ServerConfig::builder_with_provider(Arc::new(tokio_rustls::rustls::crypto::ring::default_provider()))
            .with_safe_default_protocol_versions()
            .map_err(|e| e.to_string())?
            .with_no_client_auth()
            .with_single_cert(certs, key)
            .map_err(|e| e.to_string())?;
        sc.alpn_protocols = vec![b"http/1.1".to_vec()];
        Ok(TlsAcceptor::from(Arc::new(sc)))
    }

    pub async fn accept(listener: TcpListener, acceptor: TlsAcceptor, hub: Arc<Hub>) {
        loop {
            match listener.accept().await {
                Ok((s, peer)) => {
                    s.set_nodelay(true).ok();
                    let (acceptor, hub) = (acceptor.clone(), hub.clone());
                    tokio::spawn(async move {
                        if let Ok(Ok(t)) = tokio::time::timeout(Duration::from_secs(10), acceptor.accept(s)).await {
                            server::conn(hub, t, peer).await;
                        }
                    });
                }
                Err(e) => {
                    eprintln!("accept (https): {e}");
                    tokio::time::sleep(Duration::from_millis(100)).await;
                }
            }
        }
    }
}

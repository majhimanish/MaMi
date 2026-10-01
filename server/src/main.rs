use std::net::SocketAddr;

use mami_server::blobs::BlobStore;
use mami_server::mail::Mailer;
use mami_server::push::{Fcm, Pusher};
use mami_server::store::{self, Store};
use mami_server::{AppState, Config, Turn, router};
use tracing_subscriber::EnvFilter;

/// Configuration comes from environment variables; see `server/README.md`.
#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| "info,tower_http=info".into()),
        )
        .init();

    let bind: SocketAddr = env("MAMI_BIND")
        .unwrap_or_else(|| "0.0.0.0:8080".into())
        .parse()?;
    let database_url = env("MAMI_DATABASE_URL").unwrap_or_else(|| "sqlite://mami.db".into());

    let mailer = match (env("MAMI_SMTP_URL"), env("MAMI_MAIL_FROM")) {
        (Some(url), Some(from)) => Mailer::smtp(&url, &from)?,
        _ => {
            tracing::warn!(
                "MAMI_SMTP_URL / MAMI_MAIL_FROM not set: sign-in codes will only be printed in this log"
            );
            Mailer::Log
        }
    };
    let pusher = match env("MAMI_FCM_SERVICE_ACCOUNT") {
        Some(path) => Pusher::Fcm(std::sync::Arc::new(Fcm::from_service_account_file(&path)?)),
        None => {
            tracing::warn!(
                "MAMI_FCM_SERVICE_ACCOUNT not set: phones will not be woken by push notifications"
            );
            Pusher::Disabled
        }
    };

    let mut config = Config::default();
    if let Some(mb) = env("MAMI_MAX_UPLOAD_MB") {
        config.max_blob_bytes = mb.parse::<u64>()? * 1024 * 1024;
    }
    if let Some(mb) = env("MAMI_UPLOAD_QUOTA_MB") {
        config.blob_quota_bytes = mb.parse::<u64>()? * 1024 * 1024;
    }
    if let Some(urls) = env("MAMI_STUN_URLS") {
        config.stun_urls = list(&urls);
    }
    match (env("MAMI_TURN_URLS"), env("MAMI_TURN_SECRET")) {
        (Some(urls), Some(secret)) => {
            config.turn = Some(Turn {
                urls: list(&urls),
                secret,
                ttl_s: 24 * 60 * 60,
            });
        }
        _ => tracing::warn!(
            "MAMI_TURN_URLS / MAMI_TURN_SECRET not set: calls only connect when the phones can reach each other directly"
        ),
    }
    let blobs = BlobStore::new(env("MAMI_BLOB_DIR").unwrap_or_else(|| "blobs".into()))?;

    let store = Store::new(store::open(&database_url).await?);
    tokio::spawn(housekeeping(store.clone(), blobs.clone()));
    let app = router(AppState::new(store, mailer, pusher, blobs, config));

    let listener = tokio::net::TcpListener::bind(bind).await?;
    tracing::info!(%bind, "MaMi server listening");
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown())
        .await?;
    Ok(())
}

/// Hourly clean-up:
/// * delivered messages are remembered (without their contents) for a week
///   so a resend is not delivered twice;
/// * attachments nobody downloaded within 30 days are deleted;
/// * call signalling for calls that have long rung out is dropped.
async fn housekeeping(store: Store, blobs: BlobStore) {
    const DAY_MS: i64 = 24 * 60 * 60 * 1000;
    let mut every_hour = tokio::time::interval(std::time::Duration::from_secs(60 * 60));
    loop {
        every_hour.tick().await;
        if let Err(e) = store.purge_delivered(7 * DAY_MS).await {
            tracing::warn!(error = %e, "could not purge delivered messages");
        }
        match store.take_expired_blobs(30 * DAY_MS).await {
            Ok(ids) => blobs.delete_all(&ids).await,
            Err(e) => tracing::warn!(error = %e, "could not purge old attachments"),
        }
        if let Err(e) = store.purge_stale_calls().await {
            tracing::warn!(error = %e, "could not purge old call signalling");
        }
    }
}

fn list(value: &str) -> Vec<String> {
    value
        .split(',')
        .map(str::trim)
        .filter(|v| !v.is_empty())
        .map(str::to_owned)
        .collect()
}

fn env(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|v| !v.trim().is_empty())
}

async fn shutdown() {
    let _ = tokio::signal::ctrl_c().await;
}

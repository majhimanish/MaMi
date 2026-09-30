use std::net::SocketAddr;

use mami_server::mail::Mailer;
use mami_server::push::{Fcm, Pusher};
use mami_server::store::{self, Store};
use mami_server::{AppState, router};
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

    let store = Store::new(store::open(&database_url).await?);
    tokio::spawn(purge_delivered_messages(store.clone()));
    let app = router(AppState::new(store, mailer, pusher));

    let listener = tokio::net::TcpListener::bind(bind).await?;
    tracing::info!(%bind, "MaMi server listening");
    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown())
        .await?;
    Ok(())
}

/// Delivered messages are remembered (without their contents) for a week so a
/// resend is not delivered twice.
async fn purge_delivered_messages(store: Store) {
    let mut every_hour = tokio::time::interval(std::time::Duration::from_secs(60 * 60));
    loop {
        every_hour.tick().await;
        if let Err(e) = store.purge_delivered(7 * 24 * 60 * 60 * 1000).await {
            tracing::warn!(error = %e, "could not purge delivered messages");
        }
    }
}

fn env(name: &str) -> Option<String> {
    std::env::var(name).ok().filter(|v| !v.trim().is_empty())
}

async fn shutdown() {
    let _ = tokio::signal::ctrl_c().await;
}

//! Sends sign-in codes and invites by email.

use std::sync::{Arc, Mutex};

use lettre::message::Mailbox;
use lettre::transport::smtp::AsyncSmtpTransport;
use lettre::{AsyncTransport, Message, Tokio1Executor};

#[derive(Clone)]
pub enum Mailer {
    Smtp {
        transport: AsyncSmtpTransport<Tokio1Executor>,
        from: Mailbox,
    },
    /// Development only: prints emails to the server log.
    Log,
    /// Tests: keeps emails in memory.
    Memory(Arc<Mutex<Vec<SentMail>>>),
}

#[derive(Debug, Clone)]
pub struct SentMail {
    pub to: String,
    pub subject: String,
    pub body: String,
}

impl Mailer {
    /// `smtp_url` looks like `smtps://user:password@smtp.example.com:465`.
    pub fn smtp(smtp_url: &str, from: &str) -> Result<Self, String> {
        let transport = AsyncSmtpTransport::<Tokio1Executor>::from_url(smtp_url)
            .map_err(|e| format!("invalid MAMI_SMTP_URL: {e}"))?
            .build();
        let from = from
            .parse()
            .map_err(|e| format!("invalid MAMI_MAIL_FROM: {e}"))?;
        Ok(Self::Smtp { transport, from })
    }

    pub async fn send(&self, to: &str, subject: &str, body: String) -> Result<(), String> {
        match self {
            Self::Smtp { transport, from } => {
                let message = Message::builder()
                    .from(from.clone())
                    .to(to.parse().map_err(|e| format!("bad address: {e}"))?)
                    .subject(subject)
                    .body(body)
                    .map_err(|e| e.to_string())?;
                transport
                    .send(message)
                    .await
                    .map(|_| ())
                    .map_err(|e| e.to_string())
            }
            Self::Log => {
                tracing::warn!(%to, %subject, %body, "email not sent: SMTP is not configured");
                Ok(())
            }
            Self::Memory(sent) => {
                sent.lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .push(SentMail {
                        to: to.to_owned(),
                        subject: subject.to_owned(),
                        body,
                    });
                Ok(())
            }
        }
    }
}

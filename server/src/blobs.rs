//! Encrypted attachments waiting to be downloaded.
//!
//! Phones encrypt photos, videos, voice notes and files before uploading
//! them; the key only ever travels inside an end-to-end encrypted message.
//! The server just keeps opaque bytes on disk until the partner has fetched
//! them (or they expire).

use std::path::{Path, PathBuf};

use axum::body::Body;
use futures_util::StreamExt;
use tokio::fs::File;
use tokio::io::AsyncWriteExt;

use crate::error::{ApiError, ApiResult};

#[derive(Clone)]
pub struct BlobStore {
    dir: PathBuf,
}

impl BlobStore {
    pub fn new(dir: impl Into<PathBuf>) -> std::io::Result<Self> {
        let dir = dir.into();
        std::fs::create_dir_all(&dir)?;
        Ok(Self { dir })
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    /// Ids are 32 lowercase hex characters made by the server, so they are
    /// always safe as file names.
    fn path(&self, id: &str) -> Option<PathBuf> {
        let valid = id.len() == 32
            && id
                .bytes()
                .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b));
        valid.then(|| self.dir.join(&id[..2]).join(id))
    }

    /// Streams an upload to disk. Fails with 413, keeping nothing, once more
    /// than `limit` bytes arrive.
    pub async fn save(&self, id: &str, body: Body, limit: u64) -> ApiResult<u64> {
        let path = self.path(id).ok_or(ApiError::BadRequest("bad_id"))?;
        let parent = path.parent().expect("blob paths have a parent");
        tokio::fs::create_dir_all(parent).await?;
        let partial = path.with_extension("part");
        let result = async {
            let mut file = File::create(&partial).await?;
            let mut stream = body.into_data_stream();
            let mut size = 0u64;
            while let Some(chunk) = stream.next().await {
                let chunk = chunk.map_err(|_| ApiError::BadRequest("upload_interrupted"))?;
                size += chunk.len() as u64;
                if size > limit {
                    return Err(ApiError::TooLarge("too_large"));
                }
                file.write_all(&chunk).await?;
            }
            file.flush().await?;
            file.sync_all().await?;
            Ok(size)
        }
        .await;
        match result {
            Ok(size) => {
                tokio::fs::rename(&partial, &path).await?;
                Ok(size)
            }
            Err(e) => {
                let _ = tokio::fs::remove_file(&partial).await;
                Err(e)
            }
        }
    }

    pub async fn open(&self, id: &str) -> ApiResult<(File, u64)> {
        let path = self.path(id).ok_or(ApiError::NotFound("blob_not_found"))?;
        let file = match File::open(&path).await {
            Ok(file) => file,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                return Err(ApiError::NotFound("blob_not_found"));
            }
            Err(e) => return Err(e.into()),
        };
        let size = file.metadata().await?.len();
        Ok((file, size))
    }

    pub async fn delete(&self, id: &str) {
        if let Some(path) = self.path(id)
            && let Err(e) = tokio::fs::remove_file(&path).await
            && e.kind() != std::io::ErrorKind::NotFound
        {
            tracing::warn!(error = %e, "could not delete blob file");
        }
    }

    pub async fn delete_all(&self, ids: &[String]) {
        for id in ids {
            self.delete(id).await;
        }
    }
}

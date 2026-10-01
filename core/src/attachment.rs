//! Photos, videos, voice notes and files.
//!
//! An attachment is encrypted on the phone with a fresh random key, uploaded
//! to the server as an opaque blob, and the key travels to the partner inside
//! an end-to-end encrypted [`crate::Payload::Media`] message. The server never
//! sees the key, so it can't open the file.
//!
//! Files are encrypted in 64 KiB chunks so even a long video never has to fit
//! in memory. Each chunk is sealed with ChaCha20-Poly1305 using the STREAM
//! construction (Hoang, Reyhanitabar, Rogaway, Vizár, 2015): the nonce holds a
//! random prefix, the chunk number and a "last chunk" flag, so chunks can't be
//! reordered, dropped or cut off without the decryption failing.
//!
//! Layout: `"MAMI"`, version byte `1`, 7-byte nonce prefix, then the chunks,
//! each `min(64 KiB, rest)` bytes of ciphertext plus a 16-byte tag.

use std::fs::File;
use std::io::{BufReader, BufWriter, Read, Write};
use std::path::Path;

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use chacha20poly1305::aead::Aead;
use chacha20poly1305::{ChaCha20Poly1305, KeyInit, Nonce};
use zeroize::Zeroizing;

use crate::CoreError;

const MAGIC: &[u8; 4] = b"MAMI";
const VERSION: u8 = 1;
const PREFIX_LEN: usize = 7;
const HEADER_LEN: usize = MAGIC.len() + 1 + PREFIX_LEN;
const CHUNK: usize = 64 * 1024;
const TAG_LEN: usize = 16;

/// What the partner needs to open an uploaded file.
#[derive(uniffi::Record, Clone, Debug, PartialEq, Eq)]
pub struct FileKey {
    /// 32 random bytes, base64. Sent only inside an encrypted message.
    pub key: String,
    /// Size of the original file.
    pub plain_size: u64,
    /// Size of the encrypted file that gets uploaded.
    pub encrypted_size: u64,
}

/// Encrypts `input_path` into `output_path` with a new random key.
#[uniffi::export]
pub fn encrypt_file(input_path: String, output_path: String) -> Result<FileKey, CoreError> {
    let mut key = Zeroizing::new([0u8; 32]);
    let mut prefix = [0u8; PREFIX_LEN];
    getrandom::fill(key.as_mut()).map_err(CoreError::encryption)?;
    getrandom::fill(&mut prefix).map_err(CoreError::encryption)?;

    let result = encrypt_with(
        key.as_ref(),
        prefix,
        Path::new(&input_path),
        Path::new(&output_path),
    );
    if result.is_err() {
        let _ = std::fs::remove_file(&output_path);
    }
    let (plain_size, encrypted_size) = result?;
    Ok(FileKey {
        key: STANDARD.encode(key.as_ref()),
        plain_size,
        encrypted_size,
    })
}

/// Decrypts a downloaded file. Fails, and leaves no output behind, if any
/// byte was changed, removed or added.
#[uniffi::export]
pub fn decrypt_file(
    input_path: String,
    output_path: String,
    key: String,
) -> Result<u64, CoreError> {
    let key = Zeroizing::new(
        STANDARD
            .decode(key.trim())
            .map_err(CoreError::invalid_key)?,
    );
    if key.len() != 32 {
        return Err(CoreError::invalid_key("file keys are 32 bytes"));
    }
    let result = decrypt_with(&key, Path::new(&input_path), Path::new(&output_path));
    if result.is_err() {
        let _ = std::fs::remove_file(&output_path);
    }
    result
}

fn encrypt_with(
    key: &[u8],
    prefix: [u8; PREFIX_LEN],
    input: &Path,
    output: &Path,
) -> Result<(u64, u64), CoreError> {
    let cipher = ChaCha20Poly1305::new_from_slice(key).map_err(CoreError::invalid_key)?;
    let mut reader = BufReader::new(File::open(input).map_err(CoreError::io)?);
    let mut writer = BufWriter::new(File::create(output).map_err(CoreError::io)?);

    writer.write_all(MAGIC).map_err(CoreError::io)?;
    writer.write_all(&[VERSION]).map_err(CoreError::io)?;
    writer.write_all(&prefix).map_err(CoreError::io)?;
    let mut written = HEADER_LEN as u64;
    let mut plain_size = 0u64;

    // One chunk of look-ahead tells us which chunk is the last one.
    let mut current = vec![0u8; CHUNK];
    let mut next = vec![0u8; CHUNK];
    let mut current_len = read_full(&mut reader, &mut current)?;
    let mut counter = 0u32;
    loop {
        let next_len = if current_len == CHUNK {
            read_full(&mut reader, &mut next)?
        } else {
            0
        };
        let last = next_len == 0;
        let sealed = cipher
            .encrypt(&nonce(&prefix, counter, last), &current[..current_len])
            .map_err(|_| CoreError::encryption("chunk"))?;
        writer.write_all(&sealed).map_err(CoreError::io)?;
        written += sealed.len() as u64;
        plain_size += current_len as u64;
        if last {
            break;
        }
        counter = counter
            .checked_add(1)
            .ok_or_else(|| CoreError::encryption("file too large"))?;
        std::mem::swap(&mut current, &mut next);
        current_len = next_len;
    }
    writer.flush().map_err(CoreError::io)?;
    Ok((plain_size, written))
}

fn decrypt_with(key: &[u8], input: &Path, output: &Path) -> Result<u64, CoreError> {
    let cipher = ChaCha20Poly1305::new_from_slice(key).map_err(CoreError::invalid_key)?;
    let mut reader = BufReader::new(File::open(input).map_err(CoreError::io)?);

    let mut header = [0u8; HEADER_LEN];
    if read_full(&mut reader, &mut header)? != HEADER_LEN
        || &header[..MAGIC.len()] != MAGIC
        || header[MAGIC.len()] != VERSION
    {
        return Err(CoreError::decryption("not a MaMi file"));
    }
    let mut prefix = [0u8; PREFIX_LEN];
    prefix.copy_from_slice(&header[MAGIC.len() + 1..]);

    let mut writer = BufWriter::new(File::create(output).map_err(CoreError::io)?);
    let mut current = vec![0u8; CHUNK + TAG_LEN];
    let mut next = vec![0u8; CHUNK + TAG_LEN];
    let mut current_len = read_full(&mut reader, &mut current)?;
    let mut counter = 0u32;
    let mut plain_size = 0u64;
    loop {
        let next_len = if current_len == CHUNK + TAG_LEN {
            read_full(&mut reader, &mut next)?
        } else {
            0
        };
        let last = next_len == 0;
        let opened = cipher
            .decrypt(&nonce(&prefix, counter, last), &current[..current_len])
            .map_err(|_| CoreError::decryption("the file was changed or cut short"))?;
        writer.write_all(&opened).map_err(CoreError::io)?;
        plain_size += opened.len() as u64;
        if last {
            break;
        }
        counter = counter
            .checked_add(1)
            .ok_or_else(|| CoreError::decryption("file too large"))?;
        std::mem::swap(&mut current, &mut next);
        current_len = next_len;
    }
    writer.flush().map_err(CoreError::io)?;
    Ok(plain_size)
}

fn nonce(prefix: &[u8; PREFIX_LEN], counter: u32, last: bool) -> Nonce {
    let mut nonce = Nonce::default();
    nonce[..PREFIX_LEN].copy_from_slice(prefix);
    nonce[PREFIX_LEN..PREFIX_LEN + 4].copy_from_slice(&counter.to_be_bytes());
    nonce[PREFIX_LEN + 4] = u8::from(last);
    nonce
}

/// Reads until `buf` is full or the input ends.
fn read_full(reader: &mut impl Read, buf: &mut [u8]) -> Result<usize, CoreError> {
    let mut filled = 0;
    while filled < buf.len() {
        match reader.read(&mut buf[filled..]) {
            Ok(0) => break,
            Ok(n) => filled += n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => {}
            Err(e) => return Err(CoreError::io(e)),
        }
    }
    Ok(filled)
}

#[cfg(test)]
mod tests {
    use super::*;

    struct Scratch(std::path::PathBuf);

    impl Scratch {
        fn new(name: &str) -> Self {
            let mut random = [0u8; 8];
            getrandom::fill(&mut random).unwrap();
            let dir = std::env::temp_dir().join(format!(
                "mami-{name}-{}",
                random
                    .iter()
                    .map(|b| format!("{b:02x}"))
                    .collect::<String>()
            ));
            std::fs::create_dir_all(&dir).unwrap();
            Self(dir)
        }
        fn path(&self, name: &str) -> String {
            self.0.join(name).to_string_lossy().into_owned()
        }
    }

    impl Drop for Scratch {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn round_trip(len: usize) {
        let dir = Scratch::new("roundtrip");
        let plain: Vec<u8> = (0..len).map(|i| (i * 31 % 251) as u8).collect();
        std::fs::write(dir.path("in"), &plain).unwrap();
        let key = encrypt_file(dir.path("in"), dir.path("enc")).unwrap();
        let encrypted = std::fs::read(dir.path("enc")).unwrap();
        assert_eq!(key.plain_size, len as u64);
        assert_eq!(key.encrypted_size, encrypted.len() as u64);
        let chunks = len.div_ceil(CHUNK).max(1);
        assert_eq!(encrypted.len(), HEADER_LEN + len + chunks * TAG_LEN);
        if len >= 16 {
            assert_ne!(&encrypted[HEADER_LEN..HEADER_LEN + 16], &plain[..16]);
        }

        let size = decrypt_file(dir.path("enc"), dir.path("out"), key.key).unwrap();
        assert_eq!(size, len as u64);
        assert_eq!(std::fs::read(dir.path("out")).unwrap(), plain);
    }

    #[test]
    fn files_of_every_shape_round_trip() {
        for len in [0, 1, CHUNK - 1, CHUNK, CHUNK + 1, 3 * CHUNK, 3 * CHUNK + 17] {
            round_trip(len);
        }
    }

    #[test]
    fn every_file_gets_its_own_key() {
        let dir = Scratch::new("keys");
        std::fs::write(dir.path("in"), b"same photo").unwrap();
        let a = encrypt_file(dir.path("in"), dir.path("a")).unwrap();
        let b = encrypt_file(dir.path("in"), dir.path("b")).unwrap();
        assert_ne!(a.key, b.key);
        assert_ne!(
            std::fs::read(dir.path("a")).unwrap(),
            std::fs::read(dir.path("b")).unwrap()
        );
    }

    #[test]
    fn tampering_is_detected_and_leaves_nothing_behind() {
        let dir = Scratch::new("tamper");
        let plain = vec![7u8; 2 * CHUNK + 100];
        std::fs::write(dir.path("in"), &plain).unwrap();
        let key = encrypt_file(dir.path("in"), dir.path("enc")).unwrap();
        let good = std::fs::read(dir.path("enc")).unwrap();

        let chunk = CHUNK + TAG_LEN;
        let mut flipped = good.clone();
        flipped[HEADER_LEN + 5] ^= 1;
        let truncated = good[..HEADER_LEN + 2 * chunk].to_vec();
        let mut swapped = good.clone();
        swapped[HEADER_LEN..HEADER_LEN + 2 * chunk].rotate_left(chunk);
        let mut extended = good.clone();
        extended.extend_from_slice(&[0u8; 32]);

        for (name, bad) in [
            ("flipped", flipped),
            ("truncated", truncated),
            ("swapped", swapped),
            ("extended", extended),
        ] {
            std::fs::write(dir.path(name), bad).unwrap();
            let out = dir.path(&format!("{name}.out"));
            assert!(
                decrypt_file(dir.path(name), out.clone(), key.key.clone()).is_err(),
                "{name}"
            );
            assert!(!Path::new(&out).exists(), "{name} left output behind");
        }

        let other = encrypt_file(dir.path("in"), dir.path("other")).unwrap();
        assert!(decrypt_file(dir.path("enc"), dir.path("wrong"), other.key).is_err());
        assert!(decrypt_file(dir.path("enc"), dir.path("bad"), "short".into()).is_err());
    }
}

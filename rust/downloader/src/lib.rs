//! Optional native downloader for callers that need a private destination.
//!
//! The Android app uses `DownloadManager` for durable public Downloads, while this crate
//! provides a small, well-behaved JNI primitive for background/private files: it validates
//! HTTP status codes, creates parent directories, uses byte ranges only when the server
//! advertises them, writes chunks at fixed offsets, and never lets a Rust panic cross JNI.

use futures::future::join_all;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint};
use jni::JNIEnv;
use reqwest::Client;
use std::fs::{create_dir_all, File, OpenOptions};
use std::io::{Seek, SeekFrom, Write};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use tokio::runtime::Builder;

const DEFAULT_CHUNK_SIZE: u64 = 1024 * 1024;
const DEFAULT_THREADS: usize = 4;

pub struct DownloadTask {
    url: String,
    output_path: String,
    total_size: AtomicU64,
    downloaded: Arc<AtomicU64>,
    thread_count: usize,
}

impl DownloadTask {
    pub fn new(url: String, output_path: String, thread_count: usize) -> Self {
        Self {
            url,
            output_path,
            total_size: AtomicU64::new(0),
            downloaded: Arc::new(AtomicU64::new(0)),
            thread_count: thread_count.clamp(1, 16),
        }
    }

    pub async fn download(&self) -> Result<(), String> {
        let client = Client::builder()
            .user_agent("MyBrowser/1.0")
            .build()
            .map_err(|e| format!("client creation failed: {e}"))?;
        let head = client.head(&self.url).send().await;
        let (total_size, supports_range) = match head {
            Ok(response) if response.status().is_success() => (
                response.content_length().unwrap_or(0),
                response
                    .headers()
                    .get("accept-ranges")
                    .and_then(|v| v.to_str().ok())
                    .map(|v| v.eq_ignore_ascii_case("bytes"))
                    .unwrap_or(false),
            ),
            _ => (0, false),
        };
        self.total_size.store(total_size, Ordering::Relaxed);

        if supports_range && total_size >= DEFAULT_CHUNK_SIZE && self.thread_count > 1 {
            self.download_multi_threaded(total_size).await
        } else {
            self.download_single_threaded().await
        }
    }

    async fn download_single_threaded(&self) -> Result<(), String> {
        ensure_parent(&self.output_path)?;
        let client = Client::builder()
            .user_agent("MyBrowser/1.0")
            .build()
            .map_err(|e| format!("client creation failed: {e}"))?;
        let mut response = client
            .get(&self.url)
            .send()
            .await
            .map_err(|e| format!("GET request failed: {e}"))?;
        if !response.status().is_success() {
            return Err(format!("GET returned HTTP {}", response.status()));
        }

        if self.total_size.load(Ordering::Relaxed) == 0 {
            if let Some(length) = response.content_length() {
                self.total_size.store(length, Ordering::Relaxed);
            }
        }

        let mut file = File::create(&self.output_path)
            .map_err(|e| format!("failed to create output file: {e}"))?;
        while let Some(chunk) = response
            .chunk()
            .await
            .map_err(|e| format!("read failed: {e}"))?
        {
            file.write_all(&chunk)
                .map_err(|e| format!("write failed: {e}"))?;
            self.downloaded
                .fetch_add(chunk.len() as u64, Ordering::Relaxed);
        }
        file.flush().map_err(|e| format!("flush failed: {e}"))?;
        Ok(())
    }

    async fn download_multi_threaded(&self, total_size: u64) -> Result<(), String> {
        ensure_parent(&self.output_path)?;
        let chunk_count = self
            .thread_count
            .min(total_size.div_ceil(DEFAULT_CHUNK_SIZE) as usize)
            .max(1);
        let ranges = split_ranges(total_size, chunk_count);

        let file = File::create(&self.output_path)
            .map_err(|e| format!("failed to create output file: {e}"))?;
        file.set_len(total_size)
            .map_err(|e| format!("failed to preallocate output file: {e}"))?;
        drop(file);

        let jobs = ranges.into_iter().map(|(start, end)| {
            download_chunk(
                self.url.clone(),
                self.output_path.clone(),
                start,
                end,
                self.downloaded.clone(),
            )
        });
        let results = join_all(jobs).await;
        if let Some(error) = results.into_iter().find_map(Result::err) {
            let _ = std::fs::remove_file(&self.output_path);
            return Err(error);
        }
        Ok(())
    }

    pub fn progress(&self) -> (u64, u64) {
        (
            self.downloaded.load(Ordering::Relaxed),
            self.total_size.load(Ordering::Relaxed),
        )
    }
}

fn split_ranges(total_size: u64, count: usize) -> Vec<(u64, u64)> {
    if total_size == 0 || count == 0 {
        return Vec::new();
    }
    let count = count.min(total_size as usize).max(1);
    let base = total_size / count as u64;
    (0..count)
        .map(|index| {
            let start = index as u64 * base;
            let end = if index + 1 == count {
                total_size - 1
            } else {
                (index as u64 + 1) * base - 1
            };
            (start, end)
        })
        .collect()
}

async fn download_chunk(
    url: String,
    output_path: String,
    start: u64,
    end: u64,
    downloaded: Arc<AtomicU64>,
) -> Result<(), String> {
    let client = Client::builder()
        .user_agent("MyBrowser/1.0")
        .build()
        .map_err(|e| format!("client creation failed: {e}"))?;
    let mut response = client
        .get(url)
        .header("Range", format!("bytes={start}-{end}"))
        .send()
        .await
        .map_err(|e| format!("range request failed: {e}"))?;
    if response.status() != reqwest::StatusCode::PARTIAL_CONTENT {
        return Err(format!("range request returned HTTP {}", response.status()));
    }

    let mut file = OpenOptions::new()
        .write(true)
        .open(&output_path)
        .map_err(|e| format!("failed to open output file: {e}"))?;
    file.seek(SeekFrom::Start(start))
        .map_err(|e| format!("failed to seek output file: {e}"))?;

    let expected = end - start + 1;
    let mut written = 0u64;
    while let Some(chunk) = response
        .chunk()
        .await
        .map_err(|e| format!("range read failed: {e}"))?
    {
        file.write_all(&chunk)
            .map_err(|e| format!("range write failed: {e}"))?;
        written += chunk.len() as u64;
        downloaded.fetch_add(chunk.len() as u64, Ordering::Relaxed);
    }
    if written != expected {
        return Err(format!(
            "range length mismatch: expected {expected}, got {written}"
        ));
    }
    Ok(())
}

fn ensure_parent(path: &str) -> Result<(), String> {
    if let Some(parent) = Path::new(path)
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
    {
        create_dir_all(parent).map_err(|e| format!("failed to create parent directory: {e}"))?;
    }
    Ok(())
}

#[no_mangle]
pub extern "system" fn Java_com_mybrowser_download_NativeDownloader_nativeDownload(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    output_path: JString,
    thread_count: jint,
) -> jboolean {
    let result = catch_unwind(AssertUnwindSafe(|| {
        let url: String = env.get_string(&url).ok()?.into();
        let output_path: String = env.get_string(&output_path).ok()?.into();
        let threads = if thread_count > 0 {
            thread_count as usize
        } else {
            DEFAULT_THREADS
        };
        let runtime = Builder::new_current_thread().enable_all().build().ok()?;
        let task = DownloadTask::new(url, output_path, threads);
        runtime.block_on(task.download()).ok()
    }))
    .ok()
    .flatten()
    .is_some();
    if result {
        1
    } else {
        0
    }
}

#[cfg(test)]
mod tests {
    use super::split_ranges;

    #[test]
    fn ranges_cover_file_without_gaps() {
        let ranges = split_ranges(10, 3);
        assert_eq!(ranges, vec![(0, 2), (3, 5), (6, 9)]);
    }

    #[test]
    fn range_count_is_bounded_by_file_size() {
        assert_eq!(split_ranges(2, 4), vec![(0, 0), (1, 1)]);
    }
}

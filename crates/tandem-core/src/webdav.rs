//! The files of another device as a WebDAV share on this machine.
//!
//! Finder and Explorer both know how to open a WebDAV address as a drive, so this is what makes a phone show up
//! between the other drives: a small web server on the loopback address that turns every request into a request for
//! the files of the other device (see `files`). It listens on this machine only, and every request has to carry a
//! password that is made for the occasion and given to the one who mounts.

use std::convert::Infallible;
use std::io::SeekFrom;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use dav_server::DavHandler;
use dav_server::body::Body;
use dav_server::davpath::DavPath;
use dav_server::fakels::FakeLs;
use dav_server::fs::{
    DavDirEntry, DavFile, DavFileSystem, DavMetaData, FsError, FsFuture, FsResult, FsStream, OpenOptions, ReadDirMeta,
};
use futures_util::stream;
use hyper::body::Incoming;
use hyper::{Request, Response, StatusCode};
use hyper_util::rt::TokioIo;
use tokio::io::AsyncWriteExt;
use tokio::net::TcpListener;
use tokio_util::sync::CancellationToken;

use crate::engine::rand_u64;
use crate::error::{Error, Result};
use crate::files::{FsCode, FsEntry, FsClient};

/// A read asks the other device for at least this much, so a program that reads in small pieces does not cost a
/// round trip for each of them.
const READ_AHEAD: usize = 1024 * 1024;

/// A share that is being served. Stop it when the drive is no longer wanted.
pub struct WebDavShare {
    /// Where to mount it: `http://127.0.0.1:<port>/<name>`. The last part is what the drive will be called.
    pub url: String,
    pub user: String,
    pub password: String,
    stop: CancellationToken,
}

impl WebDavShare {
    pub fn stop(&self) {
        self.stop.cancel();
    }
}

impl Drop for WebDavShare {
    fn drop(&mut self) {
        self.stop.cancel();
    }
}

/// Starts serving the files of `client`'s device. `name` is what the drive will be called.
pub async fn serve(client: FsClient, name: &str) -> Result<WebDavShare> {
    let listener = TcpListener::bind(("127.0.0.1", 0)).await?;
    let port = listener.local_addr()?.port();
    let user = "tandem".to_string();
    let password = format!("{:016x}{:016x}", rand_u64(), rand_u64());
    // The name is a path part of the address, so it cannot hold a slash. The handler wants the prefix as it reads when
    // decoded, and the address carries it encoded.
    let name = name.replace(['/', '\\'], "_");
    let drive = percent_encode(&name);
    let handler = DavHandler::builder()
        .filesystem(Box::new(DavFs { client }))
        .locksystem(FakeLs::new())
        .strip_prefix(format!("/{name}"))
        .build_handler();
    let expected = format!("Basic {}", data_encoding::BASE64.encode(format!("{user}:{password}").as_bytes()));
    let hosts = [format!("127.0.0.1:{port}"), format!("localhost:{port}")];
    let stop = CancellationToken::new();
    let cancelled = stop.clone();
    tokio::spawn(async move {
        loop {
            let stream = tokio::select! {
                _ = cancelled.cancelled() => break,
                accepted = listener.accept() => match accepted {
                    Ok((stream, _)) => stream,
                    Err(_) => continue,
                },
            };
            let (handler, expected, hosts, cancelled) = (handler.clone(), expected.clone(), hosts.clone(), cancelled.clone());
            tokio::spawn(async move {
                let service = hyper::service::service_fn(move |request: Request<Incoming>| {
                    let (handler, expected, hosts) = (handler.clone(), expected.clone(), hosts.clone());
                    async move { Ok::<_, Infallible>(answer(&handler, &expected, &hosts, request).await) }
                });
                let connection = hyper::server::conn::http1::Builder::new().serve_connection(TokioIo::new(stream), service);
                tokio::select! {
                    _ = cancelled.cancelled() => {}
                    _ = connection => {}
                }
            });
        }
    });
    Ok(WebDavShare { url: format!("http://127.0.0.1:{port}/{drive}"), user, password, stop })
}

async fn answer(handler: &DavHandler, expected: &str, hosts: &[String], request: Request<Incoming>) -> Response<Body> {
    // A web page cannot be made to talk to this by naming it differently: the name it is asked under has to be ours.
    let host_ok = request
        .headers()
        .get(hyper::header::HOST)
        .and_then(|h| h.to_str().ok())
        .is_some_and(|h| hosts.iter().any(|ok| ok.eq_ignore_ascii_case(h)));
    let authorized = request.headers().get(hyper::header::AUTHORIZATION).and_then(|h| h.to_str().ok()) == Some(expected);
    if !host_ok {
        return refuse(StatusCode::FORBIDDEN, "not here");
    }
    if !authorized {
        let mut response = refuse(StatusCode::UNAUTHORIZED, "a password is needed");
        response.headers_mut().insert(hyper::header::WWW_AUTHENTICATE, hyper::header::HeaderValue::from_static("Basic realm=\"Tandem\""));
        return response;
    }
    // For finding out what a program asks of the share: every request is written to the file named by this variable,
    // when it starts and when it is answered. Not for people, who have no use for it.
    let trace = std::env::var_os("TANDEM_WEBDAV_LOG");
    let line = format!("{} {}", request.method(), request.uri());
    if let Some(path) = &trace {
        trace_line(path, &format!("> {line}"));
    }
    let started = std::time::Instant::now();
    let response = handler.handle(request).await;
    if let Some(path) = &trace {
        trace_line(path, &format!("< {} {line} in {} ms", response.status().as_u16(), started.elapsed().as_millis()));
    }
    response
}

fn trace_line(path: &std::ffi::OsStr, line: &str) {
    use std::io::Write;
    if let Ok(mut file) = std::fs::OpenOptions::new().create(true).append(true).open(path) {
        let _ = writeln!(file, "{line}");
    }
}

fn refuse(status: StatusCode, text: &'static str) -> Response<Body> {
    let mut response = Response::new(Body::from(text));
    *response.status_mut() = status;
    response
}

fn percent_encode(text: &str) -> String {
    let mut out = String::new();
    for byte in text.bytes() {
        if byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.' | b'~') {
            out.push(byte as char);
        } else {
            out.push_str(&format!("%{byte:02X}"));
        }
    }
    out
}

// ---- The files ------------------------------------------------------------------------------------------------

#[derive(Clone)]
struct DavFs {
    client: FsClient,
}

/// A path as the other device knows it: `/Share/folder/file`, without a slash at the end.
fn remote(path: &DavPath) -> String {
    let text = String::from_utf8_lossy(path.as_bytes()).into_owned();
    let trimmed = text.trim_end_matches('/');
    if trimmed.is_empty() { "/".to_string() } else { trimmed.to_string() }
}

fn fs_error(error: &Error) -> FsError {
    match error {
        Error::Files { code, .. } => match code {
            FsCode::NotFound => FsError::NotFound,
            FsCode::Denied | FsCode::Disabled | FsCode::Outside | FsCode::NotDir | FsCode::IsDir => FsError::Forbidden,
            FsCode::Exists | FsCode::NotEmpty => FsError::Exists,
            FsCode::TooLarge => FsError::TooLarge,
            FsCode::Unsupported => FsError::IsRemote,
            _ => FsError::GeneralFailure,
        },
        _ => FsError::GeneralFailure,
    }
}

#[derive(Debug, Clone)]
struct Meta {
    len: u64,
    modified_ms: u64,
    dir: bool,
}

impl Meta {
    fn of(entry: &FsEntry) -> Meta {
        Meta { len: entry.size, modified_ms: entry.modified_ms, dir: entry.dir }
    }

    const ROOT: Meta = Meta { len: 0, modified_ms: 0, dir: true };
}

impl DavMetaData for Meta {
    fn len(&self) -> u64 {
        self.len
    }

    fn modified(&self) -> FsResult<SystemTime> {
        Ok(UNIX_EPOCH + Duration::from_millis(self.modified_ms))
    }

    fn is_dir(&self) -> bool {
        self.dir
    }
}

struct Entry {
    name: String,
    meta: Meta,
}

impl DavDirEntry for Entry {
    fn name(&self) -> Vec<u8> {
        self.name.clone().into_bytes()
    }

    fn metadata(&'_ self) -> FsFuture<'_, Box<dyn DavMetaData>> {
        let meta = self.meta.clone();
        Box::pin(async move { Ok(Box::new(meta) as Box<dyn DavMetaData>) })
    }
}

impl DavFileSystem for DavFs {
    fn open<'a>(&'a self, path: &'a DavPath, options: OpenOptions) -> FsFuture<'a, Box<dyn DavFile>> {
        Box::pin(async move {
            let path = remote(path);
            if options.append {
                return Err(FsError::NotImplemented);
            }
            if options.write || options.create || options.create_new || options.truncate {
                if options.create_new && self.client.stat(&path).await.is_ok() {
                    return Err(FsError::Exists);
                }
                let temp = std::env::temp_dir().join(format!("tandem-webdav-{:x}", rand_u64()));
                let file = tokio::fs::File::create(&temp).await.map_err(|e| io_error(&e))?;
                return Ok(Box::new(WriteFile { client: self.client.clone(), path, temp, file: Some(file), written: 0, uploaded: false })
                    as Box<dyn DavFile>);
            }
            let entry = self.client.stat(&path).await.map_err(|e| fs_error(&e))?;
            if entry.dir {
                return Err(FsError::Forbidden);
            }
            Ok(Box::new(ReadFile { client: self.client.clone(), path, meta: Meta::of(&entry), position: 0, block: (0, Vec::new()) })
                as Box<dyn DavFile>)
        })
    }

    fn read_dir<'a>(&'a self, path: &'a DavPath, _meta: ReadDirMeta) -> FsFuture<'a, FsStream<Box<dyn DavDirEntry>>> {
        Box::pin(async move {
            let items = self.client.list(&remote(path)).await.map_err(|e| fs_error(&e))?;
            let entries: Vec<FsResult<Box<dyn DavDirEntry>>> = items
                .into_iter()
                .map(|item| Ok(Box::new(Entry { meta: Meta::of(&item), name: item.name }) as Box<dyn DavDirEntry>))
                .collect();
            Ok(Box::pin(stream::iter(entries)) as FsStream<Box<dyn DavDirEntry>>)
        })
    }

    fn metadata<'a>(&'a self, path: &'a DavPath) -> FsFuture<'a, Box<dyn DavMetaData>> {
        Box::pin(async move {
            let path = remote(path);
            if path == "/" {
                return Ok(Box::new(Meta::ROOT) as Box<dyn DavMetaData>);
            }
            let entry = self.client.stat(&path).await.map_err(|e| fs_error(&e))?;
            Ok(Box::new(Meta::of(&entry)) as Box<dyn DavMetaData>)
        })
    }

    fn create_dir<'a>(&'a self, path: &'a DavPath) -> FsFuture<'a, ()> {
        Box::pin(async move { self.client.mkdir(&remote(path)).await.map_err(|e| fs_error(&e)) })
    }

    fn remove_dir<'a>(&'a self, path: &'a DavPath) -> FsFuture<'a, ()> {
        Box::pin(async move { self.client.remove(&remote(path), false).await.map_err(|e| fs_error(&e)) })
    }

    fn remove_file<'a>(&'a self, path: &'a DavPath) -> FsFuture<'a, ()> {
        Box::pin(async move { self.client.remove(&remote(path), false).await.map_err(|e| fs_error(&e)) })
    }

    fn rename<'a>(&'a self, from: &'a DavPath, to: &'a DavPath) -> FsFuture<'a, ()> {
        Box::pin(async move { self.client.rename(&remote(from), &remote(to), true).await.map_err(|e| fs_error(&e)) })
    }
}

/// A file that is read: pieces of it come from the other device, a block at a time.
#[derive(Debug)]
struct ReadFile {
    client: FsClient,
    path: String,
    meta: Meta,
    position: u64,
    /// The block that was fetched last: where it starts and what it holds.
    block: (u64, Vec<u8>),
}

impl DavFile for ReadFile {
    fn metadata(&'_ mut self) -> FsFuture<'_, Box<dyn DavMetaData>> {
        let meta = self.meta.clone();
        Box::pin(async move { Ok(Box::new(meta) as Box<dyn DavMetaData>) })
    }

    fn write_buf(&'_ mut self, _buf: Box<dyn bytes::Buf + Send>) -> FsFuture<'_, ()> {
        Box::pin(async { Err(FsError::Forbidden) })
    }

    fn write_bytes(&'_ mut self, _buf: bytes::Bytes) -> FsFuture<'_, ()> {
        Box::pin(async { Err(FsError::Forbidden) })
    }

    fn read_bytes(&'_ mut self, count: usize) -> FsFuture<'_, bytes::Bytes> {
        Box::pin(async move {
            let (start, length) = (self.block.0, self.block.1.len() as u64);
            if !(self.position >= start && self.position < start + length) {
                let wanted = count.max(READ_AHEAD) as u64;
                let data = self.client.read(&self.path, self.position, wanted).await.map_err(|e| fs_error(&e))?;
                self.block = (self.position, data);
            }
            let (start, held) = (self.block.0, &self.block.1);
            let from = ((self.position - start) as usize).min(held.len());
            let to = (from + count).min(held.len());
            let piece = bytes::Bytes::copy_from_slice(&held[from..to]);
            self.position += piece.len() as u64;
            Ok(piece)
        })
    }

    fn seek(&'_ mut self, pos: SeekFrom) -> FsFuture<'_, u64> {
        Box::pin(async move {
            self.position = match pos {
                SeekFrom::Start(n) => n,
                SeekFrom::Current(n) => self.position.saturating_add_signed(n),
                SeekFrom::End(n) => self.meta.len.saturating_add_signed(n),
            };
            Ok(self.position)
        })
    }

    fn flush(&'_ mut self) -> FsFuture<'_, ()> {
        Box::pin(async { Ok(()) })
    }
}

/// A file that is written: what comes in is kept in a temporary file here, and goes to the other device whole, when
/// the writer is done. The other device only ever sees a file that is complete.
#[derive(Debug)]
struct WriteFile {
    client: FsClient,
    path: String,
    temp: std::path::PathBuf,
    file: Option<tokio::fs::File>,
    written: u64,
    uploaded: bool,
}

impl DavFile for WriteFile {
    fn metadata(&'_ mut self) -> FsFuture<'_, Box<dyn DavMetaData>> {
        let meta = Meta { len: self.written, modified_ms: now_ms(), dir: false };
        Box::pin(async move { Ok(Box::new(meta) as Box<dyn DavMetaData>) })
    }

    fn write_buf(&'_ mut self, mut buf: Box<dyn bytes::Buf + Send>) -> FsFuture<'_, ()> {
        Box::pin(async move {
            let file = self.file.as_mut().ok_or(FsError::GeneralFailure)?;
            while buf.has_remaining() {
                let chunk = buf.chunk();
                file.write_all(chunk).await.map_err(|e| io_error(&e))?;
                let n = chunk.len();
                self.written += n as u64;
                buf.advance(n);
            }
            Ok(())
        })
    }

    fn write_bytes(&'_ mut self, buf: bytes::Bytes) -> FsFuture<'_, ()> {
        Box::pin(async move {
            let file = self.file.as_mut().ok_or(FsError::GeneralFailure)?;
            file.write_all(&buf).await.map_err(|e| io_error(&e))?;
            self.written += buf.len() as u64;
            Ok(())
        })
    }

    fn read_bytes(&'_ mut self, _count: usize) -> FsFuture<'_, bytes::Bytes> {
        Box::pin(async { Err(FsError::Forbidden) })
    }

    fn seek(&'_ mut self, _pos: SeekFrom) -> FsFuture<'_, u64> {
        Box::pin(async { Err(FsError::NotImplemented) })
    }

    fn flush(&'_ mut self) -> FsFuture<'_, ()> {
        Box::pin(async move {
            if self.uploaded {
                return Ok(());
            }
            if let Some(mut file) = self.file.take() {
                file.flush().await.map_err(|e| io_error(&e))?;
            }
            self.client.upload(&self.temp, &self.path, true, |_, _| {}).await.map_err(|e| fs_error(&e))?;
            self.uploaded = true;
            let _ = tokio::fs::remove_file(&self.temp).await;
            Ok(())
        })
    }
}

impl Drop for WriteFile {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.temp);
    }
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// What a failure of this machine's own disk is called to a WebDAV client.
fn io_error(error: &std::io::Error) -> FsError {
    match error.kind() {
        std::io::ErrorKind::PermissionDenied => FsError::Forbidden,
        std::io::ErrorKind::NotFound => FsError::NotFound,
        _ => FsError::GeneralFailure,
    }
}

//! The channel between `tandemd run` and the commands that talk to it: one line in, one reply out.
//!
//! Where there are Unix sockets it is one in the data directory that only the user can use. Windows has none worth
//! using, so there it is a port on the loopback address, and the number of the port and a secret that every line has
//! to start with are written to a file in the data directory, which belongs to the user's profile. Another account
//! on the machine can reach the port but cannot read the secret.

use std::path::Path;

use anyhow::Result;
use tandem_core::Engine;

use crate::answer;

#[cfg(unix)]
mod imp {
    use super::*;
    use anyhow::Context;
    use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
    use tokio::net::{UnixListener, UnixStream};

    fn socket_path(data_dir: &Path) -> std::path::PathBuf {
        data_dir.join("tandemd.sock")
    }

    pub async fn serve(engine: Engine, data_dir: &Path) -> Result<()> {
        let socket = socket_path(data_dir);
        let _ = std::fs::remove_file(&socket);
        let listener = UnixListener::bind(&socket).with_context(|| format!("cannot listen on {}", socket.display()))?;
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&socket, std::fs::Permissions::from_mode(0o600))?;
        }
        loop {
            let (stream, _) = listener.accept().await?;
            let engine = engine.clone();
            tokio::spawn(async move {
                let (read, mut write) = stream.into_split();
                let mut line = String::new();
                if BufReader::new(read).read_line(&mut line).await.is_err() {
                    return;
                }
                let reply = answer(&engine, &line).await;
                let _ = write.write_all(reply.as_bytes()).await;
            });
        }
    }

    pub async fn ask(data_dir: &Path, line: &str) -> Result<Option<String>> {
        let Ok(mut stream) = UnixStream::connect(socket_path(data_dir)).await else { return Ok(None) };
        stream.write_all(format!("{line}\n").as_bytes()).await?;
        stream.shutdown().await?;
        let mut reply = String::new();
        tokio::io::AsyncReadExt::read_to_string(&mut stream, &mut reply).await?;
        Ok(Some(reply))
    }

    pub fn forget(data_dir: &Path) {
        let _ = std::fs::remove_file(socket_path(data_dir));
    }
}

#[cfg(windows)]
mod imp {
    use super::*;
    use anyhow::Context;
    use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
    use tokio::net::{TcpListener, TcpStream};

    fn port_file(data_dir: &Path) -> std::path::PathBuf {
        data_dir.join("tandemd.port")
    }

    fn secret() -> Result<String> {
        let mut bytes = [0u8; 16];
        getrandom::fill(&mut bytes).map_err(|e| anyhow::anyhow!("no randomness for the control secret: {e}"))?;
        Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
    }

    pub async fn serve(engine: Engine, data_dir: &Path) -> Result<()> {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await.context("cannot listen on the loopback address")?;
        let port = listener.local_addr()?.port();
        let secret = secret()?;
        std::fs::write(port_file(data_dir), format!("{port} {secret}")).context("cannot write the control port file")?;
        loop {
            let (stream, _) = listener.accept().await?;
            let engine = engine.clone();
            let secret = secret.clone();
            tokio::spawn(async move {
                let (read, mut write) = stream.into_split();
                let mut line = String::new();
                if BufReader::new(read).read_line(&mut line).await.is_err() {
                    return;
                }
                // A line that does not start with the secret came from a program that could not read the file.
                let reply = match line.trim_end().strip_prefix(secret.as_str()).map(str::trim_start) {
                    Some(command) => answer(&engine, command).await,
                    None => "error\nnot allowed\n".to_string(),
                };
                let _ = write.write_all(reply.as_bytes()).await;
            });
        }
    }

    pub async fn ask(data_dir: &Path, line: &str) -> Result<Option<String>> {
        // No file, or one left by a daemon that is gone, means no daemon.
        let Ok(text) = std::fs::read_to_string(port_file(data_dir)) else { return Ok(None) };
        let Some((port, secret)) = text.trim().split_once(' ') else { return Ok(None) };
        let Ok(port) = port.parse::<u16>() else { return Ok(None) };
        let Ok(mut stream) = TcpStream::connect(("127.0.0.1", port)).await else { return Ok(None) };
        stream.write_all(format!("{secret} {line}\n").as_bytes()).await?;
        stream.shutdown().await?;
        let mut reply = String::new();
        stream.read_to_string(&mut reply).await?;
        Ok(Some(reply))
    }

    pub fn forget(data_dir: &Path) {
        let _ = std::fs::remove_file(port_file(data_dir));
    }
}

pub use imp::{ask as ask_daemon, forget, serve as serve_control};

//! The channel between a running Tandem and a command on the same machine: one line in, one reply out.
//!
//! Where there are Unix sockets it is one in the data directory that only the user can use. Windows has none worth
//! using, so there it is a port on the loopback address, and the number of the port and a secret that every line has
//! to start with are written to a file in the data directory, which belongs to the user's profile. Another account
//! on the machine can reach the port but cannot read the secret.

use std::future::Future;
use std::io::Result;
use std::path::{Path, PathBuf};
use std::sync::Arc;

use tokio::io::{AsyncBufReadExt, AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, BufReader};

/// Nothing a command says comes near this; it keeps a program that never ends its line from filling the memory.
const MAX_LINE: u64 = 1024 * 1024;

async fn read_line(read: impl AsyncRead + Unpin) -> Option<String> {
    let mut line = String::new();
    BufReader::new(read.take(MAX_LINE)).read_line(&mut line).await.ok()?;
    Some(line)
}

async fn answer_on<F, Fut>(read: impl AsyncRead + Unpin, mut write: impl AsyncWrite + Unpin, secret: Option<&str>, handler: &F)
where
    F: Fn(String) -> Fut,
    Fut: Future<Output = String>,
{
    let Some(line) = read_line(read).await else { return };
    let reply = match secret {
        None => handler(line).await,
        // A line that does not start with the secret came from a program that could not read the file.
        // Only the end of the line goes: a tab at the end belongs to the command, as an empty last argument.
        Some(secret) => match line.trim_end_matches(['\r', '\n']).strip_prefix(secret) {
            Some(command) => handler(command.trim_start().to_string()).await,
            None => "error\nnot allowed\n".to_string(),
        },
    };
    let _ = write.write_all(reply.as_bytes()).await;
}

#[cfg(unix)]
mod imp {
    use super::*;
    use tokio::net::{UnixListener, UnixStream};

    fn socket_path(dir: &Path, name: &str) -> PathBuf {
        dir.join(format!("{name}.sock"))
    }

    pub async fn serve<F, Fut>(dir: &Path, name: &str, handler: F) -> Result<()>
    where
        F: Fn(String) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = String> + Send + 'static,
    {
        use std::os::unix::fs::PermissionsExt;
        let socket = socket_path(dir, name);
        let _ = std::fs::remove_file(&socket);
        let listener = UnixListener::bind(&socket)?;
        std::fs::set_permissions(&socket, std::fs::Permissions::from_mode(0o600))?;
        let handler = Arc::new(handler);
        loop {
            let (stream, _) = listener.accept().await?;
            let handler = handler.clone();
            tokio::spawn(async move {
                let (read, write) = stream.into_split();
                answer_on(read, write, None, handler.as_ref()).await;
            });
        }
    }

    pub async fn ask(dir: &Path, name: &str, line: &str) -> Result<Option<String>> {
        let Ok(mut stream) = UnixStream::connect(socket_path(dir, name)).await else { return Ok(None) };
        stream.write_all(format!("{line}\n").as_bytes()).await?;
        stream.shutdown().await?;
        let mut reply = String::new();
        stream.read_to_string(&mut reply).await?;
        Ok(Some(reply))
    }

    pub fn forget(dir: &Path, name: &str) {
        let _ = std::fs::remove_file(socket_path(dir, name));
    }
}

#[cfg(windows)]
mod imp {
    use super::*;
    use tokio::net::{TcpListener, TcpStream};

    fn port_file(dir: &Path, name: &str) -> PathBuf {
        dir.join(format!("{name}.port"))
    }

    fn secret() -> String {
        format!("{:016x}{:016x}", crate::engine::rand_u64(), crate::engine::rand_u64())
    }

    pub async fn serve<F, Fut>(dir: &Path, name: &str, handler: F) -> Result<()>
    where
        F: Fn(String) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = String> + Send + 'static,
    {
        let listener = TcpListener::bind(("127.0.0.1", 0)).await?;
        let port = listener.local_addr()?.port();
        let secret = secret();
        std::fs::write(port_file(dir, name), format!("{port} {secret}"))?;
        let handler = Arc::new(handler);
        loop {
            let (stream, _) = listener.accept().await?;
            let handler = handler.clone();
            let secret = secret.clone();
            tokio::spawn(async move {
                let (read, write) = stream.into_split();
                answer_on(read, write, Some(&secret), handler.as_ref()).await;
            });
        }
    }

    pub async fn ask(dir: &Path, name: &str, line: &str) -> Result<Option<String>> {
        // No file, or one left by a Tandem that is gone, means no Tandem.
        let Ok(text) = std::fs::read_to_string(port_file(dir, name)) else { return Ok(None) };
        let Some((port, secret)) = text.trim().split_once(' ') else { return Ok(None) };
        let Ok(port) = port.parse::<u16>() else { return Ok(None) };
        let Ok(mut stream) = TcpStream::connect(("127.0.0.1", port)).await else { return Ok(None) };
        stream.write_all(format!("{secret} {line}\n").as_bytes()).await?;
        stream.shutdown().await?;
        let mut reply = String::new();
        stream.read_to_string(&mut reply).await?;
        Ok(Some(reply))
    }

    pub fn forget(dir: &Path, name: &str) {
        let _ = std::fs::remove_file(port_file(dir, name));
    }
}

/// Answers the commands of this machine until the program ends. `name` keeps channels in one folder apart.
pub use imp::serve;
/// The reply of the running Tandem, or `None` when none runs here.
pub use imp::ask;
/// Takes away what `serve` left in the folder.
pub use imp::forget;

#[cfg(test)]
mod tests {
    use super::*;

    async fn answer(line: &str, secret: Option<&str>) -> String {
        let mut reply = Vec::new();
        answer_on(line.as_bytes(), &mut reply, secret, &|command: String| async move { format!("[{command}]") }).await;
        String::from_utf8(reply).unwrap()
    }

    #[tokio::test]
    async fn an_empty_last_argument_survives_with_and_without_a_secret() {
        assert_eq!(answer("notify\t\n", None).await, "[notify\t\n]");
        assert_eq!(answer("abc notify\t\n", Some("abc")).await, "[notify\t]");
        assert_eq!(answer("abc notify\t\r\n", Some("abc")).await, "[notify\t]");
    }

    #[tokio::test]
    async fn a_line_without_the_secret_is_refused() {
        assert_eq!(answer("wrong notify\thallo\n", Some("abc")).await, "error\nnot allowed\n");
    }
}

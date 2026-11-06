//! The channel between `tandemd run` and the commands that talk to it (see `tandem_core::local`).

use std::path::Path;

use anyhow::{Context, Result};
use tandem_core::{Engine, local};

use crate::answer;

const NAME: &str = "tandemd";

pub async fn serve_control(engine: Engine, data_dir: &Path) -> Result<()> {
    local::serve(data_dir, NAME, move |line| {
        let engine = engine.clone();
        async move { answer(&engine, &line).await }
    })
    .await
    .with_context(|| format!("cannot listen in {}", data_dir.display()))
}

pub async fn ask_daemon(data_dir: &Path, line: &str) -> Result<Option<String>> {
    Ok(local::ask(data_dir, NAME, line).await?)
}

pub fn forget(data_dir: &Path) {
    local::forget(data_dir, NAME);
}

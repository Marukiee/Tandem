//! Starts the Tandem engine and hands it what only the app can give: where the identity is kept, where files go, and
//! somewhere to send the events. The engine is the same one the phone and the Mac use.

use std::path::Path;
use std::sync::Arc;

use tandem_core::ffi::{
    TandemConfig, TandemEngine, TandemError, TandemEvent, TandemEventSink, TandemFiles, TandemPlatform, TandemVault,
    tandem_init_logging,
};
use tandem_core::platform::{sanitize_name, unique_path};
use tandem_core::store::{FileSecretStore, SecretStore, Store};
use tauri::{AppHandle, Emitter, Manager};

use crate::{events, names, settings, state::AppState};

/// The identity key lives in a file in the profile of the person, as it does for the command line daemon.
struct Vault(FileSecretStore);

impl TandemVault for Vault {
    fn load(&self) -> Result<Option<Vec<u8>>, TandemError> {
        self.0.load().map_err(|e| TandemError::Failed { reason: e.to_string() })
    }

    fn save(&self, secret: Vec<u8>) -> Result<(), TandemError> {
        self.0.save(&secret).map_err(|e| TandemError::Failed { reason: e.to_string() })
    }
}

struct Files {
    app: AppHandle,
}

fn io_failure(e: std::io::Error) -> TandemError {
    TandemError::Failed { reason: e.to_string() }
}

impl TandemFiles for Files {
    /// Never called: on Windows the core opens a file by its path itself.
    fn open_read(&self, source: String) -> Result<i32, TandemError> {
        Err(TandemError::Failed { reason: format!("{source} is opened by path on Windows") })
    }

    fn store_download(&self, temp_path: String, name: String, _mime: String) -> Result<String, TandemError> {
        let dir = settings::download_dir(&self.app);
        std::fs::create_dir_all(&dir).map_err(io_failure)?;
        let target = unique_path(&dir, &names::windows_safe(&sanitize_name(&name)));
        let temp = Path::new(&temp_path);
        if std::fs::rename(temp, &target).is_err() {
            // Another drive: copy, then drop the temporary file.
            std::fs::copy(temp, &target).map_err(io_failure)?;
            let _ = std::fs::remove_file(temp);
        }
        Ok(target.to_string_lossy().into_owned())
    }
}

struct Sink {
    app: AppHandle,
}

impl TandemEventSink for Sink {
    fn on_event(&self, event: TandemEvent) {
        events::handle(&self.app, event);
    }
}

pub fn start(app: AppHandle) {
    // The engine opens its socket and announces itself on the network, so it starts off the main thread.
    std::thread::spawn(move || {
        let state = app.state::<AppState>();
        log::info!("the engine is starting");
        let built = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| build(&app)))
            .unwrap_or_else(|panic| Err(format!("the engine panicked while starting: {}", panic_text(&*panic))));
        match built {
            Ok(engine) => {
                engine.set_media_viewer(Arc::new(crate::live::Viewer { app: app.clone() }));
                engine.set_audio_sink(Arc::new(crate::sound::Sink));
                log::info!("the engine runs as {} on port {}", engine.name(), engine.port());
                *state.engine.write().unwrap() = Some(engine);
                events::refresh_devices(&app);
                let _ = app.emit("engine-ready", ());
            }
            Err(reason) => {
                log::error!("the engine did not start: {reason}");
                *state.error.lock().unwrap() = Some(reason.clone());
                let _ = app.emit("engine-error", reason);
            }
        }
    });
}

fn panic_text(panic: &(dyn std::any::Any + Send)) -> String {
    panic
        .downcast_ref::<&str>()
        .map(|text| text.to_string())
        .or_else(|| panic.downcast_ref::<String>().cloned())
        .unwrap_or_else(|| "no message".to_string())
}

fn build(app: &AppHandle) -> Result<Arc<TandemEngine>, String> {
    let data_dir = app.path().app_data_dir().map_err(|e| e.to_string())?;
    std::fs::create_dir_all(&data_dir).map_err(|e| e.to_string())?;
    let store = Store::new(&data_dir).map_err(|e| e.to_string())?;
    let name = hostname::get().ok().and_then(|h| h.into_string().ok()).unwrap_or_else(|| if cfg!(windows) { "Windows PC".to_string() } else { "Linux PC".to_string() });
    tandem_init_logging(false);
    let config = TandemConfig {
        data_dir: data_dir.to_string_lossy().into_owned(),
        device_name: name,
        platform: if cfg!(windows) { TandemPlatform::Windows } else { TandemPlatform::Linux },
        model: None,
        app_version: app.package_info().version.to_string(),
        port: 47820,
        enable_mdns: true,
        caps: vec![
            "clipboard".into(),
            "share".into(),
            "notify".into(),
            "input".into(),
            "battery".into(),
            "media".into(),
            "screen.view".into(),
            "camera.view".into(),
        ],
        low_power: false,
    };
    TandemEngine::start(
        config,
        Arc::new(Vault(FileSecretStore::new(store))),
        Arc::new(Files { app: app.clone() }),
        Arc::new(Sink { app: app.clone() }),
    )
    .map_err(|e| e.to_string())
}

uniffi::setup_scaffolding!();

mod error;
mod server;
mod state;

pub use error::CoreError;

use std::sync::{Arc, Mutex};
use tokio::runtime::Runtime;
use tokio::sync::oneshot;

#[derive(uniffi::Record, Clone, serde::Serialize)]
pub struct Location {
    pub latitude: f64,
    pub longitude: f64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub altitude: Option<f64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub accuracy_m: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub bearing_deg: Option<f32>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub speed_mps: Option<f32>,
    pub timestamp_ms: i64,
}

struct RunningServer {
    runtime: Runtime,
    shutdown: oneshot::Sender<()>,
    port: u16,
}

#[derive(uniffi::Object)]
pub struct Core {
    running: Mutex<Option<RunningServer>>,
    state: Arc<state::SharedState>,
}

#[uniffi::export(with_foreign)]
pub trait LocationProvider: Send + Sync {
    fn current(&self) -> Option<Location>;
}

#[uniffi::export(with_foreign)]
pub trait LocationStreamer: Send + Sync {
    fn start(&self);
    fn stop(&self);
}

#[uniffi::export]
impl Core {
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            running: Mutex::new(None),
            state: Arc::new(state::SharedState::new()),
        })
    }

    pub fn start_server(&self, port: u16) -> Result<(), CoreError> {
        let mut guard = self.running.lock().unwrap();
        if let Some(r) = guard.as_ref() {
            return Err(CoreError::AlreadyRunning(r.port));
        }

        let runtime = Runtime::new().map_err(|e| CoreError::Runtime(e.to_string()))?;

        let bind_ip = if cfg!(debug_assertions) { [0u8, 0, 0, 0] } else { [127, 0, 0, 1] };
        let addr = std::net::SocketAddr::from((bind_ip, port));
        let (tx, rx) = oneshot::channel::<()>();
        let state = self.state.clone();

        runtime.block_on(async {
            let listener = tokio::net::TcpListener::bind(addr).await.map_err(|e| {
                if e.kind() == std::io::ErrorKind::AddrInUse {
                    CoreError::AddressInUse(port)
                } else {
                    CoreError::Bind(e.to_string())
                }
            })?;
            let app = server::router(state);
            tokio::spawn(async move {
                let _ = axum::serve(listener, app)
                    .with_graceful_shutdown(async { let _ = rx.await; })
                    .await;
            });
            Ok::<(), CoreError>(())
        })?;

        *guard = Some(RunningServer { runtime, shutdown: tx, port });
        Ok(())
    }

    pub fn stop_server(&self) -> Result<(), CoreError> {
        let running = self.running.lock().unwrap().take().ok_or(CoreError::NotRunning)?;
        let _ = running.shutdown.send(());
        running.runtime.shutdown_background();
        Ok(())
    }

    pub fn is_running(&self) -> bool {
        self.running.lock().unwrap().is_some()
    }

    pub fn server_port(&self) -> Option<u16> {
        self.running.lock().unwrap().as_ref().map(|r| r.port)
    }

    pub fn set_location_provider(&self, provider: Arc<dyn LocationProvider>) {
        *self.state.location_provider.write() = Some(provider);
    }

    pub fn set_location_streamer(&self, streamer: Arc<dyn LocationStreamer>) {
        *self.state.location_streamer.write() = Some(streamer);
    }

    pub fn broadcast_location(&self, loc: Location) {
        self.state.location_tx.send(loc).ok();
    }
}

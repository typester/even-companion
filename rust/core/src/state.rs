use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize};
use std::sync::Arc;
use std::time::Instant;
use parking_lot::{Mutex, RwLock};
use tokio::sync::broadcast;
use crate::{Location, LocationProvider, LocationStreamer, SttStreamer};

#[derive(Clone)]
pub struct TranscriptEntry {
    pub seq: u64,
    pub text: String,
    pub is_final: bool,
}

pub struct SttSession {
    pub id: String,
    pub engine: String,
    pub transcripts: Mutex<Vec<TranscriptEntry>>,
    pub notify: tokio::sync::Notify,
    pub last_active: Mutex<Instant>,
    pub next_seq: AtomicU64,
    pub ended: AtomicBool,
}

pub struct SharedState {
    pub location_provider: RwLock<Option<Arc<dyn LocationProvider>>>,
    pub location_tx: broadcast::Sender<Location>,
    pub location_streamer: RwLock<Option<Arc<dyn LocationStreamer>>>,
    pub subscriber_count: AtomicUsize,

    pub stt_streamers: RwLock<HashMap<String, Arc<dyn SttStreamer>>>,
    pub stt_sessions: RwLock<HashMap<String, Arc<SttSession>>>,
}

impl SharedState {
    pub fn new() -> Self {
        let (location_tx, _) = broadcast::channel(16);
        Self {
            location_provider: RwLock::new(None),
            location_tx,
            location_streamer: RwLock::new(None),
            subscriber_count: AtomicUsize::new(0),
            stt_streamers: RwLock::new(HashMap::new()),
            stt_sessions: RwLock::new(HashMap::new()),
        }
    }
}

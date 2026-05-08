use parking_lot::RwLock;
use std::sync::{atomic::AtomicUsize, Arc};
use tokio::sync::broadcast;
use crate::{Location, LocationProvider, LocationStreamer};

pub struct SharedState {
    pub location_provider: RwLock<Option<Arc<dyn LocationProvider>>>,
    pub location_tx: broadcast::Sender<Location>,
    pub location_streamer: RwLock<Option<Arc<dyn LocationStreamer>>>,
    pub subscriber_count: AtomicUsize,
}

impl SharedState {
    pub fn new() -> Self {
        let (location_tx, _) = broadcast::channel(16);
        Self {
            location_provider: RwLock::new(None),
            location_tx,
            location_streamer: RwLock::new(None),
            subscriber_count: AtomicUsize::new(0),
        }
    }
}

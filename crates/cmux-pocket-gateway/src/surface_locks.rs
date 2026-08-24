use parking_lot::Mutex;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::Mutex as AsyncMutex;

/// Manages per-surface asynchronous locks to serialize same-surface work
/// while permitting concurrent work across different surfaces.
#[derive(Debug, Default, Clone)]
pub struct SurfaceLockManager {
    locks: Arc<Mutex<HashMap<String, Arc<AsyncMutex<()>>>>>,
}

impl SurfaceLockManager {
    pub fn new() -> Self {
        Self {
            locks: Arc::new(Mutex::new(HashMap::new())),
        }
    }

    /// Retrieves or instantiates an `Arc<tokio::sync::Mutex<()>>` for the given surface.
    pub fn get_surface_mutex(&self, surface_id: &str) -> Arc<AsyncMutex<()>> {
        let mut map = self.locks.lock();
        map.entry(surface_id.to_string())
            .or_insert_with(|| Arc::new(AsyncMutex::new(())))
            .clone()
    }
}

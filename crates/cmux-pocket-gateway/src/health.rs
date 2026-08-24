use cmux_pocket_protocol::health::BackendHealth;
use parking_lot::RwLock;
use std::sync::Arc;

/// Thread-safe tracker for Gateway backend health status.
#[derive(Debug, Clone)]
pub struct HealthTracker {
    health: Arc<RwLock<BackendHealth>>,
}

impl Default for HealthTracker {
    fn default() -> Self {
        Self {
            health: Arc::new(RwLock::new(BackendHealth::healthy())),
        }
    }
}

impl HealthTracker {
    pub fn new() -> Self {
        Self::default()
    }

    /// Returns a copy of current backend health.
    pub fn current(&self) -> BackendHealth {
        self.health.read().clone()
    }

    /// Updates backend health status.
    pub fn set_health(&self, new_health: BackendHealth) {
        let mut guard = self.health.write();
        *guard = new_health;
    }

    /// Marks backend as healthy.
    pub fn mark_healthy(&self) {
        self.set_health(BackendHealth::healthy());
    }

    /// Marks backend as unhealthy with reason.
    pub fn mark_unhealthy(&self, reason: impl Into<String>) {
        self.set_health(BackendHealth::unhealthy(reason));
    }

    /// Marks backend as recovering.
    pub fn mark_recovering(&self) {
        self.set_health(BackendHealth::recovering());
    }

    /// Checks if currently healthy.
    pub fn is_healthy(&self) -> bool {
        self.health.read().is_healthy()
    }
}

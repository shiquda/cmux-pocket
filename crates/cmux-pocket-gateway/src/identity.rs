//! Durable surface identity map (UUID -> short ref).
//!
//! cmux agent events and hook session records identify a surface by its durable
//! UUID, while every Pocket RPC — and therefore every id the Android client
//! holds — addresses the short `surface:N` ref. The tree poller refreshes this
//! map on each poll so event consumers can translate without another cmux call.

use cmux_pocket_protocol::WorkspaceInfo;
use parking_lot::Mutex;
use std::collections::HashMap;
use std::sync::Arc;

/// Maps durable surface UUIDs onto the current surface refs.
#[derive(Debug, Clone, Default)]
pub struct SurfaceIdentityMap {
    inner: Arc<Mutex<HashMap<String, String>>>,
}

impl SurfaceIdentityMap {
    pub fn new() -> Self {
        Self::default()
    }

    /// Replaces the map from a freshly polled tree.
    ///
    /// Surfaces whose host does not report a UUID are simply absent: an
    /// unmapped event keeps its UUID rather than being attributed to a guess.
    pub fn update(&self, workspaces: &[WorkspaceInfo]) {
        let mut map = HashMap::new();
        for workspace in workspaces {
            for surface in &workspace.surfaces {
                if let Some(uuid) = surface.surface_uuid.as_deref() {
                    map.insert(uuid.to_string(), surface.id.clone());
                }
            }
        }
        *self.inner.lock() = map;
    }

    /// Resolves a durable UUID to the current surface ref.
    pub fn resolve(&self, uuid: &str) -> Option<String> {
        self.inner.lock().get(uuid).cloned()
    }

    /// Number of mapped surfaces (test helper).
    pub fn len(&self) -> usize {
        self.inner.lock().len()
    }

    /// Whether the map holds no surfaces at all.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use cmux_pocket_protocol::SurfaceInfo;

    fn tree(entries: &[(&str, &str, Option<&str>)]) -> Vec<WorkspaceInfo> {
        let mut workspace = WorkspaceInfo::new("ws", "ws");
        workspace.surfaces = entries
            .iter()
            .map(|(id, uuid, _)| {
                let mut surface = SurfaceInfo::new(*id);
                surface.surface_uuid = Some((*uuid).to_string());
                surface
            })
            .collect();
        vec![workspace]
    }

    #[test]
    fn resolves_uuid_to_ref() {
        let map = SurfaceIdentityMap::new();
        map.update(&tree(&[("surface:1", "UUID-A", None)]));

        assert_eq!(map.resolve("UUID-A").as_deref(), Some("surface:1"));
        assert_eq!(map.resolve("UUID-B"), None);
    }

    #[test]
    fn update_drops_surfaces_that_disappeared() {
        let map = SurfaceIdentityMap::new();
        map.update(&tree(&[("surface:1", "UUID-A", None), ("surface:2", "UUID-B", None)]));
        map.update(&tree(&[("surface:2", "UUID-B", None)]));

        assert_eq!(map.resolve("UUID-A"), None);
        assert_eq!(map.resolve("UUID-B").as_deref(), Some("surface:2"));
        assert_eq!(map.len(), 1);
    }

    #[test]
    fn surfaces_without_uuid_are_not_mapped() {
        let map = SurfaceIdentityMap::new();
        let mut workspace = WorkspaceInfo::new("ws", "ws");
        workspace.surfaces = vec![SurfaceInfo::new("surface:1")];
        map.update(&[workspace]);

        assert!(map.is_empty());
    }
}

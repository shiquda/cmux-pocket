//! Asynchronous backend trait for cmux interactions.

use async_trait::async_trait;
use cmux_pocket_protocol::{BackendHealth, RenderGridFrame, SurfaceInfo, WorkspaceInfo};

use crate::error::CmuxError;
use crate::events::CmuxEventStream;

/// How a remote client intends cmux to deliver terminal input.
///
/// `Text` is always sent as a positional argument after `--`, while `Key` is
/// reserved for an intentional control key selected by the client UI.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum TerminalInputKind {
    #[default]
    Text,
    Key,
}

/// Explicit, opt-in delivery options for one terminal input request.
///
/// A caller must never infer `force` from a failed input. It is only used by
/// a user-enabled, temporary remote-takeover mode.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct TerminalInputOptions {
    pub kind: TerminalInputKind,
    pub force: bool,
    pub paste: bool,
}

/// Core interface for cmux backend implementations (live subprocess or mock).
#[async_trait]
pub trait CmuxBackend: Send + Sync {
    /// Pings cmux to verify host reachability.
    async fn ping(&self) -> Result<(), CmuxError>;

    /// Checks the health status of cmux backend.
    async fn health(&self) -> Result<BackendHealth, CmuxError>;

    /// Lists all workspaces and surfaces in cmux.
    async fn list_workspaces(&self) -> Result<Vec<WorkspaceInfo>, CmuxError>;

    /// Creates a new workspace on the host.
    async fn create_workspace(&self, name: &str) -> Result<WorkspaceInfo, CmuxError>;

    /// Selects an active workspace on the host.
    async fn select_workspace(&self, workspace_key: &str) -> Result<(), CmuxError>;

    /// Creates a new surface/tab inside a workspace.
    async fn create_surface(
        &self,
        workspace_key: &str,
        title: Option<&str>,
        surface_type: Option<&str>,
    ) -> Result<SurfaceInfo, CmuxError>;

    /// Closes a surface by ID.
    async fn close_surface(
        &self,
        surface_id: &str,
        workspace_key: Option<&str>,
    ) -> Result<bool, CmuxError>;

    /// Sends literal text or control keys to a surface.
    async fn send_input(&self, surface_id: &str, text: &str) -> Result<(), CmuxError>;

    /// Sends terminal input with explicit delivery options.
    ///
    /// Implementations that do not support the newer options retain the safe
    /// default behavior: they receive ordinary input with no forced takeover.
    async fn send_input_with_options(
        &self,
        surface_id: &str,
        text: &str,
        _options: TerminalInputOptions,
    ) -> Result<(), CmuxError> {
        self.send_input(surface_id, text).await
    }

    /// Handles terminal scrolling for a surface, returning the updated frame.
    async fn handle_scroll(
        &self,
        surface_id: &str,
        delta_lines: f64,
        col: usize,
        row: usize,
    ) -> Result<RenderGridFrame, CmuxError>;

    /// Fetches a full terminal snapshot for a surface.
    async fn get_snapshot(
        &self,
        surface_id: &str,
        max_scrollback_rows: usize,
    ) -> Result<RenderGridFrame, CmuxError>;

    /// Fallback screen reader parsing raw ANSI output into a RenderGridFrame.
    async fn read_screen_fallback(&self, surface_id: &str) -> Result<RenderGridFrame, CmuxError>;

    /// Lists compact notification records from cmux.
    async fn list_notifications(&self) -> Result<Vec<String>, CmuxError>;

    /// Spawns the long-lived events stream child process.
    async fn spawn_events_stream(&self) -> Result<CmuxEventStream, CmuxError>;
}

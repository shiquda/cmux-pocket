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

/// Input flags the installed cmux CLI accepts.
///
/// cmux 0.65.0 introduced an agent draft/dialog guard together with `--force`
/// and `--paste`; older builds parse unknown flags as literal input, so the
/// Gateway must omit them there.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct CmuxCapabilities {
    /// `cmux send` / `send-key` accept `--force` (cmux >= 0.65.0).
    pub force: bool,
    /// `cmux send` accepts `--paste` (cmux >= 0.65.0).
    pub paste: bool,
}

impl CmuxCapabilities {
    /// Reads supported flags from `cmux send --help` and `cmux send-key --help`.
    ///
    /// Detection is based on the flag listing, never on the version string:
    /// nightly builds and backports are common. When a probe fails, callers
    /// pass empty help text and get the legacy (flagless) behaviour.
    pub fn from_help(send_help: &str, send_key_help: &str) -> Self {
        Self {
            force: lists_flag(send_help, "--force") && lists_flag(send_key_help, "--force"),
            paste: lists_flag(send_help, "--paste"),
        }
    }
}

/// Returns true when a help page lists `flag` as a standalone option.
fn lists_flag(help: &str, flag: &str) -> bool {
    help.lines().any(|line| {
        let line = line.trim_start();
        line.strip_prefix(flag)
            .is_some_and(|rest| rest.is_empty() || rest.starts_with(char::is_whitespace))
    })
}

#[cfg(test)]
mod tests {
    use super::CmuxCapabilities;

    const SEND_HELP_065: &str = "cmux send

Usage: cmux send [flags] [--] <text>

Flags:
  --workspace <id|ref|index>   Target workspace
  --surface <id|ref|index>     Target surface
  --paste                      Paste the text instead of typing it
  --force                      Send even over a draft or into an open dialog
";
    const SEND_KEY_HELP_065: &str = "cmux send-key

Usage: cmux send-key [flags] [--] <key>

Flags:
  --workspace <id|ref|index>   Target workspace
  --surface <id|ref|index>     Target surface
  --force                      Send even into an open dialog
";
    const SEND_HELP_064: &str = "cmux send

Usage: cmux send [flags] [--] <text>

Send text to a terminal surface. Escape sequences: \\n and \\r send Enter, \\t sends Tab.

Flags:
  --workspace <id|ref|index>   Target workspace (default: $CMUX_WORKSPACE_ID)
  --surface <id|ref|index>     Target surface (default: $CMUX_SURFACE_ID)
  --window <id|ref|index>      Window context for workspace/surface refs and indexes
";
    const SEND_KEY_HELP_064: &str = "cmux send-key

Usage: cmux send-key [flags] [--] <key>

Flags:
  --workspace <id|ref|index>   Target workspace
  --surface <id|ref|index>     Target surface
";

    #[test]
    fn legacy_help_reports_no_new_flags() {
        assert_eq!(
            CmuxCapabilities::from_help(SEND_HELP_064, SEND_KEY_HELP_064),
            CmuxCapabilities::default()
        );
    }

    #[test]
    fn current_help_reports_force_and_paste() {
        assert_eq!(
            CmuxCapabilities::from_help(SEND_HELP_065, SEND_KEY_HELP_065),
            CmuxCapabilities {
                force: true,
                paste: true,
            }
        );
    }

    #[test]
    fn force_requires_both_commands() {
        let caps = CmuxCapabilities::from_help(SEND_HELP_065, SEND_KEY_HELP_064);
        assert!(!caps.force);
        assert!(caps.paste);
    }

    #[test]
    fn prose_mentions_are_not_flags() {
        let help = "Flags:\n  --surface <id>   Target\n";
        assert!(!CmuxCapabilities::from_help(help, help).force);
    }
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

    /// Renames a workspace by key/ID.
    async fn rename_workspace(&self, _workspace_key: &str, _title: &str) -> Result<WorkspaceInfo, CmuxError> {
        Err(CmuxError::unavailable("rename_workspace not supported"))
    }

    /// Closes/deletes a workspace by key/ID.
    async fn close_workspace(&self, _workspace_key: &str) -> Result<bool, CmuxError> {
        Err(CmuxError::unavailable("close_workspace not supported"))
    }

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
    /// Renames a surface/tab by ID.
    async fn rename_surface(
        &self,
        _surface_id: &str,
        _title: &str,
        _workspace_key: Option<&str>,
    ) -> Result<SurfaceInfo, CmuxError> {
        Err(CmuxError::unavailable("rename_surface not supported"))
    }

    /// Sends literal text or control keys to a surface.
    async fn send_input(&self, surface_id: &str, text: &str) -> Result<(), CmuxError>;

    /// Sends terminal input with an explicit text/key classification.
    ///
    /// Implementations that cannot distinguish kinds fall back to ordinary
    /// input; the live backend additionally applies `--force` when the
    /// installed cmux supports it.
    async fn send_input_with_kind(
        &self,
        surface_id: &str,
        text: &str,
        _kind: TerminalInputKind,
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

//! Live cmux backend implementation using `tokio::process::Command`.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use async_trait::async_trait;
use cmux_pocket_protocol::{
    ansi_lines_to_render_grid, normalize_official_replay, BackendHealth, RenderGridFrame,
    SurfaceInfo, WorkspaceInfo,
};
use parking_lot::Mutex;
use serde_json::Value;
use tokio::process::Command;
use tokio::time::timeout;
use tracing::{debug, warn};

use crate::args::{
    close_surface_args, list_notifications_args, new_surface_args, new_workspace_args, ping_args,
    read_screen_args, rpc_replay_args, rpc_scroll_args, select_workspace_args, send_input_args,
    tree_args,
};
use crate::backend::CmuxBackend;
use crate::error::CmuxError;
use crate::events::CmuxEventStream;
use crate::tree_parser::{extract_surface_id, parse_workspace_tree};

/// Default timeout for short cmux CLI commands (e.g. ping, tree, send).
pub const DEFAULT_COMMAND_TIMEOUT: Duration = Duration::from_secs(3);

/// Connects to a real live cmux instance via CLI subprocess invocations.
#[derive(Debug, Clone)]
pub struct LiveCmuxBackend {
    cmux_path: PathBuf,
    default_timeout: Duration,
    state_seqs: Arc<Mutex<HashMap<String, u64>>>,
    render_epochs: Arc<Mutex<HashMap<String, String>>>,
    epoch_counter: Arc<AtomicU64>,
}

impl Default for LiveCmuxBackend {
    fn default() -> Self {
        Self::new()
    }
}

impl LiveCmuxBackend {
    /// Creates a new `LiveCmuxBackend` looking for `cmux` in standard PATH with 3s timeout.
    pub fn new() -> Self {
        Self {
            cmux_path: PathBuf::from("cmux"),
            default_timeout: DEFAULT_COMMAND_TIMEOUT,
            state_seqs: Arc::new(Mutex::new(HashMap::new())),
            render_epochs: Arc::new(Mutex::new(HashMap::new())),
            epoch_counter: Arc::new(AtomicU64::new(1)),
        }
    }

    /// Creates a `LiveCmuxBackend` with a specific executable path.
    pub fn with_path(path: impl Into<PathBuf>) -> Self {
        Self {
            cmux_path: path.into(),
            default_timeout: DEFAULT_COMMAND_TIMEOUT,
            state_seqs: Arc::new(Mutex::new(HashMap::new())),
            render_epochs: Arc::new(Mutex::new(HashMap::new())),
            epoch_counter: Arc::new(AtomicU64::new(1)),
        }
    }

    /// Creates a `LiveCmuxBackend` with a specific executable path and default command timeout.
    pub fn with_path_and_timeout(path: impl Into<PathBuf>, command_timeout: Duration) -> Self {
        Self {
            cmux_path: path.into(),
            default_timeout: command_timeout,
            state_seqs: Arc::new(Mutex::new(HashMap::new())),
            render_epochs: Arc::new(Mutex::new(HashMap::new())),
            epoch_counter: Arc::new(AtomicU64::new(1)),
        }
    }

    /// Returns the configured cmux binary path.
    pub fn cmux_path(&self) -> &Path {
        &self.cmux_path
    }

    /// Returns the next monotonic state sequence number for a surface.
    pub fn next_state_seq(&self, surface_id: &str) -> u64 {
        let mut seqs = self.state_seqs.lock();
        let seq = seqs.entry(surface_id.to_string()).or_insert(0);
        *seq += 1;
        *seq
    }

    /// Returns or creates a stable render epoch string for a surface.
    pub fn get_or_create_render_epoch(&self, surface_id: &str) -> String {
        let mut epochs = self.render_epochs.lock();
        epochs
            .entry(surface_id.to_string())
            .or_insert_with(|| {
                let id = self.epoch_counter.fetch_add(1, Ordering::SeqCst);
                format!("epoch-live-{id:06x}")
            })
            .clone()
    }

    /// Runs a cmux CLI subcommand with bounded timeout and stderr redaction.
    pub async fn run_cmux(&self, args: &[impl AsRef<str>]) -> Result<String, CmuxError> {
        let op = args
            .first()
            .map(|a| a.as_ref().to_string())
            .unwrap_or_else(|| "cmux".to_string());
        let str_args: Vec<&str> = args.iter().map(|a| a.as_ref()).collect();

        debug!(op = %op, path = %self.cmux_path.display(), "Executing cmux command");

        let mut cmd = Command::new(&self.cmux_path);
        cmd.args(&str_args)
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());

        let child_future = cmd.output();

        let output = match timeout(self.default_timeout, child_future).await {
            Ok(Ok(out)) => out,
            Ok(Err(e)) => {
                if e.kind() == std::io::ErrorKind::NotFound {
                    return Err(CmuxError::unavailable(format!(
                        "cmux executable not found at {}",
                        self.cmux_path.display()
                    )));
                }
                return Err(CmuxError::unavailable(format!(
                    "failed to execute cmux {op}: {e}"
                )));
            }
            Err(_) => {
                return Err(CmuxError::timeout(op, self.default_timeout));
            }
        };

        if !output.status.success() {
            let code = output.status.code();
            // Stderr is logged at debug level for troubleshooting, but NOT included in CmuxError
            // to ensure no private/sensitive text leaks into user-visible error strings.
            let stderr_sample = String::from_utf8_lossy(&output.stderr);
            warn!(
                op = %op,
                exit_code = ?code,
                stderr_len = stderr_sample.len(),
                "cmux command returned non-zero exit code"
            );
            debug!("cmux stderr was: {stderr_sample}");
            return Err(CmuxError::non_zero_exit(op, code));
        }

        let stdout_str = String::from_utf8_lossy(&output.stdout).trim().to_string();
        Ok(stdout_str)
    }
}

#[async_trait]
impl CmuxBackend for LiveCmuxBackend {
    async fn ping(&self) -> Result<(), CmuxError> {
        self.run_cmux(&ping_args()).await?;
        Ok(())
    }

    async fn health(&self) -> Result<BackendHealth, CmuxError> {
        match self.ping().await {
            Ok(()) => Ok(BackendHealth::Healthy),
            Err(e) => Ok(BackendHealth::unhealthy(e.to_string())),
        }
    }

    async fn list_workspaces(&self) -> Result<Vec<WorkspaceInfo>, CmuxError> {
        let raw = self.run_cmux(&tree_args()).await?;
        parse_workspace_tree(&raw)
    }

    async fn create_workspace(&self, name: &str) -> Result<WorkspaceInfo, CmuxError> {
        self.run_cmux(&new_workspace_args(name)).await?;
        let workspaces = self.list_workspaces().await?;
        let target = workspaces
            .into_iter()
            .find(|w| w.name == name)
            .unwrap_or_else(|| WorkspaceInfo::new(format!("ws-{name}"), name));
        Ok(target)
    }

    async fn select_workspace(&self, workspace_key: &str) -> Result<(), CmuxError> {
        self.run_cmux(&select_workspace_args(workspace_key)).await?;
        Ok(())
    }

    async fn create_surface(
        &self,
        workspace_key: &str,
        title: Option<&str>,
        surface_type: Option<&str>,
    ) -> Result<SurfaceInfo, CmuxError> {
        let raw = self
            .run_cmux(&new_surface_args(workspace_key, surface_type))
            .await?;

        let created_id = extract_surface_id(&raw).ok_or_else(|| {
            CmuxError::parse_error(format!("cmux new-surface did not return surface ID: {raw}"))
        })?;

        if let Ok(workspaces) = self.list_workspaces().await {
            if let Some(ws) = workspaces
                .into_iter()
                .find(|w| w.id == workspace_key || w.key.as_deref() == Some(workspace_key))
            {
                if let Some(surf) = ws.surfaces.into_iter().find(|s| s.id == created_id) {
                    return Ok(surf);
                }
            }
        }

        Ok(SurfaceInfo {
            id: created_id,
            surface_type: surface_type.unwrap_or("terminal").to_string(),
            title: title
                .map(ToString::to_string)
                .or_else(|| Some("terminal".to_string())),
            workspace_key: Some(workspace_key.to_string()),
            pane_id: None,
            tab_index: 0,
            agent_state: None,
            attention: false,
            dead: false,
            cwd: None,
            extra: serde_json::Map::new(),
        })
    }

    async fn close_surface(
        &self,
        surface_id: &str,
        workspace_key: Option<&str>,
    ) -> Result<bool, CmuxError> {
        self.run_cmux(&close_surface_args(surface_id, workspace_key))
            .await?;
        Ok(true)
    }

    async fn send_input(&self, surface_id: &str, text: &str) -> Result<(), CmuxError> {
        self.run_cmux(&send_input_args(surface_id, text)).await?;
        Ok(())
    }

    async fn handle_scroll(
        &self,
        surface_id: &str,
        delta_lines: f64,
        col: usize,
        row: usize,
    ) -> Result<RenderGridFrame, CmuxError> {
        let args = rpc_scroll_args(surface_id, delta_lines, col, row);
        match self.run_cmux(&args).await {
            Ok(raw) => {
                if let Ok(val) = serde_json::from_str::<Value>(&raw) {
                    if val.get("render_grid").is_some()
                        || val.as_object().is_some_and(|o| o.contains_key("columns"))
                    {
                        let seq = self.next_state_seq(surface_id);
                        if let Ok(frame) = normalize_official_replay(&val, surface_id, seq, false) {
                            return Ok(frame);
                        }
                    }
                }
            }
            Err(e) => {
                warn!("cmux mobile.terminal.scroll RPC failed: {e}; falling back to snapshot");
            }
        }
        self.get_snapshot(surface_id, 0).await
    }

    async fn get_snapshot(
        &self,
        surface_id: &str,
        max_scrollback_rows: usize,
    ) -> Result<RenderGridFrame, CmuxError> {
        let seq = self.next_state_seq(surface_id);
        let include_sb = max_scrollback_rows > 0;
        let args = rpc_replay_args(surface_id, max_scrollback_rows);

        match self.run_cmux(&args).await {
            Ok(raw) => {
                if let Ok(val) = serde_json::from_str::<Value>(&raw) {
                    match normalize_official_replay(&val, surface_id, seq, include_sb) {
                        Ok(frame) => return Ok(frame),
                        Err(e) => {
                            warn!("Failed to normalize official terminal.replay payload: {e}; falling back to read-screen");
                        }
                    }
                }
            }
            Err(e) => {
                warn!("cmux terminal.replay RPC failed: {e}; falling back to read-screen");
            }
        }

        self.read_screen_fallback(surface_id).await
    }

    async fn read_screen_fallback(&self, surface_id: &str) -> Result<RenderGridFrame, CmuxError> {
        let seq = self.next_state_seq(surface_id);
        let epoch = self.get_or_create_render_epoch(surface_id);

        let raw = self
            .run_cmux(&read_screen_args(surface_id))
            .await
            .map_err(|e| {
                warn!("read-screen fallback failed: {e}");
                e
            })?;

        let raw_lines: Vec<&str> = raw.lines().collect();
        let frame = ansi_lines_to_render_grid(&raw_lines, surface_id, seq, Some(&epoch));
        Ok(frame)
    }

    async fn list_notifications(&self) -> Result<Vec<String>, CmuxError> {
        let raw = self.run_cmux(&list_notifications_args()).await?;
        let lines = raw
            .lines()
            .map(str::trim)
            .filter(|l| !l.is_empty())
            .map(ToString::to_string)
            .collect();
        Ok(lines)
    }

    async fn spawn_events_stream(&self) -> Result<CmuxEventStream, CmuxError> {
        CmuxEventStream::spawn(&self.cmux_path)
    }
}

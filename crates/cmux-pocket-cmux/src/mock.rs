//! Mock cmux backend and terminal session fixtures for testing.

use async_trait::async_trait;
use cmux_pocket_protocol::{
    display_cell_width, BackendHealth, Cursor, RenderGridFrame, RowSpan, Style, SurfaceInfo,
    WorkspaceInfo, RENDER_GRID_FORMAT_V1,
};
use parking_lot::Mutex;
use serde_json::Value;
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Arc;
use tokio::sync::mpsc;

use crate::backend::CmuxBackend;
use crate::error::CmuxError;
use crate::events::CmuxEventStream;

/// Per-surface reports: `client_id -> (columns, rows, generation)`.
type ViewportReports = HashMap<String, HashMap<String, (usize, usize, u64)>>;

/// Simulates a live cmux terminal session with RenderGrid frames for testing.
#[derive(Debug, Clone)]
pub struct MockTerminalSession {
    pub surface_id: String,
    pub title: String,
    pub columns: usize,
    pub rows: usize,
    /// Geometry before any mobile viewport report; restored when all reports clear.
    pub base_columns: usize,
    pub base_rows: usize,
    pub state_seq: u64,
    pub render_epoch: String,
    pub render_revision: u64,
    pub cursor_row: usize,
    pub cursor_col: usize,
    pub lines: Vec<String>,
}

impl MockTerminalSession {
    pub fn new(surface_id: impl Into<String>, title: impl Into<String>) -> Self {
        let surface_id = surface_id.into();
        let title = title.into();
        let columns = 80;
        let rows = 24;
        let mut lines = Vec::with_capacity(rows);
        lines.push(format!("=== cmux Terminal ({title} / {surface_id}) ==="));
        lines.push("❯ ".to_string());
        for _ in 2..rows {
            lines.push(String::new());
        }

        Self {
            surface_id: surface_id.clone(),
            title,
            columns,
            rows,
            base_columns: columns,
            base_rows: rows,
            state_seq: 1,
            render_epoch: "epoch-mock-1".to_string(),
            render_revision: 1,
            cursor_row: 1,
            cursor_col: 2,
            lines,
        }
    }

    pub fn apply_input(&mut self, text: &str) {
        self.state_seq += 1;
        self.render_revision += 1;

        let has_enter = text.contains('\n') || text.contains('\r');
        let raw_chars: String = text.chars().filter(|&c| c != '\r' && c != '\n').collect();

        if !raw_chars.is_empty() && self.cursor_row < self.lines.len() {
            self.lines[self.cursor_row].push_str(&raw_chars);
            self.cursor_col += display_cell_width(&raw_chars);
        }

        if has_enter {
            let current_line = if self.cursor_row < self.lines.len() {
                self.lines[self.cursor_row].clone()
            } else {
                String::new()
            };

            let cmd = if let Some(rest) = current_line.strip_prefix("❯ ") {
                rest.trim()
            } else {
                current_line.trim()
            };

            self.cursor_row += 1;
            if self.cursor_row >= self.rows.saturating_sub(2) {
                if !self.lines.is_empty() {
                    self.lines.remove(0);
                }
                self.lines.push(String::new());
                self.cursor_row = self.rows.saturating_sub(3);
            }

            let output = match cmd {
                "help" => Some(
                    "Available commands: status, tabs, workspaces, clear, ping, date".to_string(),
                ),
                "status" => Some(format!(
                    "Session {} healthy (title: {})",
                    self.surface_id, self.title
                )),
                "tabs" => Some("Active surfaces: 3 terminal tabs attached".to_string()),
                "clear" => {
                    self.lines = vec!["❯ ".to_string()];
                    for _ in 1..self.rows {
                        self.lines.push(String::new());
                    }
                    self.cursor_row = 0;
                    self.cursor_col = 2;
                    return;
                }
                "ping" => Some("pong! (cmux mobile bridge v2)".to_string()),
                "" => None,
                other => Some(format!("zsh: command not found: {other}")),
            };

            if let Some(out) = output {
                if self.cursor_row < self.lines.len() {
                    self.lines[self.cursor_row] = out;
                }
                self.cursor_row += 1;
                if self.cursor_row >= self.rows.saturating_sub(1) {
                    self.cursor_row = self.rows.saturating_sub(2);
                }
            }

            if self.cursor_row < self.lines.len() {
                self.lines[self.cursor_row] = "❯ ".to_string();
            }
            self.cursor_col = 2;
        }
    }

    pub fn get_full_snapshot(&self, max_scrollback_rows: usize) -> RenderGridFrame {
        let mut row_spans = Vec::new();
        for (idx, line) in self.lines.iter().enumerate() {
            if !line.is_empty() {
                let style_id = if line.starts_with('❯') { 1 } else { 0 };
                let width = display_cell_width(line);
                row_spans.push(RowSpan {
                    row: idx,
                    column: 0,
                    style_id,
                    text: line.clone(),
                    cell_width: Some(width),
                    extra: serde_json::Map::new(),
                });
            }
        }

        let styles = vec![
            Style {
                id: 0,
                foreground: Some("#D4D4D4".to_string()),
                background: Some("#1E1E1E".to_string()),
                bold: false,
                italic: false,
                underline: false,
                inverse: false,
                extra: serde_json::Map::new(),
            },
            Style {
                id: 1,
                foreground: Some("#00FF7F".to_string()),
                background: Some("#1E1E1E".to_string()),
                bold: true,
                italic: false,
                underline: false,
                inverse: false,
                extra: serde_json::Map::new(),
            },
        ];

        RenderGridFrame {
            format: RENDER_GRID_FORMAT_V1.to_string(),
            surface_id: self.surface_id.clone(),
            state_seq: self.state_seq,
            render_epoch: Some(self.render_epoch.clone()),
            render_revision: Some(self.render_revision),
            columns: self.columns,
            rows: self.rows,
            full: true,
            cleared_rows: vec![],
            cursor: Some(Cursor {
                row: self.cursor_row,
                column: self.cursor_col,
                visible: true,
                style: "block".to_string(),
                blinking: false,
                extra: serde_json::Map::new(),
            }),
            styles,
            row_spans,
            active_screen: Some("primary".to_string()),
            terminal_background: Some("#1E1E1E".to_string()),
            terminal_foreground: Some("#D4D4D4".to_string()),
            history_rows: Some(500),
            row_space_revision: Some(1),
            scrollback_rows: if max_scrollback_rows > 0 {
                Some(0)
            } else {
                None
            },
            scrollback_spans: if max_scrollback_rows > 0 {
                Some(vec![])
            } else {
                None
            },
            extra: serde_json::Map::new(),
        }
    }
}

/// In-memory mock cmux backend with deterministic initial workspace & surface fixtures.
#[derive(Debug, Clone)]
pub struct MockCmuxBackend {
    workspaces: Arc<Mutex<Vec<WorkspaceInfo>>>,
    sessions: Arc<Mutex<HashMap<String, MockTerminalSession>>>,
    notifications: Arc<Mutex<Vec<String>>>,
    id_counter: Arc<AtomicU64>,
    offline: Arc<AtomicBool>,
    /// surface -> client_id -> (columns, rows, generation), mirroring the host's
    /// minimum-across-clients viewport reports.
    viewport_reports: Arc<Mutex<ViewportReports>>,
    viewport_report_calls: Arc<AtomicU64>,
    viewport_supported: Arc<AtomicBool>,
}

impl Default for MockCmuxBackend {
    fn default() -> Self {
        Self::new()
    }
}

impl MockCmuxBackend {
    /// Creates a mock backend pre-populated with standard testing workspaces.
    pub fn new() -> Self {
        let ws_main = WorkspaceInfo {
            id: "ws-main".to_string(),
            key: Some("ws-main".to_string()),
            name: "cmux-main".to_string(),
            order: 0,
            active_on_host: true,
            cwd: None,
            surfaces: vec![
                SurfaceInfo {
                    id: "surf-main-1".to_string(),
                    surface_type: "terminal".to_string(),
                    title: Some("zsh".to_string()),
                    workspace_key: Some("ws-main".to_string()),
                    pane_id: None,
                    tab_index: 0,
                    agent_state: None,
                    attention: false,
                    dead: false,
                    cwd: None,
                    extra: serde_json::Map::new(),
                },
                SurfaceInfo {
                    id: "surf-main-2".to_string(),
                    surface_type: "terminal".to_string(),
                    title: Some("Claude Code".to_string()),
                    workspace_key: Some("ws-main".to_string()),
                    pane_id: None,
                    tab_index: 1,
                    agent_state: Some("working".to_string()),
                    attention: false,
                    dead: false,
                    cwd: None,
                    extra: serde_json::Map::new(),
                },
                SurfaceInfo {
                    id: "surf-main-3".to_string(),
                    surface_type: "terminal".to_string(),
                    title: Some("tests".to_string()),
                    workspace_key: Some("ws-main".to_string()),
                    pane_id: None,
                    tab_index: 2,
                    agent_state: None,
                    attention: false,
                    dead: false,
                    cwd: None,
                    extra: serde_json::Map::new(),
                },
            ],
            extra: serde_json::Map::new(),
        };

        let ws_android = WorkspaceInfo {
            id: "ws-android".to_string(),
            key: Some("ws-android".to_string()),
            name: "android-dev".to_string(),
            order: 1,
            active_on_host: false,
            cwd: None,
            surfaces: vec![
                SurfaceInfo {
                    id: "surf-android-1".to_string(),
                    surface_type: "terminal".to_string(),
                    title: Some("gradle build".to_string()),
                    workspace_key: Some("ws-android".to_string()),
                    pane_id: None,
                    tab_index: 0,
                    agent_state: None,
                    attention: false,
                    dead: false,
                    cwd: None,
                    extra: serde_json::Map::new(),
                },
                SurfaceInfo {
                    id: "surf-android-2".to_string(),
                    surface_type: "terminal".to_string(),
                    title: Some("logcat".to_string()),
                    workspace_key: Some("ws-android".to_string()),
                    pane_id: None,
                    tab_index: 1,
                    agent_state: None,
                    attention: false,
                    dead: false,
                    cwd: None,
                    extra: serde_json::Map::new(),
                },
            ],
            extra: serde_json::Map::new(),
        };

        let ws_exp = WorkspaceInfo {
            id: "ws-exp".to_string(),
            key: Some("ws-exp".to_string()),
            name: "experiments".to_string(),
            order: 2,
            active_on_host: false,
            cwd: None,
            surfaces: vec![SurfaceInfo {
                id: "surf-exp-1".to_string(),
                surface_type: "terminal".to_string(),
                title: Some("Codex MCP".to_string()),
                workspace_key: Some("ws-exp".to_string()),
                pane_id: None,
                tab_index: 0,
                agent_state: Some("needs_input".to_string()),
                attention: true,
                dead: false,
                cwd: None,
                extra: serde_json::Map::new(),
            }],
            extra: serde_json::Map::new(),
        };

        let workspaces = vec![ws_main, ws_android, ws_exp];
        let mut sessions = HashMap::new();

        for ws in &workspaces {
            for s in &ws.surfaces {
                sessions.insert(
                    s.id.clone(),
                    MockTerminalSession::new(&s.id, s.title.as_deref().unwrap_or("zsh")),
                );
            }
        }

        Self {
            workspaces: Arc::new(Mutex::new(workspaces)),
            sessions: Arc::new(Mutex::new(sessions)),
            notifications: Arc::new(Mutex::new(Vec::new())),
            id_counter: Arc::new(AtomicU64::new(100)),
            offline: Arc::new(AtomicBool::new(false)),
            viewport_reports: Arc::new(Mutex::new(HashMap::new())),
            viewport_report_calls: Arc::new(AtomicU64::new(0)),
            viewport_supported: Arc::new(AtomicBool::new(true)),
        }
    }

    /// Simulates the cmux app being stopped; `ping` and `list_workspaces` then fail.
    pub fn set_offline(&self, offline: bool) {
        self.offline.store(offline, Ordering::SeqCst);
    }

    fn is_offline(&self) -> bool {
        self.offline.load(Ordering::SeqCst)
    }

    /// Simulates a transient capability-probe failure.
    pub fn set_viewport_supported(&self, supported: bool) {
        self.viewport_supported.store(supported, Ordering::SeqCst);
    }

    /// Number of applied viewport reports (test helper for lease renewal).
    pub fn viewport_report_calls(&self) -> u64 {
        self.viewport_report_calls.load(Ordering::SeqCst)
    }

    /// Applies the minimum viewport across all reported clients for a surface.
    fn recompute_viewport(&self, surface_id: &str) {
        let reports = self.viewport_reports.lock().clone();
        let mut sessions = self.sessions.lock();
        let session = sessions
            .entry(surface_id.to_string())
            .or_insert_with(|| MockTerminalSession::new(surface_id, "zsh"));
        let limit = reports
            .get(surface_id)
            .and_then(|by_client| {
                let columns = by_client.values().map(|(c, _, _)| *c).min()?;
                let rows = by_client.values().map(|(_, r, _)| *r).min()?;
                Some((columns, rows))
            })
            .unwrap_or((session.base_columns, session.base_rows));
        if (session.columns, session.rows) == limit {
            return;
        }
        session.columns = limit.0;
        session.rows = limit.1;
        session.lines.resize(limit.1, String::new());
        session.lines.truncate(limit.1);
        session.state_seq += 1;
        session.render_revision += 1;
        session.cursor_row = session.cursor_row.min(limit.1.saturating_sub(1));
        session.cursor_col = session.cursor_col.min(limit.0.saturating_sub(1));
    }

    /// Returns the reports currently held for a surface (test helper).
    pub fn viewport_reports_for(&self, surface_id: &str) -> Vec<(String, usize, usize, u64)> {
        let reports = self.viewport_reports.lock();
        reports
            .get(surface_id)
            .map(|by_client| {
                by_client
                    .iter()
                    .map(|(client, (c, r, g))| (client.clone(), *c, *r, *g))
                    .collect()
            })
            .unwrap_or_default()
    }

    /// Adds a mock notification record.
    pub fn add_notification(&self, record: impl Into<String>) {
        let mut notes = self.notifications.lock();
        notes.push(record.into());
    }

    fn next_id(&self, prefix: &str) -> String {
        let val = self.id_counter.fetch_add(1, Ordering::SeqCst);
        format!("{prefix}-{val:06x}")
    }

    fn get_or_create_session(&self, surface_id: &str) -> MockTerminalSession {
        let mut sessions = self.sessions.lock();
        sessions
            .entry(surface_id.to_string())
            .or_insert_with(|| MockTerminalSession::new(surface_id, "zsh"))
            .clone()
    }
}

#[async_trait]
impl CmuxBackend for MockCmuxBackend {
    async fn ping(&self) -> Result<(), CmuxError> {
        if self.is_offline() {
            return Err(CmuxError::unavailable("Socket not found"));
        }
        Ok(())
    }

    async fn health(&self) -> Result<BackendHealth, CmuxError> {
        if self.is_offline() {
            return Ok(BackendHealth::unhealthy("Socket not found"));
        }
        Ok(BackendHealth::Healthy)
    }

    async fn list_workspaces(&self) -> Result<Vec<WorkspaceInfo>, CmuxError> {
        if self.is_offline() {
            return Err(CmuxError::unavailable("Socket not found"));
        }
        let ws = self.workspaces.lock().clone();
        Ok(ws)
    }

    async fn create_workspace(&self, name: &str) -> Result<WorkspaceInfo, CmuxError> {
        let ws_id = self.next_id("ws");
        let mut workspaces = self.workspaces.lock();
        let final_name = if name.is_empty() {
            format!("workspace {}", workspaces.len() + 1)
        } else {
            name.to_string()
        };
        let new_ws = WorkspaceInfo {
            id: ws_id.clone(),
            key: Some(ws_id.clone()),
            name: final_name,
            order: workspaces.len() as i32,
            active_on_host: false,
            cwd: None,
            surfaces: Vec::new(),
            extra: serde_json::Map::new(),
        };
        workspaces.push(new_ws.clone());
        Ok(new_ws)
    }

    async fn rename_workspace(
        &self,
        workspace_key: &str,
        title: &str,
    ) -> Result<WorkspaceInfo, CmuxError> {
        let mut workspaces = self.workspaces.lock();
        let ws = workspaces
            .iter_mut()
            .find(|w| w.id == workspace_key || w.key.as_deref() == Some(workspace_key))
            .ok_or_else(|| {
                CmuxError::parse_error(format!("workspace not found: {workspace_key}"))
            })?;
        ws.name = title.to_string();
        Ok(ws.clone())
    }

    async fn close_workspace(&self, workspace_key: &str) -> Result<bool, CmuxError> {
        let mut workspaces = self.workspaces.lock();
        let pos = workspaces
            .iter()
            .position(|w| w.id == workspace_key || w.key.as_deref() == Some(workspace_key));
        if let Some(idx) = pos {
            let removed = workspaces.remove(idx);
            let mut sessions = self.sessions.lock();
            for s in removed.surfaces {
                sessions.remove(&s.id);
            }
            Ok(true)
        } else {
            Ok(false)
        }
    }

    async fn select_workspace(&self, workspace_key: &str) -> Result<(), CmuxError> {
        let mut workspaces = self.workspaces.lock();
        for ws in workspaces.iter_mut() {
            ws.active_on_host = ws.id == workspace_key || ws.key.as_deref() == Some(workspace_key);
        }
        Ok(())
    }

    async fn create_surface(
        &self,
        workspace_key: &str,
        title: Option<&str>,
        surface_type: Option<&str>,
    ) -> Result<SurfaceInfo, CmuxError> {
        let surf_id = self.next_id("surf");
        let surface_type_str = surface_type.unwrap_or("terminal").to_string();
        let final_title = title.map(ToString::to_string).unwrap_or_else(|| {
            if surface_type_str == "terminal" {
                "zsh".to_string()
            } else {
                surface_type_str.clone()
            }
        });

        let mut workspaces = self.workspaces.lock();
        let ws_index = workspaces
            .iter()
            .position(|w| w.id == workspace_key || w.key.as_deref() == Some(workspace_key))
            .or_else(|| {
                if !workspaces.is_empty() {
                    Some(0)
                } else {
                    None
                }
            })
            .ok_or_else(|| CmuxError::parse_error("no workspaces available"))?;

        let ws = &mut workspaces[ws_index];

        let new_surf = SurfaceInfo {
            id: surf_id.clone(),
            surface_type: surface_type_str,
            title: Some(final_title.clone()),
            workspace_key: Some(ws.id.clone()),
            pane_id: None,
            tab_index: ws.surfaces.len() as i32,
            agent_state: None,
            attention: false,
            dead: false,
            cwd: None,
            extra: serde_json::Map::new(),
        };

        ws.surfaces.push(new_surf.clone());
        drop(workspaces);

        let mut sessions = self.sessions.lock();
        sessions.insert(
            surf_id.clone(),
            MockTerminalSession::new(&surf_id, &final_title),
        );

        Ok(new_surf)
    }

    async fn close_surface(
        &self,
        surface_id: &str,
        _workspace_key: Option<&str>,
    ) -> Result<bool, CmuxError> {
        let mut workspaces = self.workspaces.lock();
        let mut found = false;
        for ws in workspaces.iter_mut() {
            if let Some(pos) = ws.surfaces.iter().position(|s| s.id == surface_id) {
                ws.surfaces.remove(pos);
                found = true;
                break;
            }
        }
        if found {
            let mut sessions = self.sessions.lock();
            sessions.remove(surface_id);
        }
        Ok(found)
    }

    async fn rename_surface(
        &self,
        surface_id: &str,
        title: &str,
        workspace_key: Option<&str>,
    ) -> Result<SurfaceInfo, CmuxError> {
        let mut workspaces = self.workspaces.lock();
        let mut found_surface = None;
        for ws in workspaces.iter_mut() {
            if workspace_key.is_none()
                || ws.id == workspace_key.unwrap()
                || ws.key.as_deref() == workspace_key
            {
                if let Some(surf) = ws.surfaces.iter_mut().find(|s| s.id == surface_id) {
                    surf.title = Some(title.to_string());
                    found_surface = Some(surf.clone());
                    break;
                }
            }
        }
        if let Some(surf) = found_surface {
            let mut sessions = self.sessions.lock();
            if let Some(session) = sessions.get_mut(surface_id) {
                session.title = title.to_string();
            }
            Ok(surf)
        } else {
            Err(CmuxError::parse_error(format!(
                "surface not found: {surface_id}"
            )))
        }
    }

    async fn send_input(&self, surface_id: &str, text: &str) -> Result<(), CmuxError> {
        let mut sessions = self.sessions.lock();
        let session = sessions
            .entry(surface_id.to_string())
            .or_insert_with(|| MockTerminalSession::new(surface_id, "zsh"));
        session.apply_input(text);
        Ok(())
    }

    async fn handle_scroll(
        &self,
        surface_id: &str,
        _delta_lines: f64,
        _col: usize,
        _row: usize,
    ) -> Result<RenderGridFrame, CmuxError> {
        let session = self.get_or_create_session(surface_id);
        Ok(session.get_full_snapshot(0))
    }

    async fn get_snapshot(
        &self,
        surface_id: &str,
        max_scrollback_rows: usize,
    ) -> Result<RenderGridFrame, CmuxError> {
        let session = self.get_or_create_session(surface_id);
        Ok(session.get_full_snapshot(max_scrollback_rows))
    }

    async fn read_screen_fallback(&self, surface_id: &str) -> Result<RenderGridFrame, CmuxError> {
        let session = self.get_or_create_session(surface_id);
        Ok(session.get_full_snapshot(0))
    }

    async fn list_notifications(&self) -> Result<Vec<String>, CmuxError> {
        let notes = self.notifications.lock().clone();
        Ok(notes)
    }

    async fn supports_viewport_resize(&self) -> bool {
        self.viewport_supported.load(Ordering::SeqCst)
    }

    async fn apply_viewport_report(
        &self,
        surface_id: &str,
        columns: u32,
        rows: u32,
        client_id: &str,
        generation: u64,
    ) -> Result<RenderGridFrame, CmuxError> {
        self.viewport_report_calls.fetch_add(1, Ordering::SeqCst);
        {
            let mut reports = self.viewport_reports.lock();
            let by_client = reports.entry(surface_id.to_string()).or_default();
            if let Some((_, _, existing_generation)) = by_client.get(client_id) {
                if *existing_generation > generation {
                    return Err(CmuxError::ViewportTransition);
                }
            }
            by_client.insert(
                client_id.to_string(),
                (columns as usize, rows as usize, generation),
            );
        }
        self.recompute_viewport(surface_id);
        Ok(self.get_or_create_session(surface_id).get_full_snapshot(0))
    }

    async fn clear_viewport_report(
        &self,
        surface_id: &str,
        client_id: &str,
        _generation: u64,
    ) -> Result<(), CmuxError> {
        {
            let mut reports = self.viewport_reports.lock();
            if let Some(by_client) = reports.get_mut(surface_id) {
                by_client.remove(client_id);
                if by_client.is_empty() {
                    reports.remove(surface_id);
                }
            }
        }
        self.recompute_viewport(surface_id);
        Ok(())
    }

    async fn spawn_events_stream(&self) -> Result<CmuxEventStream, CmuxError> {
        let (_tx, rx) = mpsc::unbounded_channel::<Value>();
        Ok(CmuxEventStream::mock(rx))
    }
}

use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::collections::HashMap;

use crate::health::ProtocolError;

pub const RENDER_GRID_FORMAT_V1: &str = "cmux.render-grid.v1";
pub const DEFAULT_TERMINAL_BG: &str = "#1E1E1E";
pub const DEFAULT_TERMINAL_FG: &str = "#D4D4D4";

fn default_render_grid_format() -> String {
    RENDER_GRID_FORMAT_V1.to_string()
}

fn default_cursor_style() -> String {
    "block".to_string()
}

fn default_active_screen() -> String {
    "primary".to_string()
}

fn default_terminal_bg() -> String {
    DEFAULT_TERMINAL_BG.to_string()
}

fn default_terminal_fg() -> String {
    DEFAULT_TERMINAL_FG.to_string()
}

fn default_true() -> bool {
    true
}

// ---------------------------------------------------------------------------
// Render Models
// ---------------------------------------------------------------------------

/// Terminal cursor state.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Cursor {
    pub row: usize,
    pub column: usize,
    #[serde(default = "default_true")]
    pub visible: bool,
    #[serde(default = "default_cursor_style")]
    pub style: String,
    #[serde(default)]
    pub blinking: bool,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl Default for Cursor {
    fn default() -> Self {
        Self {
            row: 0,
            column: 0,
            visible: true,
            style: default_cursor_style(),
            blinking: false,
            extra: Map::new(),
        }
    }
}

impl Cursor {
    pub fn new(row: usize, column: usize) -> Self {
        Self {
            row,
            column,
            visible: true,
            style: default_cursor_style(),
            blinking: false,
            extra: Map::new(),
        }
    }
}

/// Text style/color attributes for spans.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Style {
    pub id: usize,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub foreground: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub background: Option<String>,
    #[serde(default)]
    pub bold: bool,
    #[serde(default)]
    pub italic: bool,
    #[serde(default)]
    pub underline: bool,
    #[serde(default)]
    pub inverse: bool,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl Default for Style {
    fn default() -> Self {
        Self {
            id: 0,
            foreground: Some(DEFAULT_TERMINAL_FG.to_string()),
            background: Some(DEFAULT_TERMINAL_BG.to_string()),
            bold: false,
            italic: false,
            underline: false,
            inverse: false,
            extra: Map::new(),
        }
    }
}

impl Style {
    pub fn new(id: usize, fg: Option<String>, bg: Option<String>, bold: bool) -> Self {
        Self {
            id,
            foreground: fg,
            background: bg,
            bold,
            italic: false,
            underline: false,
            inverse: false,
            extra: Map::new(),
        }
    }
}

/// A span of styled text at a specific grid row and column.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RowSpan {
    pub row: usize,
    pub column: usize,
    #[serde(default)]
    pub style_id: usize,
    pub text: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cell_width: Option<usize>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl RowSpan {
    pub fn new(row: usize, column: usize, style_id: usize, text: impl Into<String>) -> Self {
        let text_str = text.into();
        let width = display_cell_width(&text_str);
        Self {
            row,
            column,
            style_id,
            text: text_str,
            cell_width: Some(width),
            extra: Map::new(),
        }
    }

    pub fn with_width(
        row: usize,
        column: usize,
        style_id: usize,
        text: impl Into<String>,
        cell_width: usize,
    ) -> Self {
        Self {
            row,
            column,
            style_id,
            text: text.into(),
            cell_width: Some(cell_width),
            extra: Map::new(),
        }
    }
}

/// Authoritative terminal render grid frame matching format `cmux.render-grid.v1`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RenderGridFrame {
    #[serde(default = "default_render_grid_format")]
    pub format: String,
    pub surface_id: String,
    pub state_seq: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub render_epoch: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub render_revision: Option<u64>,
    pub columns: usize,
    pub rows: usize,
    #[serde(default)]
    pub full: bool,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub cleared_rows: Vec<usize>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cursor: Option<Cursor>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub styles: Vec<Style>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub row_spans: Vec<RowSpan>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub active_screen: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub history_rows: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub row_space_revision: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub scrollback_rows: Option<usize>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub scrollback_spans: Option<Vec<RowSpan>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub terminal_background: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub terminal_foreground: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl RenderGridFrame {
    pub fn new(surface_id: impl Into<String>, state_seq: u64, columns: usize, rows: usize) -> Self {
        Self {
            format: default_render_grid_format(),
            surface_id: surface_id.into(),
            state_seq,
            render_epoch: None,
            render_revision: Some(state_seq),
            columns: columns.max(1),
            rows: rows.max(1),
            full: true,
            cleared_rows: Vec::new(),
            cursor: Some(Cursor::default()),
            styles: vec![Style::default()],
            row_spans: Vec::new(),
            active_screen: Some(default_active_screen()),
            history_rows: None,
            row_space_revision: None,
            scrollback_rows: None,
            scrollback_spans: None,
            terminal_background: Some(default_terminal_bg()),
            terminal_foreground: Some(default_terminal_fg()),
            extra: Map::new(),
        }
    }
}

// ---------------------------------------------------------------------------
// Display Cell Width
// ---------------------------------------------------------------------------

/// Computes the terminal cell display width for a single unicode character.
/// Wide glyphs (CJK, emoji, wide symbols) occupy 2 cells; control/zero-width chars occupy 0.
pub fn char_cell_width(c: char) -> usize {
    if c == '\n' || c == '\r' {
        return 0;
    }

    let code = c as u32;

    // Zero-width control and combining characters
    if (0x0000..=0x001F).contains(&code)
        || (0x007F..=0x009F).contains(&code)
        || (0x0300..=0x036F).contains(&code)
        || (0xFE00..=0xFE0F).contains(&code)
        || (0xE0100..=0xE01EF).contains(&code)
        || (0x200B..=0x200D).contains(&code)
        || code == 0xFEFF
    {
        return 0;
    }

    // CJK and common wide glyph ranges (East Asian Wide / Fullwidth)
    if (0x1100..=0x115F).contains(&code)
        || (0x2E80..=0xA4CF).contains(&code)
        || (0xAC00..=0xD7A3).contains(&code)
        || (0xF900..=0xFAFF).contains(&code)
        || (0xFE10..=0xFE19).contains(&code)
        || (0xFE30..=0xFE6F).contains(&code)
        || (0xFF01..=0xFF60).contains(&code)
        || (0xFFE0..=0xFFE6).contains(&code)
        || (0x1F300..=0x1F64F).contains(&code)
        || (0x1F680..=0x1F6FF).contains(&code)
        || (0x1F900..=0x1F9FF).contains(&code)
        || (0x1FA70..=0x1FAFF).contains(&code)
        || (0x20000..=0x2FA1F).contains(&code)
        || (0x30000..=0x3134F).contains(&code)
    {
        2
    } else {
        1
    }
}

/// Approximate terminal cell width of a string.
pub fn display_cell_width(text: &str) -> usize {
    text.chars().map(char_cell_width).sum()
}

// ---------------------------------------------------------------------------
// Normalization
// ---------------------------------------------------------------------------

/// Convert a cmux `terminal.replay` payload into a slim `cmux.render-grid.v1` frame.
/// Drops scrollback/modes by default so the Android client receives the authoritative viewport.
/// When `include_scrollback` is true, attaches history_rows, row_space_revision, scrollback_rows,
/// and scrollback_spans.
pub fn normalize_official_replay(
    payload: &Value,
    surface_id: &str,
    state_seq: u64,
    include_scrollback: bool,
) -> Result<RenderGridFrame, ProtocolError> {
    let payload_obj = payload.as_object().ok_or_else(|| {
        ProtocolError::Normalization("terminal.replay payload must be an object".to_string())
    })?;

    let grid = match payload_obj.get("render_grid") {
        Some(Value::Object(map)) => map,
        _ => payload_obj,
    };

    // 1. Process row_spans and compute max span end
    let mut row_spans: Vec<RowSpan> = Vec::new();
    let mut max_span_end = 0usize;

    if let Some(Value::Array(spans_arr)) = grid.get("row_spans") {
        for span_val in spans_arr {
            if let Some(span_obj) = span_val.as_object() {
                let row = span_obj.get("row").and_then(Value::as_u64).unwrap_or(0) as usize;
                let column = span_obj.get("column").and_then(Value::as_u64).unwrap_or(0) as usize;
                let style_id = span_obj
                    .get("style_id")
                    .and_then(Value::as_u64)
                    .unwrap_or(0) as usize;
                let text = span_obj
                    .get("text")
                    .and_then(Value::as_str)
                    .unwrap_or("")
                    .to_string();
                let cell_width = span_obj
                    .get("cell_width")
                    .and_then(Value::as_u64)
                    .map(|w| w as usize)
                    .unwrap_or_else(|| display_cell_width(&text));

                max_span_end = max_span_end.max(column + cell_width);

                let mut extra = span_obj.clone();
                extra.remove("row");
                extra.remove("column");
                extra.remove("style_id");
                extra.remove("text");
                extra.remove("cell_width");

                row_spans.push(RowSpan {
                    row,
                    column,
                    style_id,
                    text,
                    cell_width: Some(cell_width),
                    extra,
                });
            }
        }
    }

    // 2. Compute columns and rows
    let raw_cols = grid
        .get("columns")
        .or_else(|| payload_obj.get("columns"))
        .and_then(Value::as_u64)
        .unwrap_or(80) as usize;

    let raw_rows = grid
        .get("rows")
        .or_else(|| payload_obj.get("rows"))
        .and_then(Value::as_u64)
        .unwrap_or(24) as usize;

    let columns = raw_cols.max(max_span_end).max(1);
    let rows = raw_rows.max(1);

    // 3. Epoch and revision
    let render_epoch = grid
        .get("render_epoch")
        .or_else(|| payload_obj.get("render_epoch"))
        .and_then(Value::as_str)
        .map(ToString::to_string);

    let render_revision = grid
        .get("render_revision")
        .and_then(Value::as_u64)
        .or(Some(state_seq));

    // 4. Cleared rows
    let cleared_rows = grid
        .get("cleared_rows")
        .and_then(Value::as_array)
        .map(|arr| {
            arr.iter()
                .filter_map(Value::as_u64)
                .map(|r| r as usize)
                .collect()
        })
        .unwrap_or_default();

    // 5. Cursor
    let cursor = if let Some(Value::Object(c)) = grid.get("cursor") {
        let row = c.get("row").and_then(Value::as_u64).unwrap_or(0) as usize;
        let column = c.get("column").and_then(Value::as_u64).unwrap_or(0) as usize;
        let visible = c.get("visible").and_then(Value::as_bool).unwrap_or(true);
        let style = c
            .get("style")
            .and_then(Value::as_str)
            .unwrap_or("block")
            .to_string();
        let blinking = c.get("blinking").and_then(Value::as_bool).unwrap_or(false);

        let mut extra = c.clone();
        extra.remove("row");
        extra.remove("column");
        extra.remove("visible");
        extra.remove("style");
        extra.remove("blinking");

        Cursor {
            row,
            column,
            visible,
            style,
            blinking,
            extra,
        }
    } else {
        Cursor::default()
    };

    // 6. Styles
    let mut styles: Vec<Style> = Vec::new();
    if let Some(Value::Array(styles_arr)) = grid.get("styles") {
        for s_val in styles_arr {
            if let Some(s_obj) = s_val.as_object() {
                let id = s_obj.get("id").and_then(Value::as_u64).unwrap_or(0) as usize;
                let foreground = s_obj
                    .get("foreground")
                    .and_then(Value::as_str)
                    .map(ToString::to_string);
                let background = s_obj
                    .get("background")
                    .and_then(Value::as_str)
                    .map(ToString::to_string);
                let bold = s_obj.get("bold").and_then(Value::as_bool).unwrap_or(false);
                let italic = s_obj
                    .get("italic")
                    .and_then(Value::as_bool)
                    .unwrap_or(false);
                let underline = s_obj
                    .get("underline")
                    .and_then(Value::as_bool)
                    .unwrap_or(false);
                let inverse = s_obj
                    .get("inverse")
                    .and_then(Value::as_bool)
                    .unwrap_or(false);

                let mut extra = s_obj.clone();
                extra.remove("id");
                extra.remove("foreground");
                extra.remove("background");
                extra.remove("bold");
                extra.remove("italic");
                extra.remove("underline");
                extra.remove("inverse");

                styles.push(Style {
                    id,
                    foreground,
                    background,
                    bold,
                    italic,
                    underline,
                    inverse,
                    extra,
                });
            }
        }
    }
    if styles.is_empty() {
        styles.push(Style::default());
    }

    // 7. Active screen and terminal colors
    let active_screen = grid
        .get("active_screen")
        .and_then(Value::as_str)
        .map(ToString::to_string)
        .or_else(|| Some(default_active_screen()));

    let terminal_background = grid
        .get("terminal_background")
        .and_then(Value::as_str)
        .map(ToString::to_string)
        .or_else(|| Some(default_terminal_bg()));

    let terminal_foreground = grid
        .get("terminal_foreground")
        .and_then(Value::as_str)
        .map(ToString::to_string)
        .or_else(|| Some(default_terminal_fg()));

    // 8. History metadata
    let history_rows = grid
        .get("history_rows")
        .or_else(|| payload_obj.get("history_rows"))
        .and_then(Value::as_u64);

    let row_space_revision = grid
        .get("row_space_revision")
        .or_else(|| payload_obj.get("row_space_revision"))
        .and_then(Value::as_u64);

    // 9. Scrollback (only if explicitly included)
    let mut scrollback_spans: Option<Vec<RowSpan>> = None;
    let mut scrollback_rows: Option<usize> = None;

    if include_scrollback {
        let raw_sb_spans = grid
            .get("scrollback_spans")
            .or_else(|| payload_obj.get("scrollback_spans"));

        if let Some(Value::Array(sb_arr)) = raw_sb_spans {
            let mut spans = Vec::new();
            for s_val in sb_arr {
                if let Some(s_obj) = s_val.as_object() {
                    let row = s_obj.get("row").and_then(Value::as_u64).unwrap_or(0) as usize;
                    let column = s_obj.get("column").and_then(Value::as_u64).unwrap_or(0) as usize;
                    let style_id =
                        s_obj.get("style_id").and_then(Value::as_u64).unwrap_or(0) as usize;
                    let text = s_obj
                        .get("text")
                        .and_then(Value::as_str)
                        .unwrap_or("")
                        .to_string();
                    let cell_width = s_obj
                        .get("cell_width")
                        .and_then(Value::as_u64)
                        .map(|w| w as usize)
                        .unwrap_or_else(|| display_cell_width(&text));

                    let mut extra = s_obj.clone();
                    extra.remove("row");
                    extra.remove("column");
                    extra.remove("style_id");
                    extra.remove("text");
                    extra.remove("cell_width");

                    spans.push(RowSpan {
                        row,
                        column,
                        style_id,
                        text,
                        cell_width: Some(cell_width),
                        extra,
                    });
                }
            }
            scrollback_spans = Some(spans);
        }

        let raw_sb_rows = grid
            .get("scrollback_rows")
            .or_else(|| payload_obj.get("scrollback_rows"))
            .and_then(Value::as_u64)
            .map(|r| r as usize);

        if let Some(r) = raw_sb_rows {
            scrollback_rows = Some(r);
        } else if let Some(spans) = &scrollback_spans {
            let max_r = spans.iter().map(|s| s.row).max();
            scrollback_rows = Some(max_r.map(|m| m + 1).unwrap_or(0));
        }
    }

    // 10. Lossless unknown field retention
    let mut extra = grid.clone();
    let known_keys = [
        "format",
        "surface_id",
        "state_seq",
        "render_epoch",
        "render_revision",
        "columns",
        "rows",
        "full",
        "cleared_rows",
        "cursor",
        "styles",
        "row_spans",
        "active_screen",
        "history_rows",
        "row_space_revision",
        "scrollback_rows",
        "scrollback_spans",
        "terminal_background",
        "terminal_foreground",
        "modes", // Spec explicitly drops modes on slim frames
    ];
    for key in known_keys {
        extra.remove(key);
    }

    Ok(RenderGridFrame {
        format: default_render_grid_format(),
        surface_id: surface_id.to_string(),
        state_seq,
        render_epoch,
        render_revision,
        columns,
        rows,
        full: true,
        cleared_rows,
        cursor: Some(cursor),
        styles,
        row_spans,
        active_screen,
        history_rows,
        row_space_revision,
        scrollback_rows,
        scrollback_spans,
        terminal_background,
        terminal_foreground,
        extra,
    })
}

// ---------------------------------------------------------------------------
// ANSI Fallback Parser
// ---------------------------------------------------------------------------

pub const ANSI_COLORS: &[(u32, &str)] = &[
    (30, "#1E1E1E"),
    (31, "#FF5252"),
    (32, "#00FF7F"),
    (33, "#FFD600"),
    (34, "#40C4FF"),
    (35, "#E040FB"),
    (36, "#00E5FF"),
    (37, "#D4D4D4"),
    (90, "#888888"),
    (91, "#FF8A80"),
    (92, "#B9F6CA"),
    (93, "#FFE57F"),
    (94, "#80D8FF"),
    (95, "#EA80FC"),
    (96, "#84FFFF"),
    (97, "#FFFFFF"),
];

pub const ANSI_BG_COLORS: &[(u32, &str)] = &[
    (40, "#1E1E1E"),
    (41, "#B71C1C"),
    (42, "#1B5E20"),
    (43, "#F57F17"),
    (44, "#0D47A1"),
    (45, "#4A148C"),
    (46, "#006064"),
    (47, "#CCCCCC"),
    (100, "#424242"),
    (101, "#D32F2F"),
    (102, "#388E3C"),
    (103, "#FBC02D"),
    (104, "#1976D2"),
    (105, "#7B1FA2"),
    (106, "#0097A7"),
    (107, "#EEEEEE"),
];

fn lookup_ansi_fg(code: u32) -> Option<&'static str> {
    ANSI_COLORS
        .iter()
        .find(|(c, _)| *c == code)
        .map(|(_, col)| *col)
}

fn lookup_ansi_bg(code: u32) -> Option<&'static str> {
    ANSI_BG_COLORS
        .iter()
        .find(|(c, _)| *c == code)
        .map(|(_, col)| *col)
}

/// Parsed ANSI span containing text segment and color/style properties.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AnsiSpan {
    pub char_offset: usize,
    pub char_len: usize,
    pub foreground: Option<String>,
    pub background: Option<String>,
    pub bold: bool,
}

/// Parses a line containing ANSI SGR escape sequences into clean text and styled spans.
/// Handles 16 standard ANSI colors, 24-bit truecolor (38;2;r;g;b / 48;2;r;g;b), bold, and reset.
/// UTF-8 safe across all char boundaries.
pub fn parse_ansi_line(line: &str) -> (String, Vec<AnsiSpan>) {
    let mut clean_text = String::new();
    let mut spans = Vec::new();

    let mut current_fg: Option<String> = None;
    let mut current_bg: Option<String> = None;
    let mut current_bold = false;

    let mut chars = line.chars().peekable();
    let mut current_col = 0usize;

    while let Some(c) = chars.next() {
        if c == '\x1b' && chars.peek() == Some(&'[') {
            chars.next(); // Consume '['
            let mut seq = String::new();
            while let Some(&next_c) = chars.peek() {
                if next_c == 'm' {
                    chars.next();
                    break;
                }
                if next_c.is_ascii_digit() || next_c == ';' {
                    seq.push(chars.next().unwrap());
                } else {
                    // Unknown escape sequence delimiter, break
                    break;
                }
            }

            // Parse SGR codes
            if seq.is_empty() || seq == "0" {
                current_fg = None;
                current_bg = None;
                current_bold = false;
            } else {
                let codes: Vec<u32> = seq
                    .split(';')
                    .filter_map(|s| s.parse::<u32>().ok())
                    .collect();

                let mut idx = 0;
                while idx < codes.len() {
                    let code = codes[idx];
                    match code {
                        0 => {
                            current_fg = None;
                            current_bg = None;
                            current_bold = false;
                        }
                        1 => {
                            current_bold = true;
                        }
                        22 => {
                            current_bold = false;
                        }
                        39 => {
                            current_fg = None;
                        }
                        49 => {
                            current_bg = None;
                        }
                        38 if idx + 4 < codes.len() && codes[idx + 1] == 2 => {
                            let (r, g, b) = (codes[idx + 2], codes[idx + 3], codes[idx + 4]);
                            current_fg = Some(format!("#{r:02X}{g:02X}{b:02X}"));
                            idx += 4;
                        }
                        48 if idx + 4 < codes.len() && codes[idx + 1] == 2 => {
                            let (r, g, b) = (codes[idx + 2], codes[idx + 3], codes[idx + 4]);
                            current_bg = Some(format!("#{r:02X}{g:02X}{b:02X}"));
                            idx += 4;
                        }
                        c => {
                            if let Some(col) = lookup_ansi_fg(c) {
                                current_fg = Some(col.to_string());
                            } else if let Some(col) = lookup_ansi_bg(c) {
                                current_bg = Some(col.to_string());
                            }
                        }
                    }
                    idx += 1;
                }
            }
        } else {
            // Regular text segment
            let mut segment = String::new();
            segment.push(c);
            while let Some(&next_c) = chars.peek() {
                if next_c == '\x1b' {
                    break;
                }
                segment.push(chars.next().unwrap());
            }

            let char_len = segment.chars().count();
            clean_text.push_str(&segment);
            spans.push(AnsiSpan {
                char_offset: current_col,
                char_len,
                foreground: current_fg
                    .clone()
                    .or_else(|| Some(DEFAULT_TERMINAL_FG.to_string())),
                background: current_bg
                    .clone()
                    .or_else(|| Some(DEFAULT_TERMINAL_BG.to_string())),
                bold: current_bold,
            });
            current_col += char_len;
        }
    }

    // Powerline / prompt fallback styling when no ANSI codes present
    if spans.is_empty() && !clean_text.is_empty() {
        let stripped = clean_text.trim();
        let (fg, bold) =
            if stripped.starts_with('❯') || stripped.starts_with('$') || stripped.starts_with('➜')
            {
                ("#00FF7F", true)
            } else if stripped.starts_with("###")
                || stripped.starts_with("==")
                || stripped.starts_with("---")
            {
                ("#00E5FF", true)
            } else if stripped.to_lowercase().contains("error")
                || stripped.to_lowercase().contains("failed")
            {
                ("#FF5252", false)
            } else if stripped.to_lowercase().contains("success")
                || stripped.to_lowercase().contains("connected")
            {
                ("#69F0AE", false)
            } else {
                (DEFAULT_TERMINAL_FG, false)
            };

        spans.push(AnsiSpan {
            char_offset: 0,
            char_len: clean_text.chars().count(),
            foreground: Some(fg.to_string()),
            background: Some(DEFAULT_TERMINAL_BG.to_string()),
            bold,
        });
    }

    (clean_text, spans)
}

/// Fallback screen converter: turns lines of terminal text (e.g. from `cmux read-screen`)
/// into a full `RenderGridFrame`.
pub fn ansi_lines_to_render_grid(
    raw_lines: &[&str],
    surface_id: &str,
    state_seq: u64,
    render_epoch: Option<&str>,
) -> RenderGridFrame {
    let mut styles: Vec<Style> = Vec::new();
    let mut style_map: HashMap<(Option<String>, Option<String>, bool), usize> = HashMap::new();
    let mut row_spans: Vec<RowSpan> = Vec::new();
    let mut max_detected_cols = 80usize;

    let mut get_style_id = |fg: Option<String>, bg: Option<String>, bold: bool| -> usize {
        let key = (fg.clone(), bg.clone(), bold);
        if let Some(&id) = style_map.get(&key) {
            return id;
        }
        let id = styles.len();
        styles.push(Style {
            id,
            foreground: fg,
            background: bg,
            bold,
            italic: false,
            underline: false,
            inverse: false,
            extra: Map::new(),
        });
        style_map.insert(key, id);
        id
    };

    for (r_idx, raw_line) in raw_lines.iter().enumerate() {
        let (clean_text, spans) = parse_ansi_line(raw_line);
        let line_width = display_cell_width(&clean_text);
        max_detected_cols = max_detected_cols.max(line_width);

        if spans.is_empty() {
            continue;
        }

        let clean_chars: Vec<char> = clean_text.chars().collect();

        for span in spans {
            let segment: String = clean_chars
                .iter()
                .skip(span.char_offset)
                .take(span.char_len)
                .collect();

            let cell_width = display_cell_width(&segment);
            let s_id = get_style_id(span.foreground, span.background, span.bold);

            let char_prefix: String = clean_chars.iter().take(span.char_offset).collect();
            let start_column = display_cell_width(&char_prefix);

            row_spans.push(RowSpan {
                row: r_idx,
                column: start_column,
                style_id: s_id,
                text: segment,
                cell_width: Some(cell_width),
                extra: Map::new(),
            });
            max_detected_cols = max_detected_cols.max(start_column + cell_width);
        }
    }

    if styles.is_empty() {
        styles.push(Style::default());
    }

    let rows = raw_lines.len().max(24);
    let columns = max_detected_cols.max(80);

    RenderGridFrame {
        format: default_render_grid_format(),
        surface_id: surface_id.to_string(),
        state_seq,
        render_epoch: render_epoch.map(ToString::to_string),
        render_revision: Some(state_seq),
        columns,
        rows,
        full: true,
        cleared_rows: Vec::new(),
        cursor: Some(Cursor {
            row: raw_lines.len().saturating_sub(1),
            column: 0,
            visible: true,
            style: default_cursor_style(),
            blinking: false,
            extra: Map::new(),
        }),
        styles,
        row_spans,
        active_screen: Some(default_active_screen()),
        history_rows: None,
        row_space_revision: None,
        scrollback_rows: None,
        scrollback_spans: None,
        terminal_background: Some(default_terminal_bg()),
        terminal_foreground: Some(default_terminal_fg()),
        extra: Map::new(),
    }
}

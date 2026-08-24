use cmux_pocket_protocol::*;
use serde_json::json;

#[test]
fn test_display_cell_width() {
    assert_eq!(display_cell_width("abc"), 3);
    assert_eq!(display_cell_width("中文"), 4);
    assert_eq!(display_cell_width("a中b"), 4);
    assert_eq!(display_cell_width("hello\nworld\r"), 10);
    assert_eq!(display_cell_width("🚀"), 2);
    assert_eq!(display_cell_width("🚀🎉"), 4);
    assert_eq!(display_cell_width("【】"), 4);
}

#[test]
fn test_uses_official_columns_and_strips_scrollback() {
    let payload = json!({
        "seq": 9,
        "columns": 145,
        "rows": 46,
        "surface_id": "UUID-SHOULD-NOT-LEAK",
        "render_grid": {
            "format": "cmux.render-grid.v1",
            "columns": 145,
            "rows": 46,
            "full": true,
            "state_seq": 0,
            "render_epoch": "epoch-live",
            "render_revision": 12,
            "cursor": {"row": 45, "column": 3, "visible": true, "style": "block", "blinking": true},
            "styles": [{"id": 0, "foreground": "#000", "background": "#FFF"}],
            "row_spans": [
                {"row": 0, "column": 0, "style_id": 0, "text": "hello", "cell_width": 5},
                {"row": 0, "column": 140, "style_id": 0, "text": "end", "cell_width": 3}
            ],
            "scrollback_spans": [{"row": 0, "text": "should-not-pass"}],
            "modes": [{"code": 7, "on": true}],
            "terminal_background": "#FEFFFF",
            "terminal_foreground": "#000000"
        }
    });

    let frame = normalize_official_replay(&payload, "surface:170", 4, false).unwrap();
    assert_eq!(frame.format, "cmux.render-grid.v1");
    assert_eq!(frame.surface_id, "surface:170");
    assert_eq!(frame.columns, 145);
    assert_eq!(frame.rows, 46);
    assert_eq!(frame.state_seq, 4);
    assert!(frame.full);
    assert_eq!(frame.row_spans.len(), 2);
    assert!(frame.scrollback_spans.is_none());
    assert!(frame.scrollback_rows.is_none());
    assert_eq!(frame.terminal_background.as_deref(), Some("#FEFFFF"));
    assert_eq!(frame.terminal_foreground.as_deref(), Some("#000000"));
}

#[test]
fn test_poll_retains_history_metadata_and_excludes_spans() {
    let payload = json!({
        "render_grid": {
            "columns": 80,
            "rows": 24,
            "row_spans": [{"row": 0, "column": 0, "style_id": 0, "text": "visible", "cell_width": 7}],
            "scrollback_spans": [{"row": 0, "text": "older-line"}],
            "scrollback_rows": 1,
            "history_rows": 1500,
            "row_space_revision": 9
        }
    });

    let frame = normalize_official_replay(&payload, "surface:170", 5, false).unwrap();
    assert_eq!(frame.surface_id, "surface:170");
    assert_eq!(frame.history_rows, Some(1500));
    assert_eq!(frame.row_space_revision, Some(9));
    assert!(frame.scrollback_rows.is_none());
    assert!(frame.scrollback_spans.is_none());
}

#[test]
fn test_includes_scrollback_when_explicitly_requested() {
    let payload = json!({
        "render_grid": {
            "columns": 80,
            "rows": 24,
            "row_spans": [{"row": 0, "column": 0, "style_id": 0, "text": "visible", "cell_width": 7}],
            "scrollback_spans": [
                {"row": 0, "column": 0, "style_id": 0, "text": "older-line"},
                {"row": 1, "column": 0, "style_id": 0, "text": "oldest-line", "cell_width": 11}
            ],
            "scrollback_rows": 2,
            "history_rows": 1200,
            "row_space_revision": 7
        }
    });

    let frame = normalize_official_replay(&payload, "surface:170", 5, true).unwrap();
    assert_eq!(frame.surface_id, "surface:170");
    assert_eq!(frame.history_rows, Some(1200));
    assert_eq!(frame.row_space_revision, Some(7));
    assert_eq!(frame.scrollback_rows, Some(2));
    let sb_spans = frame.scrollback_spans.as_ref().unwrap();
    assert_eq!(sb_spans.len(), 2);
    assert_eq!(sb_spans[0].text, "older-line");
    assert_eq!(sb_spans[0].cell_width, Some(10));
    assert_eq!(sb_spans[1].cell_width, Some(11));
}

#[test]
fn test_derives_scrollback_rows_from_max_span_row_when_absent() {
    let payload = json!({
        "render_grid": {
            "columns": 80,
            "rows": 24,
            "row_spans": [{"row": 0, "column": 0, "style_id": 0, "text": "visible", "cell_width": 7}],
            "scrollback_spans": [
                {"row": 0, "column": 0, "style_id": 0, "text": "first-older"},
                {"row": 4, "column": 0, "style_id": 0, "text": "fifth-older", "cell_width": 11}
            ],
            "history_rows": 1200,
            "row_space_revision": 7
        }
    });

    let frame = normalize_official_replay(&payload, "surface:170", 5, true).unwrap();
    assert_eq!(frame.surface_id, "surface:170");
    assert_eq!(frame.scrollback_rows, Some(5));
    assert_eq!(frame.scrollback_spans.as_ref().unwrap().len(), 2);
}

#[test]
fn test_widens_columns_to_span_end() {
    let payload = json!({
        "render_grid": {
            "columns": 80,
            "rows": 10,
            "row_spans": [
                {"row": 0, "column": 90, "style_id": 0, "text": "right-edge", "cell_width": 10}
            ]
        }
    });

    let frame = normalize_official_replay(&payload, "surface:1", 1, false).unwrap();
    assert_eq!(frame.columns, 100);
}

#[test]
fn test_preserves_unknown_render_fields() {
    let payload = json!({
        "render_grid": {
            "columns": 80,
            "rows": 24,
            "custom_backend_gpu_texture": "tex_1234",
            "future_flag_v3": true,
            "row_spans": [
                {
                    "row": 0,
                    "column": 0,
                    "style_id": 0,
                    "text": "test",
                    "span_extra_meta": 42
                }
            ]
        }
    });

    let frame = normalize_official_replay(&payload, "surface:1", 1, false).unwrap();
    assert_eq!(
        frame.extra.get("custom_backend_gpu_texture").unwrap(),
        "tex_1234"
    );
    assert_eq!(frame.extra.get("future_flag_v3").unwrap(), true);
    assert_eq!(frame.row_spans[0].extra.get("span_extra_meta").unwrap(), 42);

    // Serialization round-trip preserves the extra fields
    let serialized = serde_json::to_value(&frame).unwrap();
    assert_eq!(serialized["custom_backend_gpu_texture"], "tex_1234");
    assert_eq!(serialized["future_flag_v3"], true);
}

#[test]
fn test_ansi_parsing_and_truecolor() {
    // 1. Standard 16-color & bold
    let raw = "\x1b[31;1mError:\x1b[0m \x1b[32mSuccess\x1b[0m";
    let (clean, spans) = parse_ansi_line(raw);
    assert_eq!(clean, "Error: Success");
    assert_eq!(spans.len(), 3);
    assert_eq!(spans[0].foreground.as_deref(), Some("#FF5252"));
    assert!(spans[0].bold);
    assert_eq!(spans[2].foreground.as_deref(), Some("#00FF7F"));
    assert!(!spans[2].bold);

    // 2. 24-bit Truecolor
    let truecolor_raw = "\x1b[38;2;255;128;0mOrange Text\x1b[0m";
    let (tc_clean, tc_spans) = parse_ansi_line(truecolor_raw);
    assert_eq!(tc_clean, "Orange Text");
    assert_eq!(tc_spans[0].foreground.as_deref(), Some("#FF8000"));

    // 3. Screen lines to render grid
    let screen = [
        "\x1b[34m~/code\x1b[0m ❯ cargo build",
        "   Compiling cmux v0.1",
    ];
    let grid = ansi_lines_to_render_grid(&screen, "surface:1", 10, Some("epoch-1"));
    assert_eq!(grid.surface_id, "surface:1");
    assert_eq!(grid.state_seq, 10);
    assert_eq!(grid.render_epoch.as_deref(), Some("epoch-1"));
    assert!(grid.row_spans.len() >= 2);
}

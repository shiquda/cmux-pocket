#!/usr/bin/env python3
"""
cmux WebSocket Bridge Gateway v2
Bridges local cmux Unix Domain Socket / CLI to authenticated WebSocket over LAN / Tailscale.
Supports multi-workspace, multi-tab surface navigation, real-time JSON-RPC proxying,
and per-surface MobileTerminalRenderGrid event push.
"""

import asyncio
import ipaddress
import json
import logging
import os
import re
import shutil
import subprocess
import sys
import time
import threading
import unicodedata
import uuid
from typing import Dict, List, Set, Any, Optional, Tuple, Iterable, Callable


logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger("cmux-gateway")

DEFAULT_WS_HOST = "127.0.0.1"
DEFAULT_WS_PORT = 8088
DEFAULT_SOCKET_PATH = "/tmp/cmux.sock"

def parse_agent_completion_event(event: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """Normalize cmux agent turn completion events for mobile clients."""
    if event.get("type") != "event":
        return None

    payload = event.get("payload") or {}
    if not isinstance(payload, dict):
        return None
    agent = event.get("agent") or payload.get("agent") or {}
    if not isinstance(agent, dict):
        agent = {}
    hook_name = str(payload.get("hook_event_name") or "").lower()
    event_name = str(event.get("name") or "")
    category = str(agent.get("category") or payload.get("category") or "").lower()
    is_completion = (
        (event_name.startswith("agent.hook.") and hook_name in {"stop", "sessionend"})
        or category == "turn-complete"
    )
    if not is_completion:
        return None

    surface_id = event.get("surface_id") or payload.get("surface_id")
    if not surface_id:
        return None
    return {
        "event_id": event.get("id"),
        "workspace_id": event.get("workspace_id") or payload.get("workspace_id"),
        "surface_id": surface_id,
        "agent_kind": agent.get("kind") or payload.get("_source"),
        "category": "turn-complete",
    }

def notification_record_is_completion(record: str, notification_id: str) -> bool:
    """Match cmux's compact list-notifications record without forwarding text."""
    fields = record.rstrip("\n").split("|")
    if len(fields) < 8 or fields[0].split(":", 1)[-1] != notification_id:
        return False
    return fields[6].strip().casefold() in {"complete", "completed", "done"}

def is_loopback_bind_host(host: str) -> bool:
    normalized = host.strip().strip("[]")
    if normalized.lower() == "localhost":
        return True
    try:
        return ipaddress.ip_address(normalized).is_loopback
    except ValueError:
        return False


_SURFACE_ORDINAL_MAP: Dict[str, int] = {}
_CLIENT_ORDINAL_MAP: Dict[str, int] = {}
_ORDINAL_LOCK = threading.Lock()


def get_surface_ordinal(surface_id: Optional[str]) -> int:
    if not surface_id:
        return 0
    with _ORDINAL_LOCK:
        if surface_id not in _SURFACE_ORDINAL_MAP:
            _SURFACE_ORDINAL_MAP[surface_id] = len(_SURFACE_ORDINAL_MAP) + 1
        return _SURFACE_ORDINAL_MAP[surface_id]


def get_client_ordinal(session_id: Optional[str]) -> int:
    if not session_id:
        return 0
    with _ORDINAL_LOCK:
        if session_id not in _CLIENT_ORDINAL_MAP:
            _CLIENT_ORDINAL_MAP[session_id] = len(_CLIENT_ORDINAL_MAP) + 1
        return _CLIENT_ORDINAL_MAP[session_id]


def perf_trace(event: str, **kwargs: Any) -> None:
    if os.environ.get("CMUX_PERF_TRACE") != "1":
        return
    now_ms = time.monotonic() * 1000.0
    items = [f"ts_ms={now_ms:.3f}", f"event={event}"]
    for k, v in kwargs.items():
        if isinstance(v, bool):
            items.append(f"{k}={1 if v else 0}")
        elif isinstance(v, float):
            items.append(f"{k}={v:.3f}")
        elif isinstance(v, int):
            items.append(f"{k}={v}")
    logger.info("[PERF] " + " ".join(items))
def load_auth_token() -> str:
    token = os.environ.get("CMUX_AUTH_TOKEN", "").strip()
    if token:
        return token
    token_file = os.environ.get("CMUX_AUTH_TOKEN_FILE", "").strip()
    if token_file:
        with open(token_file, encoding="utf-8") as handle:
            token = handle.read().strip()
        if token:
            return token
    raise RuntimeError("Set CMUX_AUTH_TOKEN or CMUX_AUTH_TOKEN_FILE")

# ANSI 16-color map
ANSI_COLORS = {
    30: "#1E1E1E", 31: "#FF5252", 32: "#00FF7F", 33: "#FFD600",
    34: "#40C4FF", 35: "#E040FB", 36: "#00E5FF", 37: "#D4D4D4",
    90: "#888888", 91: "#FF8A80", 92: "#B9F6CA", 93: "#FFE57F",
    94: "#80D8FF", 95: "#EA80FC", 96: "#84FFFF", 97: "#FFFFFF"
}

ANSI_BG_COLORS = {
    40: "#1E1E1E", 41: "#B71C1C", 42: "#1B5E20", 43: "#F57F17",
    44: "#0D47A1", 45: "#4A148C", 46: "#006064", 47: "#CCCCCC",
    100: "#424242", 101: "#D32F2F", 102: "#388E3C", 103: "#FBC02D",
    104: "#1976D2", 105: "#7B1FA2", 106: "#0097A7", 107: "#EEEEEE"
}



def display_cell_width(text: str) -> int:
    """Approximate terminal cell width. CJK and common wide glyphs occupy 2 cells."""
    width = 0
    for ch in text:
        code = ord(ch)
        if ch in ("\n", "\r"):
            continue
        if unicodedata.east_asian_width(ch) in ("F", "W"):
            width += 2
        elif (
            0x1100 <= code <= 0x115F
            or 0x2E80 <= code <= 0xA4CF
            or 0xAC00 <= code <= 0xD7A3
            or 0xF900 <= code <= 0xFAFF
            or 0xFE10 <= code <= 0xFE19
            or 0xFE30 <= code <= 0xFE6F
            or 0xFF01 <= code <= 0xFF60
            or 0xFFE0 <= code <= 0xFFE6
            or 0x1F300 <= code <= 0x1F64F
            or 0x1F680 <= code <= 0x1F6FF
            or 0x20000 <= code <= 0x2FA1F
        ):
            width += 2
        else:
            width += 1
    return width


_CWD_CACHE: Dict[str, Tuple[float, Optional[str]]] = {}
_CWD_TTL_SEC = 10.0
_SHELL_NAMES = ("zsh", "bash", "fish", "nu", "sh")


def abbreviate_home(path: str) -> str:
    home = os.path.expanduser("~")
    if path == home:
        return "~"
    prefix = home + os.sep
    if path.startswith(prefix):
        return "~/" + path[len(prefix):]
    return path


def tty_cwd(tty: Optional[str]) -> Optional[str]:
    if not tty:
        return None
    now = time.time()
    cached = _CWD_CACHE.get(tty)
    if cached and now - cached[0] < _CWD_TTL_SEC:
        return cached[1]
    resolved = _resolve_tty_cwd(tty)
    _CWD_CACHE[tty] = (now, resolved)
    return resolved


def _resolve_tty_cwd(tty: str) -> Optional[str]:
    short = tty.replace("/dev/", "")
    try:
        ps = subprocess.run(
            ["ps", "-axo", "pid=,tty=,command="],
            capture_output=True,
            text=True,
            timeout=2.0,
        )
    except Exception:
        return None
    candidates: List[Tuple[int, int]] = []
    for raw in ps.stdout.splitlines():
        parts = raw.split(None, 2)
        if len(parts) < 3:
            continue
        pid_s, proc_tty, cmd = parts
        if proc_tty != short:
            continue
        lowered = cmd.lower()
        if not any(name in lowered for name in _SHELL_NAMES):
            continue
        try:
            pid = int(pid_s)
        except ValueError:
            continue
        rank = 0 if cmd.lstrip().startswith("-") else 1
        candidates.append((rank, pid))
    if not candidates:
        return None
    candidates.sort()
    pid = candidates[0][1]
    try:
        lsof = subprocess.run(
            ["/usr/sbin/lsof", "-a", "-p", str(pid), "-d", "cwd", "-Fn"],
            capture_output=True,
            text=True,
            timeout=2.0,
        )
    except Exception:
        return None
    for line in lsof.stdout.splitlines():
        if line.startswith("n") and len(line) > 1:
            return abbreviate_home(line[1:])
    return None


def workspace_tree_signature(workspaces: List[Dict[str, Any]]) -> str:
    payload = [
        (
            ws.get("id"),
            ws.get("name"),
            ws.get("cwd"),
            tuple((s.get("id"), s.get("title"), s.get("cwd")) for s in (ws.get("surfaces") or [])),
        )
        for ws in workspaces
    ]
    return json.dumps(payload, ensure_ascii=False)


def normalize_official_replay(
    payload: Dict[str, Any],
    surface_id: str,
    state_seq: int,
    include_scrollback: bool = False,
) -> Dict[str, Any]:
    """
    Convert a cmux `terminal.replay` payload into a slim cmux.render-grid.v1 frame.
    Drops scrollback/modes by default so the Android client receives the authoritative viewport.
    When include_scrollback is True, attaches optional history_rows, row_space_revision,
    scrollback_rows, and scrollback_spans.
    """
    grid = payload.get("render_grid") if isinstance(payload.get("render_grid"), dict) else payload
    if not isinstance(grid, dict):
        raise ValueError("terminal.replay payload missing render_grid")

    row_spans = list(grid.get("row_spans") or [])
    max_span_end = 0
    for span in row_spans:
        if not isinstance(span, dict):
            continue
        start = int(span.get("column") or 0)
        cell_width = span.get("cell_width")
        if cell_width is None:
            cell_width = display_cell_width(str(span.get("text") or ""))
        max_span_end = max(max_span_end, start + int(cell_width))

    columns = int(grid.get("columns") or payload.get("columns") or 80)
    rows = int(grid.get("rows") or payload.get("rows") or 24)
    columns = max(columns, max_span_end, 1)
    rows = max(rows, 1)

    frame: Dict[str, Any] = {
        "format": "cmux.render-grid.v1",
        "surface_id": surface_id,
        "state_seq": state_seq,
        "render_epoch": grid.get("render_epoch") or payload.get("render_epoch"),
        "render_revision": grid.get("render_revision") or state_seq,
        "columns": columns,
        "rows": rows,
        "full": True,
        "cleared_rows": grid.get("cleared_rows") or [],
        "cursor": grid.get("cursor") or {
            "row": 0,
            "column": 0,
            "visible": True,
            "style": "block",
            "blinking": False,
        },
        "styles": grid.get("styles") or [
            {"id": 0, "foreground": "#D4D4D4", "background": "#1E1E1E", "bold": False, "italic": False}
        ],
        "row_spans": row_spans,
        "active_screen": grid.get("active_screen") or "primary",
        "terminal_background": grid.get("terminal_background") or "#1E1E1E",
        "terminal_foreground": grid.get("terminal_foreground") or "#D4D4D4",
    }

    history_rows = grid.get("history_rows") if "history_rows" in grid else payload.get("history_rows")
    if history_rows is not None:
        frame["history_rows"] = int(history_rows)

    row_space_rev = grid.get("row_space_revision") if "row_space_revision" in grid else payload.get("row_space_revision")
    if row_space_rev is not None:
        frame["row_space_revision"] = int(row_space_rev)

    if include_scrollback:
        raw_sb_spans = grid.get("scrollback_spans") if "scrollback_spans" in grid else payload.get("scrollback_spans")
        if raw_sb_spans is not None:
            sb_spans = []
            for span in raw_sb_spans:
                if isinstance(span, dict):
                    span_copy = dict(span)
                    if span_copy.get("cell_width") is None:
                        span_copy["cell_width"] = display_cell_width(str(span_copy.get("text") or ""))
                    sb_spans.append(span_copy)
            frame["scrollback_spans"] = sb_spans

        raw_sb_rows = grid.get("scrollback_rows") if "scrollback_rows" in grid else payload.get("scrollback_rows")
        if raw_sb_rows is not None:
            frame["scrollback_rows"] = int(raw_sb_rows)
        elif raw_sb_spans is not None:
            sb_list = frame.get("scrollback_spans", [])
            if sb_list:
                max_row = max(int(s.get("row") or 0) for s in sb_list)
                frame["scrollback_rows"] = max(0, max_row + 1)
            else:
                frame["scrollback_rows"] = 0

    return frame


def parse_ansi_line(line: str) -> Tuple[str, List[Tuple[int, int, Dict[str, Any]]]]:
    """
    Parses a line containing ANSI escape sequences into clean text and styled spans.
    Returns (clean_text, spans) where span is (start_col, length, style_dict).
    """
    ansi_regex = re.compile(r'\x1b\[([0-9;]*)m')
    clean_chars = []
    spans = []

    current_fg = None
    current_bg = None
    current_bold = False

    last_idx = 0
    current_col = 0

    has_ansi = bool(ansi_regex.search(line))

    if has_ansi:
        for match in ansi_regex.finditer(line):
            text_segment = line[last_idx:match.start()]
            if text_segment:
                clean_chars.append(text_segment)
                span_len = len(text_segment)
                spans.append((current_col, span_len, {
                    "foreground": current_fg or "#D4D4D4",
                    "background": current_bg or "#1E1E1E",
                    "bold": current_bold
                }))
                current_col += span_len

            # Parse ANSI codes
            codes_str = match.group(1)
            if not codes_str or codes_str == "0":
                current_fg = None
                current_bg = None
                current_bold = False
            else:
                codes = [int(c) for c in codes_str.split(";") if c.isdigit()]
                idx = 0
                while idx < len(codes):
                    code = codes[idx]
                    if code == 0:
                        current_fg = None
                        current_bg = None
                        current_bold = False
                    elif code == 1:
                        current_bold = True
                    elif code in ANSI_COLORS:
                        current_fg = ANSI_COLORS[code]
                    elif code in ANSI_BG_COLORS:
                        current_bg = ANSI_BG_COLORS[code]
                    elif code == 38 and idx + 4 < len(codes) and codes[idx + 1] == 2:
                        r, g, b = codes[idx + 2], codes[idx + 3], codes[idx + 4]
                        current_fg = f"#{r:02X}{g:02X}{b:02X}"
                        idx += 4
                    elif code == 48 and idx + 4 < len(codes) and codes[idx + 1] == 2:
                        r, g, b = codes[idx + 2], codes[idx + 3], codes[idx + 4]
                        current_bg = f"#{r:02X}{g:02X}{b:02X}"
                        idx += 4
                    idx += 1

            last_idx = match.end()

        # Trailing segment
        trailing_text = line[last_idx:]
        if trailing_text:
            clean_chars.append(trailing_text)
            spans.append((current_col, len(trailing_text), {
                "foreground": current_fg or "#D4D4D4",
                "background": current_bg or "#1E1E1E",
                "bold": current_bold
            }))

        clean_text = "".join(clean_chars)
        return clean_text, spans

    clean_text = line

    # Powerline segment recognition
    if "" in clean_text or "" in clean_text or "╭──" in clean_text or "╰─" in clean_text:
        parts = re.split(r'(||╭──|╰─)', clean_text)
        col = 0
        for p in parts:
            if not p:
                continue
            fg = "#D4D4D4"
            bold = False
            if p in ("", ""):
                fg = "#666666"
            elif "" in p:
                fg = "#FFD600"
                bold = True
            elif "" in p or "/" in p:
                fg = "#00E5FF"
                bold = True
            elif "󰪣" in p or "Gemini" in p or "Claude" in p or "GPT" in p:
                fg = "#E040FB"
                bold = True
            elif "" in p or "main" in p or "master" in p:
                fg = "#00FF7F"
                bold = True
            elif "" in p or "%" in p or "󰁨" in p:
                fg = "#FFAB40"
            elif "╭──" in p or "╰─" in p:
                fg = "#888888"

            spans.append((col, len(p), {"foreground": fg, "background": "#1E1E1E", "bold": bold}))
            col += len(p)
        return clean_text, spans

    # Command prompt or syntax patterns
    if clean_text:
        stripped = clean_text.strip()
        fg = "#D4D4D4"
        bold = False
        if stripped.startswith("❯") or stripped.startswith("$") or stripped.startswith("➜"):
            fg = "#00FF7F"
            bold = True
        elif stripped.startswith("###") or stripped.startswith("==") or stripped.startswith("---"):
            fg = "#00E5FF"
            bold = True
        elif stripped.startswith("- ") or stripped.startswith("* "):
            fg = "#EEEEEE"
        elif "error" in stripped.lower() or "failed" in stripped.lower():
            fg = "#FF5252"
        elif "success" in stripped.lower() or "connected" in stripped.lower():
            fg = "#69F0AE"
        elif stripped.startswith("1.") or stripped.startswith("2.") or stripped.startswith("3."):
            fg = "#FFD54F"

        spans.append((0, len(clean_text), {
            "foreground": fg,
            "background": "#1E1E1E",
            "bold": bold
        }))

    return clean_text, spans

async def fanout_screen_snapshots(
    clients: Iterable[Any],
    fetch_snapshot: Callable[[str], Any],
    priority_surfaces: Optional[Set[str]] = None,
) -> Dict[str, Dict[str, Any]]:
    """
    For a set of clients, groups authenticated clients subscribed to
    'terminal.render_grid' by their active_surface_id and focus_generation,
    fetches each unique surface snapshot concurrently (independent execution,
    no all-surface barrier), and fans out the resulting frame to clients.

    Priority surfaces are scheduled first. Completed surfaces push frames
    immediately without waiting for other surfaces to finish.
    """
    surface_map: Dict[str, List[Tuple[Any, int]]] = {}
    for client in list(clients):
        if (
            getattr(client, "authenticated", False)
            and getattr(client, "active_surface_id", None)
            and "terminal.render_grid" in getattr(client, "subscribed_topics", set())
        ):
            focus_gen = getattr(client, "focus_generation", 0)
            surface_map.setdefault(client.active_surface_id, []).append((client, focus_gen))

    if not surface_map:
        return {}

    priority_set = set(priority_surfaces or [])
    sorted_surfaces = sorted(
        surface_map.keys(),
        key=lambda sid: (0 if sid in priority_set else 1)
    )

    completed_snapshots: Dict[str, Dict[str, Any]] = {}
    completed_lock = asyncio.Lock()

    async def _fetch_and_deliver(sid: str, target_clients: List[Tuple[Any, int]]):
        res = fetch_snapshot(sid)
        if asyncio.iscoroutine(res) or isinstance(res, asyncio.Future):
            snapshot = await res
        else:
            snapshot = res

        if not isinstance(snapshot, dict):
            logger.warning(f"Snapshot for surface {sid} is not a dict: {type(snapshot)}")
            return

        async with completed_lock:
            completed_snapshots[sid] = snapshot

        for client, focus_gen in target_clients:
            if hasattr(client, "enqueue_render_frame"):
                client.enqueue_render_frame(sid, focus_gen, snapshot)
            else:
                frame_msg = {
                    "event": "terminal.render_grid",
                    "data": snapshot,
                }
                res = client.send_json(frame_msg)
                if asyncio.iscoroutine(res) or isinstance(res, asyncio.Future):
                    await res

    tasks = [
        asyncio.create_task(_fetch_and_deliver(sid, surface_map[sid]))
        for sid in sorted_surfaces
    ]
    if tasks:
        await asyncio.gather(*tasks, return_exceptions=False)
    return completed_snapshots


class MockTerminalSession:
    """Simulates a live cmux terminal session with RenderGrid frames for testing."""
    def __init__(self, surface_id: str, title: str = "zsh", columns: int = 80, rows: int = 24):
        self.surface_id = surface_id
        self.title = title
        self.columns = columns
        self.rows = rows
        self.state_seq = 1
        self.render_epoch = f"epoch-{uuid.uuid4().hex[:8]}"
        self.render_revision = 1
        self.cursor_row = 1
        self.cursor_col = 2
        self.lines: List[str] = [
            f"=== cmux Terminal ({self.title} / {self.surface_id}) ===",
            "❯ "
        ] + ["" for _ in range(rows - 2)]

    def apply_input(self, text: str) -> None:
        self.state_seq += 1
        self.render_revision += 1

        has_enter = "\n" in text or "\r" in text
        raw_chars = text.replace("\r", "").replace("\n", "")

        if raw_chars:
            self.lines[self.cursor_row] += raw_chars
            self.cursor_col += len(raw_chars)

        if has_enter:
            current_line = self.lines[self.cursor_row]
            cmd = current_line[2:].strip()
            self.cursor_row += 1
            if self.cursor_row >= self.rows - 2:
                self.lines.pop(0)
                self.lines.append("")
                self.cursor_row = self.rows - 3

            output = ""
            if cmd == "help":
                output = "Available commands: status, tabs, workspaces, clear, ping, date"
            elif cmd == "status":
                output = f"Session {self.surface_id} healthy (title: {self.title})"
            elif cmd == "tabs":
                output = "Active surfaces: 3 terminal tabs attached"
            elif cmd == "clear":
                self.lines = ["❯ "] + ["" for _ in range(self.rows - 1)]
                self.cursor_row = 0
                self.cursor_col = 2
                return
            elif cmd == "ping":
                output = "pong! (cmux mobile bridge v2)"
            elif cmd:
                output = f"zsh: command not found: {cmd}"

            if output:
                self.lines[self.cursor_row] = output
                self.cursor_row += 1
                if self.cursor_row >= self.rows - 1:
                    self.cursor_row = self.rows - 2

            self.lines[self.cursor_row] = "❯ "
            self.cursor_col = 2

    def handle_input(self, text: str) -> Dict[str, Any]:
        old_cursor_row = self.cursor_row
        self.apply_input(text)
        cleared_rows = [old_cursor_row, self.cursor_row]
        row_spans = []
        for r in range(max(0, old_cursor_row - 1), min(self.rows, self.cursor_row + 1)):
            if r < len(self.lines) and self.lines[r]:
                row_spans.append({
                    "row": r,
                    "column": 0,
                    "style_id": 1 if self.lines[r].startswith("❯") else 0,
                    "text": self.lines[r],
                    "cell_width": len(self.lines[r]),
                })
        return {
            "format": "cmux.render-grid.v1",
            "surface_id": self.surface_id,
            "state_seq": self.state_seq,
            "render_epoch": self.render_epoch,
            "render_revision": self.render_revision,
            "columns": self.columns,
            "rows": self.rows,
            "full": False,
            "cleared_rows": cleared_rows,
            "cursor": {
                "row": self.cursor_row,
                "column": self.cursor_col,
                "visible": True,
                "style": "block",
                "blinking": False
            },
            "styles": [
                {"id": 0, "foreground": "#D4D4D4", "background": "#1E1E1E", "bold": False, "italic": False},
                {"id": 1, "foreground": "#00FF7F", "background": "#1E1E1E", "bold": True, "italic": False}
            ],
            "row_spans": row_spans,
            "active_screen": "primary",
            "terminal_background": "#1E1E1E",
            "terminal_foreground": "#D4D4D4"
        }

    def get_full_snapshot(self, max_scrollback_rows: int = 0) -> Dict[str, Any]:
        row_spans = []
        for idx, line in enumerate(self.lines):
            if line:
                style_id = 1 if line.startswith("❯") else 0
                row_spans.append({
                    "row": idx,
                    "column": 0,
                    "style_id": style_id,
                    "text": line,
                    "cell_width": len(line)
                })

        snapshot: Dict[str, Any] = {
            "format": "cmux.render-grid.v1",
            "surface_id": self.surface_id,
            "state_seq": self.state_seq,
            "render_epoch": self.render_epoch,
            "render_revision": self.render_revision,
            "columns": self.columns,
            "rows": self.rows,
            "full": True,
            "cleared_rows": [],
            "cursor": {
                "row": self.cursor_row,
                "column": self.cursor_col,
                "visible": True,
                "style": "block",
                "blinking": False
            },
            "styles": [
                {"id": 0, "foreground": "#D4D4D4", "background": "#1E1E1E", "bold": False, "italic": False},
                {"id": 1, "foreground": "#00FF7F", "background": "#1E1E1E", "bold": True, "italic": False}
            ],
            "row_spans": row_spans,
            "active_screen": "primary",
            "terminal_background": "#1E1E1E",
            "terminal_foreground": "#D4D4D4",
            "history_rows": 500,
            "row_space_revision": 1,
        }
        if max_scrollback_rows > 0:
            snapshot["scrollback_rows"] = 0
            snapshot["scrollback_spans"] = []
        return snapshot

class MockCmuxBackend:
    """Mock backend implementing multi-workspace and multi-tab surfaces."""
    def __init__(self):
        self.workspaces: List[Dict[str, Any]] = [
            {
                "id": "ws-main",
                "key": "ws-main",
                "name": "cmux-main",
                "order": 0,
                "active_on_host": True,
                "surfaces": [
                    {"id": "surf-main-1", "type": "terminal", "title": "zsh", "workspace_key": "ws-main", "tab_index": 0},
                    {"id": "surf-main-2", "type": "terminal", "title": "Claude Code", "workspace_key": "ws-main", "tab_index": 1, "agent_state": "working"},
                    {"id": "surf-main-3", "type": "terminal", "title": "tests", "workspace_key": "ws-main", "tab_index": 2}
                ]
            },
            {
                "id": "ws-android",
                "key": "ws-android",
                "name": "android-dev",
                "order": 1,
                "active_on_host": False,
                "surfaces": [
                    {"id": "surf-android-1", "type": "terminal", "title": "gradle build", "workspace_key": "ws-android", "tab_index": 0},
                    {"id": "surf-android-2", "type": "terminal", "title": "logcat", "workspace_key": "ws-android", "tab_index": 1}
                ]
            },
            {
                "id": "ws-exp",
                "key": "ws-exp",
                "name": "experiments",
                "order": 2,
                "active_on_host": False,
                "surfaces": [
                    {"id": "surf-exp-1", "type": "terminal", "title": "Codex MCP", "workspace_key": "ws-exp", "tab_index": 0, "agent_state": "needs_input", "attention": True}
                ]
            }
        ]
        self.terminal_sessions: Dict[str, MockTerminalSession] = {}
        for ws in self.workspaces:
            for s in ws["surfaces"]:
                self.terminal_sessions[s["id"]] = MockTerminalSession(surface_id=s["id"], title=s.get("title", "zsh"))

    def list_workspaces(self) -> List[Dict[str, Any]]:
        return self.workspaces

    def create_workspace(self, name: str, initial_surface: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        ws_id = f"ws-{uuid.uuid4().hex[:6]}"
        surfaces = []
        if initial_surface:
            surf_id = f"surf-{uuid.uuid4().hex[:6]}"
            title = initial_surface.get("title") or "zsh"
            surf = {
                "id": surf_id,
                "type": initial_surface.get("type", "terminal"),
                "title": title,
                "workspace_key": ws_id,
                "tab_index": 0
            }
            surfaces.append(surf)
            self.terminal_sessions[surf_id] = MockTerminalSession(surface_id=surf_id, title=title)

        new_ws = {
            "id": ws_id,
            "key": ws_id,
            "name": name,
            "order": len(self.workspaces),
            "active_on_host": False,
            "surfaces": surfaces
        }
        self.workspaces.append(new_ws)
        return new_ws

    def select_workspace(self, workspace_key: str):
        for ws in self.workspaces:
            ws["active_on_host"] = (ws["key"] == workspace_key or ws["id"] == workspace_key)

    def create_surface(self, workspace_key: str, title: Optional[str] = None, surface_type: str = "terminal") -> Dict[str, Any]:
        ws = next((w for w in self.workspaces if w["key"] == workspace_key or w["id"] == workspace_key), None)
        if not ws:
            ws = self.workspaces[0]

        surf_id = f"surf-{uuid.uuid4().hex[:6]}"
        final_title = title or ("zsh" if surface_type == "terminal" else surface_type)
        new_surf = {
            "id": surf_id,
            "type": surface_type,
            "title": final_title,
            "workspace_key": ws["key"],
            "tab_index": len(ws["surfaces"])
        }
        ws["surfaces"].append(new_surf)
        self.terminal_sessions[surf_id] = MockTerminalSession(surface_id=surf_id, title=final_title)
        return new_surf

    def close_surface(self, surface_id: str, workspace_key: Optional[str] = None) -> bool:
        for ws in self.workspaces:
            for s in ws["surfaces"]:
                if s["id"] == surface_id:
                    ws["surfaces"].remove(s)
                    self.terminal_sessions.pop(surface_id, None)
                    return True
        return False

    def get_or_create_terminal_session(self, surface_id: str) -> MockTerminalSession:
        if surface_id not in self.terminal_sessions:
            self.terminal_sessions[surface_id] = MockTerminalSession(surface_id=surface_id)
        return self.terminal_sessions[surface_id]

    def send_input(self, surface_id: str, text: str) -> None:
        session = self.get_or_create_terminal_session(surface_id)
        session.apply_input(text)

    def handle_input(self, surface_id: str, text: str) -> Dict[str, Any]:
        session = self.get_or_create_terminal_session(surface_id)
        return session.handle_input(text)
    def handle_scroll(self, surface_id: str, delta_lines: float, col: int, row: int) -> Dict[str, Any]:
        return self.get_snapshot(surface_id, max_scrollback_rows=0)

    def get_snapshot(self, surface_id: str, max_scrollback_rows: int = 0) -> Dict[str, Any]:
        session = self.get_or_create_terminal_session(surface_id)
        return session.get_full_snapshot(max_scrollback_rows=max_scrollback_rows)

class LiveCmuxBackend:
    """Connects to real live Mac cmux via CLI / Unix socket with rich color parsing."""
    def __init__(self):
        self.state_seqs: Dict[str, int] = {}
        self.render_epochs: Dict[str, str] = {}
        self._state_seq_lock = threading.Lock()

    def _next_state_seq(self, surface_id: str) -> int:
        with self._state_seq_lock:
            seq = self.state_seqs.get(surface_id, 0) + 1
            self.state_seqs[surface_id] = seq
            return seq

    def _run_cmux(self, args: List[str]) -> str:
        cmd = ["cmux"] + args
        res = subprocess.run(cmd, capture_output=True, text=True)
        if res.returncode != 0:
            op = args[0] if args else "cmux"
            raise RuntimeError(f"cmux {op} failed with exit code {res.returncode}")
        return res.stdout.strip()

    def list_workspaces(self) -> List[Dict[str, Any]]:
        try:
            raw = self._run_cmux(["tree", "--all", "--json"])
            data = json.loads(raw)
            workspaces = []
            for win in data.get("windows", []):
                for ws in win.get("workspaces", []):
                    surfaces = []
                    target_surf = None
                    first_terminal_surf = None
                    for pane in ws.get("panes", []):
                        for surf in pane.get("surfaces", []):
                            if surf.get("tty"):
                                if first_terminal_surf is None:
                                    first_terminal_surf = surf
                                if surf.get("selected") or surf.get("selected_in_pane"):
                                    target_surf = surf
                                    break
                        if target_surf:
                            break
                    if not target_surf:
                        target_surf = first_terminal_surf

                    resolved_cwd = tty_cwd(target_surf.get("tty")) if target_surf else None

                    for pane in ws.get("panes", []):
                        for surf in pane.get("surfaces", []):
                            surf_tty = surf.get("tty")
                            surf_cwd = None
                            if surf is target_surf:
                                surf_cwd = resolved_cwd
                            elif surf_tty and surf_tty in _CWD_CACHE:
                                now = time.time()
                                cached = _CWD_CACHE[surf_tty]
                                if now - cached[0] < _CWD_TTL_SEC:
                                    surf_cwd = cached[1]

                            surfaces.append({
                                "id": surf.get("ref"),
                                "type": surf.get("type", "terminal"),
                                "title": surf.get("title") or "terminal",
                                "workspace_key": ws.get("id"),
                                "cwd": surf_cwd,
                            })
                    workspaces.append({
                        "id": ws.get("id"),
                        "key": ws.get("id"),
                        "name": ws.get("title") or ws.get("ref"),
                        "active_on_host": ws.get("selected", False),
                        "cwd": resolved_cwd,
                        "surfaces": surfaces
                    })
            if workspaces:
                return workspaces
        except Exception as e:
            logger.error(f"LiveCmuxBackend list_workspaces error: {e}")
        return []

    def create_workspace(self, name: str, initial_surface: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        try:
            self._run_cmux(["new-workspace", "--name", name])
            workspaces = self.list_workspaces()
            target = next((w for w in workspaces if w["name"] == name), workspaces[-1] if workspaces else None)
            return target or {"id": str(uuid.uuid4()), "name": name, "surfaces": []}
        except Exception as e:
            logger.error(f"LiveCmuxBackend create_workspace error: {e}")
            return {"id": str(uuid.uuid4()), "name": name, "surfaces": []}

    def select_workspace(self, workspace_key: str):
        try:
            self._run_cmux(["select-workspace", "--workspace", workspace_key])
        except Exception as e:
            logger.error(f"LiveCmuxBackend select_workspace error: {e}")

    def create_surface(self, workspace_key: str, title: Optional[str] = None, surface_type: str = "terminal") -> Dict[str, Any]:
        try:
            args = [
                "new-surface",
                "--workspace",
                workspace_key,
                "--type",
                surface_type,
                "--focus",
                "false",
            ]
            created_output = self._run_cmux(args)
            created_match = re.search(r"\b(surface:[^\s]+)", created_output)
            created_id = created_match.group(1) if created_match else None
            workspaces = self.list_workspaces()
            ws = next((w for w in workspaces if w["key"] == workspace_key), None)
            if ws and created_id:
                created_surface = next((surface for surface in ws["surfaces"] if surface["id"] == created_id), None)
                if created_surface:
                    return created_surface
            if created_id:
                return {
                    "id": created_id,
                    "type": surface_type,
                    "title": title or "terminal",
                    "workspace_key": workspace_key,
                }
            raise RuntimeError(f"cmux did not return the created surface: {created_output!r}")
        except Exception as e:
            logger.error(f"LiveCmuxBackend create_surface error: {e}")
            raise

    def close_surface(self, surface_id: str, workspace_key: Optional[str] = None) -> bool:
        try:
            args = ["close-surface", "--surface", surface_id]
            if workspace_key:
                args.extend(["--workspace", workspace_key])
            self._run_cmux(args)
            return True
        except Exception as e:
            logger.error(f"LiveCmuxBackend close_surface error: {e}")
            return False

    def send_input(self, surface_id: str, text: str) -> None:
        t0 = time.monotonic()
        try:
            if text == "\u001b":
                self._run_cmux(["send-key", "--surface", surface_id, "escape"])
            elif text == "\t":
                self._run_cmux(["send-key", "--surface", surface_id, "tab"])
            elif text == "\u001b[A":
                self._run_cmux(["send-key", "--surface", surface_id, "up"])
            elif text == "\u001b[B":
                self._run_cmux(["send-key", "--surface", surface_id, "down"])
            elif text == "\u001b[C":
                self._run_cmux(["send-key", "--surface", surface_id, "right"])
            elif text == "\u001b[D":
                self._run_cmux(["send-key", "--surface", surface_id, "left"])
            elif text == "\u0003":
                self._run_cmux(["send-key", "--surface", surface_id, "ctrl-c"])
            elif text == "\u0004":
                self._run_cmux(["send-key", "--surface", surface_id, "ctrl-d"])
            elif text == "\u007f" or text == "\b":
                self._run_cmux(["send-key", "--surface", surface_id, "backspace"])
            elif text == "\n" or text == "\r":
                self._run_cmux(["send-key", "--surface", surface_id, "enter"])
            else:
                self._run_cmux(["send", "--surface", surface_id, text])
        finally:
            dt_ms = (time.monotonic() - t0) * 1000.0
            perf_trace("host_input", surface_ord=get_surface_ordinal(surface_id), host_input_ms=dt_ms)

    def handle_input(self, surface_id: str, text: str) -> Dict[str, Any]:
        self.send_input(surface_id, text)
        return self.get_snapshot(surface_id)

    def handle_scroll(self, surface_id: str, delta_lines: float, col: int, row: int) -> Dict[str, Any]:
        payload = self._rpc("mobile.terminal.scroll", {
            "surface_id": surface_id,
            "delta_lines": delta_lines,
            "col": col,
            "row": row,
            "max_scrollback_rows": 1,
        })
        if isinstance(payload.get("render_grid"), dict):
            return normalize_official_replay(payload, surface_id, self._next_state_seq(surface_id), include_scrollback=False)
        return self.get_snapshot(surface_id, max_scrollback_rows=0)

    def _rpc(self, method: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        args = ["rpc", method]
        if params:
            args.append(json.dumps(params))
        raw = self._run_cmux(args)
        if not raw:
            raise RuntimeError(f"cmux {method} returned empty output")
        payload = json.loads(raw)
        if not isinstance(payload, dict):
            raise RuntimeError(f"cmux {method} returned non-object JSON")
        return payload

    def _fallback_read_screen(self, surface_id: str, seq: int) -> Dict[str, Any]:
        epoch = self.render_epochs.setdefault(surface_id, f"epoch-{uuid.uuid4().hex[:6]}")
        try:
            raw = self._run_cmux(["read-screen", "--surface", surface_id])
            raw_lines = raw.splitlines()
        except Exception as e:
            logger.error(f"LiveCmuxBackend fallback read-screen error: {e}")
            raw_lines = [f"[cmux: live surface {surface_id}]"]

        styles_list: List[Dict[str, Any]] = []
        style_id_map: Dict[Tuple[str, str, bool], int] = {}
        row_spans: List[Dict[str, Any]] = []
        max_detected_cols = 80

        def get_style_id(fg: str, bg: str, bold: bool) -> int:
            key = (fg, bg, bold)
            if key not in style_id_map:
                new_id = len(styles_list)
                style_id_map[key] = new_id
                styles_list.append({
                    "id": new_id,
                    "foreground": fg,
                    "background": bg,
                    "bold": bold,
                    "italic": False
                })
            return style_id_map[key]

        for r_idx, raw_l in enumerate(raw_lines):
            clean_text, spans = parse_ansi_line(raw_l)
            line_width = display_cell_width(clean_text)
            max_detected_cols = max(max_detected_cols, line_width)
            if not spans:
                continue
            col = 0
            for (_ignored_start, span_len, style_data) in spans:
                segment_text = clean_text[col:col + span_len] if col < len(clean_text) else ""
                if not segment_text and span_len:
                    # parse_ansi_line start_col is a python index; prefer that if in range
                    start = _ignored_start
                    segment_text = clean_text[start:start + span_len]
                cell_width = display_cell_width(segment_text) if segment_text else span_len
                s_id = get_style_id(
                    fg=style_data["foreground"],
                    bg=style_data["background"],
                    bold=style_data["bold"]
                )
                row_spans.append({
                    "row": r_idx,
                    "column": display_cell_width(clean_text[:_ignored_start]) if _ignored_start <= len(clean_text) else col,
                    "style_id": s_id,
                    "text": segment_text or clean_text[_ignored_start:_ignored_start + span_len],
                    "cell_width": cell_width
                })
                col += span_len
                max_detected_cols = max(max_detected_cols, _ignored_start + cell_width)

        return {
            "format": "cmux.render-grid.v1",
            "surface_id": surface_id,
            "state_seq": seq,
            "render_epoch": epoch,
            "render_revision": seq,
            "columns": max(max_detected_cols, 80),
            "rows": max(len(raw_lines), 24),
            "full": True,
            "cleared_rows": [],
            "cursor": {
                "row": max(0, len(raw_lines) - 1),
                "column": 0,
                "visible": True,
                "style": "block",
                "blinking": False
            },
            "styles": styles_list or [
                {"id": 0, "foreground": "#D4D4D4", "background": "#1E1E1E", "bold": False, "italic": False}
            ],
            "row_spans": row_spans,
            "active_screen": "primary",
            "terminal_background": "#1E1E1E",
            "terminal_foreground": "#D4D4D4"
        }

    def get_snapshot(self, surface_id: str, max_scrollback_rows: int = 0) -> Dict[str, Any]:
        t0 = time.monotonic()
        seq = self._next_state_seq(surface_id)
        include_sb = max_scrollback_rows > 0
        try:
            params: Dict[str, Any] = {
                "surface_id": surface_id,
                "anchor": "screen",
                "max_scrollback_rows": max_scrollback_rows,
            }
            payload = self._rpc("terminal.replay", params)
            frame = normalize_official_replay(
                payload,
                surface_id,
                seq,
                include_scrollback=include_sb,
            )
            logger.debug(
                "Official replay snapshot surface=%s cols=%s rows=%s spans=%s",
                surface_id,
                frame.get("columns"),
                frame.get("rows"),
                len(frame.get("row_spans") or []),
            )
            return frame
        except Exception as e:
            logger.warning(f"Official terminal.replay failed for {surface_id}: {e}; falling back to read-screen")
            return self._fallback_read_screen(surface_id, seq)
        finally:
            dt_ms = (time.monotonic() - t0) * 1000.0
            perf_trace("snapshot_fetch", surface_ord=get_surface_ordinal(surface_id), snapshot_fetch_ms=dt_ms)

def create_cmux_backend():
    mode = os.environ.get("CMUX_GATEWAY_BACKEND", "auto").lower()
    if mode == "mock":
        logger.info("Using MockCmuxBackend (forced by CMUX_GATEWAY_BACKEND=mock)")
        return MockCmuxBackend()

    if shutil.which("cmux"):
        try:
            res = subprocess.run(["cmux", "ping"], capture_output=True, text=True, timeout=2)
            if res.returncode == 0 or "PONG" in res.stdout or "ok" in res.stdout or "pong" in res.stdout:
                logger.info("Live cmux detected! Using LiveCmuxBackend.")
                return LiveCmuxBackend()
        except Exception as e:
            logger.warning(f"Failed to ping cmux: {e}")

    logger.info("cmux socket not reachable. Falling back to MockCmuxBackend.")
    return MockCmuxBackend()


class CmuxGatewayClientSession:
    """Manages one connected Android WebSocket client session with serialized outbound delivery."""
    def __init__(self, websocket, gateway):
        self.websocket = websocket
        self.gateway = gateway
        self.session_id = str(uuid.uuid4())
        self.client_ordinal = get_client_ordinal(self.session_id)
        self.authenticated = False
        self.subscribed_topics: Set[str] = set()
        self.active_surface_id: Optional[str] = None
        self.focus_generation: int = 0

        self._control_queue: asyncio.Queue[Tuple[Dict[str, Any], float, Optional[asyncio.Future]]] = asyncio.Queue()
        self._latest_render_frame: Optional[Tuple[str, int, Dict[str, Any], float]] = None
        self._notify_event: asyncio.Event = asyncio.Event()
        self._closed: bool = False
        self._writer_task: Optional[asyncio.Task] = None

    def start_writer(self):
        if not self._writer_task or self._writer_task.done():
            self._writer_task = asyncio.create_task(self._writer_loop())

    def set_active_surface(self, surface_id: Optional[str]):
        if self.active_surface_id != surface_id:
            self.active_surface_id = surface_id
            self.focus_generation += 1
            # Invalidate any pending render frame from previous focus
            self._latest_render_frame = None

    async def enqueue_control(self, data: Dict[str, Any], wait: bool = True):
        """Enqueue FIFO must-deliver message (RPC response, error, broadcast event, explicit replay, delta frames)."""
        if self._closed:
            if wait:
                raise ConnectionError("Client session is closed")
            return
        fut: Optional[asyncio.Future] = None
        if wait:
            try:
                loop = asyncio.get_running_loop()
                fut = loop.create_future()
            except RuntimeError:
                pass
        await self._control_queue.put((data, time.monotonic(), fut))
        self._notify_event.set()
        if fut is not None:
            await fut

    def enqueue_render_frame(self, surface_id: str, focus_generation: int, frame: Dict[str, Any]):
        """
        Enqueue or coalesce render frames.
        Poll-generated full snapshots (full==True) coalesce in a bounded latest-wins slot.
        Non-full/delta frames trigger authoritative full recovery via request_priority_refresh
        instead of buffering unbounded delta backlogs.
        """
        if self._closed:
            return
        # Surface isolation & focus generation check
        if self.active_surface_id != surface_id or self.focus_generation != focus_generation:
            return

        is_full = frame.get("full", True)
        if not is_full:
            # Non-full delta frame cannot be safely buffered in unbounded queue; request authoritative full recovery
            if self.gateway and hasattr(self.gateway, "request_priority_refresh"):
                self.gateway.request_priority_refresh(surface_id)
            return

        # Full snapshots coalesce in bounded latest-wins slot
        self._latest_render_frame = (surface_id, focus_generation, frame, time.monotonic())
        self._notify_event.set()
    async def send_json(self, data: Dict[str, Any]):
        """Compatibility & standard must-deliver entrypoint; awaits actual websocket transmission."""
        await self.enqueue_control(data, wait=True)

    async def _writer_loop(self):
        try:
            while not self._closed:
                while self._control_queue.empty() and self._latest_render_frame is None and not self._closed:
                    self._notify_event.clear()
                    await self._notify_event.wait()

                if self._closed:
                    break

                # 1. Drain control queue (FIFO must-deliver)
                while not self._control_queue.empty():
                    msg, enq_time, fut = self._control_queue.get_nowait()
                    q_wait_ms = (time.monotonic() - enq_time) * 1000.0
                    t_enc0 = time.monotonic()
                    raw = json.dumps(msg)
                    enc_ms = (time.monotonic() - t_enc0) * 1000.0
                    t_send0 = time.monotonic()
                    try:
                        await self.websocket.send(raw)
                        send_ms = (time.monotonic() - t_send0) * 1000.0
                        if fut is not None and not fut.done():
                            fut.set_result(None)
                    except Exception as exc:
                        if fut is not None and not fut.done():
                            fut.set_exception(exc)
                        raise
                    perf_trace(
                        "control_send",
                        client_ord=self.client_ordinal,
                        queue_depth=self._control_queue.qsize(),
                        queue_wait_ms=q_wait_ms,
                        encode_ms=enc_ms,
                        send_ms=send_ms,
                    )

                # 2. Send latest render frame if present and valid
                if self._latest_render_frame is not None:
                    target_surf, target_focus, frame, enq_time = self._latest_render_frame
                    self._latest_render_frame = None

                    # Verify surface and focus generation match
                    if self.active_surface_id == target_surf and self.focus_generation == target_focus:
                        q_wait_ms = (time.monotonic() - enq_time) * 1000.0
                        frame_msg = {
                            "event": "terminal.render_grid",
                            "data": frame,
                        }
                        t_enc0 = time.monotonic()
                        raw = json.dumps(frame_msg)
                        enc_ms = (time.monotonic() - t_enc0) * 1000.0
                        t_send0 = time.monotonic()
                        await self.websocket.send(raw)
                        send_ms = (time.monotonic() - t_send0) * 1000.0
                        perf_trace(
                            "frame_send",
                            client_ord=self.client_ordinal,
                            surface_ord=get_surface_ordinal(target_surf),
                            focus_gen=target_focus,
                            queue_wait_ms=q_wait_ms,
                            encode_ms=enc_ms,
                            send_ms=send_ms,
                        )
        except (asyncio.CancelledError, GeneratorExit):
            pass
        except Exception as e:
            logger.info(f"Writer error for client {self.session_id}: {e}")
            self._fail_pending_futures(e)
            raise
        finally:
            self._fail_pending_futures(ConnectionError("Writer terminated"))

    def _fail_pending_futures(self, exc: Exception):
        while not self._control_queue.empty():
            try:
                _msg, _enq, fut = self._control_queue.get_nowait()
                if fut is not None and not fut.done():
                    fut.set_exception(exc)
            except Exception:
                break

    async def close(self):
        if self._closed:
            return
        self._closed = True
        self._notify_event.set()
        self._fail_pending_futures(ConnectionError("Client session closed"))
        current = asyncio.current_task()
        if self._writer_task and not self._writer_task.done() and current != self._writer_task:
            self._writer_task.cancel()
            try:
                await self._writer_task
            except (asyncio.CancelledError, Exception):
                pass
        try:
            await self.websocket.close()
        except Exception:
            pass
    async def handle_message(self, message_str: str):
        try:
            msg = json.loads(message_str)
        except Exception as e:
            logger.error(f"Invalid JSON from {self.session_id}: {e}")
            await self.send_json({"error": "invalid_json", "detail": str(e)})
            return

        # 1. Auth frame
        if not self.authenticated:
            if msg.get("type") == "auth" or "token" in msg:
                token = msg.get("token", "")
                if self.gateway.verify_token(token):
                    self.authenticated = True
                    logger.info(f"Client {self.session_id} authenticated successfully.")
                    await self.send_json({
                        "type": "auth_ok",
                        "session_id": self.session_id,
                        "server_version": "2.0.0",
                        "capabilities": [
                            "terminal.render_grid.v1",
                            "terminal.input.ordered.v1",
                            "workspace.changes.v1",
                            "events.v1",
                            "client_focus.v1",
                            "multi_surface.v1",
                        ],
                    })
                else:
                    logger.warning(f"Client {self.session_id} auth failed with invalid token")
                    await self.send_json({"type": "auth_error", "reason": "invalid_token"})
                    await self.websocket.close(1008, "Auth failed")
            else:
                await self.send_json({"type": "auth_error", "reason": "unauthenticated"})
                await self.websocket.close(1008, "Unauthenticated")
            return

        # 2. JSON-RPC Request / Method handling
        req_id = msg.get("id")
        method = msg.get("method")
        params = msg.get("params", {})

        if not method:
            return

        if method in ("mobile.terminal.input", "terminal.input", "mobile.terminal.scroll", "terminal.scroll"):
            logger.debug("Handling RPC method=%s", method)
        else:
            logger.info("Handling RPC [%s] method: %s", req_id, method)

        if method == "mobile.host.status":
            await self.send_json({
                "id": req_id,
                "result": {
                    "mac_display_name": "cmux Host",
                    "mac_app_version": "2.0.0",
                    "capabilities": [
                        "terminal.render_grid.v1",
                        "terminal.input.ordered.v1",
                        "workspace.changes.v1",
                        "events.v1",
                        "client_focus.v1",
                        "multi_surface.v1",
                    ],
                },
            })
        elif method in ("mobile.workspace.list", "workspace.list"):
            workspaces = self.gateway.backend.list_workspaces()
            if not self.active_surface_id and workspaces:
                for ws in workspaces:
                    if ws.get("active_on_host") and ws.get("surfaces"):
                        self.set_active_surface(ws["surfaces"][0]["id"])
                        break
                if not self.active_surface_id and workspaces[0].get("surfaces"):
                    self.set_active_surface(workspaces[0]["surfaces"][0]["id"])

            await self.send_json({
                "id": req_id,
                "result": {
                    "workspaces": workspaces,
                },
            })
        elif method == "mobile.workspace.create":
            mutation_id = params.get("mutation_id")
            name = params.get("name", "New Workspace")
            init_surf = params.get("initial_surface")
            new_ws = self.gateway.backend.create_workspace(name, init_surf)
            res_data: Dict[str, Any] = {
                "status": "ok",
                "workspace": new_ws,
            }
            if mutation_id is not None:
                res_data["mutation_id"] = mutation_id
            await self.send_json({
                "id": req_id,
                "result": res_data,
            })
            broadcast_data: Dict[str, Any] = {
                "action": "workspace_created",
                "workspace": new_ws,
            }
            if mutation_id is not None:
                broadcast_data["mutation_id"] = mutation_id
            await self.gateway.broadcast_event("workspace.tree", broadcast_data)
        elif method == "mobile.workspace.select":
            # Client-local navigation only. Do not move Mac workspace focus.
            mutation_id = params.get("mutation_id")
            ws_key = params.get("workspace_key") or params.get("workspace_id")
            res_data = {"status": "ok", "workspace_key": ws_key, "host_focus_moved": False}
            if mutation_id is not None:
                res_data["mutation_id"] = mutation_id
            await self.send_json({
                "id": req_id,
                "result": res_data,
            })
        elif method == "mobile.surface.create":
            mutation_id = params.get("mutation_id")
            ws_key = params.get("workspace_key") or params.get("workspace_id") or "ws-main"
            title = params.get("title")
            surf_type = params.get("type", "terminal")
            new_surf = self.gateway.backend.create_surface(ws_key, title, surf_type)
            res_data = {
                "status": "ok",
                "surface": new_surf,
            }
            if mutation_id is not None:
                res_data["mutation_id"] = mutation_id
            await self.send_json({
                "id": req_id,
                "result": res_data,
            })
            broadcast_data = {
                "action": "surface_created",
                "surface": new_surf,
            }
            if mutation_id is not None:
                broadcast_data["mutation_id"] = mutation_id
            await self.gateway.broadcast_event("workspace.tree", broadcast_data)
        elif method == "mobile.surface.close":
            mutation_id = params.get("mutation_id")
            surf_id = params.get("surface_id")
            ws_key = params.get("workspace_key")
            success = self.gateway.backend.close_surface(surf_id, ws_key)
            if success and self.active_surface_id == surf_id:
                self.set_active_surface(None)
            res_data = {
                "status": "ok" if success else "error",
                "surface_id": surf_id,
            }
            if mutation_id is not None:
                res_data["mutation_id"] = mutation_id
            await self.send_json({
                "id": req_id,
                "result": res_data,
            })
            broadcast_data = {
                "action": "surface_closed",
                "surface_id": surf_id,
            }
            if mutation_id is not None:
                broadcast_data["mutation_id"] = mutation_id
            await self.gateway.broadcast_event("workspace.tree", broadcast_data)
        elif method == "mobile.surface.focus":
            mutation_id = params.get("mutation_id")
            surf_id = params.get("surface_id")
            self.set_active_surface(surf_id)
            res_data = {"status": "ok", "surface_id": surf_id}
            if mutation_id is not None:
                res_data["mutation_id"] = mutation_id
            await self.send_json({
                "id": req_id,
                "result": res_data,
            })
            if "terminal.render_grid" in self.subscribed_topics and self.active_surface_id:
                self.gateway.request_priority_refresh(self.active_surface_id)
        elif method == "mobile.events.subscribe":
            topics = params.get("topics", [])
            for t in topics:
                self.subscribed_topics.add(t)
            logger.info(f"Client {self.session_id} subscribed to topics: {topics}")
            await self.send_json({
                "id": req_id,
                "result": {
                    "stream_id": params.get("stream_id", str(uuid.uuid4())),
                    "already_subscribed": False,
                    "event_transport": "websocket",
                },
            })
            if "terminal.render_grid" in self.subscribed_topics and self.active_surface_id:
                snapshot = await self.gateway.get_surface_snapshot(
                    self.active_surface_id,
                    0,
                )
                await self.send_json({
                    "event": "terminal.render_grid",
                    "data": snapshot,
                })
        elif method in ("mobile.terminal.input", "terminal.input"):
            surf_id = params.get("surface_id") or self.active_surface_id or "surface:1"
            text = params.get("text", "")
            try:
                if hasattr(self.gateway.backend, "send_input"):
                    await asyncio.to_thread(self.gateway.backend.send_input, surf_id, text)
                else:
                    await asyncio.to_thread(self.gateway.backend.handle_input, surf_id, text)
            except Exception as e:
                logger.error(f"Input delivery failed for surface {surf_id}: {e}")
                await self.send_json({
                    "id": req_id,
                    "error": {"code": -32000, "message": f"Input failed: {e}"},
                })
                return

            # Success ACK returned only after host acceptance and before replay
            await self.send_json({
                "id": req_id,
                "result": {"status": "ok", "surface_id": surf_id},
            })
            # Immediate priority refresh for the surface
            self.gateway.request_priority_refresh(surf_id)
        elif method in ("mobile.terminal.scroll", "terminal.scroll"):
            surf_id = params.get("surface_id") or self.active_surface_id or "surface:1"
            delta_lines = float(params.get("delta_lines") or 0.0)
            col = int(params.get("col") or 0)
            row = int(params.get("row") or 0)
            snapshot = await self.gateway.handle_surface_scroll(
                surf_id,
                delta_lines,
                col,
                row,
            )
            await self.send_json({
                "id": req_id,
                "result": {"status": "ok", "surface_id": surf_id},
            })
            await self.send_json({
                "event": "terminal.render_grid",
                "data": snapshot,
            })
        elif method in ("mobile.terminal.replay", "terminal.replay"):
            surf_id = params.get("surface_id") or self.active_surface_id or "surface:1"
            max_sb = params.get("max_scrollback_rows")
            try:
                max_sb_int = max(0, min(int(max_sb), 1000)) if max_sb is not None else 0
            except (ValueError, TypeError):
                max_sb_int = 0
            snapshot = await self.gateway.get_surface_snapshot(
                surf_id,
                max_sb_int,
            )
            await self.send_json({
                "id": req_id,
                "result": snapshot,
            })
            await self.send_json({
                "event": "terminal.render_grid",
                "data": snapshot,
            })
        elif method in ("mobile.terminal.viewport", "terminal.viewport"):
            cols = params.get("viewport_columns", 80)
            rows = params.get("viewport_rows", 24)
            await self.send_json({
                "id": req_id,
                "result": {
                    "accepted": True,
                    "columns": cols,
                    "rows": rows,
                    "geometry_owner": False,
                },
            })
        else:
            await self.send_json({
                "id": req_id,
                "error": {
                    "code": -32601,
                    "message": f"Method '{method}' not implemented in gateway",
                },
            })


class CmuxWebSocketGateway:
    """Main Gateway Server."""
    def __init__(self, auth_token: str, host: str = DEFAULT_WS_HOST, port: int = DEFAULT_WS_PORT):
        if not is_loopback_bind_host(host):
            raise ValueError("Gateway only permits loopback binds; terminate TLS in a local reverse proxy")
        if not auth_token:
            raise ValueError("auth_token must be provided")
        self.host = host
        self.port = port
        self.auth_token = auth_token
        self.backend = create_cmux_backend()
        self.clients: Set[CmuxGatewayClientSession] = set()
        self.server = None
        self._priority_surfaces: Set[str] = set()
        self._surface_locks: Dict[str, asyncio.Lock] = {}
        self._refresh_trigger: asyncio.Event = asyncio.Event()
        self._screen_poller_task: Optional[asyncio.Task] = None
        self._tree_poller_task: Optional[asyncio.Task] = None
        self._poller_task: Optional[asyncio.Task] = None
        self._agent_event_task: Optional[asyncio.Task] = None
        self._agent_event_ids: Set[str] = set()

    def verify_token(self, token: str) -> bool:
        return token == self.auth_token

    def _get_surface_lock(self, surface_id: str) -> asyncio.Lock:
        lock = self._surface_locks.get(surface_id)
        if lock is None:
            lock = asyncio.Lock()
            self._surface_locks[surface_id] = lock
        return lock

    async def get_surface_snapshot(self, surface_id: str, max_scrollback_rows: int = 0) -> Dict[str, Any]:
        """Per-surface serialized snapshot fetch to preserve strictly monotonic sequence and delivery ordering."""
        lock = self._get_surface_lock(surface_id)
        async with lock:
            return await asyncio.to_thread(self.backend.get_snapshot, surface_id, max_scrollback_rows)

    async def handle_surface_scroll(self, surface_id: str, delta_lines: float, col: int, row: int) -> Dict[str, Any]:
        """Per-surface serialized scroll handling."""
        lock = self._get_surface_lock(surface_id)
        async with lock:
            return await asyncio.to_thread(self.backend.handle_scroll, surface_id, delta_lines, col, row)

    def request_priority_refresh(self, surface_id: str):
        if surface_id:
            self._priority_surfaces.add(surface_id)
            self._refresh_trigger.set()

    async def broadcast_event(self, event_name: str, data: Dict[str, Any]):
        for client in list(self.clients):
            if client.authenticated and event_name in client.subscribed_topics:
                await client.send_json({
                    "event": event_name,
                    "data": data,
                })

    async def _live_screen_poller(self):
        while True:
            try:
                try:
                    await asyncio.wait_for(self._refresh_trigger.wait(), timeout=0.05)
                    self._refresh_trigger.clear()
                except asyncio.TimeoutError:
                    pass

                priority = set(self._priority_surfaces)
                self._priority_surfaces.clear()

                if self.clients:
                    await fanout_screen_snapshots(
                        self.clients,
                        lambda sid: self.get_surface_snapshot(sid, 0),
                        priority_surfaces=priority,
                    )
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.debug(f"Screen poller tick error: {e}")
                await asyncio.sleep(0.05)

    async def _live_tree_poller(self):
        last_tree_sig = None
        while True:
            try:
                await asyncio.sleep(5.0)
                if isinstance(self.backend, LiveCmuxBackend):
                    workspaces = await asyncio.to_thread(self.backend.list_workspaces)
                    sig = workspace_tree_signature(workspaces)
                    if sig != last_tree_sig:
                        last_tree_sig = sig
                        await self.broadcast_event("workspace.tree", {
                            "action": "sync",
                            "workspaces": workspaces,
                        })
            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.debug(f"Tree poller tick error: {e}")

    @staticmethod
    def _notification_record(notification_id: str) -> Optional[str]:
        try:
            output = subprocess.run(
                ["cmux", "list-notifications"],
                capture_output=True,
                text=True,
                timeout=2,
            ).stdout
            return next((line for line in output.splitlines() if notification_id in line), None)
        except (OSError, subprocess.SubprocessError):
            return None

    async def _live_agent_event_poller(self):
        """Forward cmux agent turn completions to subscribed mobile clients."""
        while True:
            process = None
            try:
                if not shutil.which("cmux") or not isinstance(self.backend, LiveCmuxBackend):
                    await asyncio.sleep(5.0)
                    continue

                process = await asyncio.create_subprocess_exec(
                    "cmux", "events",
                    "--category", "agent",
                    "--category", "notification",
                    "--reconnect",
                    "--no-heartbeat",
                    stdout=asyncio.subprocess.PIPE,
                    stderr=asyncio.subprocess.DEVNULL,
                )
                assert process.stdout is not None
                while True:
                    line = await process.stdout.readline()
                    if not line:
                        break
                    try:
                        event = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    completion = parse_agent_completion_event(event)
                    if not completion and event.get("name") == "notification.created":
                        payload = event.get("payload") or {}
                        notification_id = payload.get("notification_id")
                        surface_id = event.get("surface_id") or payload.get("surface_id")
                        if notification_id and surface_id:
                            record = await asyncio.to_thread(self._notification_record, notification_id)
                            if record and notification_record_is_completion(record, notification_id):
                                completion = {
                                    "event_id": event.get("id") or notification_id,
                                    "workspace_id": event.get("workspace_id") or payload.get("workspace_id"),
                                    "surface_id": surface_id,
                                    "agent_kind": None,
                                    "category": "turn-complete",
                                }
                    if not completion:
                        continue
                    event_id = completion.get("event_id")
                    if event_id and event_id in self._agent_event_ids:
                        continue
                    if event_id:
                        self._agent_event_ids.add(event_id)
                        if len(self._agent_event_ids) > 2048:
                            self._agent_event_ids = set(list(self._agent_event_ids)[-1024:])
                    await self.broadcast_event("agent.session.completed", completion)
            except asyncio.CancelledError:
                if process and process.returncode is None:
                    process.terminate()
                    await process.wait()
                break
            except Exception as e:
                logger.debug(f"Agent event poller error: {e}")
            finally:
                if process and process.returncode is None:
                    process.terminate()
                    await process.wait()
            await asyncio.sleep(2.0)

    async def _handle_connection(self, websocket, path=None):
        client = CmuxGatewayClientSession(websocket, self)
        self.clients.add(client)
        client.start_writer()
        logger.info(f"Client connected: {client.session_id} from {websocket.remote_address}")
        try:
            async for message in websocket:
                await client.handle_message(message)
        except Exception as e:
            logger.info(f"Client {client.session_id} disconnected: {e}")
        finally:
            await client.close()
            self.clients.discard(client)
            logger.info(f"Client cleaned up: {client.session_id}")

    async def start(self):
        import websockets
        self.server = await websockets.serve(
            self._handle_connection,
            self.host,
            self.port,
        )
        self._screen_poller_task = asyncio.create_task(self._live_screen_poller())
        self._tree_poller_task = asyncio.create_task(self._live_tree_poller())
        self._poller_task = self._screen_poller_task
        logger.info(f"cmux WebSocket Gateway v2 listening on ws://{self.host}:{self.port}")
        if isinstance(self.backend, LiveCmuxBackend):
            self._agent_event_task = asyncio.create_task(self._live_agent_event_poller())

    async def stop(self):
        if self._screen_poller_task:
            self._screen_poller_task.cancel()
        if self._tree_poller_task:
            self._tree_poller_task.cancel()
        for client in list(self.clients):
            await client.close()
        self.clients.clear()
        if self.server:
            self.server.close()
            await self.server.wait_closed()
            logger.info("Gateway server stopped.")
        if self._agent_event_task:
            self._agent_event_task.cancel()
            await asyncio.gather(self._agent_event_task, return_exceptions=True)


async def main():
    port = int(os.environ.get("CMUX_GATEWAY_PORT", DEFAULT_WS_PORT))
    host = os.environ.get("CMUX_GATEWAY_HOST", DEFAULT_WS_HOST)
    token = load_auth_token()

    gateway = CmuxWebSocketGateway(host=host, port=port, auth_token=token)
    await gateway.start()

    try:
        await asyncio.Future()  # run forever
    except (KeyboardInterrupt, asyncio.CancelledError):
        pass
    finally:
        await gateway.stop()


if __name__ == "__main__":
    asyncio.run(main())

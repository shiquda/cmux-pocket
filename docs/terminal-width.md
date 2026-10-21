# Phone-width terminal layout

cmux keeps one PTY grid per surface. The phone can ask that grid to take its
width so host-side TUIs reflow for the phone instead of being squeezed into the
Mac's 148-column layout. This document describes exactly what that mode does,
what it changes on the Mac, and how it is released.

## What the mode does

The bottom terminal bar's **Width** control opens a per-terminal panel. It
shows the computer's current grid, the grid the phone would request, and the
fact that the change is shared. Nothing is sent until **Lay out for this phone**
is selected.

- Target columns = `floor(available canvas width / current cell width)`,
  clamped to the host's 20–300 range.
- Target rows = `max(rows that fit the phone, rows the computer currently
  shows)`, clamped to 5–120. The extra rows keep the bottom of the Mac view
  intact instead of truncating it on the phone.
- The phone reports a *desired* grid. cmux takes the smallest report across all
  attached clients, then the Mac pane's font-fit can shrink it further. The
  phone therefore treats only an authoritative full baseline frame as the
  effective grid; it never claims the requested size as applied.
- Rows are intentionally not reduced to the phone's height: a taller grid keeps
  the Mac session's scroll position and prompt visible.

## Sharing and the Mac baseline

The PTY is shared. Enabling phone width reflows the same terminal on the Mac,
and any other connected phone sees the shared minimum. The panel states this
before enabling.

The "computer" grid is captured from the last full baseline frame received
while the mode was off. It is frozen while the mode is on, so it cannot be
replaced by the narrowed grid. If another client was already constraining the
host, that captured grid is the shared minimum, not the pane's theoretical
width — the UI shows what the host actually produced, and the panel never
pretends to know an uncaptured laptop size.

## Lifecycle

- **Zoom**: pinch and A−/A+ refit after a 300 ms settle, coalescing every
  intermediate sample into one report. The target is recomputed from the font
  metrics and the real canvas size.
- **Restore**: the persistent restore button (and the panel's **Restore
  computer layout**) remove this client's report. The Gateway keeps a lease
  renewed every 1.5 s; restoring stops the lease and clears the host report.
- **History and selection**: while reading scrollback or selecting text,
  automatic refits are paused so the resize cannot invalidate anchors. The
  panel's **Refit** first returns to live output, then resizes.
- **Surface switch, background, connection end**: this client's report is
  released immediately. Releasing one client never clears another client's
  report. The mode is not persisted across launches.
- **Crash**: reports are non-sticky replays piggybacked on
  `mobile.terminal.replay`, so they expire about five seconds after the last
  renewal. Killing the Gateway (not just closing the WebSocket) therefore
  restores the Mac grid by itself.

Every report carries a monotonically increasing generation. The Gateway fences
reports and clears by generation per `(session, client, surface)`, so an
in-flight report captured before a newer one cannot land afterwards.

## Protocol surface

`mobile.terminal.viewport` (also `terminal.viewport`):

```json
{"surface_id": "surface:7", "client_id": "uuid", "viewport_generation": 42,
 "viewport_columns": 44, "viewport_rows": 46}
```

or, to release this client's report:

```json
{"surface_id": "surface:7", "client_id": "uuid", "viewport_generation": 43,
 "clear": true}
```

The Gateway requires `terminal.viewport.v1` in the host capabilities before it
advertises the mode; otherwise clients disable the control and the method
returns `METHOD_NOT_FOUND`. Reports are clamped by the Gateway and again by the
host. A host still resizing answers with `viewport_transition`; the Gateway
returns `pending: true` immediately and lets the lease task retry, so a slow
resize never blocks terminal input on the same connection. Effective geometry
is then confirmed by the next authoritative baseline frames.

## Verified behaviour

Against cmux 0.64.25 on macOS with a live surface:

| Step | Grid | `stty size` |
| --- | --- | --- |
| Baseline | 148 × 46 | `46 148` |
| Report 44 × 46 | 44 × 46 | `46 44` |
| After 3.5 s (renewal) | 44 × 46 | `46 44` |
| Clear | 148 × 46 | `46 148` |
| WebSocket close (lease release) | 148 × 46 within 0.14 s | `46 148` |
| Gateway `SIGKILL` (TTL only) | 148 × 46 within 4.94 s | `46 148` |

On a physical Android device (1080 × 2340, 440 dpi, portrait), with the panel
showing `当前 148 列 × 46 行 / 预计 44 列 × 46 行`:

| Action | Host grid | Device observation |
| --- | --- | --- |
| Enable | 44 × 46 | `stty size` → `46 44`; the 216-character test line wrapped at 44 |
| A+ twice | 39 × 46 | bar showed `39 列` after the 300 ms settle |
| Restore | 148 × 46 | bar returned to `宽度`, restore icon gone |
| A+ once after restore | 148 × 46 | local zoom did not ask the host again |
| Home (background) | 148 × 46 | release completed within 4 s |
| Enable from Settings → Display | 37 × 46 | mirror showed `已生效 37 列 × 46 行` |
| Gateway process killed | 148 × 46 | connection error surfaced; grid restored via TTL |

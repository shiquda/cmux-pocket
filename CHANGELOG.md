# Changelog

All notable changes to cmux Pocket are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

GitHub Release bodies are generated from this file: `.github/workflows/release-apk.yml`
extracts the section for the pushed tag while publishing, and
`.github/workflows/release-notes.yml` re-syncs a published release after a manual run.

## [Unreleased]

### Added

- `cmux-pocket upgrade` upgrades the CLI and Gateway without the user having to remember the package manager: it reads the latest GitHub Release tag from the `releases/latest` redirect, compares it with the running build, identifies the install source (Homebrew `Cellar`/`opt`/`bin`, `CARGO_HOME/bin`, or a `brew list --versions` hit), and delegates to `brew upgrade shiquda/cmux-pocket/cmux-pocket` or `cargo install --force --git … --tag <release> cmux-pocket-cli`. The registered LaunchAgent is restarted afterwards through the existing service restart path, the printed report names the old and new build, and it points at **Settings → Connection → Recheck version** in the app. An unrecognized install is never overwritten: both manual commands are printed and the command exits non-zero. `--check` reports without changing anything and `--json` follows the existing envelope.
- `cmux-pocket status` reports the Gateway build (`gateway_version`) alongside the protocol version, so a client can tell which Gateways are actually running the upgraded binary.

## [0.3.0] - 2026-10-22

**Phone-width terminal, floating window, and one-command pairing**

### Highlights

- **Phone-width terminal layout**: the shared PTY is asked to reflow to the phone's real column count, so host-side TUIs fit the screen instead of being zoomed or panned.

  <img src="https://raw.githubusercontent.com/shiquda/cmux-pocket/main/docs/screenshots/terminal.png" width="300" alt="Phone-width terminal layout">

- **Floating terminal window**: the focused session keeps streaming into Android picture-in-picture after you leave the app, with optional auto-open, an aspect-ratio slider, and per-window text size.

  <img src="https://raw.githubusercontent.com/shiquda/cmux-pocket/main/docs/screenshots/floating-window.png" width="300" alt="Floating terminal window in picture-in-picture">

- **One-command pairing**: `cmux-pocket qr` picks between saved endpoints, a verified Tailscale Serve route, Cloudflare Tunnel hostnames, and USB, then re-renders the QR code live; the app scans it and previews host, port, TLS, and a masked token before importing.

  <img src="https://raw.githubusercontent.com/shiquda/cmux-pocket/main/docs/screenshots/qr-pairing.png" width="300" alt="Scanning connection QR code">

- **Session completion feedback**: a short pulse for the session on screen, a tappable capsule for any other session, and a notification that names its target when the app is backgrounded.

### Added

- Phone-width terminal layout: a two-mode switch in the bottom bar (phone icon for phone layout, computer icon for the desktop layout, placed left of fit-width) drives the real PTY; **Settings → Display** stores only the default for newly opened terminals. The request is renewed with a lease (heartbeats every 1.5 s; renewals stop after 6 s without one), released on surface switch, background, disconnect or restore, and expires on its own if the Gateway dies or the phone's network path goes dark. Target columns come from the live canvas and font metrics, rows preserve the computer's row count, and every effective size comes from an authoritative full frame rather than the request.
- Floating terminal window: the focused session renders into Android picture-in-picture with a bottom-aligned, word-wrapped tail of the grid. **Settings → Floating window** offers an enable switch, **Auto-open when leaving the app**, a 0.6–2.39 aspect-ratio slider, a 2–24 sp font size, and a live preview; the accessory bar's picture-in-picture button opens it on demand. **Adapt terminal layout** reflows the real grid to the window's measured width and font size on every resize, and releases the report when the window closes so the full-screen layout comes back.
- Session completion feedback: when the session on screen finishes its agent turn, the phone gives a short haptic pulse; any other session raises a tappable capsule under the floating island (`workspace › #N title Done`) that jumps straight to it, with a `+N` badge for queued completions. **Settings → Notifications** turns the pulse off (on by default). While the app is in the foreground these replace the system notification; a backgrounded app still posts one labelled with its target (`workspace › #N title`), and tapping it opens that surface — including on a cold start or when the Gateway reports a workspace id the mobile tree does not know.
- Workspace and tab management: create a workspace (optionally with its first tab), rename workspaces and tabs, and confirm before deleting the active workspace or closing the active tab.

  <img src="https://raw.githubusercontent.com/shiquda/cmux-pocket/main/docs/screenshots/session-drawer.png" width="300" alt="Sessions and surfaces drawer">
- Edge-drag scrollback: a drag the rendered grid can no longer absorb continues into history at the top edge and walks back to live output at the bottom; the right joystick shares the same offset handler and its rail shows the current history position.
- Remote wake: when the Gateway is online but cmux.app is not running, the Android terminal shows a **Mac online · cmux not running** card with a one-tap **Wake cmux on Mac** action. The Gateway launches the app through LaunchServices in the background (10-second debounce), polls the socket every 500 ms for up to 15 seconds, then pushes a full workspace sync.
- Android **Settings → Connection** shows the Pocket Gateway version, the app version, the cmux protocol version, and remote-wake support with a compatibility badge, a **Recheck version** action, a copyable Mac upgrade command, and an APK download link when the app is older than the Gateway.

  <img src="https://raw.githubusercontent.com/shiquda/cmux-pocket/main/docs/screenshots/connection.png" width="300" alt="Connection settings and Gateway versions">
- `mobile.host.status` now reports `gateway_version`, `protocol_version`, and `cmux_status` (`installed`, `running`, `can_wake`), and Gateways advertise the `mobile.host.wake_cmux.v1` capability plus a new `mobile.host.wake_cmux` RPC.
- Connection QR pairing: `cmux-pocket qr` renders a QR code and a `cmux-pocket://` import link for the resolved endpoint, and Android **Settings → Connection → Scan QR** or the deep link imports it after a preview showing host, port, TLS state, and a masked token.
- Interactive QR picker: `cmux-pocket qr` shows the first candidate's QR code immediately and re-renders it live as you switch between saved endpoints, a verified Tailscale Serve route, Cloudflare Tunnel hostnames, and USB (ADB reverse) with ↑/↓ (Enter confirms, Esc cancels); `--endpoint` skips it, and `--no-interactive`, `--json`, and `--url-only` keep the previous single-shot behavior.
- `cmux-pocket endpoint add|list|remove` stores named phone endpoints in `config.toml`; the legacy `config.endpoint` still acts as the default.
- Endpoint discovery: Tailscale candidates require a Serve root route that targets this Gateway, Cloudflare Tunnel hostnames come from local `~/.cloudflared` ingress rules and only auto-select while the tunnel process runs, and ADB reverse is offered as a dev-only picker entry.
- `cmux-pocket setup --tailscale` reuses a healthy Gateway, verifies authenticated workspace access, configures private HTTPS Serve forwarding, and reports conflicting routes without changing them.
- Android connection assistant with a live checklist (reachability, token acceptance, Gateway status, update subscription, workspace access), the host setup command, and an **Open Tailscale** shortcut.
- Floating breadcrumb island over the terminal exposing connection state, workspace, and session, with a session drawer for switching surfaces and workspaces.
- Full-screen settings hub grouped as Connection, Appearance (Display, Language, Floating window), Notifications, and Support (Updates, Open Source, Diagnostics, Storage); every control persists immediately.
- Language choice between System default, English, and 简体中文, persisted in encrypted preferences; a fresh install follows the device locale and falls back to English.
- In-app update check against the latest GitHub Release with a semver comparison and direct APK download prompt.
- Diagnostics: opt-in network and RPC logger with a 500-line buffer, token redaction, in-app viewer, and redacted JSON export.
- Profile export asks whether the clipboard JSON should carry Gateway access keys; without them the backup holds endpoints only and a key can be filled in after importing.
- Foreground recovery: returning to the app reconnects immediately instead of waiting for the next scheduled retry.
- Homebrew distribution follows releases automatically: `shiquda/homebrew-cmux-pocket` renders its formula from the latest GitHub Release tag and commit, so `brew upgrade` reaches a new Gateway without editing the tap by hand. The formula now installs the tagged source rather than the moving `main` branch.

### Changed

- Settings no longer reserve a pinned bottom bar for AGPL-3.0 metadata and **Apply & Save**: every control persists immediately, the close button dismisses the page, and the version, license link, and copyright now scroll at the end of the settings list, returning the full viewport to the content on small and landscape screens.
- The terminal renders beneath the system status bar; the fixed workspace top bar and surface tab bar are replaced by the floating island, and the accessory keyboard is a single scrollable row of `ESC`, `Enter`, `TAB`, `^C`, arrows, `|`, `/`, `-`, and `~`, followed by the floating-window and settings shortcuts.
- Colors come from the Neon Grid token set documented in `DESIGN.md` and exposed by `CmuxTheme`, including system bar icon appearance and Material 3 components in dark mode.
- Layout orientation follows the real window size, so rotating no longer recreates the activity; the landscape sidebar and settings pages were reflowed for English labels.
- The right joystick now scrolls at 1.6× its previous default speed.

### Fixed

- Terminal input from Android is no longer swallowed by cmux 0.65.0's draft and dialog guard: the Gateway probes `cmux send --help` / `send-key --help` once (cached for five minutes) and sends every keystroke with `--force` when the flag exists. Older cmux builds, which reject unknown flags by typing them as text, keep the flagless command. IME text is classified separately from intentional keys, bound to the tab that opened the keyboard, and multi-character text uses `--paste` where supported so `\n` and option-like text stay literal. Timed-out local cmux processes are now killed and reaped.
- Light and dark mode legibility pass: the status and navigation bars now follow the palette instead of staying black in light mode, terminal-palette swatch labels pick ink from the swatch's own luminance, empty terminal states render on the app background instead of on the dark terminal well, and accent chips, diagnostics logs, update and compatibility badges, gateway warnings, and the settings footer use AA-contrast ink in both modes.
- Pending RPC requests now time out when the Gateway becomes unreachable, rejected credentials and DNS/TLS failures are reported distinctly, and queued messages from a previous socket are ignored.
- A long-press on the terminal shows exactly one contextual bubble (Copy, Paste, Interrupt, Clear) with drag handles, replacing the duplicate Copy pill.
- `cmux-pocket qr` no longer emits an unreachable `wss://<node>.ts.net` endpoint when Tailscale Serve is not configured: automatic discovery now requires `tailscale serve status` to show the root route pointing at the local Gateway.
- Settings content is no longer squeezed by the section rail, and palette swatch labels ellipsise instead of being cut mid-word.
- The settings header tagline reports the installed app version instead of a hardcoded `0.2.0`.
- Upgrades now put the new Gateway in service without a logout: the Homebrew formula kickstarts the registered LaunchAgent during install, and `cmux-pocket setup` restarts a healthy Gateway whose reported build is older than the CLI it is invoked from. Previously `brew upgrade` replaced the binary on disk while launchd kept the previous process, so the phone stayed on old Gateway behaviour until the next login.

### Upgrading

- Install the signed APK from the release assets. Existing connection profiles keep working, and the app still speaks protocol 2.0.0.
- Remote wake and the version panel need Gateway 0.3.0: run `brew upgrade shiquda/cmux-pocket/cmux-pocket`, then `cmux-pocket status`. A Gateway without the wake capability is reported in **Settings → Connection** instead of failing silently.
- Profile export now asks whether to include Gateway access keys; exports without them can be completed after import.
- The floating window needs a device with Android picture-in-picture support (Android 8.0+).

## [0.2.0] - 2026-10-14

**Rust Gateway and Homebrew CLI, background connections, per-terminal text size**

### Added

- Rust Gateway with a Homebrew-installed CLI (`brew install shiquda/cmux-pocket/cmux-pocket`), covering `setup`, `status`, and a managed macOS `launchd` login service that stores the token with user-only permissions.
- The Gateway connection is kept alive through Android backgrounding with an ongoing connection notification.
- Android is notified when a cmux agent turn completes.
- Terminal text size controls (A−/A+, pinch, **Fit Width**, **Readable**, double-tap to fit) with the text size and view remembered per terminal across tab switches and app restarts.
- README hero and real-device mockups replacing the previous text-only introduction.

### Changed

- Gateway health checks now verify authenticated workspace access instead of accepting any authenticated socket.
- The `launchd` service persists the bundled cmux path so the login service can find cmux after restarts.

### Fixed

- Invalid Android connection profiles stay editable and recoverable instead of blocking the settings screen.

## [0.1.0] - 2026-08-24

**First signed Android release**

### Added

- Android companion rendering the authoritative `cmux.render-grid.v1` terminal grid; Android runs no second local PTY or VT parser.
- Workspace and tab switching on the phone without moving the Mac's current focus, synchronized tab creation and deletion, and confirmation before closing the active tab.
- Phone-local scrollback for main-screen output, while alternate-screen and TUI scrolling stay owned by cmux on the Mac.
- Encrypted Android connection profiles with a random Gateway token; non-loopback connections require `wss://`, and the Gateway binds to loopback only.
- Compact terminal keyboard with modifiers, navigation keys, and F1–F12.
- Terminal text selection and copy.
- Protected APK release workflow that verifies the signing certificate fingerprint, application ID, and version name before publishing the signed APK.

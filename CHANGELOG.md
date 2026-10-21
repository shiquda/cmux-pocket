# Changelog

All notable changes to cmux Pocket are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

GitHub Release bodies are generated from this file: `.github/workflows/release-apk.yml`
extracts the section for the pushed tag while publishing, and
`.github/workflows/release-notes.yml` re-syncs a published release after a manual run.

## [Unreleased]

**Immersive terminal, one-tap pairing, and Tailscale setup assistance**

### Added

- Session completion feedback: when the session on screen finishes its agent turn, the phone gives a short haptic pulse; any other session raises a tappable capsule under the floating island (`workspace › #N title Done`) that jumps straight to it, with a `+N` badge for queued completions. **Settings → Notifications** turns the pulse off (on by default). While the app is in the foreground these replace the system notification; a backgrounded app still posts one labelled with its target (`workspace › #N title`), and tapping it opens that surface — including on a cold start or when the Gateway reports a workspace id the mobile tree does not know.
- Remote wake: when the Gateway is online but cmux.app is not running, the Android terminal shows a **Mac online · cmux not running** card with a one-tap **Wake cmux on Mac** action. The Gateway launches the app through LaunchServices in the background (10-second debounce), polls the socket every 500 ms for up to 15 seconds, then pushes a full workspace sync.
- Android **Settings → Connection** shows the Pocket Gateway version, the app version, the cmux protocol version, and remote-wake support with a compatibility badge, a **Recheck version** action, a copyable Mac upgrade command, and an APK download link when the app is older than the Gateway.
- `mobile.host.status` now reports `gateway_version`, `protocol_version`, and `cmux_status` (`installed`, `running`, `can_wake`), and Gateways advertise the `mobile.host.wake_cmux.v1` capability plus a new `mobile.host.wake_cmux` RPC.
- Phone-width terminal layout: a two-mode switch in the bottom bar (phone icon for phone layout, computer icon for the desktop layout, placed left of fit-width) drives the real PTY; **Settings → Display** stores only the default for newly opened terminals. The request is renewed with a lease (heartbeats every 1.5 s; renewals stop after 6 s without one), released on surface switch, background, disconnect or restore, and expires on its own if the Gateway dies or the phone's network path goes dark. Target columns come from the live canvas and font metrics, rows preserve the computer's row count, and every effective size comes from an authoritative full frame rather than the request.
- Connection QR pairing: `cmux-pocket qr` renders a QR code and a `cmux-pocket://` import link for the resolved endpoint (explicit flag, saved endpoint, or Tailscale discovery), and Android **Settings → Connection & Host → Scan QR** or the deep link imports it after a preview showing host, port, TLS state, and a masked token.
- `cmux-pocket setup --tailscale` reuses a healthy Gateway, verifies authenticated workspace access, configures private HTTPS Serve forwarding, and reports conflicting routes without changing them.
- Android connection assistant with a live checklist (reachability, token acceptance, Gateway status, update subscription, workspace access), the host setup command, and an **Open Tailscale** shortcut.
- Floating breadcrumb island over the terminal exposing connection state, workspace, and session, with a session drawer for switching surfaces and workspaces.
- Full-screen settings hub with a section rail: General, Connection, Display, Language, Updates, Open Source, Diagnostics, Storage.
- Language choice between System default, English, and 简体中文, persisted in encrypted preferences; a fresh install follows the device locale and falls back to English.
- In-app update check against the latest GitHub Release with a semver comparison and direct APK download prompt.
- Diagnostics: opt-in network and RPC logger with a 500-line buffer, token redaction, in-app viewer, and redacted JSON export; connection profiles can also be exported to and imported from JSON.
- Homebrew distribution follows releases automatically: `shiquda/homebrew-cmux-pocket` renders its formula from the latest GitHub Release tag and commit, so `brew upgrade` reaches a new Gateway without editing the tap by hand. The formula now installs the tagged source rather than the moving `main` branch.

### Changed

- Settings no longer reserve a pinned bottom bar for AGPL-3.0 metadata and **Apply & Save**: every control persists immediately, the close button dismisses the page, and the version, license link, and copyright now scroll at the end of the settings list, returning the full viewport to the content on small and landscape screens.
- The terminal renders beneath the system status bar; the fixed workspace top bar and surface tab bar are replaced by the floating island, and the accessory keyboard is a single row of ESC, latching CTRL/ALT, TAB, `^C`, `|`, `/`, `-`, `~`, and arrows.
- Colors come from the Neon Grid token set documented in `DESIGN.md` and exposed by `CmuxTheme`, including system bar icon appearance and Material 3 components in dark mode.
- Layout orientation follows the real window size, so rotating no longer recreates the activity; the landscape sidebar and settings section rail were reflowed for English labels.

### Fixed

- Terminal input from Android is no longer swallowed by cmux 0.65.0's draft and dialog guard: the Gateway probes `cmux send --help` / `send-key --help` once (cached for five minutes) and sends every keystroke with `--force` when the flag exists. Older cmux builds, which reject unknown flags by typing them as text, keep the flagless command. IME text is classified separately from intentional keys, bound to the tab that opened the keyboard, and multi-character text uses `--paste` where supported so `\n` and option-like text stay literal. Timed-out local cmux processes are now killed and reaped.
- Light and dark mode legibility pass: the status and navigation bars now follow the palette instead of staying black in light mode, terminal-palette swatch labels pick ink from the swatch's own luminance, empty terminal states render on the app background instead of on the dark terminal well, and accent chips, diagnostics logs, update and compatibility badges, gateway warnings, and the settings footer use AA-contrast ink in both modes.
- Pending RPC requests now time out when the Gateway becomes unreachable, rejected credentials and DNS/TLS failures are reported distinctly, and queued messages from a previous socket are ignored.
- A long-press on the terminal shows exactly one contextual bubble (Copy, Paste, Interrupt, Clear) with drag handles, replacing the duplicate Copy pill.
- Settings content is no longer squeezed by the section rail, and palette swatch labels ellipsise instead of being cut mid-word.
- `cmux-pocket setup` now restarts a healthy Gateway whose reported build is older than the CLI it is invoked from. Previously `brew upgrade` replaced the binary on disk while launchd kept the previous process, so the phone stayed on old Gateway behaviour until logout.

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

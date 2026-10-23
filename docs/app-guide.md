# App guide

[Quick start](../README.md#quick-start) · [Networking & troubleshooting](networking.md)

## Sessions and navigation

Switch workspaces or tabs on the phone without changing the Mac's active focus. The floating island shows the current workspace, session number, and connection status; tap it to open the sessions drawer. Wider screens offer a collapsible workspace sidebar.

<table>
  <tr>
    <td><img src="screenshots/terminal.png" width="220" alt="Phone-width terminal with the floating island"></td>
    <td><img src="screenshots/session-drawer.png" width="220" alt="Session drawer listing workspaces and terminals"></td>
    <td><img src="screenshots/floating-window.png" width="220" alt="Terminal running in an Android picture-in-picture window"></td>
    <td><img src="screenshots/qr-pairing.png" width="220" alt="Scanning a connection QR code"></td>
  </tr>
</table>

Captured on a 1080 × 2400 Android emulator against a local Gateway.

![The same live session on a phone in portrait and a tablet in landscape](assets/device-portrait-landscape.png)

## Touch and keyboard

- **Pinch** to change text size, or use **A− / A+**.
- **Drag** with one or two fingers to scroll history or navigate wide columns. Main-screen scrollback stays local to Android; alternate-screen/TUI scrollback remains cmux-managed.
- **Double tap** to fit the terminal width to the screen.
- **Width** in the bottom bar requests a shared PTY resize so host-side TUIs reflow for the phone. Restore returns to the Mac layout; leaving a surface or backgrounding releases this device's report. Other connected clients can constrain the size. See [terminal width](terminal-width.md).
- **Long press** terminal text to select it and access Copy selection, Copy screen, Paste at cursor, Send interrupt (Ctrl+C), or Clear screen.

The accessory keyboard row provides `ESC`, `Enter`, `TAB`, `^C`, arrows, symbols, and shortcuts for the floating window and settings. Open the Computer Keyboard panel for F1–F12 and function combinations.

## Settings and diagnostics

![Connection settings in cmux Pocket](assets/connection-settings.png)

- **Connection profiles:** manage hosts with Android Keystore-backed encrypted credential storage.
- **Versions:** the Connection page shows Gateway, app, and protocol versions, compatibility warnings, the Mac upgrade command, and the latest APK link.
- **Remote wake:** when the Gateway is online but cmux is closed, **Wake cmux on Mac** starts the app. This cannot wake a sleeping or offline Mac; see [remote wake](networking.md#remote-wake-and-gateway-version-checks).
- **Diagnostics:** inspect the built-in 500-line connection/RPC log or export redacted JSON to the clipboard.
- **Backup:** export/import host profiles through clipboard JSON. Export lets you choose whether to include access keys; treat an export containing keys as a secret.
- **Updates:** check GitHub Releases from the app and download the latest APK.
- **Language:** switch between English and 简体中文 at runtime.

## APK verification

Download the signed APK from [GitHub Releases](https://github.com/shiquda/cmux-pocket/releases/latest).

- **Application ID:** `app.cmuxpocket`
- **Signing certificate SHA-256:** `c801d49f10948b465936c3155fa5ffef4fea7b2a1b0f029804bf4a7ea68282fa`

For connection setup, scanner behavior, and import confirmation, see [QR pairing](networking.md#3-qr-code-pairing-and-one-tap-import-cmux-pocket).

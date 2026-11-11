# cmux Pocket

cmux Pocket is an independent Android companion for [cmux](https://cmux.com/) on Mac, for developers who want remote access to their terminal workspaces and AI agent sessions from a phone.

[![Release](https://img.shields.io/github/v/release/shiquda/cmux-pocket?style=for-the-badge&logo=github&label=release&labelColor=27303D&color=0D1117)](https://github.com/shiquda/cmux-pocket/releases/latest)
[![Release build](https://img.shields.io/github/actions/workflow/status/shiquda/cmux-pocket/release.yml?style=for-the-badge&logo=githubactions&logoColor=white&label=release%20build&labelColor=27303D)](https://github.com/shiquda/cmux-pocket/actions/workflows/release.yml)
[![License](https://img.shields.io/github/license/shiquda/cmux-pocket?style=for-the-badge&labelColor=27303D&color=0877D2)](LICENSE)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white&labelColor=27303D)](#quick-start)
[![macOS 13+](https://img.shields.io/badge/macOS-13%2B-000000?style=for-the-badge&logo=apple&logoColor=white&labelColor=27303D)](#quick-start)
[![Homebrew tap](https://img.shields.io/badge/Homebrew-tap-FBB040?style=for-the-badge&logo=homebrew&logoColor=white&labelColor=27303D)](https://github.com/shiquda/homebrew-cmux-pocket)

> **Disclaimer:** Independent community project. Not affiliated with or endorsed by cmux. “cmux” identifies the compatibility target only.

[Download Signed APK](https://github.com/shiquda/cmux-pocket/releases/latest) · [Highlights](#highlights) · [Quick Start](#quick-start) · [Documentation](#documentation)

![cmux Pocket: Mac desktop showing cmux terminal alongside Android phone displaying the same workspace](docs/assets/hero.png)

## Highlights

<table>
  <tr>
    <td align="center" valign="top" width="25%">
      <img src="docs/screenshots/terminal.png" width="180" alt="Phone-width terminal and Claude Code agent companion"><br><br>
      <strong>Phone-width Terminal</strong><br>
      <sub>Dynamic PTY reflow for AI agents &amp; TUIs</sub>
    </td>
    <td align="center" valign="top" width="25%">
      <img src="docs/screenshots/session-drawer.png" width="180" alt="Independent workspace and surface drawer"><br><br>
      <strong>Independent Drawer</strong><br>
      <sub>Switch surfaces without Mac focus conflict</sub>
    </td>
    <td align="center" valign="top" width="25%">
      <img src="docs/screenshots/floating-window.png" width="180" alt="Terminal running in Android picture-in-picture mode"><br><br>
      <strong>Floating Window</strong><br>
      <sub>Live PiP terminal stream over any app</sub>
    </td>
    <td align="center" valign="top" width="25%">
      <img src="docs/screenshots/qr-pairing.png" width="180" alt="Scanning connection QR code from terminal"><br><br>
      <strong>One-tap Pairing</strong><br>
      <sub>Instant QR import &amp; Keystore security</sub>
    </td>
  </tr>
</table>

## Quick Start

You need **Android 8.0+** and a **Mac with macOS 13+** running cmux. Keep the Mac awake while connected.

1. **Install Android:** download and install the latest signed APK from [GitHub Releases](https://github.com/shiquda/cmux-pocket/releases/latest).
2. **Start the Mac Gateway:**

   ```bash
   brew install shiquda/cmux-pocket/cmux-pocket
   cmux-pocket setup
   cmux-pocket status
   ```

3. **Connect the devices:** for the recommended Tailscale route, install Tailscale on both devices, connect them to the same tailnet, and enable MagicDNS and HTTPS certificates. With the `tailscale` CLI available on the Mac, run:

   ```bash
   cmux-pocket setup --tailscale
   cmux-pocket qr
   ```

   Select the Tailscale endpoint in the QR picker. For TLS setup and other networks, see the [networking guide](docs/networking.md#2-choose-a-secure-boundary).
4. **Pair:** open **Settings → Connection → Scan QR** on Android, scan the code, review the preview, and tap **Import & Connect**. Your cmux workspaces and tabs appear on the phone.

![One-tap QR pairing: generate a QR on the Mac, scan it in cmux Pocket, and workspaces appear on Android](docs/assets/qr-pairing-flow.png)

If workspace access fails, check [cmux socket permissions](docs/networking.md#cmux-socket-access): cmux's default process restriction can reject the background Gateway. Keep pairing QR codes private—they contain your access token.

## Documentation

- [App guide & screenshots](docs/app-guide.md): sessions, touch gestures, keyboard, floating window, settings, and APK verification.
- [Networking & troubleshooting](docs/networking.md): Tailscale, LAN/TLS proxies, Cloudflare Tunnel, QR pairing, Gateway upgrades, and connection diagnostics.
- [Terminal width](docs/terminal-width.md): phone-width layout and shared terminal resizing.
- [Architecture](docs/architecture.md) · [Design system](DESIGN.md)
- [Development & releases](docs/development.md) · [Changelog](CHANGELOG.md)

Report bugs through [GitHub Issues](https://github.com/shiquda/cmux-pocket/issues). Report vulnerabilities privately via [GitHub Security Advisories](https://github.com/shiquda/cmux-pocket/security/advisories/new); never include tokens, pairing codes, or private tunnel URLs in public reports.

## License

Code: [AGPL-3.0](LICENSE). Bundled Maple Mono NF: [SIL Open Font License 1.1](LICENSES/MapleMono-OFL-1.1.txt). See [third-party notices](THIRD_PARTY_NOTICES).

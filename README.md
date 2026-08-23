# cmux Pocket

cmux Pocket is an independent Android terminal companion: it connects your phone to cmux running on your Mac and renders workspaces and tabs using the structured terminal grid that cmux provides.

> This is an independent community project. It is not affiliated with cmux and is not endorsed by the cmux project or its maintainers. The name "cmux" appears only to describe the software it is compatible with.

This repository ships source code only. No prebuilt APK is provided.

## Features

- Renders terminals from the authoritative `cmux.render-grid.v1` data; no local PTY/VT runs on Android.
- Switch between multiple Workspaces and Tabs on the phone independently, without following or changing the Mac's current focus.
- Syncs tab creation and deletion from the Mac; closing the active tab requires confirmation on the phone.
- Local main-screen scrollback on the phone; alternate screen/TUI scrolling remains handled on the Mac side.
- Supports USB loopback connections, saved connection profiles, LAN discovery, and user-provided `wss://` endpoints; non-loopback connections must use TLS.
- Built-in compact terminal keyboard with modifier keys, navigation keys, and F1–F12.
- The Gateway requires a random authentication token and listens on `127.0.0.1` only by default.

## Prerequisites

- [cmux](https://cmux.com/) installed and running on macOS, with `cmux ping` succeeding.
- Python 3, plus [uv](https://docs.astral.sh/uv/) to run the Gateway with isolated dependencies.
- Android Studio or Android SDK 35, with the SDK path configured via `ANDROID_HOME` or an uncommitted `android/local.properties` file.
- JDK 17.
- For USB install/connect: Android Platform Tools (`adb`) and a device with USB debugging enabled.

## Quick Start (USB, recommended path)

Unless stated otherwise, run the commands below from the repository root. Use two terminal windows: terminal A runs the Gateway in the foreground; terminal B runs build, adb, and token commands.

### 1. Generate the auth token and start the Gateway

In terminal A (repository root):

```bash
mkdir -p "$HOME/.config/cmux-pocket"
umask 077
openssl rand -hex 32 > "$HOME/.config/cmux-pocket/gateway-token"
CMUX_AUTH_TOKEN_FILE="$HOME/.config/cmux-pocket/gateway-token" \
  uv run --with websockets python3 gateway/cmux_gateway.py
```

This command runs in the foreground and stays blocked; that is expected. Run the remaining steps in terminal B. To stop the Gateway, press `Ctrl-C` in terminal A.

The startup log should show both lines:

```text
Live cmux detected! Using LiveCmuxBackend.
cmux WebSocket Gateway v2 listening on ws://127.0.0.1:8088
```

If the log shows `MockCmuxBackend`, the Gateway is not connected to a real cmux; make sure cmux is running and `cmux ping` succeeds.

### 2. Read the token (in terminal B)

The token file is mode 600, readable only by its owner. Do not paste the token into command lines, logs, or screenshots; read the file directly and copy it into the app:

```bash
cat "$HOME/.config/cmux-pocket/gateway-token"
```

### 3. Build and install the Android app

Still in terminal B:

```bash
cd android
./gradlew assembleDebug
cd ..
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

### 4. Set up the USB channel and configure the app

In terminal B, set up port forwarding (re-run after the device reboots or is replugged):

```bash
adb reverse tcp:8088 tcp:8088
```

Then on the phone, open cmux Pocket:

1. Open **Settings**.
2. Select the built-in **USB** profile.
3. Confirm Host is `127.0.0.1` and Port is `8088` (these fields are not editable for the USB profile; no changes needed).
4. Paste the token from step 2 into the **Token** field.
5. Tap **Apply & Save** to save and connect.

Once connected, the app shows the Workspaces and Tabs currently visible in cmux.

## Using the App

### Connection profiles

The app keeps a list of connection profiles. The built-in `USB` profile (`127.0.0.1:8088`) always stays first and cannot be deleted; your custom profiles follow, sorted by most recent use.

- **Save a custom profile**: fill in Host, Port, and Token in Settings, then tap **Add Host** to name and save the endpoint as a new profile. Later, select that profile from the list and tap **Apply & Save** to switch to it.
- **LAN discovery**: tapping **Scan Wi-Fi** scans port `8088` on your phone's current subnet (plus the port in the Port field, if you entered a different one). Discovered endpoints are listed with latency and tagged `USB` or `LAN`. Note: the Gateway listens on loopback only by default, so a LAN scan normally finds nothing unless you run TLS forwarding yourself; even when a reachable port is found, the app only opens plaintext connections to `127.0.0.1`/`localhost` — all non-loopback addresses must use `wss://` (see below).
- The Host field accepts a hostname, an IP, or a full URL of the form `wss://host` or `wss://host:port`; when you enter a `wss://` URL the app connects over TLS and the separate Port field is ignored.
- **Tunnel port**: for a standard HTTPS/WSS tunnel or reverse proxy, the public TLS port is usually `443` — not the Gateway's local `8088`. Either set Host to `wss://your-tunnel-host` (Port field ignored), or enter the bare hostname with Port `443`. Only use `8088` as the public port if your proxy explicitly exposes `8088`.

### Working in the terminal

- The top bar switches between Workspaces and Tabs; switching happens only on the phone and does not change the Mac's current focus in cmux.
- Newly created and deleted tabs sync in both directions; closing the active tab on the phone requires confirmation.
- Main-screen scrollback scrolls locally on the phone; scrolling inside alternate screen/TUI apps (such as full-screen editors) is still handled on the Mac side.
- The built-in compact terminal keyboard provides modifiers (Ctrl, Alt, Shift), arrow/navigation keys, and F1–F12.

## Remote Connections (WSS only)

The Gateway listens on `127.0.0.1` only and rejects non-loopback binds. Remote access therefore requires a TLS reverse proxy or secure tunnel that you set up yourself, forwarding an external `wss://` endpoint to the local `ws://127.0.0.1:8088`.

This project does not provide or bundle any proxy or tunnel deployment: the repository contains no domain names, Tunnel IDs, port mappings, or cloud credentials. Setting up and hardening that TLS endpoint is entirely your responsibility.

Baseline requirements:

- The app allows plaintext `ws://` only to `127.0.0.1` or `localhost`; every non-loopback connection must use `wss://`. Plaintext LAN connections are not supported.
- The remote endpoint must present a trusted TLS certificate (self-signed certificates are not supported) and must continue to use a strong random token.
- In the app, save a custom profile whose Host is your public `wss://` endpoint (see "Connection profiles" above). For a standard HTTPS/WSS tunnel, set Host to `wss://your-tunnel-host` (the Port field is ignored) or the bare hostname with Port `443`. Do not reuse the local Gateway port `8088` as the public tunnel port unless your proxy explicitly exposes `8088`.

## Configuration Reference

### Gateway environment variables

| Variable | Description |
| --- | --- |
| `CMUX_AUTH_TOKEN` | Provide the auth token directly as an environment variable |
| `CMUX_AUTH_TOKEN_FILE` | Read the token from the given file (recommended; set file mode 600); used when `CMUX_AUTH_TOKEN` is unset |
| `CMUX_GATEWAY_PORT` | Override the listen port (default `8088`). If you change it, keep all three in sync: `CMUX_GATEWAY_PORT`, `adb reverse tcp:<port> tcp:<port>`, and the app's Port field |
| `CMUX_GATEWAY_HOST` | Override the bind host (default `127.0.0.1`). The host must remain a loopback address (`127.0.0.1` or `localhost`); the Gateway refuses non-loopback binds and exits with an error |
| `CMUX_GATEWAY_BACKEND` | Set to `mock` to force `MockCmuxBackend` (testing only; does not connect to a real cmux) |

The auth token must come from `CMUX_AUTH_TOKEN` or `CMUX_AUTH_TOKEN_FILE`. The repository ships no default token, and the Gateway never logs it.

### Android build configuration

| Item | Requirement |
| --- | --- |
| JDK | JDK 17 |
| Android SDK | SDK 35 |
| SDK location | `ANDROID_HOME` environment variable, or `sdk.dir` in an uncommitted `android/local.properties` |

### Default ports and addresses

| Item | Value |
| --- | --- |
| Gateway listen address | `127.0.0.1:8088` (loopback only) |
| USB forwarding | `adb reverse tcp:8088 tcp:8088` |
| App package name | `app.cmuxpocket` |

## Troubleshooting

| Symptom | Likely cause | Fix |
| --- | --- | --- |
| Gateway log shows `MockCmuxBackend` | cmux is not running, or the Gateway cannot find the `cmux` CLI | Start cmux, verify `cmux ping` succeeds in a terminal, then restart the Gateway. The mock backend is for tests only |
| `cmux ping` fails | cmux is not installed, not running, or not on `PATH` | Get cmux running properly on the Mac before starting the Gateway |
| The phone receives no data | USB forwarding was never set up, or stopped working after the device was replugged | Re-run `adb reverse tcp:8088 tcp:8088`; confirm the device is authorized with `adb devices` |
| Authentication failure / connection refused | The Token in the app does not match the token the Gateway actually uses (e.g. token regenerated without updating the app) | Read the current token with `cat "$HOME/.config/cmux-pocket/gateway-token"`, update it in Settings, then Apply & Save |
| `./gradlew` cannot find the SDK | Neither `ANDROID_HOME` nor `android/local.properties` is configured | Point either one at your Android SDK 35 installation; `local.properties` is machine-local — do not commit it |
| `./gradlew` reports a Java version error | JDK 17 is not in use | Point `JAVA_HOME` at JDK 17 |
| Non-loopback connections fail | The app forbids plaintext `ws://` to non-loopback addresses | Use a `wss://` endpoint; front the Gateway with your own TLS reverse proxy/tunnel forwarding to `127.0.0.1:8088` |
| TLS handshake failure / certificate error | The proxy certificate is untrusted or misconfigured | Use a trusted certificate; verify the proxy forwards `wss://` correctly to `ws://127.0.0.1:8088` |
| Stuck in CONNECTING through a tunnel | A bare tunnel hostname was paired with the local Gateway port `8088`, while the public WSS endpoint uses TLS port `443` | Set Host to `wss://your-tunnel-host` (Port field ignored) or the bare hostname with Port `443`; only use `8088` if the proxy explicitly exposes it |
| Connected but the terminal is blank | The Gateway is running the Mock backend, or cmux has no renderable visible Workspace | Confirm the Gateway log says `LiveCmuxBackend`; on the Mac, make sure cmux has a visible Workspace containing tabs |

## Development and Tests

### Android unit tests and build

```bash
cd android
./gradlew testDebugUnitTest assembleDebug
```

### Gateway unit and integration tests

From the repository root:

```bash
cd gateway
uv run --with websockets python3 -m unittest test_gateway test_replay_normalize
```

### Android–Gateway protocol E2E test

Must be run from the repository root (the test locates the `gateway/` module by relative path):

```bash
uv run --with websockets python3 tests/e2e_android_gateway_test.py
```

Tests use a mock Gateway bound to `127.0.0.1` and a fixed test token; these test values are not production credentials.

## Project Layout

```text
android/    Android app (Kotlin, Jetpack Compose, OkHttp)
gateway/    Authenticated WebSocket Gateway on the Mac (Python)
tests/      Android–Gateway protocol E2E tests
```

Research notes, internal specs, machine configuration, build artifacts, and credentials from development do not belong in the public repository.

## Security

- The Gateway listens on loopback only. Remote access must go through a local TLS reverse proxy or secure tunnel forwarding to that loopback endpoint.
- The app allows plaintext WebSocket only to `127.0.0.1` or `localhost`; all non-loopback connections must use `wss://`.
- On Android, connection profiles and tokens are stored in encrypted SharedPreferences backed by the Android Keystore, app data backup is disabled, and legacy plaintext settings are migrated and deleted on upgrade. Still, use a trusted phone with a device lock enabled.

Report security issues privately via the GitHub repository's **Security → Report a vulnerability**. Never post tokens, addresses, logs, or terminal contents in public Issues.

## Contributing

Issues describing reproducible problems are welcome, as are narrowly scoped Pull Requests that include verification results. Do not submit real terminal contents, authentication tokens, private host addresses, or other personal data.

## License

Unless stated otherwise, the project code is released under the [GNU Affero General Public License v3.0](LICENSE).

The Maple Mono NF font bundled with the app is licensed under the [SIL Open Font License 1.1](LICENSES/MapleMono-OFL-1.1.txt). See [THIRD_PARTY_NOTICES](THIRD_PARTY_NOTICES) for details.

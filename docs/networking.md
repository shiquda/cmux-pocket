# Networking and setup

cmux Pocket connects to the Gateway through a WebSocket. The Gateway remains loopback-only on the Mac; a LAN or Internet connection must terminate TLS before forwarding to it.

## Connection rules

| Path | App endpoint | Intended use |
| --- | --- | --- |
| USB reverse | `ws://127.0.0.1:8088` | Temporary development and emulator/device testing |
| LAN through your TLS proxy | `wss://gateway.example.test` | Recommended when the phone and Mac share a network |
| Tunnel, VPN, or private ingress | `wss://gateway.example.test` | Recommended when the Mac is not directly reachable |

`ws://` is rejected for non-loopback hosts. The Gateway refuses non-loopback bind addresses, so do not try to expose it by changing `CMUX_GATEWAY_HOST`.

## 1. Install cmux Pocket and start the Gateway

On macOS, install and start cmux, then verify:

```bash
cmux ping
```

### cmux socket access

The Gateway must be allowed to query cmux workspaces from the process that runs it. A successful `cmux ping` in a cmux terminal does not prove that the launchd service has the same access.

cmux's default **cmux processes only** socket mode rejects processes started by launchd. In that mode, the phone can authenticate with the Gateway while workspace discovery fails with `Access denied - only processes started inside cmux can connect`.

To keep this access mode, run the Gateway in a dedicated **cmux terminal**, using the existing configuration and token:

```bash
cmux-pocket service stop
cmux-pocket gateway run
```

Keep that terminal tab open while using the phone. The Mac must be awake, with cmux, Tailscale (if used), and the Gateway running. This foreground process ends when the tab is closed and does not automatically restart at login.

For a background login service, configure cmux to permit authenticated local automation. If your installed cmux version supports **Password mode**, configure it in cmux's Automation settings; its CLI can use the password saved in cmux Settings. This socket password is separate from the Gateway token entered on Android. Follow the [cmux configuration documentation](https://cmux.com/docs/configuration#automation) for your version, then restart the Gateway service and run `cmux-pocket gateway probe` to verify workspace access. cmux Pocket does not change cmux's access settings automatically.

### Install the Gateway

Install the Rust CLI from the project tap and run the idempotent setup:

```bash
brew install shiquda/cmux-pocket/cmux-pocket
cmux-pocket setup
cmux-pocket status
```

`cmux-pocket setup` creates the user-only config and token files, generates the macOS LaunchAgent, starts the loopback Gateway, and performs a local authenticated probe. It preserves an existing token and never prints the raw token. Use:

```bash
cmux-pocket config path
cmux-pocket token path
cmux-pocket doctor --deep
```

for local paths and diagnostics. The default local listener is:

```text
ws://127.0.0.1:8088
```

This local URL is the proxy or tunnel's upstream. It is not the URL to enter in the Android app for LAN or remote use.

Keep the token private. Do not put it in shell history, screenshots, issue reports, committed files, or public tunnel configuration.

### Gateway administration

The CLI owns configuration, token lifecycle, diagnostics, logs, and the per-user LaunchAgent:

```bash
cmux-pocket config show
cmux-pocket token show                 # fingerprint and permissions only
cmux-pocket service status
cmux-pocket gateway probe
cmux-pocket logs --lines 100
cmux-pocket service restart
```

Rotate only when required; rotation invalidates the old Android credential. Copy the new secret to Android without putting it in shell history or logs:

```bash
cmux-pocket token rotate
pbcopy < "$(cmux-pocket token path)"
```

`cmux-pocket service uninstall` removes only the CLI-owned LaunchAgent. Homebrew installation and upgrades do not create tokens, start services, or mutate user configuration; the formula does restart an already-registered LaunchAgent after install, so a `brew upgrade` puts the new Gateway build in service immediately. Running `cmux-pocket setup` after an upgrade also restarts a Gateway whose reported build is older than the CLI, because launchd otherwise keeps the previous process running; a Gateway at the same or a newer build is reused untouched.

### Remote wake and Gateway version checks

The Gateway runs as a login agent, but the cmux desktop app may not be running after a reboot or a manual quit. When the phone is connected and the Gateway reports `cmux_status.running: false`, the terminal replaces its empty state with a **Wake cmux on Mac** card:

1. The phone sends `mobile.host.wake_cmux`; the Gateway verifies it is an authenticated session.
2. The Gateway runs `/usr/bin/open -g -b com.cmuxterm.app`. `-g` launches in the background, so the Mac's current window keeps focus, and repeated requests are absorbed for 10 seconds.
3. The Gateway probes `cmux ping` every 500 ms for up to 15 seconds. When the socket answers, it broadcasts a full `workspace.tree` sync and the phone switches to the live terminal.

Remote wake only covers a Mac that is powered on and awake with the Gateway online. A sleeping or offline Mac is a network reachability problem, not a wake problem.

`mobile.host.status` reports `gateway_version`, `protocol_version`, `capabilities`, and `cmux_status` (`installed`, `running`, `can_wake`). Android compares this against its own version in **Settings → Connection → Host & connection**:

| State | Meaning | Action offered |
| --- | --- | --- |
| Match | Same feature level and the wake capability is present | None |
| Gateway outdated | Missing `mobile.host.wake_cmux.v1` or older than the app | Copy `brew update && brew upgrade cmux-pocket && cmux-pocket setup`, then recheck |
| Incompatible | Different protocol major version | Upgrade the Mac Gateway |
| Client outdated | Gateway is newer than the app | Download the latest APK from GitHub Releases |

The version card reads live state, so **Recheck version** refreshes it without reconnecting.

## 2. Choose a secure boundary

### Tailscale connection assistant

Install Tailscale on the Mac and Android phone. Connect both to the same tailnet, enable MagicDNS and HTTPS certificates in Tailscale, and make the `tailscale` CLI available on the Mac. Keep the Mac awake with cmux running.

Run:

```bash
cmux-pocket setup --tailscale
```

This command performs the existing Gateway setup and an authenticated workspace query. It reuses a healthy running Gateway, including one running in a cmux terminal. Otherwise it attempts to start the login service. Once workspace access succeeds, it checks Tailscale and configures background HTTPS Serve on port `443`, forwarding to the configured loopback Gateway address and port. It verifies the resulting Serve configuration before printing a `wss://` phone endpoint. It does not start another listener if a healthy Gateway already exists, rotate your token, overwrite an existing conflicting root route, or enable public Funnel.

If Tailscale asks you to enable Serve for the tailnet, run `tailscale serve` with your local Gateway port once and follow its HTTPS setup link. Then rerun `cmux-pocket setup --tailscale`. Commands have a bounded wait; an incomplete setup reports a failure with recovery instructions.

If Gateway workspace verification fails, follow [cmux socket access](#cmux-socket-access). The default cmux process restriction may require a foreground Gateway:

```bash
# In a dedicated cmux terminal:
cmux-pocket service stop
cmux-pocket gateway run

# In another terminal while the first stays open:
cmux-pocket setup --tailscale
```

On Android, open **Settings → Connection & Host**, enter the printed phone endpoint (port `443`) and the Gateway token from the path reported by setup, then tap **Check connection**. Existing profiles remain encrypted on the phone. The assistant checks the entered values by reconnecting the active session. It never sends terminal input or changes Mac-side terminal focus as part of the check.

The checklist shows where the handshake stopped:

| Step | What success proves | Recovery if it fails |
| --- | --- | --- |
| Reach Gateway | The phone opened a WebSocket to the configured endpoint | Check host/port, Mac awake state, Tailscale on both devices, and Serve configuration |
| Accept token | The Gateway accepted the phone credential | Copy the existing Gateway token again; it is separate from a cmux automation password |
| Verify Gateway response | The endpoint responds to the Pocket status RPC | Check reverse-proxy forwarding and `cmux-pocket gateway probe` |
| Subscribe to workspace updates | The session subscribed before fetching its workspace baseline | Reconnect; if it persists, update the Gateway and run its probe |
| Read cmux workspaces | The Gateway process can query cmux | Open cmux and check socket access permissions; run the Gateway from a cmux terminal if required |

An empty but valid workspace list passes the handshake and shows zero workspaces/terminals. Authentication with a failed workspace query does **not** show Ready. Failures remain visible on the terminal screen and in Settings, with step-specific guidance. A network failure alone cannot prove whether the phone VPN, host VPN, or Gateway is stopped, so Pocket presents these as checks instead of guessing a cause. **Open Tailscale** is available when the Android Tailscale app is installed; VPN connection and sign-in remain controlled by Tailscale.

Use these Mac commands to inspect the setup later:

```bash
cmux-pocket gateway probe
tailscale status
tailscale serve status
```

Tailscale Serve keeps its background configuration, but the Mac, cmux, VPN, and Gateway must remain running. QR pairing (`cmux-pocket qr`) transfers the Gateway endpoint, TLS state, and token in one scan; see §3.

### LAN with a TLS reverse proxy

Use a reverse proxy on the Mac or another host that is reachable from the phone over the LAN. The proxy should:

1. Listen on a trusted TLS hostname and certificate.
2. Accept WebSocket upgrades.
3. Forward only to `ws://127.0.0.1:8088`.
4. Keep the Gateway off the LAN interface.
5. Restrict access to the intended LAN, VPN, or authenticated users.

Use a stable hostname that resolves to the proxy from the phone. In the app, enter either the complete `wss://` URL or a hostname with public TLS port `443`.

Do not configure a plaintext `ws://` LAN endpoint. A reachable LAN port is not sufficient; the transport must be TLS-protected.

### Reverse proxy or hosted ingress

A reverse proxy may terminate TLS at a trusted edge and forward the WebSocket to the Mac through a private link. The public side should normally use port `443`; the Gateway's local `8088` is not automatically a public port.

Verify all of the following before configuring the app:

- The certificate is trusted by Android.
- WebSocket upgrade requests are forwarded without buffering or HTTP polling conversion.
- The proxy can reach the Mac's loopback Gateway through its private link.
- The proxy does not log or expose the Gateway token.
- Idle and read timeouts are long enough for a persistent WebSocket.

### Cloud or other secure tunnel

A tunnel can provide the TLS boundary when the Mac cannot accept inbound LAN connections. Configure the tunnel's public HTTPS/WSS ingress to forward to the local Gateway upstream, normally `ws://127.0.0.1:8088`.

Use the provider's standard public TLS port, normally `443`. Do not enter `8088` in the app unless the provider explicitly exposes that port publicly. Do not publish tunnel IDs, public domains, credentials, or local configuration files in this repository.


### Concrete example: Cloudflare Tunnel

This example uses a **locally managed** Cloudflare Tunnel to publish the Gateway through a Cloudflare-managed HTTPS hostname. Replace every value in angle brackets with your own value. The names, domain, tunnel ID, and paths below are placeholders; do not copy credentials or private hostnames into a public issue or screenshot.

Prerequisites:

- `cmux-pocket` installed from the project Homebrew tap.
- `cloudflared` installed on the Mac.
- A Cloudflare account with a domain managed by Cloudflare.

#### 1. Start the loopback Gateway

Run this in one terminal on the Mac:

```bash
cmux-pocket setup
cmux-pocket status
```

The CLI creates the user-only token and config, installs the LaunchAgent, and starts the Gateway. The Gateway must remain on `127.0.0.1:8088`. `cloudflared` will connect to this local origin; do not change the Gateway bind address to make it reachable from the network.

#### 2. Create the tunnel and DNS route

Run the interactive login once, then create a named tunnel and route a hostname to it:

```bash
cloudflared tunnel login
cloudflared tunnel create cmux-pocket-gateway
cloudflared tunnel route dns cmux-pocket-gateway cmux-gateway.example.com
```

The `create` command writes a tunnel credentials file under `~/.cloudflared`. Keep that file private. The DNS route points the example hostname at the tunnel; it does not expose the Gateway until `cloudflared` is running.

#### 3. Create a locally managed ingress configuration

Save this as `~/.cloudflared/cmux-pocket-gateway.yml`, substituting the tunnel UUID and your own local account path:

```yaml
tunnel: <TUNNEL_UUID>
credentials-file: /Users/<your-mac-user>/.cloudflared/<TUNNEL_UUID>.json

ingress:
  - hostname: cmux-gateway.example.com
    service: http://127.0.0.1:8088
  - service: http_status:404
```

The final catch-all rule is required. The public HTTPS/WSS hostname is terminated by Cloudflare and forwarded to the loopback HTTP/WebSocket origin. Cloudflare supports proxied WebSockets without additional tunnel configuration; if the WebSockets setting is disabled for the zone, enable it in **Network → WebSockets**.

Validate the rules before starting the tunnel:

```bash
cloudflared --config "$HOME/.cloudflared/cmux-pocket-gateway.yml" tunnel ingress validate
cloudflared --config "$HOME/.cloudflared/cmux-pocket-gateway.yml" tunnel ingress rule https://cmux-gateway.example.com
```

#### 4. Run the tunnel

Run this in another terminal:

```bash
cloudflared --config "$HOME/.cloudflared/cmux-pocket-gateway.yml" tunnel run cmux-pocket-gateway
```

Then configure cmux Pocket with:

```text
Host or URL: cmux-gateway.example.com
Port:        443
Transport:   wss://
Token:       the secret stored at the path printed by `cmux-pocket token path`
```

The app should authenticate and display the current Workspaces and Tabs. Keep Cloudflare's hostname, tunnel credentials, Gateway token, and local configuration files private. Cloudflare may restart an edge connection; cmux Pocket's keepalive and bounded reconnect behavior handle normal WebSocket interruptions, but a reconnect can briefly interrupt the active session.

Official references: [Cloudflare Tunnel setup](https://developers.cloudflare.com/tunnel/setup/), [locally managed configuration files](https://developers.cloudflare.com/tunnel/advanced/local-management/configuration-file/), [routing](https://developers.cloudflare.com/tunnel/routing/), and [WebSockets](https://developers.cloudflare.com/network/websockets/).

### VPN or private network

A VPN is suitable when the phone and Mac can reach a private ingress that terminates TLS. The VPN does not remove the WSS requirement: use a trusted `wss://` endpoint and keep the Gateway bound to loopback.

### Tailscale Serve

With Tailscale connected on both devices, run this on the Mac:

```bash
tailscale serve --bg --https=443 http://127.0.0.1:8088
```

If Serve is not enabled for the tailnet, follow the account enablement link printed by the command. Once enabled, Serve prints an HTTPS URL such as `https://mac-name.tail-example.ts.net/`. In Android, use that full hostname with `wss://`, port `443`, and the existing Gateway token. A numeric Tailscale IP does not provide this TLS hostname, and a pipe between two `nc` commands is not a bidirectional TLS WebSocket proxy.

[Tailscale Serve](https://tailscale.com/docs/reference/tailscale-cli/serve) handles TLS and forwards WebSocket connections to the loopback Gateway within the tailnet. It does not replace the Gateway process or grant it permission to access cmux's socket.


## 3. QR code pairing and one-tap import (`cmux-pocket://`)

cmux Pocket provides automatic pairing to transfer your Gateway endpoint, port, TLS settings, and authentication token to your Android phone without manually retyping credentials.

### Generating a QR code on the Mac

Run the `qr` command in any terminal:

```bash
cmux-pocket qr
```

Or display a QR code automatically after setup:

```bash
cmux-pocket setup --tailscale --qr
```

The CLI renders an ANSI QR code block directly in the terminal, accompanied by the `cmux-pocket://` import URI.

### Interactive endpoint picker and discovery

Running `cmux-pocket qr` in a terminal opens a picker by default. It renders the first
available endpoint's QR code immediately; ↑/↓ (or `j`/`k`) switches endpoints and re-renders
the QR for the new one, and Enter confirms. `q` or Esc cancels. `--endpoint` skips the picker,
and `--no-interactive` keeps the old single-shot behavior for scripts. Non-TTY invocations,
`--json`, and `--url-only` are always non-interactive.

Candidates are discovered locally:

| Candidate | How it is found | Auto-selected |
| --- | --- | --- |
| `--endpoint` | Explicit flag | Yes (skips the picker) |
| Legacy default | `config.endpoint` | Yes |
| Named endpoints | `cmux-pocket endpoint add <URL> --name <NAME>` | Yes, in saved order |
| Tailscale | `tailscale status` **and** a Serve route whose `/` target is this Gateway | Yes |
| Cloudflare Tunnel | `~/.cloudflared/*.yml` ingress hostname whose origin is `127.0.0.1:<gateway-port>`, with a matching local `cloudflared` process | Only when the tunnel process is running |
| USB (ADB reverse) | `adb devices` plus an existing `adb reverse tcp:<port> tcp:<port>` | Never; dev only |

The picker marks each entry `verified` when the Mac-side route was confirmed, and shows
`unverified` for saved endpoints and stopped tunnels. A phone may still be unable to reach
an otherwise verified route (different network), so the app's connection check remains the
final test.

Tailscale discovery only reports a `.ts.net` URL when `tailscale serve status` shows the root
route forwarding to the Gateway; an unconfigured or conflicting route is not offered.

### Saved endpoints and precedence

The non-interactive path resolves one endpoint using strict precedence:

1. **Explicit CLI argument (`--endpoint <URL>`)**:
   Takes top priority. Use this for a single-use connection or testing without modifying your configuration:
   ```bash
   cmux-pocket qr --endpoint wss://custom.proxy.example.com:8443 --name "Workstation"
   ```

2. **Saved endpoints**, in this order: the legacy `config.endpoint`, then entries added through
   `cmux-pocket endpoint`:
   ```bash
   cmux-pocket endpoint add wss://gateway.lan.example --name LAN
   cmux-pocket endpoint add wss://cmux-gateway.example.com --name Tunnel
   cmux-pocket endpoint list
   cmux-pocket qr
   ```
   The legacy form remains supported:
   ```bash
   cmux-pocket config set endpoint wss://custom.proxy.example.com:8443
   ```

3. **Verified Tailscale Serve route** (see above).

4. A Cloudflare Tunnel hostname is selected automatically only while its tunnel process is running.

ADB reverse is offered only in the interactive picker. LAN endpoints are not guessed: add the
TLS hostname behind your Caddy/Nginx proxy with `endpoint add --name LAN`.

**Loopback safety:**
cmux-pocket never automatically assumes a local loopback address (`127.0.0.1`) can be reached by a remote phone over Wi-Fi or cellular. If no remote endpoint is found, the CLI will output actionable setup instructions rather than generating a misleading QR code. To use loopback over ADB reverse, pick the USB candidate or pass `--endpoint ws://127.0.0.1:8088` explicitly.

### Android scanning and deep link import

Two import paths are supported:

1. **In-app QR scanner**:
   In cmux Pocket, open **Settings → Connection & Host** and tap **Scan QR**.
   - Camera permission is requested on first use.
   - If denied, the app provides guidance on enabling camera access in Settings, or continuing with manual entry.
   - Scanning can be canceled at any time, returning safely to the previous screen without altering existing settings.

2. **External QR scanners and deep links**:
   Scanning with an external barcode scanner or tapping a `cmux-pocket://` link will launch cmux Pocket on both cold start and when running in the background.
   - **Compatibility notice:** External scanner behavior depends on the scanner tool and Android vendor ROM. While dedicated barcode scanners (such as ZXing Barcode Scanner) and supported browser links handle custom schemes, some stock camera apps only support `http://` and `https://` URLs. If your stock camera does not recognize the custom scheme, use the in-app scanner (**Settings → Scan QR**).

### Import preview and confirmation

Both in-app scanning and external deep links always open the **Import Gateway Profile** preview dialog:

- **No silent overwrite:** If an existing profile matches the endpoint or name, the dialog flags the match and gives you an explicit choice: **Update Existing** or **Save as New**.
- **No automatic connection on link open:** Opening a link or scanning a code will never initiate a connection automatically. The user must explicitly tap **Import & Connect** or confirm the action.
- **Token masking:** The secret bearer token is masked in the preview (`0123••••cdef`) and can be revealed with the **Show** toggle before saving.
- **Cancellation:** Tapping **Cancel** dismisses the dialog without modifying any saved profiles or changing the active connection.

### Security and credential considerations

> ⚠️ **Bearer Token Security:** The QR code and `cmux-pocket://` URI contain your active Gateway authentication token. Anyone who scans this QR code or opens this link can connect to your Gateway and view active cmux workspaces.
> - Do NOT screenshot the QR code or post it in public chat groups, bug reports, or repositories.
> - Non-loopback endpoints must use TLS (`wss://` or `https://`). The app and CLI strictly reject cleartext non-loopback connections.
> - External deep link payloads are untrusted inputs: the app strictly validates the protocol scheme, version (`v=1`), host, port, and token before displaying the preview dialog.
## 4. Manual configuration in the Android app

1. Open **Settings**.
2. Tap **Add Host** and create a named profile.
3. Set **Host or URL** to your secure endpoint, for example `wss://gateway.example.test`.
4. Use port `443` for a bare hostname, or omit the separate port when the full URL contains its own port.
5. Paste the Gateway token and tap **Reconnect**. Edits are saved as you type; there is no separate apply step.

The app stores profiles and tokens in encrypted Android preferences. A token is never needed in the hostname or URL.

The built-in **USB** profile is reserved for loopback/ADB testing. It cannot replace a TLS boundary for LAN or remote access.

## Reconnection behavior

After an established session loses its WebSocket, cmux Pocket retries the saved endpoint automatically after:

```text
1s → 2s → 4s → 8s → 15s → 30s
```

It makes six automatic attempts, then shows a paused state that requires **Reconnect**. A successful connection resets the schedule. A manual disconnect clears the saved retry target.

If authentication fails repeatedly, first verify that the app token matches the token currently used by the Gateway. If the endpoint or certificate is invalid, correct the profile before retrying.

## Background operation

When cmux Pocket has an active Gateway session, it runs a small Android foreground service with an ongoing **Connection** notification. This keeps the app process important while the phone is backgrounded, so the WebSocket can continue receiving workspace updates and agent-completion events.

Android can still terminate the service after a force-stop, battery-management action, or other system policy decision. If that happens, reopen the app and use **Reconnect**; the bounded retry schedule is described above.

## Agent completion notifications

The Gateway follows cmux's local event stream for agent `Stop`/turn-complete events and completion notifications. The event carries the cmux Workspace and Surface identifiers; the Gateway forwards only those identifiers and a generic completion category to the phone, not terminal text or notification contents.

For agent-hook completions, install the relevant cmux integration (for example, `cmux hooks setup`) so cmux can associate the agent session with its tab. On Android 13 and later, allow the **Agent completions** notification permission. Tapping a notification opens cmux Pocket and selects the matching tab after the Workspace list has synchronized.


## ADB reverse: development only

For emulator or local USB testing, start the Gateway and run:

```bash
adb devices
adb reverse tcp:8088 tcp:8088
```

In the app, select **USB**, use `127.0.0.1:8088`, enter the token, and tap **Reconnect**. Re-run `adb reverse` after reconnecting or rebooting the device.

ADB reverse is intentionally not the normal LAN/remote setup. It depends on USB debugging and does not provide a user-managed network boundary.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| cmux is unreachable | The Rust Gateway remains resident and reports a degraded backend. Start cmux, verify `cmux ping`, then run `cmux-pocket service restart` or `cmux-pocket gateway probe`. |
| Authentication fails | Read the current token from the protected token file and update the app profile. Never log or publish the token. |
| Non-loopback connection is rejected | Change the profile to a trusted `wss://` endpoint. Plaintext LAN `ws://` is unsupported. |
| Phone connects but no Workspaces appear | Run `cmux-pocket gateway probe`. The Gateway must be able to query workspaces from its own process; cmux's **cmux processes only** mode rejects launchd. See [cmux socket access](#cmux-socket-access). |
| TLS handshake fails | Use a certificate trusted by Android and verify the proxy forwards WebSocket upgrades. |
| Tunnel connects but stays in CONNECTING | Use the public TLS endpoint and port, normally `wss://...` on `443`; do not pair a public hostname with local port `8088` unless explicitly configured. |
| Reconnect pauses | Tap **Reconnect** after fixing the endpoint, token, certificate, or network boundary. |
| USB test receives no data | Re-run `adb reverse tcp:8088 tcp:8088` and confirm the device is authorized. |

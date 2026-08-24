# Networking and setup

cmux Pocket connects to the Gateway through a WebSocket. The Gateway remains loopback-only on the Mac; a LAN or Internet connection must terminate TLS before forwarding to it.

## Connection rules

| Path | App endpoint | Intended use |
| --- | --- | --- |
| USB reverse | `ws://127.0.0.1:8088` | Temporary development and emulator/device testing |
| LAN through your TLS proxy | `wss://gateway.example.test` | Recommended when the phone and Mac share a network |
| Tunnel, VPN, or private ingress | `wss://gateway.example.test` | Recommended when the Mac is not directly reachable |

`ws://` is rejected for non-loopback hosts. The Gateway refuses non-loopback bind addresses, so do not try to expose it by changing `CMUX_GATEWAY_HOST`.

## 1. Start cmux and the Gateway

On macOS, install and start cmux, then verify:

```bash
cmux ping
```

Create a token file readable only by your user and start the Gateway:

```bash
mkdir -p "$HOME/.config/cmux-pocket"
umask 077
openssl rand -hex 32 > "$HOME/.config/cmux-pocket/gateway-token"
CMUX_AUTH_TOKEN_FILE="$HOME/.config/cmux-pocket/gateway-token" \
  uv run --with websockets python3 gateway/cmux_gateway.py
```

The Gateway's local listener is normally:

```text
ws://127.0.0.1:8088
```

This local URL is the proxy or tunnel's upstream. It is not the URL to enter in the Android app for LAN or remote use.

Keep the token private. Do not put it in shell history, screenshots, issue reports, committed files, or public tunnel configuration.

## 2. Choose a secure boundary

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

- A Cloudflare account with a domain managed by Cloudflare.
- `cloudflared` installed on the Mac.
- A Gateway token stored in a user-only file.

#### 1. Start the loopback Gateway

Run this in one terminal on the Mac:

```bash
mkdir -p "$HOME/.config/cmux-pocket"
umask 077
openssl rand -hex 32 > "$HOME/.config/cmux-pocket/gateway-token"
CMUX_AUTH_TOKEN_FILE="$HOME/.config/cmux-pocket/gateway-token" \
  uv run --with websockets python3 gateway/cmux_gateway.py
```

The Gateway must remain on `127.0.0.1:8088`. `cloudflared` will connect to this local origin; do not change the Gateway bind address to make it reachable from the network.

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
Token:       contents of ~/.config/cmux-pocket/gateway-token
```

The app should authenticate and display the current Workspaces and Tabs. Keep Cloudflare's hostname, tunnel credentials, Gateway token, and local configuration files private. Cloudflare may restart an edge connection; cmux Pocket's keepalive and bounded reconnect behavior handle normal WebSocket interruptions, but a reconnect can briefly interrupt the active session.

Official references: [Cloudflare Tunnel setup](https://developers.cloudflare.com/tunnel/setup/), [locally managed configuration files](https://developers.cloudflare.com/tunnel/advanced/local-management/configuration-file/), [routing](https://developers.cloudflare.com/tunnel/routing/), and [WebSockets](https://developers.cloudflare.com/network/websockets/).

### VPN or private network

A VPN is suitable when the phone and Mac can reach a private ingress that terminates TLS. The VPN does not remove the WSS requirement: use a trusted `wss://` endpoint and keep the Gateway bound to loopback.

## 3. Configure the Android app

1. Open **Settings**.
2. Tap **Add Host** and create a named profile.
3. Set **Host or URL** to your secure endpoint, for example `wss://gateway.example.test`.
4. Use port `443` for a bare hostname, or omit the separate port when the full URL contains its own port.
5. Paste the Gateway token and tap **Apply & Save**.

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

In the app, select **USB**, use `127.0.0.1:8088`, enter the token, and tap **Apply & Save**. Re-run `adb reverse` after reconnecting or rebooting the device.

ADB reverse is intentionally not the normal LAN/remote setup. It depends on USB debugging and does not provide a user-managed network boundary.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| `MockCmuxBackend` appears in the Gateway log | Start cmux, verify `cmux ping`, then restart the Gateway. Mock mode is testing-only. |
| Authentication fails | Read the current token from the protected token file and update the app profile. Never log or publish the token. |
| Non-loopback connection is rejected | Change the profile to a trusted `wss://` endpoint. Plaintext LAN `ws://` is unsupported. |
| TLS handshake fails | Use a certificate trusted by Android and verify the proxy forwards WebSocket upgrades. |
| Tunnel connects but stays in CONNECTING | Use the public TLS endpoint and port, normally `wss://...` on `443`; do not pair a public hostname with local port `8088` unless explicitly configured. |
| Reconnect pauses | Tap **Reconnect** after fixing the endpoint, token, certificate, or network boundary. |
| USB test receives no data | Re-run `adb reverse tcp:8088 tcp:8088` and confirm the device is authorized. |

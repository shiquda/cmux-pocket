# Architecture

[Quick start](../README.md#quick-start) · [Networking](networking.md) · [Design system](../DESIGN.md)

![Android app connecting through a TLS boundary to the loopback Gateway and cmux on macOS](assets/architecture.svg)

1. **cmux (macOS)** owns the real PTY, VT parsing, terminal state, and process lifecycles.
2. **cmux-pocket Gateway (macOS)** is a Rust daemon bound to `127.0.0.1:8088` by default. It queries cmux over its UNIX domain socket and streams `cmux.render-grid.v1` differential grid frames over WebSocket.
3. **TLS boundary** exposes the loopback Gateway through Tailscale HTTPS Serve, a LAN reverse proxy, or a secure tunnel.
4. **cmux Pocket (Android)** renders grid cells with Jetpack Compose and Maple Mono Nerd Font, handling touch and keyboard input without a local shell or terminal parser. Batched text runs, memoized ARGB cell parsing, and cached row pictures keep rendering fast.

## Network and data boundaries

![Android reaching the loopback Mac Gateway through LAN, Tailscale, or Cloudflare Tunnel](assets/networking-bridges.png)

The Gateway refuses non-loopback binds, and Android requires `wss://` for non-loopback endpoints; cleartext `ws://` is limited to loopback/USB testing. See [connection rules](networking.md#connection-rules) before configuring a proxy or tunnel.

The Gateway authenticates clients with a random bearer token; Android keeps profiles and tokens in Android Keystore-backed encrypted preferences and disables app backup. There is no project-operated backend or analytics service. The camera serves QR pairing only, and agent-completion notifications are optional.

QR codes and profile exports containing access keys are credentials: keep them out of public screenshots, logs, and issues. Report vulnerabilities privately through [GitHub Security Advisories](https://github.com/shiquda/cmux-pocket/security/advisories/new).

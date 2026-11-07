# Bridge and Paseo

Running the bridge, its ports and flags, LAN discovery and Tailscale. Publishing it to npm is in [bridge-publishing.md](bridge-publishing.md).

- Paseo daemon: `paseo daemon` (listens on `ws://127.0.0.1:6767/ws`).
- Ports: plain `ws://` bridge = **8137**, secure (`--secure` / TLS) = **8443** (`defaultPort()` in `lenscode-bridge/src/config.ts`, mirrored by `DEFAULT_BRIDGE_PORT` / `DEFAULT_SECURE_BRIDGE_PORT` in `net/BridgeAddress.kt`). A bare IP typed in the app takes its port from the scheme, so a store build (always secure) needs only the IP; `--port` overrides.
- Run: `./scripts/setup-single-pc.sh` (port 8137; config via `LENSCODE_PORT`, `LENSCODE_PASEO_URL`). It exits if the `paseo` CLI isn't on PATH even when the daemon is running; then `cd lenscode-bridge && npm run dev` does the same job.
- The bridge takes flags too (`npm run dev -- --port 9000`, `--tls-cert/--tls-key`; `--help` lists them) and prints the address to enter in the app on startup.
- LAN discovery: the bridge advertises `_lenscode._tcp` over mDNS (`src/discovery.ts`, `bonjour-service`; TXT `v`, `secure`, `fp`); `--no-discovery` turns it off. The app lists bridges under Settings → Bridge connection (`net/BridgeDiscovery.kt`, only while Settings is open); picking one just fills in the address, trust still comes from the pairing code + cert prompt.
- `--tailscale` (`src/tailscale.ts`) finds the 100.64.0.0/10 interface address (and the MagicDNS name via the CLI if present) and prints it as the address to enter; it exits if there is none.
- Remote machines are all registered hosts reached over SSH; there is no special "local" machine in the app.

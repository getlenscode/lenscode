# lenscode-bridge

Connects the LensCode headset app to the coding agents you run with [Paseo](https://paseo.sh) on your own computer. The app talks to this bridge; the bridge talks to your Paseo daemon.

## Requirements

- **Node.js 22 or newer**
- **Paseo**, installed and running on the same computer (its daemon listens on `ws://127.0.0.1:6767/ws`)
- The headset and this computer on the same network

## Run it

```bash
npx lenscode-bridge
```

It prints the address to enter in the app (**Settings → Bridge connection**), for example `ws://192.168.1.20:8137`. You can say the address instead of typing it: tap **Speak address** and say the digits and "dot".

That is *dev mode*: plain `ws://`, open to everyone on your network. It works with a debug build of the app. A store build only connects over a secure `wss://` address — see below.

### Secure mode (`wss://`)

Give the bridge a TLS certificate and key and it switches to secure mode. A new headset then has to **pair**: the app asks for a 6-digit code, which the bridge prints in this terminal.

```bash
npx lenscode-bridge --tls-cert fullchain.pem --tls-key privkey.pem
```

The certificate must be trusted by the headset, so it has to come from a public certificate authority and match the address you enter in the app (a host name, not just an IP). For example, `tailscale cert <your-machine>.<tailnet>.ts.net` produces one. A self-signed certificate is rejected by a store build.

## Gemini voice routing (optional)

Free-form voice commands ("add a test for the login route") use Google's Gemini. Open `http://<your-address>:8137/setup` on your phone or computer and paste a key from <https://aistudio.google.com/apikey>. It is stored on this computer, and the headset never sees it. Without a key, voice still works through built-in commands (navigate, open a file, accept or discard changes).

## Options

```
--port <n>          port to listen on (default 8137)
--tls-cert <file>   TLS certificate (PEM, full chain)
--tls-key <file>    TLS private key
--paseo-url <url>   Paseo daemon WebSocket (default ws://127.0.0.1:6767/ws)
--project-root <d>  folder served as the "local" workspace (default: current directory)
-v, --version       print the version
-h, --help          show help
```

The same settings are available as environment variables: `LENSCODE_PORT`, `LENSCODE_TLS_CERT`, `LENSCODE_TLS_KEY`, `LENSCODE_PASEO_URL`, `LENSCODE_PASEO_PASSWORD` (if your Paseo daemon has one), `GEMINI_API_KEY`.

## Where it keeps things

`~/.config/lenscode-bridge/`: `devices.json` (paired headsets), `gemini.json` (your Gemini key, readable only by you).

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

It prints the address to enter in the app (**Settings → Bridge connection**), for example `ws://192.168.1.20:8137` (or `wss://192.168.1.20:8443` in secure mode). You can say the address instead of typing it: tap **Speak address** and say the digits and "dot".

That is *dev mode*: plain `ws://`, open to everyone on your network. It works with a debug build of the app. A store build only connects over a secure `wss://` address — see below.

### Secure mode (`wss://`)

```bash
npx lenscode-bridge --secure
```

It listens on port 8443 (plain mode uses 8137, so both can run together). The bridge makes its own certificate (kept in `~/.config/lenscode-bridge/self-signed/`) and prints its **fingerprint**, like `510D-4148-395F-0618-6472`. The first time the headset connects it shows the same fingerprint: check that the two match and tap **Trust & connect**. The headset then remembers that exact certificate, so a changed IP address is fine; if the certificate ever changes you are asked again, with a warning. A new headset then **pairs**: the app asks for a 6-digit code, which the bridge prints in this terminal.

No domain, certificate authority or tunnel is needed. Don't skip the fingerprint check: it is what proves the headset is talking to your bridge and not something else on the network.

If you would rather use a certificate from a public authority (a host name you own, `tailscale cert`, and so on), pass it in and the headset trusts it with no prompt:

```bash
npx lenscode-bridge --tls-cert fullchain.pem --tls-key privkey.pem
```

Keep `privkey.pem` private. Anyone who has it can impersonate your bridge.

## Gemini voice routing (optional)

Free-form voice commands ("add a test for the login route") use Google's Gemini. Open `http://<your-address>:8137/setup` on your phone or computer and paste a key from <https://aistudio.google.com/apikey>. It is stored on this computer, and the headset never sees it. Without a key, voice still works through built-in commands (navigate, open a file, accept or discard changes).

## Options

```
--port <n>          port to listen on (default 8137; 8443 in secure mode)
--secure            secure mode with a certificate the bridge makes itself
--tls-cert <file>   TLS certificate (PEM, full chain) from a public authority
--tls-key <file>    TLS private key
--paseo-url <url>   Paseo daemon WebSocket (default ws://127.0.0.1:6767/ws)
--project-root <d>  folder served as the "local" workspace (default: current directory)
-v, --version       print the version
-h, --help          show help
```

The same settings are available as environment variables: `LENSCODE_PORT`, `LENSCODE_SECURE=1`, `LENSCODE_TLS_CERT`, `LENSCODE_TLS_KEY`, `LENSCODE_PASEO_URL`, `LENSCODE_PASEO_PASSWORD` (if your Paseo daemon has one), `GEMINI_API_KEY`.

## Where it keeps things

`~/.config/lenscode-bridge/`: `devices.json` (paired headsets), `gemini.json` (your Gemini key, readable only by you), `self-signed/` (the certificate and key made by `--secure`).

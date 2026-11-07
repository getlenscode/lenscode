# Website (`website/`)

Dev server, deployment and icons for the marketing site.

- Dev server: `npm run dev` (http://localhost:3000, or 3001 if 3000 is busy).
- Deploy to Cloudflare (static): `website/scripts/deploy-cloudflare.sh` (`--domain <host>`, `--preview`, `--dry-run`, `--build-only`; see `--help`). Needs `npx wrangler login` or `CLOUDFLARE_API_TOKEN`. The workers.dev URL ends in the account subdomain; use a custom domain to hide it.
- Icons: `public/lenscode-logo.png` (transparent background) is the source for `public/favicon.png`, `public/apple-touch-icon.png` and `app/favicon.ico`.
- `tests/rendered-html.test.mjs` still asserts the pre-pitch copy and fails until updated.

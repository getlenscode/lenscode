# Publishing the bridge (npm)

Release steps for the `lenscode-bridge` npm package.

- `cd lenscode-bridge && npm run build` bundles `dist/lenscode-bridge.js` (esbuild; `@lenscode/protocol` is bundled in, the npm deps stay external); `npm pack` makes the tarball. Published as the unscoped `lenscode-bridge` (the `@lenscode` scope does not exist); users run `npx lenscode-bridge`. Current: `0.1.0-alpha.3`.
- Release: bump `version` in `lenscode-bridge/package.json` and `BRIDGE_VERSION` in `src/server.ts` together (a test checks they match; `npm version <v> --no-git-tag-version` also updates the lockfile), run `npm test`, then `npm login` (if needed) and `npm publish`. A version number can never be reused.
- Publishing opens a browser auth step and can sit "staged" for a few minutes before `npm view` shows it; don't publish again (`E409`). A `404` on `PUT` means you're logged out (`npm whoami` gives 401), not a missing package.
- License: GPL-3.0-or-later. The root `LICENSE` is copied into `lenscode-bridge/` and listed in its `files`. `alpha.1` and `alpha.2` were ISC (deprecate with `npm deprecate`; installed copies stay ISC); `alpha.3` is the first GPL release.

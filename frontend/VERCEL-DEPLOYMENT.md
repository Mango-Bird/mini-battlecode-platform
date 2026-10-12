# Vercel package-manager fix — 2026-10-13

## Diagnosis

Repository root originally had no package.json. frontend/package.json already pinned pnpm@11.25.0; frontend/pnpm-lock.yaml uses format9.0 and frontend/pnpm-workspace.yaml allows esbuild builds. There is no root pnpm workspace/lockfile or root vercel.json. No local .vercel/project.json exists, so live dashboard settings and the deployed Git revision could not be inspected.

The supplied log confirms Corepack did not find packageManager in the manifest it selected. Vercel supports detecting repository-root packageManager for monorepos. Its documented fallback for an override command such as pnpm install is the oldest bundled pnpm (example:6). That fallback cannot read this lock format, ignores the existing file, and then reports that frozen/headless installation has no usable lockfile. The final error therefore does not mean the file is physically missing. The excerpt does not contain the binary's version; pnpm6 is the documented fallback, not a version observed in this deployment.

Sources: [Vercel package managers](https://vercel.com/docs/package-managers), [root Corepack pin detection](https://vercel.com/changelog/improved-support-for-pnpm-corepack-and-monorepos), [Corepack environment](https://vercel.com/docs/builds/configure-a-build).

## Changes

- Added repository-root package.json with only private deployment metadata and packageManager pnpm@11.25.0. It introduces no dependencies, workspace, root lockfile or changes to Java.
- frontend/vercel.json explicitly invokes Corepack for install and build, prints the selected pnpm version, and retains frozen-lockfile. SPA rewrites/security headers/dist are unchanged.
- frontend/package.json, pnpm-lock.yaml and pnpm-workspace.yaml remain unchanged: already consistent with tested pnpm11.25.0. Lock format9.0 is a format version, not an instruction to downgrade pnpm to9.

## Dashboard settings to update/check

| Setting | Value |
|---|---|
| Root Directory | frontend |
| Framework | Vite |
| Node.js | 24.x |
| Install Command | corepack pnpm --version && corepack pnpm install --frozen-lockfile |
| Build Command | corepack pnpm build |
| Output Directory | dist |
| ENABLE_EXPERIMENTAL_COREPACK | 1, in Production and any Preview environments being deployed |
| VITE_WS_URL | Real public wss://<render-service>.onrender.com/ws |

Remove stale dashboard command overrides or align them with frontend/vercel.json. Enable source files outside Root Directory if repository-root metadata is otherwise excluded. Deploy a Git revision containing both root package.json and updated frontend/vercel.json; redeploy without the existing build cache once after changing package-manager settings. Agent has not committed, pushed, redeployed or changed dashboard settings.

Expected install log: pnpm11.25.0, no missing-packageManager warning, no incompatible-lockfile warning, successful frozen install. If it still reports missing metadata, confirm deployed commit/root directory before changing versions. A Corepack download/network error is a separate failure, not grounds to remove the lockfile or use an unfrozen install.

## Local verification

Working directory D:\Socket\BattleCode\frontend, pnpm11.25.0:

- pnpm install --frozen-lockfile: PASS, already up to date.
- pnpm test: PASS,15 tests/5 files.
- pnpm build: PASS, TypeScript and Vite. Verification process used VITE_WS_URL=wss://deployment-check.example/ws, a placeholder, not the production endpoint.
- SHA256 lockfile before and after:67101CFA8BF68DDC26A059C4C34554A062254150D21498A13104F9AB8628894F; byte-for-byte unchanged.

Logs: vercel-fix-install.log, vercel-fix-test.log, vercel-fix-build.log in frontend (gitignored). Local verification used the installed pnpm binary; a Corepack executable is not available in this local runtime. The provider's Corepack invocation and actual Vercel deployment remain unverified until the operator redeploys with the settings above. Production must rebuild with the real WSS URL.

# Free cloud staging deployment guide — Phase11B.1

Operator instructions only. Agent did not connect accounts, push, deploy or import production data. Use **new staging projects/database**; keep existing room WEB_ONLY deployment and local competitive environment intact. Until real cloud/browser acceptance below passes, deployment verification is NOT TESTED.

## 1. Review and build locally

```powershell
Set-Location D:\Socket\BattleCode
mvn -P local-bots,safe-staging clean test
mvn -P local-bots,safe-staging package
$runtimeRoot='C:\Users\Admin\.cache\codex-runtimes\codex-primary-runtime\dependencies'
$env:Path="$runtimeRoot\node\bin;$runtimeRoot\bin\fallback;$env:Path"
Set-Location frontend
$env:VITE_RUNTIME_MODE='SAFE_STAGING_WEB_ONLY'
$env:VITE_WS_URL=''
$env:VITE_BOT_API_URL=''
pnpm.cmd install --frozen-lockfile
pnpm.cmd test
node --test bff-tests/proxy.node.mjs
pnpm.cmd build
```

Staging build deliberately rejects VITE_WS_URL/VITE_BOT_API_URL, including values inherited from `.env.local`. Only staging mode uses same-origin `/api/staging/`. Remove those variables from the separate Vercel project; never give it DB/proxy/CSRF secrets with a VITE_ prefix. Legacy production build retains its existing real WSS requirement and configuration.

## 2. Neon Free — fresh staging database

Create a Neon project/branch/database explicitly for staging, preferably close to Render. Do not connect production database or import source/results. Get the **direct** hostname, not `-pooler`: the current one-instance application lease needs a session-affine connection. [Neon TLS guidance](https://neon.com/docs/connect/connect-securely) explains certificate verification; this runtime enforces verify-full using the Java CA trust store. Wrong CA/hostname or TLS-off must fail; never use non-validating SSL factory or plaintext fallback.

Using Neon owner console, create two independent LOGIN roles with unique secrets: `battlecode_staging_migrator` (schema/table owner for Flyway) and `battlecode_staging_app` (runtime, no ownership, no role memberships granting elevated writes). On the dedicated staging database grant the migrator USAGE/CREATE on public schema; revoke public CREATE. Do not use neondb_owner credentials in the app. Role creation is manual so no credentials are embedded in SQL/repository. Use random32-byte-or-more hex/base64url values (no padding), matching the config validator.

Prepare a private ignored env file from `config/safe-staging.env.example`, fill exact Vercel/Render staging origins (no trailing slash) and independent proxy/CSRF/DB secrets. Provision migrator credentials **only for the migration invocation**, not Render service. Existing Flyway V1–V5 run once explicitly:

```powershell
Set-Location D:\Socket\BattleCode
# Environment variables are already provisioned in this PowerShell process from a private secret source.
# BATTLECODE_DB_* refers to runtime identity; add the two migration-only fields for this command.
# $env:BATTLECODE_MIGRATION_DB_USER='battlecode_staging_migrator'
# $env:BATTLECODE_MIGRATION_DB_PASSWORD=<secret from manager, never commit>
java -cp 'staging-web\target\staging-web-1.0.0-SNAPSHOT.jar;staging-web\target\dependency\*' dev.battlecode.staging.StagingMigrateMain
Remove-Item Env:BATTLECODE_MIGRATION_DB_USER,Env:BATTLECODE_MIGRATION_DB_PASSWORD -ErrorAction SilentlyContinue
```

Run `staging-web/sql/runtime-grants.sql` as migration owner after migrations. Verify app role has no schema CREATE, no rating/queue/result/tournament writes, no UPDATE/DELETE bot_versions. Readiness rejects schema CREATE and elevated rating/source-version writes. Migration credentials never belong in frontend, guest or HTTP runtime. Flyway startup is not automatic in StagingMain; incompatible/unmigrated DB returns503 readiness.

No V6 migration was required. Do not alter V1–V5/checksums. Grant review is explicit for future migrations. Free Neon quotas/suspend/backup policy must be checked in your own plan console; this is staging storage, not a production backup/PITR promise. See [Neon plans](https://neon.com/docs/introduction/plans).

## 3. Render Free — new Java staging service

After your own review, push source to your GitHub repository yourself. Do not include `.env*`, `.tools`, keys or credentials. Create a new Render Web Service, Docker runtime, Free plan, repository root context, `Dockerfile.staging`. Or select `render.staging.yaml` as Blueprint path (do not apply it to an existing production service name). Auto deploy is off. [Render Blueprint fields](https://render.com/docs/blueprint-spec).

Set environment from `config/safe-staging.env.example`: BATTLECODE_ENV=staging, BATTLECODE_MODE=SAFE_STAGING_WEB_ONLY, BATTLECODE_EXECUTION=disabled, exact frontend/backend origins, independent proxy/CSRF secrets, direct TLS DB URL and runtime-role credentials. Render provides PORT; do not bake port/domain into image. No worker, DOCKER_HOST, coordinator count or execution flags are accepted. Never set WEB_ONLY here: that selects the unrelated legacy room runtime.

Image uses Java21 nonroot JRE with bounded heap/native components. No Docker socket, Python, QuickJS binary, worker image or persistent disk. Only HTTP PORT is bound. Health path `/healthz` (process alive); check `/readyz` separately for DB/lease/schema/role status. Healthy process with503 readiness is not a ready authenticated API. Render Free may spin down and have slow cold starts; it is not guaranteed uptime. No keepalive job is introduced. [Render Free constraints](https://render.com/docs/free).

Deploy only when you choose; visit HTTPS `/healthz` and `/readyz`. Inspect private sanitized logs. Unknown API routes must fail closed. Direct public `/api/staging/auth/me` without BFF proxy credentials returns403 even with forged forwarding headers. No raw TCP/worker endpoint is expected.

## 4. Vercel Hobby — separate staging project and BFF

Use a distinct project with Root Directory `frontend`, framework Vite, install `corepack pnpm install --frozen-lockfile`, build `corepack pnpm build`, output `dist`, Node22+ runtime. packageManager remains pnpm11.25.0; lockfile unchanged. Hobby eligibility/quotas apply; confirm personal non-commercial usage. Do not point this project at the current production room frontend's environment.

Public build variable: `VITE_RUNTIME_MODE=SAFE_STAGING_WEB_ONLY` only. Server-only function variables:

* `STAGING_BFF_MODE=SAFE_STAGING_WEB_ONLY`
* `STAGING_FRONTEND_ORIGIN=https://<stable-staging-vercel-domain>`
* `STAGING_BACKEND_ORIGIN=https://<new-render-service>.onrender.com`
* `STAGING_PROXY_SECRET` identical to Render; no DB/CSRF secret required by BFF.

Use `frontend/vercel.staging.json`, which preserves `/api/` function routing before SPA fallback and adds self-only CSP. Existing `frontend/vercel.json` is intentionally unchanged. Alternate configuration is not automatically selected by a Git import: either maintain a dedicated staging branch with vercel.staging.json copied to vercel.json **only on that branch/new project**, or use Vercel CLI `--local-config` against the separate linked staging project. Operator-only CLI example after creating/checking the new project:

```powershell
Set-Location D:\Socket\BattleCode\frontend
# Use your installed Vercel CLI. These commands connect/deploy only when YOU run them.
vercel link --project mini-battlecode-staging
vercel deploy --local-config vercel.staging.json --prod
```

Here `--prod` means the stable environment of the **separate staging project**, not permission to change the existing production project. Check `.vercel/project.json` identity before any operator deploy; it must never be committed. New api/staging/[...path].mjs implements a bounded same-origin BFF, not an external catch-all proxy. Default entrypoint refuses without explicit STAGING_BFF_MODE. Exact frontend origin must match the stable URL; previews are denied unless separately configured as another isolated environment. Configure matching origin on Render and redeploy both if URLs change.

Cookies remain on Vercel host; Java receives them only via authenticated BFF. No cross-site browser cookies are required. Test this behavior in actual Chromium/Firefox/WebKit; do not infer it from API tests. Third-party-cookie settings should not affect the same-origin flow, but provider routing/headers/cookie forwarding still require live verification.

## 5. Local automated staging smoke/security commands

No cloud accounts are needed. Use disposable PostgreSQL and test TLS only:

```powershell
Set-Location D:\Socket\BattleCode
$dockerPath='C:\Users\Admin\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe'
& scripts/start-phase10-test-db.ps1 -Docker $dockerPath
& scripts/prepare-staging-test-tls.ps1 -Docker $dockerPath -OpenSsl 'C:\Users\Admin\miniconda3\Library\bin\openssl.exe'
$testConfig=Get-Content .tools/phase10/postgres-test.json -Raw | ConvertFrom-Json
$tlsConfig=Get-Content .tools/phase11b1-tls/config.json -Raw | ConvertFrom-Json
$env:BATTLECODE_TEST_DB_URL=$testConfig.url
$env:BATTLECODE_TEST_DB_USER=$testConfig.user
$env:BATTLECODE_TEST_DB_PASSWORD=$testConfig.password
$env:BATTLECODE_TEST_TLS_URL=$tlsConfig.url
mvn -P local-bots,safe-staging -pl staging-web -am test '-Dtest=StagingPostgresIT,StagingTlsIT,WorkerAbsenceTest,StagingPolicyTest' '-Dsurefire.failIfNoSpecifiedTests=false' "-Djavax.net.ssl.trustStore=$($tlsConfig.trustStore)" "-Djavax.net.ssl.trustStorePassword=$($tlsConfig.trustPassword)"
```

Tests bind loopback/dynamic ports, exercise auth/CSRF/source/role/size/reconnect/TLS and close threads/connections. They are not HTTPS browser E2E. Scripts label-check before configuring the disposable container. Clean the owned test container/temporary credentials afterward; never run cleanup against your application database. Test truststore is JVM-local, not installed globally.

Optional actual container smoke with a **reviewed staging DB/env** (no browser login through plain HTTP):

```powershell
docker build -f Dockerfile.staging -t battlecode-staging-local .
# Private ignored env file contains correct safe-staging values, never worker/migration credentials.
docker run --rm --name battlecode-staging-local --memory 512m --cpus 0.1 --env-file .tools/safe-staging.env -p 127.0.0.1:10000:10000 battlecode-staging-local
# Separate PowerShell:
Invoke-RestMethod http://127.0.0.1:10000/healthz
Invoke-RestMethod http://127.0.0.1:10000/readyz
docker stats --no-stream battlecode-staging-local
# Stop sends SIGTERM for graceful shutdown:
docker stop battlecode-staging-local
```

## 6. Cloud acceptance and rollback

Two isolated browser profiles: register/login, edit/save immutable versions, verify cross-account denial, refresh/logout/expiry. Inspect Secure/HttpOnly/Strict/host-only cookies on Vercel and CSRF mutations. Confirm no source/token in browser storage/logs, no source in replay/profile responses. Fresh rankings/history show genuine empty states. Arena/Ranked/Tournaments explain execution unavailable; crafted calls remain404/403. Check cold starts, DB suspend/reconnect, readiness503, free memory/CPU and response latency. No fake ratings/replays/fixtures seeded into accounts.

Rollback: disable staging project/BFF or Render staging service; rotate shared proxy/session-CSRF/DB secrets if suspected compromised; revoke staging sessions. Redeploy previous known staging image/config only with V5 compatibility. No destructive migration rollback exists or is needed; do not downgrade/rewrite Flyway or delete user data to make startup succeed. Restore only a verified staging backup with operator approval. Existing production services/database and local environment are independent and remain untouched.

# Phase 6 — Automated Bots, LAN Validation and Benchmark

Status: **IMPLEMENTED; LAN NOT VERIFIED.** Phase 6 does not change the protocol or production game rules. A Phase 6 COMPLETE claim is withheld until the two-device LAN checklist in `docs/lan-testing.md` is completed.

## Implementation

- Added `BotMatchContext`, `BotStrategies` and one-connection `BotClient` with bounded blocking event polling, at-most-once action per turn, terminal handling and cleanup.
- Added `BotMain` for a bot joining a user-created room and `BotDemoMain` for a two-bot match against a running server.
- Added deterministic-seeded RPS choice and a Number Guessing midpoint strategy that narrows its interval from public HIGHER/LOWER history only.
- Added `BenchmarkRunner`/`BenchmarkMain`: two clients per room, at most 50 participants, bounded overall deadline and opt-in profiles.
- Added LAN preflight script and LAN/benchmark/operator documentation.
- No protocol, server, engine, competition, rule implementation, wire field or protocol version changed. Bot code imports the existing client/protocol layer only.

## Verification

Focused command:

```powershell
mvn -o -B -pl client,server -am '-Dtest=BotStrategiesTest,Phase6BotIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Result: **8 tests PASS**, 0 failures/errors/skips; log: `phase6-focused-test.log`. This includes 4 client strategy/profile tests and 4 real loopback server integration tests: bot-v-bot completion, peer-disconnect forfeit, match cancellation, and benchmark smoke. Integration checks assert sessions and TCP connections are cleaned up.

The benchmark smoke profile is two real TCP bot connections, one room, one match, and must finish with zero server sessions/connections and no benchmark errors. The automated integration test asserts those values. Packaged-server loopback measurements on 127.0.0.1:17777:

| Profile | Handshakes | Requests | Completed | Elapsed | Handshake P50/P95 | Match P50/P95 | Throughput | Errors/timeouts |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| 2 clients / 1 room | 2/2 | 7/0 failures | 1/1 | 276 ms | 3/59 ms | 50/50 ms | 3.617 matches/s | 0/0 |
| 10 clients / 5 rooms | 10/10 | 42/0 failures | 5/5 | 195 ms | 6/64 ms | 55/63 ms | 25.533 matches/s | 0/0 |

These are short local functional samples, not capacity claims. See `phase6-benchmark-smoke.log` and `phase6-benchmark-10.log` for raw output.

## LAN status and remaining acceptance

The preflight and exact two-machine checklist are documented in `docs/lan-testing.md`. This environment only ran loopback tests; no second physical machine was available. Therefore LAN reachability and cross-device match play are **NOT VERIFIED**, and Phase 6 is **not marked COMPLETE**.

## Full regression and package verification

```powershell
mvn -o -B clean test
mvn -o -B package
```

Both commands passed on 2026-10-12. `mvn -o -B clean test` ran **175 tests**, 0 failures/errors/skips; log: `phase6-clean-test.log`. `mvn -o -B package` completed BUILD SUCCESS; log: `phase6-package.log`. No Phase 7 work, tournament service, protocol extension or load-balancing change is included.

# Phase 5 — Production Game Rules & Multi-Rule Integration

Status: COMPLETE. Final verification ran on 12/10/2026 (Asia/Bangkok), using Temurin JDK 21.0.12.1 and Maven 3.9.16.

## 1. Overview

Phase 5 adds two production `GameRule` implementations to the existing Java 21 Mini Battlecode Platform:

- `rock-paper-scissors` — simultaneous Rock-Paper-Scissors, first to a configurable round-win target.
- `number-guessing` — sequential two-player guessing of a server-generated secret.

Both use the existing Phase 4 GameEngine, MatchManager, room lifecycle, private player snapshots, event queue and protocol 1.0. No network, protocol, engine or competition production Java source was changed. No API, command, enum, wire field or protocol version was added.

## 2. Files added or changed

Added:

- `rules-impl/src/main/java/dev/battlecode/rulesimpl/RockPaperScissorsRule.java`
- `rules-impl/src/main/java/dev/battlecode/rulesimpl/NumberGuessingRule.java`
- `rules-impl/src/test/java/dev/battlecode/rulesimpl/RockPaperScissorsRuleTest.java`
- `rules-impl/src/test/java/dev/battlecode/rulesimpl/NumberGuessingRuleTest.java`
- `server/src/test/java/dev/battlecode/server/ProductionGameRulesIntegrationTest.java`
- `docs/phase5-report.md`

Changed:

- `server/src/main/java/dev/battlecode/server/RoomTcpServer.java` — production registry composition.
- `server/src/main/java/dev/battlecode/server/ServerMain.java` — startup message lists production rules.
- `client/src/main/java/dev/battlecode/client/CliClient.java` — room option arguments and game-specific help.
- `server/src/test/java/dev/battlecode/server/RoomManagementIntegrationTest.java` — LIST_RULES assertion now expects the three production registrations.
- `README.md`, `docs/architecture.md`, `docs/phases.md` — current production behavior and player instructions.

Not changed: Technical Contract or protocol schema documentation (`docs/protocol.md`), POM dependency graph, production Java sources under network/protocol/engine/competition. All Phase 1–4 regression scenarios remain; one existing LIST_RULES expectation was updated to include the two new required production IDs, with its config-validation assertions retained. No database, frontend, auth, TLS, Docker, tournament, AI, persistence or dynamic rule loading was introduced.

## 3. Dependency architecture

The existing graph remains:

```text
server → network, protocol, competition, rules-api, rules-impl
network → protocol
competition → engine, rules-api
engine → rules-api
rules-impl → rules-api + Java standard library
client → protocol
```

`RoomTcpServer` default composition registers all three rules in `InMemoryRuleRegistry`. `LIST_RULES` remains registry-driven. `CREATE_ROOM` resolves and validates the chosen rule through Lobby; `START_MATCH` resolves it through MatchManager. `NetworkRequestDispatcher` has no rule-specific branching. Both game rules are immutable/stateless implementations; per-match mutable values live in immutable private `RuleState` objects.

## 4. Rock Paper Scissors

ID: `rock-paper-scissors`. Exactly two players (`maxPlayers=2`); `roundsToWin` defaults to 3 and accepts 1..10. No other rule option is accepted.

Each action must contain only `choice=ROCK`, `choice=PAPER` or `choice=SCISSORS`. The first valid choice does not advance the shared turn. Engine submitted-player tracking rejects a second action from that player while the opponent may still submit. A tie gives no point. Winning rounds increment only the winner's score; the match ends at the configured target. Phase 4 `maxTurns` remains the upper bound and cancels without inventing a winner if reached first.

The current turn view shows each player's submitted flag; the recipient may see their own pending choice, but cannot see the opponent's. Resolved rounds expose both choices, round result and scores in each recipient's private `STATE` view. The final reveal and winner are carried by the existing FINISHED winners/reason fields because terminal Phase 4 snapshots intentionally omit rule views.

## 5. Number Guessing

ID: `number-guessing`. Exactly two players (`maxPlayers=2`). Options: `minNumber` default 1 and `maxNumber` default 100. The inclusive range must contain 2..1,000,000 integers; range arithmetic uses `long` before narrowing to avoid overflow. Unknown options and malformed integers are rejected.

The production no-argument rule uses `ThreadLocalRandom` to generate a per-match secret in range. A constructor accepting a bounded random-offset function supports deterministic unit/integration tests. It is used only by tests for reproducible outcomes.

Only the current player may submit one `guess=<integer>` action, with an in-range integer. An incorrect guess returns `HIGHER` or `LOWER` and passes to the other player. A correct guess ends the match. Timeouts pass the turn. The existing engine cancels at `maxTurns`; no winner is inferred. A disconnect forfeits to the remaining connected player.

The secret is absent from public view/history while the match is running. The view contains the configured range, current player, turn metadata and public guess/hint history. The view retains at most 28 guesses (four values per entry plus seven fixed values fits the engine's 120-field view cap). On a correct win, the secret is included in the existing FINISHED reason string for verification. A disconnect-forfeit reason does not reveal the secret.

## 6. Registry integration

Production IDs:

| Rule ID | Gameplay | Options |
|---|---|---|
| `room-only-no-gameplay` | No; descriptor only | none |
| `rock-paper-scissors` | Yes; simultaneous | `roundsToWin` (default 3; 1..10) |
| `number-guessing` | Yes; sequential | `minNumber` (default 1), `maxNumber` (default 100) |

The dispatcher still serializes `option.*` into the existing `GameConfig.options` map. No rule IDs or gameplay logic are hardcoded in NetworkRequestDispatcher. FakeMatchRule remains test-only.

## 7. Privacy, timeout and fairness

RPS uses `activePlayers` and `isTurnComplete` so both submissions share one expectedTurn/deadline. The engine's submitted set enforces one successful action per player per round. `viewFor` hides current unrevealed choices and returns recipient-specific immutable maps. Timeout awards the round to the sole submitter; two missing choices produce a draw with no score. Stale timeout tokens remain an engine responsibility. Disconnect returns a terminal forfeit outcome when one player remains connected; if the engine observes no connected player, its Phase 4 cancellation policy takes precedence.

Number Guessing uses the default single currentPlayer turn. Its currentPlayer state advances on incorrect guess and timeout. The secret is generated server-side, never accepted from action payload, and never appears in preterminal snapshots. Guess history is public and bounded in snapshots. A correct result reveals the secret in the existing terminal reason. Disconnect and `MAX_TURNS` follow Phase 4 winner/cancellation policy.

## 8. TCP demo commands

Build and start production server:

```powershell
Set-Location D:\Socket\BattleCode
mvn package
java -cp "server\target\server-1.0.0-SNAPSHOT.jar;server\target\dependency\*" dev.battlecode.server.ServerMain 127.0.0.1 7777
```

Start two clients in separate terminals:

```powershell
java -cp "client\target\client-1.0.0-SNAPSHOT.jar;client\target\dependency\*" dev.battlecode.client.CliClient 127.0.0.1 7777 Player-A
java -cp "client\target\client-1.0.0-SNAPSHOT.jar;client\target\dependency\*" dev.battlecode.client.CliClient 127.0.0.1 7777 Player-B
```

RPS: A runs `create_room rock-paper-scissors 2 100 1000 roundsToWin=1`; B runs `join_room <roomId>`; A runs `start_match <roomId>`; A can send `action <matchId> 0 choice=ROCK` and B can send `action <matchId> 0 choice=SCISSORS` (or either other valid choice). For rounds that do not finish the match, use the turn from the latest event.

Number Guessing: A runs `create_room number-guessing 2 100 1000 minNumber=1 maxNumber=100`; B joins; A starts; the current player sends `action <matchId> <turn> guess=<integer>`. Alternate by the latest event until correct or until the max-turn cancellation. `help` lists rule IDs, option keys, action keys and event guidance.

Multi-rule: create/start one RPS room and one Number Guessing room with four clients; their match IDs, event recipients, configs, state and outcomes remain separate.

## 9. Tests and demo evidence

Focused Phase 5 command:

```powershell
mvn -o -pl rules-impl,server -am '-Dtest=RockPaperScissorsRuleTest,NumberGuessingRuleTest,ProductionGameRulesIntegrationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

Focused run result: 21 new tests PASS, 0 failures/errors/skips. The 14 rule unit tests cover configuration and bounds, default and injected randomness, all RPS winning relationships and ties, scoring/finish, simultaneous privacy/submission, timeout, disconnect, invalid payload/turn, state isolation and bounded history. The 7 real TCP tests use `RoomTcpServer` and production rules to verify default registry IDs, RPS and Number Guessing handshakes/rooms/actions/events/winners/cleanup, private views, timeout and max-turn behavior, disconnect forfeits, and two concurrent rooms without cross-room state/event leakage.

Actual production CLI runs used the packaged `ServerMain` and `CliClient` classes over loopback:

- Multi-rule run: server port 64466; RPS match `83b13574-300a-4622-819b-50f4c7575f40`, result `RPS_WIN;round=1;choices=ROCK,SCISSORS;roundWinner=feca1278-15b7-4942-83c8-4eacdefd211e`; Number Guessing match `8ed5d39b-3b12-4dce-9b92-446e7676bed2`, result `CORRECT_GUESS;secret=1`. Four CLI processes ran the two rooms concurrently. A/B each received only the RPS match event; C/D each received only the Number Guessing match event. Both rooms emitted CLOSED after FINISHED.
- Hint run: server port 53482; Number Guessing match `caf90eb4-33d4-4cee-8925-6dde20ecb69a`. Using the real random secret and public events, guesses were 50 (LOWER), 25 (LOWER), 12 (HIGHER), 18 (HIGHER), 21 (HIGHER), 23 (HIGHER), 24 (CORRECT). The final event contained `CORRECT_GUESS;secret=24`; winner was the player who guessed 24. This is direct CLI output, not a deterministic test secret.

Full reactor commands and results:

| Command | Result | Test totals | Log |
|---|---|---:|---|
| `mvn -o -B clean test` | BUILD SUCCESS | 167 run; 0 failures, errors, skipped | `phase5-clean-test.log` |
| `mvn -o -B package` | BUILD SUCCESS | 167 run; 0 failures, errors, skipped | `phase5-package.log` |

All 9 reactor projects succeeded. The 167 tests comprise the 146 Phase 1–4 regression scenarios, 14 new rule unit tests and 7 production-rule TCP integration tests. The sole existing expectation change is the default LIST_RULES result, which now verifies all three registrations required by Phase 5. Phase 1–4 TCP, protocol, client, engine and competition suites remain green. No tests were skipped.

## 10. Regression results

Phase 4 baseline was 146 passing tests (85 Phase 1–3 regressions plus 61 Phase 4 tests). Phase 5 adds 21 tests. The existing default-registry test expectation now checks all three required production IDs; its config-validation coverage remains intact.

`mvn clean test` and `mvn package` both passed after a clean build. The `server` production JAR was inspected with `jar tf`; it contains no `FakeMatchRule`, `MatchLifecycleIntegrationTest` or `ProductionGameRulesIntegrationTest`. The runtime `rules-impl` dependency JAR contains RoomOnlyRule and both new production rules. Dependency imports were reviewed: rules-impl uses only rules-api and Java standard library; network and protocol sources remain untouched.

## 11. Known limitations

- Protocol 1.0 has no terminal rule-view payload. Final RPS reveal and Number Guessing secret use the existing `reason` string; clients should treat it as human-readable rule result text.
- Number Guessing retains the most recent 28 public guesses in each view to remain inside existing event budgets.
- Both games require a room capacity of exactly two.
- Rules and match state are in memory and disappear on server restart; there is no replay persistence, reconnect/resume or durable ranking.
- CLI action values are whitespace-delimited `key=value` tokens and cannot contain spaces.
- Existing Phase 4 lifecycle uses one competition lock and trusted synchronous callbacks; no plugin sandbox or throughput/load-test claim is added by Phase 5.

## 12. Phase 6 direction

No Phase 6 work is included. Potential follow-up areas, only after a separate scope decision, include match result/history persistence, reconnect policy, tournament orchestration, or a frontend. The Phase 5 dependency graph and trusted rule-registration boundary should remain the baseline.

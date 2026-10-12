# Phase 6 — Client Bots, LAN Validation and TCP Benchmark Design

## Scope and compatibility

Phase 6 adds client-side automated players and bounded operational tools. The bot depends on the existing client transport and protocol envelope only; it does not import server, engine, competition, or rule implementation code. Existing protocol 1.0 commands, payloads, event fields, rule behavior, and server composition remain unchanged. Existing CLI clients continue to work.

## Bot API and lifecycle

`BotStrategy` is a deterministic client-side decision contract receiving a typed immutable match context: rule id, own session id, match id, turn, and the recipient's current public/private view. It returns an action payload or no action. RPS acts once when its own `submitted.<sessionId>` is false. Number Guessing acts only when `yourTurn=true` and selects a midpoint from the configured interval narrowed by public HIGHER/LOWER history. Strategies contain no network or server dependencies.

`BotClient` owns one `DefaultTcpClient` connection and one event loop. It performs HELLO, may create a room as its host or join an existing room, and may start a match only for a room it created. The event loop blocks on the existing bounded event queue with a finite poll; it never busy-waits. It responds only to STARTED/STATE for its current match, tracks submitted turns to prevent duplicate actions, and exits on FINISHED/CANCELLED, explicit stop, I/O failure, or bounded timeout. Closing a bot closes its transport and waits for worker termination. One bot instance is not shared across matches; parallel matches use independent clients and executor tasks.

The two supported workflows are:

1. Join an existing room: the human/host creates and starts a room; `BotMain` joins it and runs the selected strategy.
2. Automated demo: `BotDemoMain` creates a room using bot A's connection, bot B joins, A starts, and both independent bots run the selected strategy until terminal state.

Supported strategies are `rps` and `number-guessing`. RPS uses a supplied repeatable choice or a seeded local random choice; Number Guessing uses public range/history only. No strategy reads server secrets or rule implementation classes.

## LAN validation

The server remains loopback-bound by default. LAN use requires an explicit reachable bind address (or `0.0.0.0`) and an operator-managed firewall rule. A PowerShell preflight checks route/TCP reachability and reports the local addresses; it does not change firewall or server settings. The two-device validation checklist uses CLI and bot commands to verify HELLO, LIST_RULES, CREATE/JOIN, START, game actions, and terminal events. No two physical devices are available to this implementation environment; that checklist must remain NOT VERIFIED until run on separate machines.

## Benchmark

`BenchmarkMain` connects to an already-running server over real TCP. Each room contains exactly two independent bot connections; the first bot creates and starts the room. Concurrency is bounded by a required participant count (even, at most 50), room count (participants/2), and an overall deadline. The default profile is 2 participants/1 room; larger proposed profiles (10/5, 20/10, 50/25) are opt-in. Number Guessing uses a small configured range so matches terminate quickly without long-running random RPS rounds. The harness does not launch a local server, bypass TCP, modify the protocol, or claim production capacity.

Metrics include requested/connected clients, room and match counts, successful/failed requests, handshake latency, match duration, completed/cancelled matches, errors/timeouts, and aggregate completion throughput. A report includes the exact host, port, profile, duration, and caveats. The smoke test uses a real ephemeral loopback `RoomTcpServer`, one room, and two clients; it is only a functional harness check, not a load claim.

## Tests and operational documents

Client tests cover strategy decisions, invalid/missing view handling, one-action-per-turn behavior, event loop termination, cleanup, demo coordination, and benchmark aggregation/limits. Server integration tests exercise two bots over real loopback TCP, including concurrent matches, disconnect and timeout paths, and resource termination. Existing regression suites remain required. Documentation includes the bot guide, LAN test guide, benchmark parameters and results, and a Phase 6 report. LAN real-device status is reported separately from loopback test status.

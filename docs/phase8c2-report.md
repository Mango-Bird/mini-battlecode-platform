# Phase 8C.2 — Executable Runner contracts

2026-10-13. Java 21; module `bot-runner`, activated with `local-bots` (automatic on Windows). Implementation, not a proposal. No JavaScript runs inside the JVM.

## Implemented

`BotSubmission` freezes UTF-8 source, version/id and exact-byte SHA-256, rejects malformed UTF-8 and source over32KiB, defensive-copies arrays and excludes source from diagnostics. `BotExecutionLimits`, `BotExecutionRequest`, `BotExecutionResult` and `BotExecutionFailure` validate immutable values; results contain exactly action XOR failure. `BotRunner` provides asynchronous execution, cancellation, lifecycle and runtime fingerprint.

`StrictActionCodec` checks byte/depth bounds, JSON duplicate keys/trailing tokens, then the existing `BotAction.decode` checks exact bot-1 shape and integer coordinates. `FakeBotRunner` is an explicitly labeled test double, never used as evidence that JavaScript ran.

`BotExecutionService` provides one active local slot, four pending entries, a2-second queue deadline, executionId/body conflict checks, cancellation and correlation validation. Delegation is outside service locks. Terminal results are write-once; cancellation linearizes against completion. The coordinator/adapter deadline is infrastructure, not BOT_TIMEOUT.

## Bounded records and design delta

The local adapter retains at most256 IDs until service close, including rejected/terminal entries. Full capacity rejects new requests; it never evicts and re-executes an old ID.256 accommodates a complete200-move OTT match. This deliberately differs from the8C.1 proposed128 records/5-minute retention: that proposal could run out before one full match. No durable duplicate guarantee across service recreation/restart. Remote runner-1 epoch/expiry/HTTPS protocol is not implemented.

## Verification

Focused `mvn -pl bot-runner -am test` passed:14 RunnerContractTest,4 ControlFramingTest and12 real QuickJS tests, plus7 existing rules-api tests. Unit coverage includes hash/UTF-8/size/immutability, exact union/schema, duplicate/conflict, queue overload, cancellation (including registration race), wrong correlation, retained-record capacity and closed-service rejection. Full reactor results are recorded in [Phase8C report](phase8c-report.md).

No public submission API, persistence, frontend or external protocol change. Source validation for arbitrary JavaScript remains unavailable and arbitrary-source admission is disabled.

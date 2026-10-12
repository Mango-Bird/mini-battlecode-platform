# Phase 6 — Bounded TCP Benchmark

`BenchmarkMain` connects to an already-running server using the same TCP client and protocol as real players. It creates one Number Guessing room per pair of bot connections, starts each match, and runs two independent bot event loops. It does not create an embedded server or bypass TCP.

## Run

```powershell
mvn package
java -cp "client\target\client-1.0.0-SNAPSHOT.jar;client\target\dependency\*" dev.battlecode.client.BenchmarkMain 127.0.0.1 7777 2 30
```

Arguments are host, port, even participant count (2..50, default 2), and total deadline seconds (default 30, maximum 300). The only default run is two participants and one room. Larger proposed profiles are opt-in:

| Participants | Rooms | Use |
|---:|---:|---|
| 2 | 1 | Functional baseline / smoke |
| 10 | 5 | Small concurrency observation |
| 20 | 10 | Medium concurrency observation |
| 50 | 25 | Upper harness bound; run only when the operator opts in |

The output includes successful/failed HELLO handshakes, successful/failed application requests, rooms, matches, completed/cancelled matches, errors, elapsed time, handshake and match P50/P95 latency, and completed-match throughput. A match that cannot complete before the total deadline fails the run. Server health and resource usage are not measured by this client harness.

## Phase 6 smoke result

The automated loopback smoke test uses a real ephemeral `RoomTcpServer`, two separately connected `BotClient` instances, a real room and match, and the complete TCP event/action lifecycle. It asserts two handshakes, one room, one completed match, no errors, and zero remaining server sessions/connections.

A packaged-server loopback run on `127.0.0.1:17777` produced these local samples:

| Profile | Requests | Completed | Elapsed | Handshake P50/P95 | Match P50/P95 | Throughput | Errors/timeouts |
|---|---:|---:|---:|---:|---:|---:|---:|
| 2 clients / 1 room | 7, 0 failures | 1/1 | 276 ms | 3/59 ms | 50/50 ms | 3.617/s | 0/0 |
| 10 clients / 5 rooms | 42, 0 failures | 5/5 | 195 ms | 6/64 ms | 55/63 ms | 25.533/s | 0/0 |

These are short local functional measurements, not capacity benchmarks. Raw output is in `phase6-benchmark-smoke.log` and `phase6-benchmark-10.log`.

No 10/20/50 participant performance run is claimed as part of Phase 6 verification. Record machine/CPU/JDK, server configuration, profile, exact output, and any environmental contention before comparing runs. Do not extrapolate a loopback result into production capacity.

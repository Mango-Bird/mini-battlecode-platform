# Phase 6 — LAN Validation Guide

Status: **NOT VERIFIED on two physical devices.** Only loopback integration tests have run in this environment. A successful loopback TCP test does not prove routing, firewall policy, Wi-Fi client isolation, or Java availability on a second computer.

## Server machine A

Build the runtime, identify the LAN IPv4 address with `ipconfig`, and explicitly bind to that address (or `0.0.0.0`). The default bind address remains loopback.

```powershell
mvn package
java -cp "server\target\server-1.0.0-SNAPSHOT.jar;server\target\dependency\*" dev.battlecode.server.ServerMain 192.168.1.10 7777
```

Replace `192.168.1.10` with machine A's actual address. Have the network administrator allow inbound TCP 7777 for the intended LAN profile if policy requires it. The project does not alter firewall rules.

## Client machine B

Copy the client JAR and `client\target\dependency\` to B, install Java 21, then run the preflight script from this repository or equivalent PowerShell:

```powershell
.\scripts\lan-preflight.ps1 -ServerAddress 192.168.1.10 -Port 7777
```

The script reports B's IPv4 addresses and TCP route/connectivity. A `TcpTestSucceeded` result verifies only the TCP endpoint.

## End-to-end sequence

1. On A, open two client sessions with `CliClient`; in the first run `create_room number-guessing 2 24 3000 minNumber=1 maxNumber=16` and copy the `roomId`.
2. On B, start `BotMain <A-LAN-IP> 7777 Lan-B <roomId> number-guessing number-guessing`. (Arguments are host, port, name, room ID, rule ID, strategy.) The bot joins and waits for the host to start.
3. On A, run `start_match <roomId>`. The bot acts using only public history; the host can observe the corresponding MATCH_EVENT and terminal result.
4. Repeat with RPS using a new room configured with `roundsToWin=1` and strategy `rps`.
5. Record both device names, OS/network type, server bind address, date, TCP preflight output, HELLO/room/match outcome, and any firewall/router changes made by the operator.

Mark this checklist VERIFIED only after the sequence succeeds between two separate physical computers. Do not expose port 7777 to an untrusted/public network; protocol 1.0 has no TLS or account authentication.

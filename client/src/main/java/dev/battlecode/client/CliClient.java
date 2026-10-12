package dev.battlecode.client;

import dev.battlecode.protocol.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class CliClient {
    private CliClient() { }

    public static void main(String[] args) {
        if (args.length > 3) {
            System.err.println("Usage: CliClient [serverIp] [port] [name]"); return;
        }
        try (var client = new DefaultTcpClient(); var console = CliConsole.create()) {
            String host = args.length > 0 ? args[0] : prompt(console, "Server IP [127.0.0.1]: ", "127.0.0.1");
            int port = Integer.parseInt(args.length > 1 ? args[1] : prompt(console, "Port [7777]: ", "7777"));
            String name = args.length > 2 ? args[2] : prompt(console, "Name [CLI]: ", "CLI");
            client.connect(host, port);
            Message hello = client.hello(name); console.println(format(hello));
            if (hello.command() != Command.ACK) return;
            console.println("Session ID: " + client.sessionId());
            console.println("Commands: ping, list_rules, list_rooms, create_room, join_room, leave_room, start_match, action, help, exit");
            var eventWorker = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("battlecode-cli-events").factory());
            eventWorker.execute(() -> {
                try {
                    while (!client.isClosed() && !Thread.currentThread().isInterrupted())
                        client.pollEvent(100, TimeUnit.MILLISECONDS).ifPresent(event -> console.println(format(event)));
                } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            });
            try {
                while (!client.isClosed()) {
                    String line = console.readLine("> ");
                    if (line == null || line.trim().equalsIgnoreCase("exit")) {
                        if (!client.isClosed()) console.println(format(client.request(Command.QUIT, Map.of())));
                        break;
                    }
                    if (line.isBlank()) continue;
                    try {
                        String[] parts = line.trim().split("\\s+");
                        switch (parts[0].toLowerCase(Locale.ROOT)) {
                            case "ping" -> { require(parts, 1, 1); console.println(format(client.request(Command.PING, Map.of()))); }
                            case "list_rules", "list_rooms" -> {
                                require(parts, 1, 3); var page = new HashMap<String,String>();
                                if (parts.length > 1) page.put("offset", parts[1]); if (parts.length > 2) page.put("limit", parts[2]);
                                console.println(format(client.request(parts[0].equalsIgnoreCase("list_rules") ? Command.LIST_RULES : Command.LIST_ROOMS, page)));
                            }
                            case "create_room" -> {
                                require(parts, 2, 128);
                                var payload = new HashMap<String,String>();
                                payload.put("ruleId", parts[1]); payload.put("maxPlayers", "2");
                                payload.put("maxTurns", "100"); payload.put("turnTimeoutMillis", "1000");
                                int index = 2;
                                String[] fields = {"maxPlayers", "maxTurns", "turnTimeoutMillis"};
                                for (String field : fields) {
                                    if (index < parts.length && !parts[index].contains("=")) payload.put(field, parts[index++]);
                                }
                                for (; index < parts.length; index++) {
                                    int separator = parts[index].indexOf('=');
                                    if (separator < 1 || separator == parts[index].length() - 1)
                                        throw new IllegalArgumentException("Use option=value after optional room limits");
                                    String key = "option." + parts[index].substring(0, separator);
                                    if (payload.putIfAbsent(key, parts[index].substring(separator + 1)) != null)
                                        throw new IllegalArgumentException("Duplicate room option");
                                }
                                console.println(format(client.request(Command.CREATE_ROOM, payload)));
                            }
                            case "join_room" -> { require(parts, 2, 2); console.println(format(client.request(Command.JOIN_ROOM, Map.of("roomId", parts[1])))); }
                            case "leave_room" -> { require(parts, 1, 1); console.println(format(client.request(Command.LEAVE_ROOM, Map.of()))); }
                            case "start_match" -> {
                                require(parts, 2, 2);
                                console.println(format(client.request(Command.START_MATCH, Map.of("roomId", parts[1]))));
                            }
                            case "action" -> {
                                require(parts, 3, 129);
                                var payload = new HashMap<String,String>();
                                payload.put("matchId", parts[1]); payload.put("turn", parts[2]);
                                for (int i = 3; i < parts.length; i++) {
                                    int separator = parts[i].indexOf('=');
                                    if (separator < 1) throw new IllegalArgumentException("Use key=value for each action field");
                                    if (payload.putIfAbsent("action." + parts[i].substring(0, separator), parts[i].substring(separator + 1)) != null)
                                        throw new IllegalArgumentException("Duplicate action field");
                                }
                                console.println(format(client.request(Command.ACTION, payload)));
                            }
                            case "room_info" -> console.println("Protocol 1.0 has no ROOM_INFO; use list_rooms to inspect rooms.");
                            case "help" -> {
                                console.println("Commands: ping, list_rules, list_rooms, create_room, join_room, leave_room, start_match, action, help, exit");
                                console.println("Rules: rock-paper-scissors (2 players; option roundsToWin=1..10, default 3; action choice=ROCK|PAPER|SCISSORS)");
                                console.println("       number-guessing (2 players; option minNumber/maxNumber, defaults 1/100; action guess=<integer>)");
                                console.println("       room-only-no-gameplay is a descriptor and cannot start a match.");
                                console.println("Syntax: create_room <ruleId> [maxPlayers] [maxTurns] [turnTimeoutMillis] [option=value ...]");
                                console.println("        action <matchId> <turn> [key=value ...]; use the latest STATE event turn and deadlineEpochMillis.");
                                console.println("MATCH_EVENT shows the recipient's private view; FINISHED/CANCELLED carries winners/reason. ERROR contains the rejection reason.");
                                console.println("Other commands: list_rules/list_rooms [offset] [limit] | join_room <roomId> | leave_room | start_match <roomId> | exit");
                            }
                            default -> console.println("Unknown CLI command; use help.");
                        }
                    } catch (IllegalArgumentException ex) {
                        console.println("CLI syntax error: " + ex.getMessage() + "; use help.");
                    }
                }
            } finally {
                eventWorker.shutdownNow();
                try { if (!eventWorker.awaitTermination(2, TimeUnit.SECONDS)) console.println("CLI event worker did not stop"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }
        } catch (IOException | IllegalArgumentException ex) { System.err.println("Client error: " + ex.getMessage()); }
    }

    private static String prompt(CliConsole input, String label, String fallback) throws IOException {
        String line = input.readLine(label);
        if (line == null) throw new EOFException("Console closed");
        return line.isBlank() ? fallback : line.trim();
    }
    private static void require(String[] parts, int min, int max) {
        if (parts.length < min || parts.length > max) throw new IllegalArgumentException("Wrong argument count");
    }
    private static String format(Message message) {
        return String.format("%s request=%s error=%s payload=%s", message.command(), message.requestId(), message.errorCode(), message.payload());
    }
}

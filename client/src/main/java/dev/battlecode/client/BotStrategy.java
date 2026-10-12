package dev.battlecode.client;

import java.util.Map;

/** Pure client-side policy. Keys are action fields before the protocol action. prefix is applied. */
@FunctionalInterface
public interface BotStrategy {
    Map<String, String> chooseAction(BotMatchContext context);
}

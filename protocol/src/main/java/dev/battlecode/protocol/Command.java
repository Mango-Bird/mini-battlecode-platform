package dev.battlecode.protocol;
/** Names are stable v1 wire identifiers. */
public enum Command { HELLO, PING, LIST_RULES, LIST_ROOMS, CREATE_ROOM, JOIN_ROOM, LEAVE_ROOM, START_MATCH, ACTION, QUIT, ACK, ERROR, ROOM_EVENT, MATCH_EVENT }

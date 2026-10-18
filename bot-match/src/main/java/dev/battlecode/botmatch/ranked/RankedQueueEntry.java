package dev.battlecode.botmatch.ranked;
public record RankedQueueEntry(MatchmakingTicket ticket,int ratingWindow,long durationSeconds){}

package dev.battlecode.botmatch.ranked;
import java.util.UUID;
public record RankedPairing(UUID pairingId,MatchmakingTicket a,MatchmakingTicket b,String policyVersion){}

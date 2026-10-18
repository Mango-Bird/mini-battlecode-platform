package dev.battlecode.botmatch.ranked;
import java.util.*;
import dev.battlecode.botmatch.LocalArenaService;
public record RankedMatch(UUID matchId,UUID pairingId,int gameNumber,String status,LocalArenaService.Snapshot snapshot,List<Map<String,Object>> ratingChanges){public RankedMatch{ratingChanges=List.copyOf(ratingChanges);}}

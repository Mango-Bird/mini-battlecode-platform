package dev.battlecode.botmatch.rating;

/** Pure math; both updates use the same pre-match snapshot. Never rounds storage values. */
public final class EloRatingEngine {
    public record Update(double a,double b){}
    public Update update(double a,int gamesA,double b,int gamesB,double scoreA,RatingPolicy policy){
        if(!Double.isFinite(a)||!Double.isFinite(b)||!(scoreA==0||scoreA==.5||scoreA==1))throw new IllegalArgumentException("Elo input");
        double expected=1/(1+Math.pow(10,(b-a)/400));
        return new Update(a+policy.k(gamesA)*(scoreA-expected),b+policy.k(gamesB)*(expected-scoreA));
    }
}

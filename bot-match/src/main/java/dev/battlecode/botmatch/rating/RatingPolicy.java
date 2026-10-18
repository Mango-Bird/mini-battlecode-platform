package dev.battlecode.botmatch.rating;

/** Server-owned immutable policy. A policy change requires a new version. */
public record RatingPolicy(String version, double initialRating, int firstThreshold, int secondThreshold,
                           double firstK, double secondK, double finalK, double initialRd, double initialVolatility,
                           double tau, int periodSeconds) {
    public static final RatingPolicy V1 = new RatingPolicy("rating-v1",1500,20,100,32,24,16,350,.06,.5,86400);
    public RatingPolicy {
        if(version==null||version.isBlank()||!Double.isFinite(initialRating)||firstThreshold<1||secondThreshold<=firstThreshold
            ||!positive(firstK)||!positive(secondK)||!positive(finalK)||!positive(initialRd)||!positive(initialVolatility)||!positive(tau)||periodSeconds<1)
            throw new IllegalArgumentException("Rating policy");
    }
    private static boolean positive(double x){return Double.isFinite(x)&&x>0;}
    public double k(int games){if(games<0)throw new IllegalArgumentException("Games");return games<firstThreshold?firstK:games<secondThreshold?secondK:finalK;}
}

package dev.battlecode.botmatch.rating;

import java.util.List;
import java.util.function.DoubleUnaryOperator;

/** Glickman's revised Illinois volatility solver; inputs are pre-period snapshots. */
public final class Glicko2RatingEngine {
    private static final double SCALE=173.7178;
    public record Rating(double rating,double ratingDeviation,double volatility){
        public Rating{if(!Double.isFinite(rating)||!Double.isFinite(ratingDeviation)||ratingDeviation<=0||!Double.isFinite(volatility)||volatility<=0)throw new IllegalArgumentException("Glicko input");}
    }
    public record Opponent(Rating rating,double score){public Opponent{if(rating==null||!(score==0||score==.5||score==1))throw new IllegalArgumentException("Score");}}
    public Rating update(Rating player,List<Opponent> opponents,double tau){
        if(!Double.isFinite(tau)||tau<=0)throw new IllegalArgumentException("Tau");
        double mu=(player.rating-1500)/SCALE,phi=player.ratingDeviation/SCALE,sigma=player.volatility;
        if(opponents.isEmpty())return new Rating(player.rating,SCALE*Math.sqrt(phi*phi+sigma*sigma),sigma);
        double varianceSum=0,scoreSum=0;
        for(var o:List.copyOf(opponents)){
            double p=o.rating.ratingDeviation/SCALE,g=1/Math.sqrt(1+3*p*p/(Math.PI*Math.PI));
            double e=1/(1+Math.exp(-g*(mu-(o.rating.rating-1500)/SCALE)));
            varianceSum+=g*g*e*(1-e);scoreSum+=g*(o.score-e);
        }
        if(!(varianceSum>0))throw new IllegalArgumentException("Degenerate ratings");
        double v=1/varianceSum,delta=v*scoreSum,a=Math.log(sigma*sigma);
        DoubleUnaryOperator f=x->{double ex=Math.exp(x),den=phi*phi+v+ex;return ex*(delta*delta-phi*phi-v-ex)/(2*den*den)-(x-a)/(tau*tau);};
        double A=a,B;
        if(delta*delta>phi*phi+v)B=Math.log(delta*delta-phi*phi-v);
        else {int k=1;while(f.applyAsDouble(a-k*tau)<0){if(++k>10000)throw new IllegalStateException("Volatility bracket");}B=a-k*tau;}
        double fa=f.applyAsDouble(A),fb=f.applyAsDouble(B);int iterations=0;
        while(Math.abs(B-A)>1e-6){
            if(++iterations>10000)throw new IllegalStateException("Volatility convergence");
            double C=A+(A-B)*fa/(fb-fa),fc=f.applyAsDouble(C);
            if(fc*fb<=0){A=B;fa=fb;}else fa/=2;
            B=C;fb=fc;
        }
        double nextSigma=Math.exp(A/2),star=Math.sqrt(phi*phi+nextSigma*nextSigma);
        double nextPhi=1/Math.sqrt(1/(star*star)+1/v);
        return new Rating(1500+SCALE*(mu+nextPhi*nextPhi*scoreSum),SCALE*nextPhi,nextSigma);
    }
}

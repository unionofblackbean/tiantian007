package dev.superwy.core;

/** Mean vanilla thrown-item trajectory, aimed at the centre of an adjacent return inlet. */
public final class DropAim {
    private DropAim(){}
    public static float pitch(double distance,double height){
        double best=Double.POSITIVE_INFINITY;float result=90;
        for(int degrees=-80;degrees<=90;degrees++){
            double radians=Math.toRadians(degrees),vx=.3*Math.cos(radians),vy=.1-.3*Math.sin(radians),x=0,y=height;
            for(int tick=0;tick<100;tick++){
                double previousX=x,previousY=y;vy-=.04;x+=vx;y+=vy;vx*=.98;vy*=.98;
                if(y<=0&&previousY>0){double landed=previousX+(x-previousX)*previousY/(previousY-y),error=Math.abs(landed-distance);if(error<best){best=error;result=degrees;}break;}
            }
        }
        return result;
    }
}

package dev.superwy.core;

/** Distance from an eye position to the target block, independent of camera and visibility. */
public final class ContainerDistance {
    private ContainerDistance(){}
    public static double toBlock(double x,double y,double z,int bx,int by,int bz){
        double dx=Math.max(Math.max(bx-x,0),x-(bx+1d));
        double dy=Math.max(Math.max(by-y,0),y-(by+1d));
        double dz=Math.max(Math.max(bz-z,0),z-(bz+1d));
        return Math.sqrt(dx*dx+dy*dy+dz*dz);
    }
}

package dev.superwy.core;

/** Inventory slots 9..35 only: reserve two work slots even when 25 parcels are carried. */
public final class PackingPolicy {
    public static final int MANIFEST_LINES=128,MAX_COUNT=1_000_000;
    private PackingPolicy(){}
    public static int slots(int count,int stackSize){return (count+Math.max(1,stackSize)-1)/Math.max(1,stackSize);}
    public static int round(int empty,int limit,boolean first){return Math.max(0,Math.min(first?limit:Math.min(24,limit),empty-2));}
    public static String recipient(String base,int index){
        if(index==0)return base;
        String suffix="_"+index;return base.substring(0,Math.min(base.length(),16-suffix.length()))+suffix;
    }
}

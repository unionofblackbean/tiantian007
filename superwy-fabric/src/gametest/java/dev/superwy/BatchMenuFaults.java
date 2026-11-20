package dev.superwy;

/** Fixture-only forced screen closure during real container clicks. */
public final class BatchMenuFaults {
    public static String item;
    public static int stage,clicks,closures;
    public static boolean armed;
    public static long closedAt,reopenedAt;
    public static void reset(String id,int mode){item=id;stage=mode;clicks=closures=0;armed=false;closedAt=reopenedAt=0;}
}

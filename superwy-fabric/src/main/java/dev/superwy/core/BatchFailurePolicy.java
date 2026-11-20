package dev.superwy.core;

import java.util.Set;

/** Only line-local failures can be skipped. Cancellation and uncertain delivery remain terminal. */
public final class BatchFailurePolicy {
    private BatchFailurePolicy(){}
    private static final Set<String> SKIPPABLE=Set.of("OUT_OF_STOCK","INVALID_ITEM","AMBIGUOUS_ITEM",
        "SOURCE_CONTAINER_MISSING","SOURCE_CHANGED","SOURCE_BOX_CHANGED","MENU_DESYNC","CURSOR_OR_MENU_DESYNC",
        "UNEXPECTED_MENU","OPEN_CONTAINER_TIMEOUT","QUICKSHULKER_OPEN_TIMEOUT","QUICKSHULKER_MENU_MISMATCH",
        "SOURCE_BOX_RETURN_BLOCKED","SOURCE_BOX_RETURN_MISMATCH","SOURCE_BOX_SYNC_TIMEOUT",
        "CONTAINER_UNLOADED","CONTAINER_OUT_OF_REACH","CONTAINER_PATH_UNREACHABLE","PATH_UNREACHABLE");
    public static boolean skippable(String code){return SKIPPABLE.contains(code);}
    public static void skip(Model.Line line,String reason){line.skipped=true;line.errorCode=reason;line.shortfall=Math.max(0,line.requested-line.fetched);}
    public static int processed(Model.Order order){return (int)order.lines.stream().filter(l->l.skipped||l.fetched>=l.requested).count();}
    public static boolean completed(Model.Order order){return order.state.equals("stored_offline")||order.state.equals("partial_stored_offline")||order.state.equals("failed")&&"NOTHING_DELIVERED".equals(order.errorCode)&&processed(order)==order.lines.size();}
}

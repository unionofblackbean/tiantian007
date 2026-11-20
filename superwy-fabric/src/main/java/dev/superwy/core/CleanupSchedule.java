package dev.superwy.core;

import java.util.List;

/** Delivery rounds and cleanup checkpoints are persisted on the orders themselves. */
public final class CleanupSchedule {
    public static final int INTERVAL=50;
    private CleanupSchedule(){}
    public static long pending(List<Model.Order> orders,Model.Order context){
        long checkpoint=orders.stream().filter(o->OrderQueue.sameQueue(o,context)&&o.cleanupRoundCounted&&o.cleanupCompleted).mapToLong(o->o.queueSequence).max().orElse(0);
        return orders.stream().filter(o->OrderQueue.sameQueue(o,context)&&o.cleanupRoundCounted&&o.queueSequence>checkpoint).count();
    }
    public static boolean due(List<Model.Order> orders,Model.Order context){return pending(orders,context)>=INTERVAL;}
}

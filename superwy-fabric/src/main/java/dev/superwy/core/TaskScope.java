package dev.superwy.core;

import java.util.Set;
import java.util.concurrent.*;
import java.util.function.*;

/** Per-order cancellation, monotonic deadline and context for nested asynchronous actions. */
public final class TaskScope {
    public static final int TIMEOUT_SECONDS=500;
    private static final ThreadLocal<TaskScope> CURRENT=new ThreadLocal<>();
    private final Set<CompletableFuture<?>> pending=ConcurrentHashMap.newKeySet();
    private final LongSupplier clock;
    private final long started;
    private final int timeoutSeconds;
    private volatile String reason;
    public TaskScope(){this(System::nanoTime);}
    public TaskScope(LongSupplier clock){this(clock,TIMEOUT_SECONDS);}
    public TaskScope(int seconds){this(System::nanoTime,seconds);}
    public TaskScope(LongSupplier clock,int seconds){this.clock=clock;timeoutSeconds=seconds;started=clock.getAsLong();}
    public boolean expired(){return clock.getAsLong()-started>=TimeUnit.SECONDS.toNanos(timeoutSeconds);}
    public boolean stopped(){return reason!=null;}
    public String reason(){return reason;}
    public void check(){if(stopped())throw new IllegalStateException(reason);}
    public static TaskScope current(){return CURRENT.get();}
    public static void checkCurrent(){var scope=current();if(scope!=null)scope.check();}
    public <T> T call(Supplier<T> operation){var previous=CURRENT.get();CURRENT.set(this);try{return operation.get();}finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);}}
    public Executor executor(Executor executor){return command->executor.execute(()->call(()->{command.run();return null;}));}
    public <T> CompletableFuture<T> track(CompletableFuture<T> future){
        pending.add(future);future.whenComplete((v,e)->pending.remove(future));
        if(stopped())future.completeExceptionally(new IllegalStateException(reason));
        return future;
    }
    public void stop(String reason){
        if(this.reason!=null)return;
        this.reason=reason;
        for(var future:pending)future.completeExceptionally(new IllegalStateException(reason));
    }
}

package dev.superwy;

import dev.superwy.core.TaskScope;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskScopeTest {
    @Test void deadlineIncludesWaitsAndExpiresAtExactly500Seconds(){
        var clock=new AtomicLong(8_000);var scope=new TaskScope(clock::get);
        clock.addAndGet(TimeUnit.SECONDS.toNanos(499));assertFalse(scope.expired());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));assertTrue(scope.expired());
        clock.incrementAndGet();assertTrue(scope.expired());
    }
    @Test void stoppingOneOrderCancelsItsWaitAndQueryButLeavesOtherWorkAlive(){
        var order=new TaskScope();var scan=new TaskScope();
        var wait=order.track(new CompletableFuture<Void>());var query=order.track(new CompletableFuture<String>());var scanning=scan.track(new CompletableFuture<Void>());
        order.stop("USER_CANCELLED");assertTrue(wait.isCompletedExceptionally());assertTrue(query.isCompletedExceptionally());assertFalse(scanning.isDone());
        assertTrue(order.track(new CompletableFuture<>()).isCompletedExceptionally());
        order.stop("ORDER_TIMEOUT");assertEquals("USER_CANCELLED",order.reason());
    }
    @Test void queuedOldClickCannotBorrowTheNextOrdersContext(){
        var old=new TaskScope();var fresh=new TaskScope();List<Runnable> queue=new ArrayList<>();var clicks=new AtomicInteger();
        var result=CompletableFuture.runAsync(()->{TaskScope.checkCurrent();clicks.incrementAndGet();},old.executor(queue::add));
        old.stop("USER_CANCELLED");fresh.call(()->{queue.removeFirst().run();return null;});
        assertTrue(result.isCompletedExceptionally());assertEquals(0,clicks.get());assertNull(TaskScope.current());
        CompletableFuture.runAsync(()->{TaskScope.checkCurrent();clicks.incrementAndGet();},fresh.executor(Runnable::run)).join();assertEquals(1,clicks.get());
    }
    @Test void nestedActionExecutorRetainsCancellationAndRestoresCallingContext(){
        var scope=new TaskScope();var callbacks=new ArrayList<Runnable>();
        scope.call(()->{assertSame(scope,TaskScope.current());scope.executor(callbacks::add).execute(()->assertSame(scope,TaskScope.current()));return null;});
        assertNull(TaskScope.current());callbacks.getFirst().run();assertNull(TaskScope.current());
    }
}

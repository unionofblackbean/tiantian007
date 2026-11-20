package dev.superwy;
import java.util.concurrent.CompletableFuture;
public final class TaskQueueGate {
    public static boolean armed,started;
    public static CompletableFuture<Void> release=new CompletableFuture<>();
}

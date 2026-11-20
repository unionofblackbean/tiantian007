package dev.superwy.game;
import dev.superwy.mixin.DebugQueryAccessor;
import dev.superwy.core.TaskScope;
import dev.superwy.core.QueryTickets;
import net.minecraft.network.Connection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntConsumer;

/** Shares vanilla's transaction allocator. Only consumes responses belonging to this connection. */
public final class Queries {
    private record Pending(ClientPacketListener connection,long deadline,CompletableFuture<CompoundTag> future,QueryTickets.Ticket<Connection> ticket){}
    private static final Map<Integer,Pending> PENDING=new ConcurrentHashMap<>();
    private static final QueryTickets<Connection> TICKETS=new QueryTickets<>(1024,60000,System::currentTimeMillis);
    public volatile boolean blockQuerySucceeded;
    public CompletableFuture<CompoundTag> block(BlockPos pos){return send(id->Minecraft.getInstance().getConnection().send(new ServerboundBlockEntityTagQueryPacket(id,pos))).thenApply(tag->{blockQuerySucceeded=true;return tag;});}
    public CompletableFuture<CompoundTag> entity(int id){return send(tx->Minecraft.getInstance().getConnection().send(new ServerboundEntityTagQueryPacket(tx,id)));}
    private CompletableFuture<CompoundTag> send(IntConsumer sender){
        TaskScope.checkCurrent();
        Minecraft mc=Minecraft.getInstance();ClientPacketListener c=mc.getConnection();
        if(c==null)return CompletableFuture.failedFuture(new IllegalStateException("DISCONNECTED"));
        if(PENDING.size()>=4)return CompletableFuture.failedFuture(new IllegalStateException("QUERY_BUSY"));
        int id=((DebugQueryAccessor)c.getDebugQueryHandler()).superwy$reserveTransaction(tag->{});
        CompletableFuture<CompoundTag> future=new CompletableFuture<>();
        Pending pending=new Pending(c,System.currentTimeMillis()+4000,future,TICKETS.remember(c.getConnection(),id));PENDING.put(id,pending);
        future.whenComplete((v,e)->PENDING.remove(id,pending));var scope=TaskScope.current();if(scope!=null)scope.track(future);
        if(!future.isDone())sender.accept(id);return future;
    }
    public static boolean route(ClientPacketListener connection,ClientboundTagQueryPacket packet){
        Pending pending=PENDING.get(packet.getTransactionId());
        if(pending==null)return TICKETS.find(connection.getConnection(),packet.getTransactionId())!=null;
        if(pending.connection!=connection)return false;
        PENDING.remove(packet.getTransactionId(),pending);
        Minecraft.getInstance().execute(()->{
            if(Minecraft.getInstance().getConnection()!=connection)pending.future.completeExceptionally(new IllegalStateException("DISCONNECTED"));
            else if(pending.ticket.error().get()!=null)pending.future.completeExceptionally(new IllegalStateException(pending.ticket.error().get()));
            else if(packet.getTag()==null)pending.future.completeExceptionally(new IllegalStateException("EMPTY_NBT_RESPONSE"));
            else pending.future.complete(packet.getTag());
        });return true;
    }
    public static QueryTickets.Ticket<Connection> decodeTicket(Connection connection,int transaction){return TICKETS.find(connection,transaction);}
    public static boolean decodePending(Connection connection,int transaction){var pending=PENDING.get(transaction);return pending!=null&&pending.connection.getConnection()==connection&&!pending.future.isDone();}
    public void tick(){TICKETS.prune();long now=System.currentTimeMillis();PENDING.forEach((id,p)->{if(now>p.deadline&&PENDING.remove(id,p))p.future.completeExceptionally(new IllegalStateException("NBT_QUERY_TIMEOUT"));});}
    private void clearPending(String reason){blockQuerySucceeded=false;PENDING.forEach((id,p)->p.future.completeExceptionally(new IllegalStateException(reason)));PENDING.clear();}
    public void abort(){clearPending("CONTROL_STOPPED");TICKETS.prune();}
    public void reset(){clearPending("DISCONNECTED");TICKETS.clear();}
}

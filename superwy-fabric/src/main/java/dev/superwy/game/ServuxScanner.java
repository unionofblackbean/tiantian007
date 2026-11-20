package dev.superwy.game;

import dev.superwy.core.TaskScope;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.level.ChunkPos;
import java.util.concurrent.*;

/** One bulk request at a time; never substitutes F3 silently or accepts an abandoned reply as fresh. */
public final class ServuxScanner implements AutoCloseable {
    private static ServuxScanner instance;
    private static boolean nativeCodec;
    private static final java.util.Map<Object,byte[]> nativeWrites=java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private ServuxWire.Fragments fragments=new ServuxWire.Fragments();
    // At most 32 pending slices (each capped at MAX_SLICE), even if decoding stalls.
    private final ThreadPoolExecutor decoding=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(32),r->{var t=new Thread(r,"superwy-servux-decode");t.setDaemon(true);return t;});
    private volatile int streamGeneration;
    private boolean closed;
    private ClientPacketListener connection;
    private CompletableFuture<Void> handshake;
    private long handshakeDeadline;
    private Request request;
    private boolean desynchronized;
    private String state="unknown";
    private record Request(ChunkPos chunk,long deadline,CompletableFuture<CompoundTag> future){}
    public ServuxScanner(){instance=this;}
    public static void register(){
        nativeCodec=FabricLoader.getInstance().isModLoaded("litematica");
        if(!nativeCodec){
            PayloadTypeRegistry.serverboundPlay().register(ServuxWire.Payload.TYPE,ServuxWire.Payload.CODEC);
            PayloadTypeRegistry.clientboundPlay().register(ServuxWire.Payload.TYPE,ServuxWire.Payload.CODEC);
            ClientPlayNetworking.registerGlobalReceiver(ServuxWire.Payload.TYPE,(payload,context)->{if(instance!=null)instance.receive(context.client().getConnection(),payload.bytes());});
        }
    }
    public String state(){return state;}
    public boolean available(){return Minecraft.getInstance().getConnection()!=null&&ClientPlayNetworking.canSend(ServuxWire.CHANNEL);}
    private CompletableFuture<Void> ready(){
        if(!available()){state="unavailable";return CompletableFuture.failedFuture(new IllegalStateException("SERVUX_UNAVAILABLE"));}
        var current=Minecraft.getInstance().getConnection();
        if(connection!=current){reset();connection=current;}
        if(handshake==null||handshake.isCompletedExceptionally()){
            handshake=new CompletableFuture<>();handshakeDeadline=System.currentTimeMillis()+4000;state="checking";
            try{send(ServuxWire.metadata());}catch(RuntimeException e){handshake.completeExceptionally(e);state="unavailable";}
        }
        return handshake;
    }
    public CompletableFuture<CompoundTag> chunk(ChunkPos pos,int minY,int maxY){
        TaskScope.checkCurrent();
        if(closed)return CompletableFuture.failedFuture(new IllegalStateException("DISCONNECTED"));
        if(desynchronized)return CompletableFuture.failedFuture(new IllegalStateException("SERVUX_RESYNC_REQUIRED"));
        if(request!=null)return CompletableFuture.failedFuture(new IllegalStateException("SERVUX_RESPONSE_PENDING"));
        var readiness=ready();
        var future=new CompletableFuture<CompoundTag>();var scope=TaskScope.current();if(scope!=null)scope.track(future);
        var waiting=new Request(pos,System.currentTimeMillis()+15000,future);request=waiting;
        readiness.whenComplete((v,error)->{
            if(request!=waiting||future.isDone()){if(error!=null&&request==waiting)request=null;return;}
            if(error!=null){request=null;future.completeExceptionally(error);return;}
            try{send(ServuxWire.chunk(pos.x(),pos.z(),minY,maxY));}catch(RuntimeException e){request=null;future.completeExceptionally(e);}
        });
        return future;
    }
    private static void send(ServuxWire.Payload payload){
        if(!nativeCodec){ClientPlayNetworking.send(payload);return;}
        var buffer=new FriendlyByteBuf(Unpooled.wrappedBuffer(ServuxWire.metadata().bytes()));
        try{
            // Litematica owns this channel's registered codec when present. Use its payload class.
            Class<?> packet=Class.forName("fi.dy.masa.litematica.network.ServuxLitematicaPacket");
            Object value=packet.getMethod("fromPacket",FriendlyByteBuf.class).invoke(null,buffer);
            if(value==null||buffer.isReadable())throw new IllegalStateException("SERVUX_CLIENT_INCOMPATIBLE");
            // Later Litematica releases use a different NBT encoding for bulk requests.
            // Keep its registered payload class, but encode our own version-1 request bytes.
            nativeWrites.put(value,payload.bytes());
            var type=Class.forName("fi.dy.masa.litematica.network.ServuxLitematicaPacket$Payload");
            ClientPlayNetworking.send((CustomPacketPayload)type.getConstructor(packet).newInstance(value));
        }catch(ReflectiveOperationException e){org.slf4j.LoggerFactory.getLogger("SuperWY").warn("Servux client payload bridge failed",e);throw new IllegalStateException("SERVUX_CLIENT_INCOMPATIBLE");}finally{buffer.release();}
    }
    public static boolean writeNative(Object packet,FriendlyByteBuf output){
        byte[] bytes=nativeWrites.get(packet);if(bytes==null)return false;output.writeBytes(bytes);return true;
    }
    /** Observe native Litematica payloads on the client thread before its own receiver consumes them. */
    public static void observe(ClientPacketListener owner,CustomPacketPayload payload){
        var target=instance;if(target==null||!nativeCodec||!payload.type().id().equals(ServuxWire.CHANNEL)||owner!=target.connection)return;
        var raw=new FriendlyByteBuf(Unpooled.buffer());
        try{
            Object packet=payload.getClass().getMethod("data").invoke(payload);
            int type=(int)packet.getClass().getMethod("getPacketType").invoke(packet);
            if(type==11){
                var slice=(FriendlyByteBuf)packet.getClass().getMethod("getBuffer").invoke(packet);
                if(slice==null||slice.readableBytes()>ServuxWire.MAX_SLICE)return;
                raw.writeVarInt(11);raw.writeBytes(slice,slice.readerIndex(),slice.readableBytes());
            }else if(type==1)packet.getClass().getMethod("toPacket",FriendlyByteBuf.class).invoke(packet,raw);
            else return;
            byte[] bytes=new byte[raw.readableBytes()];raw.readBytes(bytes);target.receive(owner,bytes);
        }catch(ReflectiveOperationException|RuntimeException error){
            org.slf4j.LoggerFactory.getLogger("SuperWY").warn("Servux client reply bridge failed",error);
            target.failRequest("SERVUX_CLIENT_INCOMPATIBLE");
        }finally{raw.release();}
    }
    private void receive(ClientPacketListener owner,byte[] bytes){
        if(owner==null||owner!=connection||Minecraft.getInstance().getConnection()!=owner)return;
        var buffer=new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
        try{
            int type=buffer.readVarInt();
            if(type==1&&handshake!=null&&!handshake.isDone()){
                CompoundTag tag=buffer.readNbt();
                if(tag==null||tag.getInt("version").orElse(-1)!=1)throw new IllegalStateException("SERVUX_PROTOCOL_UNSUPPORTED");
                state="available";handshake.complete(null);
            }else if(type==11&&request!=null&&!desynchronized){
                var waiting=request;int epoch=streamGeneration;var stream=fragments;
                // The owned byte array is immutable after receipt; worker owns fragment buffers.
                decoding.execute(()->{
                    if(epoch!=streamGeneration)return;
                    CompoundTag tag=null;RuntimeException error=null;var slice=new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
                    try{slice.readVarInt();tag=stream.accept(slice);}catch(RuntimeException e){error=e;}finally{slice.release();}
                    if(epoch!=streamGeneration||tag==null&&error==null)return;
                    var result=tag;var problem=error;Minecraft.getInstance().execute(()->completeBulk(owner,waiting,epoch,result,problem));
                });
            }
        }catch(RuntimeException e){
            String reason=e instanceof RejectedExecutionException?"SERVUX_RESPONSE_TOO_LARGE":e.getMessage()!=null&&e.getMessage().startsWith("SERVUX_")?e.getMessage():"SERVUX_RESPONSE_INVALID";
            state="error";if(handshake!=null&&!handshake.isDone())handshake.completeExceptionally(new IllegalStateException(reason));
            failRequest(reason);
        }finally{buffer.release();}
    }
    private void completeBulk(ClientPacketListener owner,Request waiting,int epoch,CompoundTag tag,RuntimeException error){
        if(epoch!=streamGeneration||request!=waiting||connection!=owner||Minecraft.getInstance().getConnection()!=owner||desynchronized)return;
        if(error==null&&(!tag.getString("Task").orElse("").equals("BulkEntityReply")||tag.getInt("chunkX").orElse(Integer.MIN_VALUE)!=waiting.chunk.x()||tag.getInt("chunkZ").orElse(Integer.MIN_VALUE)!=waiting.chunk.z()))return;
        if(error==null&&tag.get("TileEntities") instanceof ListTag){request=null;waiting.future.complete(tag);return;}
        String reason=error!=null&&error.getMessage()!=null&&error.getMessage().startsWith("SERVUX_")?error.getMessage():"SERVUX_RESPONSE_INVALID";
        failRequest(reason);
    }
    private void clearFragments(){streamGeneration++;fragments.cancel();decoding.getQueue().clear();fragments=new ServuxWire.Fragments();}
    private void failRequest(String reason){
        var waiting=request;request=null;desynchronized=true;state="error";clearFragments();
        if(waiting!=null)waiting.future.completeExceptionally(new IllegalStateException(reason));
    }
    public void tick(){
        long now=System.currentTimeMillis();
        if(handshake!=null&&!handshake.isDone()&&now>handshakeDeadline){state="unavailable";handshake.completeExceptionally(new IllegalStateException("SERVUX_HANDSHAKE_TIMEOUT"));}
        // A cancelled request still drains its late reply before the next request is allowed.
        if(request!=null&&!desynchronized&&now>request.deadline)failRequest("SERVUX_QUERY_TIMEOUT");
    }
    public void dimensionChanged(){
        if(handshake!=null&&!handshake.isDone())handshake.completeExceptionally(new IllegalStateException("DISCONNECTED"));
        if(request!=null)failRequest("DISCONNECTED");
        else {handshake=null;state="unknown";}
        clearFragments();
    }
    public void reset(){
        if(handshake!=null)handshake.completeExceptionally(new IllegalStateException("DISCONNECTED"));
        if(request!=null)request.future.completeExceptionally(new IllegalStateException("DISCONNECTED"));
        request=null;handshake=null;connection=null;desynchronized=false;clearFragments();state="unknown";
    }
    public void close(){if(closed)return;closed=true;reset();decoding.shutdownNow();if(instance==this)instance=null;nativeWrites.clear();}
}

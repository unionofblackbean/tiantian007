package dev.superwy.testutil;

import dev.superwy.game.ServuxWire;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import java.net.*;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.*;

/** Test-only server: actual Servux 0.10.4 request decoder, reply encoder and PacketSplitter. */
public final class ServuxFixture {
    public static volatile int requests,slices,metadataVersion=1;
    public static volatile boolean hold,invalid;
    private static final List<Runnable> held=new ArrayList<>();
    private static ClassLoader reference;
    private static Class<?> packetClass;
    private static boolean nativeClient;
    private static final Map<Object,byte[]> decodedRequests=Collections.synchronizedMap(new WeakHashMap<>());
    private ServuxFixture(){}
    @SuppressWarnings("unchecked")
    public static void register()throws Exception{
        reference=new URLClassLoader(new URL[]{Path.of(System.getProperty("superwy.servuxReferenceJar")).toUri().toURL()},ServuxFixture.class.getClassLoader());
        packetClass=Class.forName("fi.dy.masa.servux.network.packet.ServuxLitematicaPacket",true,reference);
        nativeClient=FabricLoader.getInstance().isModLoaded("litematica");
        CustomPacketPayload.Type<CustomPacketPayload> type;
        if(nativeClient){
            type=(CustomPacketPayload.Type<CustomPacketPayload>)Class.forName("fi.dy.masa.litematica.network.ServuxLitematicaPacket$Payload").getField("ID").get(null);
        }else type=(CustomPacketPayload.Type<CustomPacketPayload>)(Object)ServuxWire.Payload.TYPE;
        ServerPlayNetworking.registerGlobalReceiver(type,(payload,context)->receive(payload,context.player()));
    }
    private static void receive(CustomPacketPayload payload,ServerPlayer player){
        var input=new FriendlyByteBuf(Unpooled.buffer());
        try{
            if(payload instanceof ServuxWire.Payload raw)input.writeBytes(raw.bytes());
            else {Object data=payload.getClass().getMethod("data").invoke(payload);byte[] decoded=decodedRequests.remove(data);if(decoded!=null)input.writeBytes(decoded);else data.getClass().getMethod("toPacket",FriendlyByteBuf.class).invoke(data,input);}
            // The real published server codec must accept the client's bytes, not just our test decoder.
            Object decoded=packetClass.getMethod("fromPacket",FriendlyByteBuf.class).invoke(null,input);
            if(decoded==null||input.isReadable())throw new AssertionError("Servux 0.10.4 rejected client request");
            int type=(int)packetClass.getMethod("getPacketType").invoke(decoded);
            if(type==2){var tag=new CompoundTag();tag.putInt("version",metadataVersion);tag.putString("servux","0.10.4");sendPacket(player,packetClass.getMethod("MetadataResponse",CompoundTag.class).invoke(null,tag));return;}
            if(type!=7)throw new AssertionError("Scanner unexpectedly used single-container Servux query");
            requests++;var chunk=(net.minecraft.world.level.ChunkPos)packetClass.getMethod("getChunkPos").invoke(decoded);
            var request=(CompoundTag)packetClass.getMethod("getCompound").invoke(decoded);int min=request.getInt("minY").orElseThrow(),max=request.getInt("maxY").orElseThrow();
            var tags=new ListTag();
            for(var entry:player.level().getChunk(chunk.x(),chunk.z()).getBlockEntities().entrySet())if(entry.getKey().getY()>=min&&entry.getKey().getY()<=max)tags.add(entry.getValue().saveWithFullMetadata(player.registryAccess()));
            var reply=new CompoundTag();reply.putString("Task","BulkEntityReply");reply.putInt("chunkX",chunk.x());reply.putInt("chunkZ",chunk.z());reply.put("TileEntities",invalid?new CompoundTag():tags);
            reply.putByteArray("fixture_large_reply",new byte[1_100_000]);
            Runnable deliver=()->{try{sendBulk(player,reply);}catch(Exception e){throw new RuntimeException(e);}};
            if(hold)held.add(deliver);else deliver.run();
        }catch(Exception e){throw new RuntimeException(e);}finally{input.release();}
    }
    /** Integrated tests share client/server codec registries; emulate the real server's v1 decoder. */
    public static Object decodeNativeRequest(FriendlyByteBuf input){
        if(packetClass==null||input.getUnsignedByte(input.readerIndex())!=7)return null;
        try{
            byte[] bytes=new byte[input.readableBytes()];input.getBytes(input.readerIndex(),bytes);
            Object referencePacket=packetClass.getMethod("fromPacket",FriendlyByteBuf.class).invoke(null,input);
            if(referencePacket==null||input.isReadable())throw new AssertionError("Published Servux decoder rejected request");
            Object nativePacket=Class.forName("fi.dy.masa.litematica.network.ServuxLitematicaPacket").getMethod("BlockEntityRequest",net.minecraft.core.BlockPos.class).invoke(null,net.minecraft.core.BlockPos.ZERO);
            decodedRequests.put(nativePacket,bytes);return nativePacket;
        }catch(Exception e){throw new RuntimeException(e);}
    }
    public static void release(){for(var reply:List.copyOf(held))reply.run();held.clear();}
    private static void sendBulk(ServerPlayer player,CompoundTag tag)throws Exception{
        var handlerClass=Class.forName("fi.dy.masa.servux.network.IPluginServerPlayHandler",true,reference);
        var handler=Proxy.newProxyInstance(reference,new Class[]{handlerClass},(proxy,method,args)->{
            if(method.getName().equals("encodeWithSplitter")){slices++;Object packet=packetClass.getMethod("ResponseS2CData",FriendlyByteBuf.class).invoke(null,args[1]);sendPacket(player,packet);return null;}
            throw new UnsupportedOperationException(method.getName());
        });
        var full=new FriendlyByteBuf(Unpooled.buffer());full.writeVarInt(-1);full.writeNbt(tag);
        Class.forName("fi.dy.masa.servux.network.PacketSplitter",true,reference)
            .getMethod("send",handlerClass,FriendlyByteBuf.class,ServerPlayer.class,net.minecraft.server.network.ServerGamePacketListenerImpl.class).invoke(null,handler,full,player,player.connection);
    }
    private static void sendPacket(ServerPlayer player,Object referencePacket)throws Exception{
        var raw=new FriendlyByteBuf(Unpooled.buffer());
        try{
            packetClass.getMethod("toPacket",FriendlyByteBuf.class).invoke(referencePacket,raw);
            CustomPacketPayload payload;
            if(nativeClient){
                var nativePacket=Class.forName("fi.dy.masa.litematica.network.ServuxLitematicaPacket");
                Object decoded=nativePacket.getMethod("fromPacket",FriendlyByteBuf.class).invoke(null,raw);
                payload=(CustomPacketPayload)Class.forName("fi.dy.masa.litematica.network.ServuxLitematicaPacket$Payload").getConstructor(nativePacket).newInstance(decoded);
            }else{byte[] bytes=new byte[raw.readableBytes()];raw.readBytes(bytes);payload=new ServuxWire.Payload(bytes);}
            ServerPlayNetworking.send(player,payload);
        }finally{raw.release();}
    }
}

package dev.superwy.game;

import io.netty.buffer.Unpooled;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Independent implementation of Servux 0.10.x's litematics wire format. */
public final class ServuxWire {
    public static final Identifier CHANNEL=Identifier.fromNamespaceAndPath("servux","litematics");
    public static final int MAX_SLICE=1_048_576+16,MAX_WIRE=32*1024*1024;
    public static final long MAX_NBT=128L*1024*1024;
    private ServuxWire(){}
    public record Payload(byte[] bytes) implements CustomPacketPayload {
        public static final Type<Payload> TYPE=new Type<>(CHANNEL);
        public static final StreamCodec<FriendlyByteBuf,Payload> CODEC=StreamCodec.ofMember(
            (p,b)->b.writeBytes(p.bytes),b->{if(b.readableBytes()>MAX_SLICE)throw new IllegalArgumentException("SERVUX_RESPONSE_TOO_LARGE");byte[] bytes=new byte[b.readableBytes()];b.readBytes(bytes);return new Payload(bytes);});
        public Type<Payload> type(){return TYPE;}
    }
    public static Payload metadata(){return encode(2,null,new CompoundTag());}
    public static Payload chunk(int chunkX,int chunkZ,int minY,int maxY){
        var tag=new CompoundTag();tag.putString("Task","BulkEntityRequest");tag.putInt("minY",minY);tag.putInt("maxY",maxY);
        return encode(7,(chunkX&0xffffffffL)|((chunkZ&0xffffffffL)<<32),tag);
    }
    private static Payload encode(int type,Long pos,CompoundTag tag){
        var buffer=new FriendlyByteBuf(Unpooled.buffer());
        try{buffer.writeVarInt(type);if(pos!=null)buffer.writeLong(pos);buffer.writeNbt(tag);byte[] bytes=new byte[buffer.readableBytes()];buffer.readBytes(bytes);return new Payload(bytes);}finally{buffer.release();}
    }
    /** Servux prefixes only the first slice with the total length. Slices are ordered on TCP. */
    public static final class Fragments {
        private FriendlyByteBuf data;
        private int expected;
        private boolean accepting;
        private volatile boolean cancelled;
        public CompoundTag accept(FriendlyByteBuf slice){
            synchronized(this){if(cancelled)return null;accepting=true;}
            try{return read(slice);}
            finally{synchronized(this){accepting=false;if(cancelled)clear();}}
        }
        private CompoundTag read(FriendlyByteBuf slice){
            try{
                if(data==null){expected=slice.readVarInt();if(expected<2||expected>MAX_WIRE)throw new IllegalStateException("SERVUX_RESPONSE_TOO_LARGE");data=new FriendlyByteBuf(Unpooled.buffer(Math.min(expected,MAX_SLICE),expected));}
                if(slice.readableBytes()>expected-data.writerIndex())throw new IllegalStateException("SERVUX_RESPONSE_INVALID");
                data.writeBytes(slice,slice.readableBytes());
                if(data.writerIndex()!=expected)return null;
                data.readVarInt(); // The server currently uses -1 for bulk transaction IDs.
                CompoundTag tag=(CompoundTag)data.readNbt(NbtAccounter.create(MAX_NBT));
                if(tag==null||data.isReadable())throw new IllegalStateException("SERVUX_RESPONSE_INVALID");
                clear();return tag;
            }catch(RuntimeException error){clear();throw new IllegalStateException(error instanceof NbtAccounterException||"SERVUX_RESPONSE_TOO_LARGE".equals(error.getMessage())?"SERVUX_RESPONSE_TOO_LARGE":"SERVUX_RESPONSE_INVALID");}
        }
        public void clear(){if(data!=null){data.release();data=null;}expected=0;}
        /** Cancel without blocking the client thread on an in-progress NBT decode. */
        public synchronized void cancel(){cancelled=true;if(!accepting)clear();}
    }
}

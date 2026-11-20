package dev.superwy;

import dev.superwy.game.ServuxWire;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ServuxWireTest {
    @Test void cancellationReleasesPartialBufferAndDoesNotAllocateForLateSlices()throws Exception{
        var fragments=new ServuxWire.Fragments();var first=new FriendlyByteBuf(Unpooled.buffer());
        try{
            first.writeVarInt(1_000_000);first.writeByte(0);assertNull(fragments.accept(first));
            var field=ServuxWire.Fragments.class.getDeclaredField("data");field.setAccessible(true);var retained=(FriendlyByteBuf)field.get(fragments);assertEquals(1,retained.refCnt());
            fragments.cancel();assertEquals(0,retained.refCnt());assertNull(field.get(fragments));
            first.clear();first.writeVarInt(1_000_000);first.writeByte(0);assertNull(fragments.accept(first));assertNull(field.get(fragments));fragments.cancel();
        }finally{fragments.cancel();first.release();}
    }
    @Test void bulkRequestPreservesNegativeChunkAndSelectionHeight(){
        var payload=ServuxWire.chunk(-3,7,42,61);var b=new FriendlyByteBuf(Unpooled.wrappedBuffer(payload.bytes()));
        try{assertEquals(7,b.readVarInt());long chunk=b.readLong();assertEquals(-3,(int)chunk);assertEquals(7,(int)(chunk>>>32));var tag=b.readNbt();assertEquals("BulkEntityRequest",tag.getString("Task").orElseThrow());assertEquals(42,tag.getInt("minY").orElseThrow());assertEquals(61,tag.getInt("maxY").orElseThrow());assertFalse(b.isReadable());}finally{b.release();}
    }
    @Test void multipleSlicesReassembleLargeNbtAndReleaseBetweenReplies(){
        var tag=new CompoundTag();tag.putString("Task","BulkEntityReply");tag.putByteArray("large",new byte[1_500_000]);
        var wire=new FriendlyByteBuf(Unpooled.buffer());var fragments=new ServuxWire.Fragments();
        try{
            wire.writeVarInt(-1);wire.writeNbt(tag);int length=wire.readableBytes();int offset=0;CompoundTag actual=null;
            while(wire.isReadable()){
                var slice=new FriendlyByteBuf(Unpooled.buffer());try{if(offset==0)slice.writeVarInt(length);int n=Math.min(700_000,wire.readableBytes());slice.writeBytes(wire,n);offset+=n;actual=fragments.accept(slice);if(offset<length)assertNull(actual);}finally{slice.release();}
            }assertEquals(tag,actual);
            wire.clear();wire.writeVarInt(-1);wire.writeNbt(new CompoundTag());var next=new FriendlyByteBuf(Unpooled.buffer());
            try{next.writeVarInt(wire.readableBytes());next.writeBytes(wire);assertEquals(new CompoundTag(),fragments.accept(next));}finally{next.release();}
        }finally{fragments.clear();wire.release();}
    }
    @Test void excessiveAndMalformedRepliesFailWithoutPoisoningNextSession(){
        var fragments=new ServuxWire.Fragments();
        for(int size:new int[]{-1,0,ServuxWire.MAX_WIRE+1}){
            var b=new FriendlyByteBuf(Unpooled.buffer());try{b.writeVarInt(size);assertThrows(IllegalStateException.class,()->fragments.accept(b));}finally{b.release();}
        }
        var malformed=new FriendlyByteBuf(Unpooled.buffer());try{malformed.writeVarInt(2);malformed.writeByte(0);malformed.writeByte(99);assertEquals("SERVUX_RESPONSE_INVALID",assertThrows(IllegalStateException.class,()->fragments.accept(malformed)).getMessage());}finally{malformed.release();fragments.clear();}
    }
}

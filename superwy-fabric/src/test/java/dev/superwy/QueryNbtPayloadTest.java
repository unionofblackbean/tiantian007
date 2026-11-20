package dev.superwy;

import dev.superwy.game.QueryNbtPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class QueryNbtPayloadTest {
    private CompoundTag wide(int count){var root=new CompoundTag();var list=new ListTag();var item=new CompoundTag();for(int i=0;i<count;i++)list.add(item);root.put("payload",list);return root;}
    private FriendlyByteBuf buffer(CompoundTag tag){var b=new FriendlyByteBuf(Unpooled.buffer());b.writeNbt(tag);return b;}
    @Test void reproducesVanillaTwoMiBBudgetAndAcceptsOwnedReplyWithinBound(){
        var tag=wide(70000);var failures=new ArrayList<String>();
        var vanilla=buffer(tag);try{assertThrows(NbtAccounterException.class,()->QueryNbtPayload.read(vanilla,false,true,failures::add));}finally{vanilla.release();}
        var owned=buffer(tag);try{assertEquals(tag,QueryNbtPayload.read(owned,true,true,failures::add));assertEquals(0,owned.readableBytes());assertTrue(failures.isEmpty());}finally{owned.release();}
    }
    @Test void allocationOverflowIsConsumedAndReportedInsteadOfReturningAnEmptyInventory(){
        var failures=new ArrayList<String>();var b=buffer(wide(400000));
        try{assertNull(QueryNbtPayload.read(b,true,true,failures::add));assertEquals(List.of("NBT_RESPONSE_TOO_LARGE"),failures);assertEquals(0,b.readableBytes());}finally{b.release();}
        var normal=new CompoundTag();normal.putString("id","minecraft:chest");var next=buffer(normal);try{assertEquals(normal,QueryNbtPayload.read(next,true,true,failures::add));}finally{next.release();}
    }
    @Test void malformedReplyAndTrailingBytesAreRejectedOnlyForOwnedQuery(){
        for(boolean trailing:new boolean[]{false,true}){var b=new FriendlyByteBuf(Unpooled.buffer());if(trailing){b.writeNbt(new CompoundTag());b.writeByte(99);}else b.writeByte(99);
            var failures=new ArrayList<String>();try{assertNull(QueryNbtPayload.read(b,true,true,failures::add));assertEquals(List.of("NBT_RESPONSE_INVALID"),failures);assertEquals(0,b.readableBytes());}finally{b.release();}}
    }
    @Test void lateCancelledReplyIsDiscardedWithoutParsingOrCaching(){
        var b=new FriendlyByteBuf(Unpooled.buffer());b.writeByte(99);try{assertNull(QueryNbtPayload.read(b,true,false,reason->fail("Late cancelled reply should be discarded")));assertEquals(0,b.readableBytes());}finally{b.release();}
    }
    @Test void encodedByteLimitIsIndependentOfAllocationBudget(){
        var b=new FriendlyByteBuf(Unpooled.buffer());b.writeZero(QueryNbtPayload.MAX_WIRE_BYTES+1);var failures=new ArrayList<String>();
        try{assertNull(QueryNbtPayload.read(b,true,true,failures::add));assertEquals(List.of("NBT_RESPONSE_TOO_LARGE"),failures);assertEquals(0,b.readableBytes());}finally{b.release();}
    }
}

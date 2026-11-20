package dev.superwy.game;

import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import java.util.function.Consumer;

/** Only owned tag-query replies get a larger, still bounded, NBT allocation budget. */
public final class QueryNbtPayload {
    public static final long MAX_ACCOUNTED_BYTES=16L*1024*1024;
    public static final int MAX_WIRE_BYTES=2*1024*1024;
    private QueryNbtPayload(){}
    public static CompoundTag read(FriendlyByteBuf buffer,boolean owned,boolean pending,Consumer<String> failure){
        if(!owned)return buffer.readNbt();
        if(!pending){buffer.skipBytes(buffer.readableBytes());return null;}
        try{
            if(buffer.readableBytes()>MAX_WIRE_BYTES)throw new NbtAccounterException("SuperWY query wire budget exceeded");
            Tag tag=buffer.readNbt(NbtAccounter.create(MAX_ACCOUNTED_BYTES));
            if(tag!=null&&!(tag instanceof CompoundTag)||buffer.isReadable())throw new IllegalStateException("Invalid tag-query payload");
            return (CompoundTag)tag;
        }catch(RuntimeException error){
            // PacketDecoder passes a single framed packet; consume only this reply's remainder.
            buffer.skipBytes(buffer.readableBytes());
            failure.accept(error instanceof NbtAccounterException?"NBT_RESPONSE_TOO_LARGE":"NBT_RESPONSE_INVALID");
            return null;
        }
    }
}

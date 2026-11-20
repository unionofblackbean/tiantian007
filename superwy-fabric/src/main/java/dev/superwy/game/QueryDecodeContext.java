package dev.superwy.game;

import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.nbt.CompoundTag;
import java.util.function.Supplier;

/** The actual Netty connection, scoped to one decode call; never guesses from the active world. */
public final class QueryDecodeContext {
    private static final ThreadLocal<Connection> CURRENT=new ThreadLocal<>();
    private QueryDecodeContext(){}
    public static <T> T withConnection(Connection connection,Supplier<T> decode){
        var previous=CURRENT.get();if(connection==null)CURRENT.remove();else CURRENT.set(connection);
        try{return decode.get();}finally{if(previous==null)CURRENT.remove();else CURRENT.set(previous);}
    }
    public static CompoundTag read(FriendlyByteBuf buffer,int transaction){
        var connection=CURRENT.get();var ticket=connection==null?null:Queries.decodeTicket(connection,transaction);
        return QueryNbtPayload.read(buffer,ticket!=null,ticket!=null&&Queries.decodePending(connection,transaction),reason->{if(ticket!=null)ticket.error().compareAndSet(null,reason);});
    }
}

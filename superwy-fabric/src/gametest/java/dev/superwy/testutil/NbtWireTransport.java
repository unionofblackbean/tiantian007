package dev.superwy.testutil;

import dev.superwy.game.*;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.nbt.*;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundTagQueryPacket;
import io.netty.buffer.Unpooled;

/** The integrated connection normally passes packet objects. Exercise real wire codecs in tests. */
public final class NbtWireTransport {
    public static volatile boolean enabled,injectFailures;
    public static int vanillaLimited,lateDiscarded;
    /** Exercise the production PacketDecoder wrapper with its real channel/connection lookup. */
    public static void verifyPacketDecoder(net.minecraft.core.RegistryAccess registries)throws Exception{
        var field=Queries.class.getDeclaredField("TICKETS");field.setAccessible(true);
        @SuppressWarnings("unchecked") var tickets=(dev.superwy.core.QueryTickets<net.minecraft.network.Connection>)field.get(null);
        var protocol=net.minecraft.network.protocol.game.GameProtocols.CLIENTBOUND_TEMPLATE.bind(net.minecraft.network.RegistryFriendlyByteBuf.decorator(registries));
        var tag=new CompoundTag();var list=new ListTag();for(int i=0;i<70000;i++)list.add(new CompoundTag());tag.put("payload",list);
        int transaction=1000000000;
        for(boolean owner:new boolean[]{true,false}){
            var connection=new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.CLIENTBOUND){
                @Override public void exceptionCaught(io.netty.channel.ChannelHandlerContext context,Throwable error){context.fireExceptionCaught(error);}
            };
            if(owner)tickets.remember(connection,transaction);
            var channel=new io.netty.channel.embedded.EmbeddedChannel(new net.minecraft.network.PacketDecoder<>(protocol),new io.netty.channel.ChannelInboundHandlerAdapter(){
                @Override public void channelRead(io.netty.channel.ChannelHandlerContext context,Object message){
                    if(!(message instanceof ClientboundTagQueryPacket reply)||reply.getTag()!=null)throw new AssertionError("Cancelled owned reply was not discarded by PacketDecoder");
                    context.channel().attr(io.netty.util.AttributeKey.<Boolean>valueOf("superwy-decoded")).set(true);
                }
            },connection);
            var buffer=Unpooled.buffer();protocol.codec().encode(buffer,new ClientboundTagQueryPacket(transaction,tag));
            try{
                if(owner){
                    channel.writeInbound(buffer);
                    if(!Boolean.TRUE.equals(channel.attr(io.netty.util.AttributeKey.<Boolean>valueOf("superwy-decoded")).get()))throw new AssertionError("PacketDecoder did not produce owned response");
                }else{
                    boolean limited=false;
                    try{channel.writeInbound(buffer);}catch(io.netty.handler.codec.DecoderException error){limited=error.getCause() instanceof NbtAccounterException;}
                    if(!limited)throw new AssertionError("Foreign connection bypassed vanilla NBT budget");
                }
            }finally{
                // A deliberately rejected foreign reply leaves unread bytes in Netty's cumulation.
                // Do not interpret that remainder as another packet when closing the test channel.
                if(buffer.refCnt()>0)buffer.skipBytes(buffer.readableBytes());
                channel.finishAndReleaseAll();
            }
        }
        System.out.println("SUPERWY_NBT_NETTY_OK: production PacketDecoder locates exact channel connection; cancelled owned large reply discarded; same transaction on foreign connection keeps vanilla NBT limit");
    }
    public static ClientboundTagQueryPacket decode(ClientPacketListener client,ClientboundTagQueryPacket packet){
        var tag=packet.getTag();int x=-1;String name=tag==null?"":String.valueOf(tag.get("CustomName"));for(int i=4;i<=7;i++)if(name.contains("SW_nbt_"+i))x=i;
        if(Queries.decodeTicket(client.getConnection(),packet.getTransactionId())!=null&&!Queries.decodePending(client.getConnection(),packet.getTransactionId()))lateDiscarded++;
        var buffer=new FriendlyByteBuf(Unpooled.buffer());
        try{
            if(injectFailures&&x==6){buffer.writeVarInt(packet.getTransactionId());buffer.writeByte(99);}
            else{
                if(injectFailures&&x==5){var huge=new CompoundTag();var list=new ListTag();var empty=new CompoundTag();for(int i=0;i<400000;i++)list.add(empty);huge.put("payload",list);packet=new ClientboundTagQueryPacket(packet.getTransactionId(),huge);}
                ClientboundTagQueryPacket.STREAM_CODEC.encode(buffer,packet);
            }
            if(x==4){
                int start=buffer.readerIndex();boolean limited=false;
                try{ClientboundTagQueryPacket.STREAM_CODEC.decode(buffer);}catch(NbtAccounterException expected){limited=true;vanillaLimited++;}
                if(!limited)throw new AssertionError("Rich nested fixture must reproduce vanilla NBT accounting overflow");buffer.readerIndex(start);
            }
            var decoded=QueryDecodeContext.withConnection(client.getConnection(),()->ClientboundTagQueryPacket.STREAM_CODEC.decode(buffer));
            if(buffer.isReadable())throw new AssertionError("Reply decoder did not consume the complete frame");
            return decoded;
        }finally{buffer.release();}
    }
}

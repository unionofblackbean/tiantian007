package dev.superwy.game;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** QuickShulker Multi's wire protocol also works without its optional client mod. */
public final class QuickShulkerBridge {
    public record Open(int menuSlot) implements CustomPacketPayload {
        public static final Type<Open> TYPE=new Type<>(Identifier.fromNamespaceAndPath("quickshulker","open_shulker_packet"));
        public static final StreamCodec<FriendlyByteBuf,Open> CODEC=StreamCodec.ofMember((value,buf)->buf.writeInt(value.menuSlot),buf->new Open(buf.readInt()));
        public Type<Open> type(){return TYPE;}
    }
    private static boolean nativeClient;
    private QuickShulkerBridge(){}
    public static void register(){
        nativeClient=FabricLoader.getInstance().isModLoaded("quickshulker");
        // When installed, QuickShulker owns this ID and its codec expects its own payload class.
        if(!nativeClient)PayloadTypeRegistry.serverboundPlay().register(Open.TYPE,Open.CODEC);
    }
    public static boolean available(){return net.minecraft.client.Minecraft.getInstance().getConnection()!=null&&ClientPlayNetworking.canSend(Open.TYPE.id());}
    public static void open(int menuSlot){
        if(!available())throw new IllegalStateException("QUICKSHULKER_UNAVAILABLE");
        if(!nativeClient){ClientPlayNetworking.send(new Open(menuSlot));return;}
        try{Class.forName("net.kyrptonaught.quickshulker.network.OpenShulkerPacket").getMethod("sendOpenPacket",int.class).invoke(null,menuSlot);}
        catch(ReflectiveOperationException e){throw new IllegalStateException("QUICKSHULKER_CLIENT_INCOMPATIBLE",e);}
    }
}

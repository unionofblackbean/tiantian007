package dev.superwy.fault;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.superwy.game.Queries;
import dev.superwy.testutil.NbtWireTransport;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTagQueryPacket;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ClientPacketListener.class)
public abstract class NbtWireTransportMixin {
    @WrapMethod(method="handleTagQueryPacket")
    private void superwy$testWire(ClientboundTagQueryPacket packet,Operation<Void> original){
        if(!NbtWireTransport.enabled){original.call(packet);return;}
        var client=(ClientPacketListener)(Object)this;
        var decoded=NbtWireTransport.decode(client,packet);
        if(!Queries.route(client,decoded))original.call(decoded);
    }
}

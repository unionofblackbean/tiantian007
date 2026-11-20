package dev.superwy.mixin;

import dev.superwy.game.ServuxScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value=ClientCommonPacketListenerImpl.class,priority=1100)
public abstract class ServuxPayloadMixin {
    @Inject(method="handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V",at=@At("HEAD"))
    private void superwy$observe(ClientboundCustomPayloadPacket packet,CallbackInfo callback){
        if(Minecraft.getInstance().isSameThread()&&(Object)this instanceof ClientPacketListener listener)ServuxScanner.observe(listener,packet.payload());
    }
}

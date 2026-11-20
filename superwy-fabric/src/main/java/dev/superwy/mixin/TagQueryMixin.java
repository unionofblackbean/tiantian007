package dev.superwy.mixin;
import dev.superwy.game.Queries;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTagQueryPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(ClientPacketListener.class)
public class TagQueryMixin {
    @Inject(method="handleTagQueryPacket",at=@At("HEAD"),cancellable=true)
    private void superwy$handle(ClientboundTagQueryPacket packet,CallbackInfo ci) {
        if(Queries.route((ClientPacketListener)(Object)this,packet))ci.cancel();
    }
}

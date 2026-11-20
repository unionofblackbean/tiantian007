package dev.superwy.mixin;

import dev.superwy.game.ServuxScanner;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only SuperWY-owned native payload objects use the Servux 0.10.x encoder. */
@Pseudo
@Mixin(targets="fi.dy.masa.litematica.network.ServuxLitematicaPacket",remap=false)
public abstract class ServuxNativeWriteMixin {
    @Inject(method="toPacket",at=@At("HEAD"),cancellable=true,require=0)
    private void superwy$encodeOwnRequest(FriendlyByteBuf output,CallbackInfo callback){if(ServuxScanner.writeNative(this,output))callback.cancel();}
}

package dev.superwy.fault;

import dev.superwy.testutil.ServuxFixture;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets="fi.dy.masa.litematica.network.ServuxLitematicaPacket",remap=false)
public abstract class ServuxNativeDecodeFixtureMixin {
    @Inject(method="fromPacket",at=@At("HEAD"),cancellable=true,require=0)
    private static void superwy$serverVersionOneCodec(FriendlyByteBuf input,CallbackInfoReturnable<Object> callback){
        Object packet=ServuxFixture.decodeNativeRequest(input);if(packet!=null)callback.setReturnValue(packet);
    }
}

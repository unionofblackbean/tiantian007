package dev.superwy.mixin;

import dev.superwy.game.ControlGate;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class ControlClientMixin {
    @Inject(method="pauseIfInactive",at=@At("HEAD"),cancellable=true)
    private void superwy$continueBackgroundTask(CallbackInfo ci){if(ControlGate.active())ci.cancel();}
}

package dev.superwy.baritonefix.mixin;
import dev.superwy.baritonefix.SteeringBridge;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=LocalPlayer.class,priority=300)
public abstract class PlayerSteeringMixin {
    @Inject(method="aiStep",at=@At(value="INVOKE",target="Lnet/minecraft/client/player/ClientInput;tick()V",shift=At.Shift.AFTER))
    private void baritonefix$manualMotion(CallbackInfo ci){SteeringBridge.mergeManualInput();}
}

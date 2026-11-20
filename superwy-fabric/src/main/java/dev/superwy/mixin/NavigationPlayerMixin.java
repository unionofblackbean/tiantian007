package dev.superwy.mixin;
import dev.superwy.game.Navigation;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=LocalPlayer.class,priority=400)
public abstract class NavigationPlayerMixin {
    @Inject(method="aiStep",at=@At(value="INVOKE",target="Lnet/minecraft/client/player/ClientInput;tick()V",shift=At.Shift.AFTER))
    private void superwy$manualMotion(CallbackInfo ci){Navigation.mergeManualInput();}
}

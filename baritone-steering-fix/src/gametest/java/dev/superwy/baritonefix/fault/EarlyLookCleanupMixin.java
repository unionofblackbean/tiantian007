package dev.superwy.baritonefix.fault;
import baritone.api.BaritoneAPI;
import baritone.api.event.events.PlayerUpdateEvent;
import baritone.api.event.events.type.EventState;
import dev.superwy.baritonefix.SteeringGameTest;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Test-only reproduction of a target cleared before physical movement consumes it. */
@Mixin(value=LocalPlayer.class,priority=600)
public abstract class EarlyLookCleanupMixin {
    @Inject(method="aiStep",at=@At("HEAD")) private void baritonefix$earlyCleanup(CallbackInfo ci){
        if(SteeringGameTest.earlyCleanup){BaritoneAPI.getProvider().getPrimaryBaritone().getGameEventHandler().onPlayerUpdate(new PlayerUpdateEvent(EventState.POST));SteeringGameTest.cleanups++;}
    }
}

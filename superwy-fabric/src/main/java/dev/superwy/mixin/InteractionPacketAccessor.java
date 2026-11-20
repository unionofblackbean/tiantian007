package dev.superwy.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.multiplayer.prediction.PredictiveAction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reuse vanilla's selected-slot sync and interaction sequence allocation. */
@Mixin(MultiPlayerGameMode.class)
public interface InteractionPacketAccessor {
    @Invoker("ensureHasSentCarriedItem") void superwy$syncCarriedItem();
    @Invoker("startPrediction") void superwy$sendInteraction(ClientLevel level, PredictiveAction action);
}

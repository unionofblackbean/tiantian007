package dev.superwy.fault;

import dev.superwy.NavigationFaults;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Simulate another mod replacing yaw after Baritone's normal movement hook. Test source only. */
@Mixin(value=Entity.class,priority=500)
public abstract class NavigationYawConflictMixin {
    @Inject(method="moveRelative",at=@At("HEAD"))
    private void superwy$foreignFixedYaw(CallbackInfo ci){if(NavigationFaults.overrideYaw&&(Object)this==Minecraft.getInstance().player){Minecraft.getInstance().player.setYRot(180);NavigationFaults.writes++;}}
}

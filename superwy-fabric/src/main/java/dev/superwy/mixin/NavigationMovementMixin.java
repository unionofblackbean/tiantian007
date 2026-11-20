package dev.superwy.mixin;

import dev.superwy.game.Navigation;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value=Entity.class,priority=400)
public abstract class NavigationMovementMixin {
    @ModifyArg(method="moveRelative",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;getInputVector(Lnet/minecraft/world/phys/Vec3;FF)Lnet/minecraft/world/phys/Vec3;"),index=2)
    private float superwy$plannedMovementYaw(float yaw){return (Object)this==Minecraft.getInstance().player?Navigation.movementYaw(yaw):yaw;}
}

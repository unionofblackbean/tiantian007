package dev.superwy.baritonefix.mixin;
import dev.superwy.baritonefix.SteeringBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=Entity.class,priority=300)
public abstract class MovementSteeringMixin {
    @ModifyArg(method="moveRelative",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;getInputVector(Lnet/minecraft/world/phys/Vec3;FF)Lnet/minecraft/world/phys/Vec3;"),index=2)
    private float baritonefix$actualYaw(float yaw){return (Object)this==Minecraft.getInstance().player?SteeringBridge.movementYaw(yaw):yaw;}
}

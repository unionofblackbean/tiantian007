package dev.superwy.mixin;
import net.minecraft.client.DebugQueryHandler;
import net.minecraft.nbt.CompoundTag;
import java.util.function.Consumer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(DebugQueryHandler.class)
public interface DebugQueryAccessor {
    @Invoker("startTransaction") int superwy$reserveTransaction(Consumer<CompoundTag> callback);
}

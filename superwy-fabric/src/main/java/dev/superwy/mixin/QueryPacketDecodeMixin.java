package dev.superwy.mixin;

import dev.superwy.game.QueryDecodeContext;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundTagQueryPacket;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Mixin(ClientboundTagQueryPacket.class)
public abstract class QueryPacketDecodeMixin {
    @Shadow @Final private int transactionId;
    @Redirect(method="<init>(Lnet/minecraft/network/FriendlyByteBuf;)V",at=@At(value="INVOKE",target="Lnet/minecraft/network/FriendlyByteBuf;readNbt()Lnet/minecraft/nbt/CompoundTag;"))
    private CompoundTag superwy$readOwnedReply(FriendlyByteBuf buffer){return QueryDecodeContext.read(buffer,transactionId);}
}

package dev.superwy.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.superwy.game.QueryDecodeContext;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketDecoder;
import org.spongepowered.asm.mixin.Mixin;
import java.util.List;

@Mixin(PacketDecoder.class)
public abstract class QueryDecodeConnectionMixin {
    @WrapMethod(method="decode")
    private void superwy$decodeConnection(ChannelHandlerContext context,ByteBuf buffer,List<Object> packets,Operation<Void> original){
        QueryDecodeContext.withConnection(context.pipeline().get(Connection.class),()->{original.call(context,buffer,packets);return null;});
    }
}

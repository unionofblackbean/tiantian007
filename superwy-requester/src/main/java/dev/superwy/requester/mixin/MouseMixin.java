package dev.superwy.requester.mixin;
import dev.superwy.requester.RequesterClient;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=MouseHandler.class,priority=2000)
public abstract class MouseMixin {
    @Inject(method="onButton",at=@At("HEAD"),cancellable=true)
    private void superwy$pick(long window,MouseButtonInfo button,int action,CallbackInfo ci){
        if(RequesterClient.INSTANCE!=null&&RequesterClient.INSTANCE.mouse(action,button.button()))ci.cancel();
    }
}

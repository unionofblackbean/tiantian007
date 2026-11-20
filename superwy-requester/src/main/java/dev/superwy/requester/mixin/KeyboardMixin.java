package dev.superwy.requester.mixin;
import dev.superwy.requester.RequesterClient;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(KeyboardHandler.class)
public abstract class KeyboardMixin {
    @Inject(method="keyPress",at=@At("HEAD"),cancellable=true)
    private void superwy$quantity(long window,int action,KeyEvent event,CallbackInfo ci){
        if(RequesterClient.INSTANCE!=null&&RequesterClient.INSTANCE.key(action,event.key(),event.scancode(),event.modifiers()))ci.cancel();
    }
}

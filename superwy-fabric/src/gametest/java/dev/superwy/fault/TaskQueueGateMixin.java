package dev.superwy.fault;
import dev.superwy.*;
import dev.superwy.core.*;
import dev.superwy.game.OrderEngine;
import net.minecraft.client.Minecraft;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(OrderEngine.class)
public abstract class TaskQueueGateMixin {
    @Shadow private CompletableFuture<Void> fetch(InventoryIndex.Source source,Model.Line line,Model.RequestLine request){throw new AssertionError();}
    @Inject(method="fetch",at=@At("HEAD"),cancellable=true)
    private void gate(InventoryIndex.Source source,Model.Line line,Model.RequestLine request,CallbackInfoReturnable<CompletableFuture<Void>> ci){
        if(!TaskQueueGate.armed)return;TaskQueueGate.armed=false;TaskQueueGate.started=true;var scope=TaskScope.current();scope.track(TaskQueueGate.release);
        ci.setReturnValue(TaskQueueGate.release.thenComposeAsync(v->fetch(source,line,request),scope.executor(Minecraft.getInstance()::execute)));
    }
}

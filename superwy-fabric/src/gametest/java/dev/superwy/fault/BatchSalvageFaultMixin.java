package dev.superwy.fault;
import dev.superwy.*;
import dev.superwy.core.*;
import dev.superwy.game.OrderEngine;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(OrderEngine.class)
public abstract class BatchSalvageFaultMixin {
    @Inject(method="chooseSource",at=@At("HEAD"),cancellable=true)
    private void injectFailure(Model.Line line,List<InventoryIndex.Source> sources,CallbackInfoReturnable<CompletableFuture<Void>> ci){
        String target=BatchSalvageFaults.failItem;
        if(target==null||!target.equals(line.itemId))return;
        BatchSalvageFaults.failItem=null;
        ci.setReturnValue(CompletableFuture.failedFuture(new IllegalStateException("INJECTED_FAILURE")));
    }
}

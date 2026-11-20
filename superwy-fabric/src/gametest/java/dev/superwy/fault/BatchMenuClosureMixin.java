package dev.superwy.fault;

import dev.superwy.BatchMenuFaults;
import dev.superwy.game.Actions;
import dev.superwy.game.Items;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Actions.class)
public abstract class BatchMenuClosureMixin {
    @Inject(method="click",at=@At("HEAD"))
    private void beforeClick(int id,int slot,int button,CallbackInfo ci){
        var mc=Minecraft.getInstance();
        if(BatchMenuFaults.stage==0||BatchMenuFaults.armed||mc.player==null||mc.player.containerMenu.containerId!=id)return;
        if(!Items.id(mc.player.containerMenu.getSlot(slot).getItem()).equals(BatchMenuFaults.item))return;
        BatchMenuFaults.armed=true;
        if(BatchMenuFaults.stage==1){BatchMenuFaults.stage=0;BatchMenuFaults.closures++;BatchMenuFaults.closedAt=System.nanoTime();mc.player.closeContainer();}
    }
    @Inject(method="click",at=@At("TAIL"))
    private void afterClick(int id,int slot,int button,CallbackInfo ci){
        if(!BatchMenuFaults.armed||BatchMenuFaults.stage<2)return;
        if(++BatchMenuFaults.clicks==(BatchMenuFaults.stage==3?1:2)){
            BatchMenuFaults.stage=0;BatchMenuFaults.closures++;BatchMenuFaults.closedAt=System.nanoTime();Minecraft.getInstance().player.closeContainer();
        }
    }
    @Inject(method="openAttempt",at=@At("HEAD"))
    private void reopened(net.minecraft.core.BlockPos pos,org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<java.util.concurrent.CompletableFuture<Void>> ci){
        if(BatchMenuFaults.closedAt!=0&&BatchMenuFaults.reopenedAt==0)BatchMenuFaults.reopenedAt=System.nanoTime();
    }
}

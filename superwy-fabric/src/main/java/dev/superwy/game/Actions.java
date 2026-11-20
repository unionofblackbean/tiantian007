package dev.superwy.game;

import dev.superwy.core.*;
import dev.superwy.mixin.InteractionPacketAccessor;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public final class Actions {
    private static final class Wait {
        final BooleanSupplier condition,deferTimeout;final CompletableFuture<Void> future;final String code;final int timeoutMillis;long deadline;
        Wait(BooleanSupplier condition,int timeoutMillis,CompletableFuture<Void> future,String code,BooleanSupplier deferTimeout){this.condition=condition;this.timeoutMillis=timeoutMillis;this.future=future;this.code=code;this.deferTimeout=deferTimeout;deadline=System.currentTimeMillis()+timeoutMillis;}
    }
    private final List<Wait> waits=new ArrayList<>();
    public final Minecraft mc=Minecraft.getInstance();
    public final Navigation navigation=new Navigation();
    private final Queries queries;
    private final BooleanSupplier controlAllowed;
    public Actions(Queries queries){this(queries,()->true);}
    public Actions(Queries queries,BooleanSupplier controlAllowed){this.queries=queries;this.controlAllowed=controlAllowed;}
    private void requireControl(){TaskScope.checkCurrent();if(!controlAllowed.getAsBoolean())throw new IllegalStateException("CONTROL_STOPPED");}
    public java.util.concurrent.Executor executor(){var scope=TaskScope.current();return scope==null?mc::execute:scope.executor(mc::execute);}
    public CompletableFuture<Void> await(BooleanSupplier test,int milliseconds,String code){return await(test,milliseconds,code,()->false);}
    private CompletableFuture<Void> await(BooleanSupplier test,int milliseconds,String code,BooleanSupplier deferTimeout){requireControl();var f=new CompletableFuture<Void>();var scope=TaskScope.current();if(scope!=null)scope.track(f);waits.add(new Wait(test,milliseconds,f,code,deferTimeout));return f;}
    public CompletableFuture<Void> delay(int milliseconds){long due=System.currentTimeMillis()+milliseconds;return await(()->System.currentTimeMillis()>=due,milliseconds+2000,"TIMER_TIMEOUT");}
    public void tick(){
        for(Wait w:List.copyOf(waits)){
            if(w.future.isDone()){waits.remove(w);continue;}
            try{if(w.condition.getAsBoolean()){waits.remove(w);w.future.complete(null);}else if(w.deferTimeout.getAsBoolean()){w.deadline=System.currentTimeMillis()+w.timeoutMillis;}else if(System.currentTimeMillis()>w.deadline){waits.remove(w);w.future.completeExceptionally(new IllegalStateException(w.code));}}
            catch(Exception e){waits.remove(w);w.future.completeExceptionally(e);}
        }
    }
    public void abort(){navigation.stop();for(Wait w:List.copyOf(waits))w.future.completeExceptionally(new IllegalStateException("DISCONNECTED_OR_PAUSED"));waits.clear();}
    public CompletableFuture<Void> go(BlockPos p){return go(p,0);}
    public CompletableFuture<Void> goNear(BlockPos p,int radius){return go(p,radius);}
    private CompletableFuture<Void> go(BlockPos p,int radius){
        requireControl();
        if(mc.player==null)return CompletableFuture.failedFuture(new IllegalStateException("DISCONNECTED"));
        if(mc.player.blockPosition().distSqr(p)<=radius*radius)return CompletableFuture.completedFuture(null);
        try{navigation.go(p,radius);}catch(Exception e){navigation.stop();return CompletableFuture.failedFuture(e);}
        return await(navigation::poll,30000,"PATH_UNREACHABLE",Navigation::manualInputActive)
            .whenCompleteAsync((v,e)->{if(e!=null)navigation.failed(dev.superwy.game.Scanner.rootMessage(e));navigation.stop();},executor()).thenComposeAsync(v->delay(250),executor());
    }
    public CompletableFuture<Void> approach(BlockPos p){return approach(p,Navigation.CONTAINER_REACH);}
    private CompletableFuture<Void> approach(BlockPos p,double reach){
        requireControl();
        if(Navigation.containerHit(p,reach)!=null)return CompletableFuture.completedFuture(null);
        if(Navigation.loaded(p)&&!(mc.level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity))return CompletableFuture.failedFuture(new IllegalStateException("SOURCE_CONTAINER_MISSING"));
        try{navigation.goContainer(p,reach);}catch(Exception e){navigation.stop();return CompletableFuture.failedFuture(e);}
        // Keep following the path past the first boundary hit. Cancelling at that instant
        // can leave the settling player just outside reach (especially beside another box).
        double settledReach=Math.max(.1,reach-.35);
        return await(()->mc.player.onGround()&&Navigation.containerHit(p,settledReach)!=null||navigation.poll(),30000,"CONTAINER_PATH_UNREACHABLE",Navigation::manualInputActive)
            .whenCompleteAsync((v,e)->{if(e!=null)navigation.failed(dev.superwy.game.Scanner.rootMessage(e));navigation.stop();},executor())
            .thenComposeAsync(v->delay(250),executor()).thenRunAsync(()->{
                requireControl();
                if(!Navigation.loaded(p))throw new IllegalStateException("CONTAINER_UNLOADED");
                if(!(mc.level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity))throw new IllegalStateException("SOURCE_CONTAINER_MISSING");
                if(Navigation.containerHit(p,reach)==null){
                    org.slf4j.LoggerFactory.getLogger("SuperWY").warn("容器寻路结束后仍超出距离：target={}, reach={}, navigation={}",p,reach,navigation.status());
                    throw new IllegalStateException("CONTAINER_OUT_OF_REACH");
                }
            },executor());
    }
    public CompletableFuture<Void> close(){
        requireControl();
        if(mc.player==null)return CompletableFuture.failedFuture(new IllegalStateException("DISCONNECTED"));
        if(!mc.player.containerMenu.getCarried().isEmpty())return CompletableFuture.failedFuture(new IllegalStateException("CURSOR_NOT_EMPTY"));
        mc.player.closeContainer();return delay(200);
    }
    public CompletableFuture<Void> open(BlockPos p){
        return approach(p).thenComposeAsync(v->openAttempt(p),executor()).exceptionallyComposeAsync(error->{
            requireControl();
            if(!"OPEN_CONTAINER_TIMEOUT".equals(dev.superwy.game.Scanner.rootMessage(error)))return CompletableFuture.failedFuture(error);
            // Reach mods may accept 10 blocks without changing the advertised attribute.
            // If rejected, approach the advertised range without imposing a client line-of-sight rule.
            if(mc.player.containerMenu!=mc.player.inventoryMenu)return CompletableFuture.completedFuture(null);
            double reach=Math.min(Navigation.CONTAINER_REACH,mc.player.blockInteractionRange());
            if(Navigation.containerHit(p,reach)!=null)return CompletableFuture.failedFuture(error);
            return approach(p,reach).thenComposeAsync(v->openAttempt(p),executor());
        },executor()).thenComposeAsync(v->delay(250),executor());
    }
    private CompletableFuture<Void> openAttempt(BlockPos p){
            requireControl();
            if(mc.player.containerMenu!=mc.player.inventoryMenu)throw new IllegalStateException("UNEXPECTED_MENU");
            var hit=Navigation.containerHit(p,Navigation.CONTAINER_REACH);if(hit==null)throw new IllegalStateException("CONTAINER_OUT_OF_REACH");
            // No client raycast or local item-use prediction: target the cached coordinate directly.
            // Vanilla owns the sequence and selected hotbar slot; the server decides whether to open.
            var packets=(InteractionPacketAccessor)mc.gameMode;
            packets.superwy$syncCarriedItem();
            packets.superwy$sendInteraction(mc.level,sequence->new ServerboundUseItemOnPacket(InteractionHand.MAIN_HAND,hit,sequence));
            return await(()->mc.player.containerMenu!=mc.player.inventoryMenu,4000,"OPEN_CONTAINER_TIMEOUT");
    }
    /** Send a normal entity right click; the server's GCA rule supplies the menu. */
    public CompletableFuture<Void> openRecipient(net.minecraft.world.entity.player.Player recipient){
        return goNear(recipient.blockPosition(),2).thenComposeAsync(v->{
            requireControl();
            if(mc.player.containerMenu!=mc.player.inventoryMenu)throw new IllegalStateException("UNEXPECTED_MENU");
            if(mc.level.getEntity(recipient.getId())!=recipient||!mc.player.hasLineOfSight(recipient))throw new IllegalStateException("RECIPIENT_NOT_REACHABLE");
            mc.gameMode.interact(mc.player,recipient,new net.minecraft.world.phys.EntityHitResult(recipient,recipient.getBoundingBox().getCenter()),InteractionHand.MAIN_HAND);
            return await(()->mc.player.containerMenu!=mc.player.inventoryMenu,4000,"GCA_INVENTORY_OPEN_TIMEOUT");
        },executor()).thenComposeAsync(v->delay(250),executor());
    }
    public boolean quickAvailable(){return QuickShulkerBridge.available();}
    public CompletableFuture<Void> quick(int inventorySlot){
        requireControl();
        if(!quickAvailable())return CompletableFuture.failedFuture(new IllegalStateException("QUICKSHULKER_UNAVAILABLE"));
        try{
            if(mc.player.containerMenu!=mc.player.inventoryMenu)throw new IllegalStateException("UNEXPECTED_MENU");
            int menuSlot=playerSlot(inventorySlot);
            QuickShulkerBridge.open(menuSlot);
            return await(()->mc.player.containerMenu!=mc.player.inventoryMenu,4000,"QUICKSHULKER_OPEN_TIMEOUT").thenComposeAsync(v->delay(250),executor());
        }catch(Exception e){return CompletableFuture.failedFuture(e);}
    }
    public int playerSlot(int index){
        for(int i=0;i<mc.player.containerMenu.slots.size();i++){
            Slot s=mc.player.containerMenu.getSlot(i);if(s.container==mc.player.getInventory()&&s.getContainerSlot()==index)return i;
        }throw new IllegalStateException("PLAYER_SLOT_NOT_FOUND");
    }
    public List<Integer> containerSlots(){List<Integer> result=new ArrayList<>();for(int i=0;i<mc.player.containerMenu.slots.size();i++)if(mc.player.containerMenu.getSlot(i).container!=mc.player.getInventory())result.add(i);return result;}
    public ItemStack inventory(int slot){return mc.player.getInventory().getItem(slot);}
    public CompletableFuture<Map<Integer,ItemStack>> serverInventory(){requireControl();return queries.entity(mc.player.getId()).thenApply(Items::playerInventory);}
    private void click(int id,int slot,int button){
        requireControl();
        if(mc.player==null||mc.player.containerMenu.containerId!=id)throw new IllegalStateException("MENU_DESYNC");
        mc.gameMode.handleContainerInput(id,slot,button,ContainerInput.PICKUP,mc.player);
    }
    /** Exact normal clicks. Completion is a local prediction; caller must close and verify via server NBT. */
    public CompletableFuture<Void> move(int from,int to,int count){
        requireControl();
        var menu=mc.player.containerMenu;ItemStack source=menu.getSlot(from).getItem().copy(),dest=menu.getSlot(to).getItem().copy();
        if(!menu.getCarried().isEmpty()||count<1||count>source.getCount()||!menu.getSlot(from).mayPickup(mc.player)||!menu.getSlot(to).mayPlace(source))return CompletableFuture.failedFuture(new IllegalStateException("INVALID_TRANSFER"));
        int room=dest.isEmpty()?Math.min(source.getMaxStackSize(),menu.getSlot(to).getMaxStackSize(source)):Items.same(source,dest)?Math.min(source.getMaxStackSize(),menu.getSlot(to).getMaxStackSize(source))-dest.getCount():0;
        if(room<count)return CompletableFuture.failedFuture(new IllegalStateException("INVENTORY_FULL"));
        int id=menu.containerId;click(id,from,0);
        CompletableFuture<Void> chain=delay(100);
        if(count==source.getCount()) chain=chain.thenRunAsync(()->click(id,to,0),executor());
        else {
            for(int i=0;i<count;i++)chain=chain.thenRunAsync(()->click(id,to,1),executor()).thenComposeAsync(v->delay(75),executor());
            chain=chain.thenRunAsync(()->click(id,from,0),executor());
        }
        return chain.thenComposeAsync(v->delay(200),executor()).thenRunAsync(()->{
            if(mc.player.containerMenu.containerId!=id||!menu.getCarried().isEmpty())throw new IllegalStateException("CURSOR_OR_MENU_DESYNC");
            ItemStack got=menu.getSlot(to).getItem();if(!Items.same(got,source)||got.getCount()!=dest.getCount()+count)throw new IllegalStateException("TRANSFER_PREDICTION_FAILED");
        },executor());
    }
    public CompletableFuture<Void> verifySlot(int slot,ItemStack expected){
        ItemStack copy=expected.copy();return serverInventory().thenAccept(actual->{ItemStack got=actual.getOrDefault(slot,ItemStack.EMPTY);if(got.getCount()!=copy.getCount()||(!got.isEmpty()&&!Items.same(got,copy)))throw new IllegalStateException("SERVER_INVENTORY_MISMATCH");});
    }
    public void command(String command){requireControl();mc.getConnection().sendCommand(command);}
    public CompletableFuture<Void> throwMainStack(int inventorySlot,BlockPos target){
        requireControl();if(inventorySlot<9||inventorySlot>35||mc.player.containerMenu!=mc.player.inventoryMenu||!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalStateException("INVALID_CLEANUP_SLOT");
        float yaw=mc.player.getYRot(),pitch=mc.player.getXRot();
        double dx=target.getX()+.5-mc.player.getX(),dz=target.getZ()+.5-mc.player.getZ();
        float dropYaw=(float)Math.toDegrees(Math.atan2(-dx,dz)),dropPitch=DropAim.pitch(Math.hypot(dx,dz),mc.player.getEyeY()-.3-(target.getY()+1));
        mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(dropYaw,dropPitch,mc.player.onGround(),mc.player.horizontalCollision));
        mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId,playerSlot(inventorySlot),1,ContainerInput.THROW,mc.player);
        mc.getConnection().send(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(yaw,pitch,mc.player.onGround(),mc.player.horizontalCollision));
        return delay(250).thenComposeAsync(v->verifySlot(inventorySlot,ItemStack.EMPTY),executor());
    }
}

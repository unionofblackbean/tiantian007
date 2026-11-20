package dev.superwy.game;

import dev.superwy.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Single FIFO actor. Every inventory click is journalled; uncertain transfers are never replayed. */
public final class OrderEngine {
    private final Minecraft mc=Minecraft.getInstance();
    private final Store store;private final Config config;private final Queries queries;private final Actions a;private final Scanner scanner;
    private final Supplier<String> world,dimension;
    private Model.Order order;
    private CompletableFuture<Void> running;private Player recipient;
    private CompletableFuture<Void> queueMutation=CompletableFuture.completedFuture(null);
    private final OrderQueue.Progress queueProgress=new OrderQueue.Progress();
    private TaskScope scope;
    private String boundWorld,boundDimension;
    private record UnavailableSource(int lineIndex,String container,String boxHash){}
    private final Set<UnavailableSource> unavailableSources=new HashSet<>();
    private final Set<String> missingContainers=new HashSet<>();
    private boolean safeWaiting,spawned,forcedStop,uncertain,deliveryUncertain,borrowed;
    private static final class BorrowContext {
        BlockPos parent;int original,work;ItemStack expected;
        BorrowContext(BlockPos p,int original,int work,ItemStack expected){parent=p;this.original=original;this.work=work;this.expected=expected.copy();}
    }
    private record PendingTake(Model.Cargo cargo,Model.Line line,int previous,int count,ItemStack item,List<ItemStack> before,ItemStack boxAfter){}
    private BorrowContext borrowContext;private PendingTake pendingTake;
    private BlockPos currentSource;
    private int roundBudget=-1;private boolean firstRound=true;private Model.Parcel openParcel,keptParcel;
    private long lastParcelProgressAt;
    private dev.superwy.mesh.MeshHooks hooks=dev.superwy.mesh.MeshHooks.NONE;
    private String recipientLock;
    public void hooks(dev.superwy.mesh.MeshHooks hooks){this.hooks=hooks==null?dev.superwy.mesh.MeshHooks.NONE:hooks;}
    /** Mesh blocks report to the authority; the authority speaks to players and public chat. */
    private boolean quiet(){return order!=null&&order.request.meshChunkId!=null;}
    private String meshKey(BlockPos pos){return dev.superwy.mesh.MeshHooks.key(boundDimension,pos.getX(),pos.getY(),pos.getZ());}
    /** Shared stations (box supply, cleanup chest): wait up to 30 s while another robot is using them. */
    private CompletableFuture<Void> claim(BlockPos pos,int attempts){
        guard();String key=meshKey(pos);
        if(attempts<=0||!hooks.busyElsewhere(key)){hooks.using(key);return CompletableFuture.completedFuture(null);}
        return a.delay(1000).thenComposeAsync(v->claim(pos,attempts-1),ex());
    }
    private CompletableFuture<Void> lockRecipient(){
        guard();String base=order.delivery.baseFakePlayerName==null?order.delivery.fakePlayerName:order.delivery.baseFakePlayerName;
        if(base.equals(recipientLock))return CompletableFuture.completedFuture(null);
        var wait=new CompletableFuture<Void>();scope.track(wait);
        hooks.acquireRecipient(base).whenComplete((v,e)->{if(e!=null)wait.completeExceptionally(e);else wait.complete(null);});
        if(wait.isDone()&&!wait.isCompletedExceptionally()){recipientLock=base;return wait;}
        return state("waiting_recipient",null).thenComposeAsync(v->wait,ex()).thenRunAsync(()->{guard();recipientLock=base;},ex());
    }
    private void unlockRecipient(){if(recipientLock!=null){hooks.releaseRecipient(recipientLock);recipientLock=null;}}
    /** A resumed mesh block adopts cargo and parcels from the interrupted order; check they are still here. */
    private CompletableFuture<Void> verifyAdopted(){
        guard();if(!hasCargo())return CompletableFuture.completedFuture(null);
        return verifyHeldCargo().thenComposeAsync(v->a.serverInventory(),ex()).thenAcceptAsync(inventory->{
            guard();for(var parcel:order.packages)if(parcel.custody.equals("robot")){
                ItemStack box=inventory.getOrDefault(parcel.inventorySlot,ItemStack.EMPTY);
                if(box.getCount()!=1||!Items.shulker(box)||!Items.hash(box).equals(parcel.manifestHash))throw new IllegalStateException("PARCEL_CONTENT_CHANGED");
            }
        },ex());
    }
    public OrderEngine(Store store,Config config,Queries queries,Actions a,Scanner scanner,Supplier<String> world,Supplier<String> dimension){this.store=store;this.config=config;this.queries=queries;this.a=a;this.scanner=scanner;this.world=world;this.dimension=dimension;}
    public boolean busy(){return running!=null&&!running.isDone();}
    public boolean safeWaiting(){return safeWaiting;}
    public String activeId(){return order==null?null:order.orderId;}
    private Executor ex(){return scope.executor(mc::execute);}
    public CompletableFuture<Model.Order> cancel(String id){
        if(order!=null&&order.orderId.equals(id)&&busy()){
            if(!forcedStop)forceStop("USER_CANCELLED",false);
            return queueMutation.thenApply(v->store.order(id));
        }
        Model.Order pending=store.order(id);
        if(pending==null)return CompletableFuture.failedFuture(new IllegalArgumentException("NOT_FOUND"));
        if(pending.terminal())return pending.state.equals("cancelled")?CompletableFuture.completedFuture(pending):CompletableFuture.failedFuture(new IllegalArgumentException("ORDER_NOT_CANCELLABLE"));
        pending.cancelRequested=true;pending.errorCode=null;pending.terminationReason="USER_CANCELLED";pending.executionStoppedAt=Instant.now().toString();pending.state("cancelled","USER_CANCELLED");
        queueMutation=CompletableFuture.allOf(queueMutation,store.saveOrder(pending));return queueMutation.thenApply(v->store.order(id));
    }
    private void forceStop(String reason,boolean timeout){
        if(forcedStop)return;
        boolean ownedNavigation=!safeWaiting;
        forcedStop=true;safeWaiting=false;order.cancelRequested=!timeout;
        order.executionStoppedAt=Instant.now().toString();order.terminationReason=reason;order.errorCode=timeout?reason:null;
        order.needsItemReview=uncertain||borrowed||hasCargo()||spawned||order.lines.stream().anyMatch(l->l.delivered>0)||mc.player!=null&&!mc.player.containerMenu.getCarried().isEmpty();
        order.delivery.stage="interrupted";order.state(timeout?"failed":"cancelled",reason);
        // Serialize the terminal record before waking any cancelled continuations.
        queueMutation=CompletableFuture.allOf(queueMutation,store.saveOrder(order));
        scope.stop(reason);if(ownedNavigation)a.navigation.stop();
        if(timeout)announceFailure(reason);
    }
    /** Independent of inventory and path progress; waits count toward the 500-second budget. */
    public void watchdog(){
        if(!busy()||forcedStop)return;
        if(!order.terminal())queueProgress.observe(order,store.queuedCount(order)+1);
        if(scope.expired())forceStop("ORDER_TIMEOUT",true);
        else if(Instant.now().isAfter(Instant.parse(order.expiresAt)))forceStop("EXPIRED",false);
    }
    public void tick(){
        watchdog();
        if(!config.automationEnabled||store.failure!=null||mc.player==null||mc.level==null||!mc.player.isAlive())return;
        if(busy()||!scanner.safe()||!queueMutation.isDone())return;
        order=store.nextQueued(world.get(),dimension.get());if(order==null){queueProgress.idle();return;}
        boundWorld=world.get();boundDimension=dimension.get();recipient=null;spawned=forcedStop=uncertain=deliveryUncertain=borrowed=safeWaiting=false;
        queueProgress.observe(order,store.queuedCount(order)+1);
        unavailableSources.clear();missingContainers.clear();
        borrowContext=null;pendingTake=null;currentSource=null;roundBudget=-1;cargoRetries=0;strayTried=false;strayFailed=false;firstRound=true;openParcel=null;keptParcel=null;lastParcelProgressAt=0;
        order.packingMode=order.request.projectionParcels||order.request.forcePacking||manifestSlots()>config.packingRoundSlots;
        // Mesh blocks may wait for the shared recipient while another robot delivers.
        order.executionTimeoutSeconds=order.packingMode?config.packingTimeoutSeconds:order.request.meshChunkId!=null?1800:TaskScope.TIMEOUT_SECONDS;
        recipientLock=null;scope=new TaskScope(order.executionTimeoutSeconds);order.executionStartedAt=Instant.now().toString();
        running=scope.call(this::begin).handleAsync((v,error)->{
            if(forcedStop)return queueMutation;
            safeWaiting=false;a.navigation.stop();
            if(error!=null){
                String reason=Scanner.rootMessage(error);
                if(!salvageable(reason))return failOrder(reason);
                return salvage(reason).handleAsync((x,salvageError)->{
                    if(forcedStop)return queueMutation;
                    if(salvageError!=null){
                        a.navigation.stop();order.salvageErrorCode=Scanner.rootMessage(salvageError);
                        org.slf4j.LoggerFactory.getLogger("SuperWY").warn("批量取货失败后收尾未完成：order={}, reason={}, salvage={}",order.orderId,reason,order.salvageErrorCode);
                    }
                    return failOrder(reason);
                },ex()).thenCompose(f->f);
            }
            return CompletableFuture.<Void>completedFuture(null);
        },ex()).thenCompose(v->v).whenCompleteAsync((v,e)->{unlockRecipient();hooks.using(null);},mc::execute).thenRunAsync(this::announceQueueProgress,mc::execute);
    }
    private CompletableFuture<Void> failOrder(String reason){
        order.executionStoppedAt=Instant.now().toString();
        boolean cleanSalvage=order.salvageMode!=null&&order.salvageErrorCode==null;
        // A verified salvage leaves nothing to check; otherwise any delivery or held item needs a human look.
        order.needsItemReview=uncertain||borrowed||deliveryUncertain||hasCargo()||spawned||order.salvageErrorCode!=null
            ||!cleanSalvage&&order.lines.stream().anyMatch(l->l.delivered>0)||mc.player!=null&&!mc.player.containerMenu.getCarried().isEmpty();
        order.interruptedState=deliveryUncertain?"transferring":order.state;
        order.terminationReason="EXECUTION_ERROR";order.errorCode=reason;order.state("failed",reason);
        order.delivery.stage=deliveryUncertain?"uncertain":"interrupted";
        scope.stop(reason);return store.saveOrder(order).thenRunAsync(()->announceFailure(reason),mc::execute);
    }
    /**
     * Batch orders only. The robot must still be in a known, usable state; forced stops, lost worlds and
     * uncertain transfers keep the old behaviour of leaving everything in place for review.
     */
    private boolean salvageable(String reason){
        if(order.request.batchId==null||forcedStop||scope.stopped()||scope.expired())return false;
        if(Set.of("EXECUTOR_INTERRUPTED","STORAGE_UNAVAILABLE","ORDER_TIMEOUT","EXPIRED","USER_CANCELLED","CONTROL_STOPPED","CURSOR_NOT_EMPTY","RECONCILIATION_REQUIRED","DELIVERY_UNCERTAIN").contains(reason))return false;
        if(deliveryUncertain||!config.automationEnabled||config.manualControl||mc.player==null||mc.level==null||!mc.player.isAlive()||store.failure!=null)return false;
        if(!Objects.equals(world.get(),boundWorld)||!Objects.equals(dimension.get(),boundDimension)||!mc.player.containerMenu.getCarried().isEmpty())return false;
        return hasCargo()||borrowed||pendingTake!=null||spawned;
    }
    /**
     * Empty-box shortage: hand over every filled box, return loose items to the safe (cleanup) chest.
     * Any other failure: box the remaining loose items as well and hand everything over.
     */
    private CompletableFuture<Void> salvage(String reason){
        guard();
        order.salvageMode=OrderFailurePolicy.boxShortage(reason)?"stash_loose":"pack_and_deliver";
        org.slf4j.LoggerFactory.getLogger("SuperWY").info("批量取货失败，开始收尾：order={}, reason={}, mode={}",order.orderId,reason,order.salvageMode);
        return a.close().thenComposeAsync(v->reconcileTake(),ex()).thenComposeAsync(v->repairBorrow(),ex()).thenComposeAsync(v->verifyHeldCargo(),ex())
            .thenComposeAsync(v->save(),ex())
            .thenComposeAsync(v->"stash_loose".equals(order.salvageMode)?stashLoose():packRemaining(),ex())
            .thenComposeAsync(v->{guard();keptParcel=null;return ship(false);},ex())
            .thenComposeAsync(v->spawned?despawn():CompletableFuture.completedFuture(null),ex())
            .thenComposeAsync(v->save(),ex());
    }
    private CompletableFuture<Void> packRemaining(){
        guard();
        boolean packable=order.cargo.stream().anyMatch(c->c.count>0&&!Items.shulker(a.inventory(c.inventorySlot)));
        if(!order.packingMode||!packable||!a.quickAvailable())return CompletableFuture.completedFuture(null);
        return state("packing",null).thenComposeAsync(v->packNextCargo(),ex()).exceptionallyComposeAsync(error->{
            guard();
            // The supply ran dry while boxing the remainder: switch to the shortage rule.
            if(!OrderFailurePolicy.boxShortage(Scanner.rootMessage(error))||uncertain||!mc.player.containerMenu.getCarried().isEmpty())return CompletableFuture.failedFuture(error);
            order.salvageMode="stash_loose";
            return a.close().thenComposeAsync(v->stashLoose(),ex());
        },ex());
    }
    private CompletableFuture<Void> stashLoose(){
        guard();var cargo=order.cargo.stream().filter(c->c.count>0).findFirst().orElse(null);
        if(cargo==null)return save().thenComposeAsync(v->order.stashedItems>0?scanner.refresh(Items.block(config.returnEntry)).exceptionally(e->null):CompletableFuture.completedFuture(null),ex());
        ItemStack item=a.inventory(cargo.inventorySlot).copy();
        if(item.getCount()!=cargo.count||!Items.id(item).equals(cargo.itemId)||!Items.hash(item).equals(cargo.componentHash))return cargoMismatch(this::stashLoose);
        return state("stashing",null).thenComposeAsync(v->depositOnce(cargo.inventorySlot,item,"stash_salvage_item"),ex()).thenComposeAsync(n->{
            guard();cargo.count-=n;cargo.status=cargo.count==0?"stashed":"held";order.stashedItems+=n;order.stashedTo=config.returnEntry;
            return save();
        },ex()).thenComposeAsync(v->stashLoose(),ex());
    }
    private void guard(){
        scope.check();
        if(scope.expired())forceStop("ORDER_TIMEOUT",true);
        else if(Instant.now().isAfter(Instant.parse(order.expiresAt)))forceStop("EXPIRED",false);
        scope.check();
        if(!config.automationEnabled||mc.player==null||mc.level==null||!mc.player.isAlive()||!Objects.equals(world.get(),boundWorld)||!Objects.equals(dimension.get(),boundDimension))throw new IllegalStateException("EXECUTOR_INTERRUPTED");
        if(store.failure!=null)throw new IllegalStateException("STORAGE_UNAVAILABLE");
    }
    private CompletableFuture<Void> save(){guard();return store.saveOrder(order);}
    private void announceFailure(String reason){
        if(quiet())return;
        if(mc.getConnection()==null||mc.player==null||!config.automationEnabled||config.manualControl||!Objects.equals(world.get(),boundWorld)||!Objects.equals(dimension.get(),boundDimension))return;
        var messages=order.request.batchId!=null&&!"OUT_OF_STOCK".equals(reason)?OrderFailurePolicy.batchFailureReport(order,reason):OrderFailurePolicy.announcements(order,reason);
        try{for(String message:messages)mc.getConnection().sendChat(message);}
        catch(Exception ignored){/* Notification failure must not restart the order. */}
    }
    private CompletableFuture<Void> shortage(String itemId){if(!order.missingItems.contains(itemId))order.missingItems.add(itemId);return CompletableFuture.failedFuture(new IllegalStateException("OUT_OF_STOCK"));}
    private void announceQueueProgress(){
        if(!order.terminal()||quiet())return;
        if(order.cleanupErrorCode!=null&&mc.getConnection()!=null)try{mc.getConnection().sendChat("本轮已完成交付；背包存箱清理失败："+ErrorMessages.message(order.cleanupErrorCode)+"，余物已保留");}catch(Exception ignored){}
        if(order.state.equals("partial_stored_offline")||"NOTHING_DELIVERED".equals(order.errorCode))announceFailure("OUT_OF_STOCK");
        if(order.request.batchId!=null){
            boolean complete=BatchFailurePolicy.completed(order);
            batchProgress(!complete?"error":order.state.equals("stored_offline")?"done":order.state.equals("partial_stored_offline")?"partial":"empty");
            if(complete&&mc.getConnection()!=null)mc.getConnection().sendChat("搞定了，我歇逼了");
            return;
        }
        String message=queueProgress.completed(order,store.queuedCount(order));
        if(message==null||mc.getConnection()==null||mc.player==null||!config.automationEnabled||config.manualControl||!Objects.equals(world.get(),boundWorld)||!Objects.equals(dimension.get(),boundDimension))return;
        try{mc.getConnection().sendChat(message);}catch(Exception ignored){/* A chat failure must not restart a completed order. */}
    }
    private void batchProgress(String state){
        if(order.request.batchId==null||order.request.notifyPlayer==null||quiet()||mc.getConnection()==null)return;
        int completed=BatchFailurePolicy.processed(order);
        String body=dev.superwy.protocol.BatchProtocol.message(true,order.request.batchId,"progress",completed,order.lines.size(),state);
        try{mc.getConnection().sendCommand(dev.superwy.protocol.WhisperProtocol.command(order.request.notifyPlayer,body));parcelProgress(true);}catch(Exception ignored){}
    }
    private void parcelProgress(boolean force){
        if(!order.packingMode||order.request.batchId==null||order.request.notifyPlayer==null||quiet()||mc.getConnection()==null)return;
        long now=System.nanoTime();if(!force&&now-lastParcelProgressAt<2_000_000_000L)return;lastParcelProgressAt=now;
        try{mc.getConnection().sendCommand(dev.superwy.protocol.WhisperProtocol.command(order.request.notifyPlayer,dev.superwy.protocol.BatchProtocol.message(true,order.request.batchId,"parcels",order.lines.stream().mapToLong(l->l.fetched).sum(),order.lines.stream().mapToLong(l->l.requested).sum(),heldParcels())));}catch(Exception ignored){}
    }
    private CompletableFuture<Void> state(String state,String reason){guard();order.state(state,reason);return save();}
    private List<Integer> emptySlots(){List<Integer> slots=new ArrayList<>();for(int i=9;i<36;i++)if(!config.protectedSlots.contains(i)&&a.inventory(i).isEmpty()&& !reserved(i))slots.add(i);return slots;}
    private boolean reserved(int slot){return order.cargo.stream().anyMatch(c->c.inventorySlot==slot&&c.count>0)||order.packages.stream().anyMatch(p->p.inventorySlot==slot&&p.custody.equals("robot"));}
    private boolean hasLoose(){return order.cargo.stream().anyMatch(c->c.count>0);}
    private boolean hasParcels(){return order.packages.stream().anyMatch(p->p.custody.equals("robot")&&!p.lines.isEmpty());}
    private boolean hasCargo(){return hasLoose()||hasParcels();}
    private int manifestSlots(){return order.request.items.stream().mapToInt(l->PackingPolicy.slots(l.count,new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(l.itemId))).getMaxStackSize())).sum();}
    private CompletableFuture<Void> begin(){
        try{
            guard();
            if(mc.player.isSpectator())throw new IllegalStateException("SPECTATOR_MODE");
            if(!a.navigation.available())throw new IllegalStateException("AUTOMATION_DEPENDENCY_MISSING");
            order.delivery.mode=order.packingMode?"shulker_parcels":"gca_inventory";order.delivery.baseFakePlayerName=order.request.delivery.fakePlayerName;
            batchProgress("fetching");
            return state("validating",null).thenComposeAsync(v->prepareInventory(),ex()).thenComposeAsync(v->verifyAdopted(),ex()).thenComposeAsync(v->checkBatchCapacity(),ex()).thenComposeAsync(v->checkStock(),ex()).thenComposeAsync(v->next(),ex());
        }catch(Exception e){return CompletableFuture.failedFuture(e);}
    }
    private CompletableFuture<Void> prepareInventory(){
        guard();
        // Only the active FIFO order closes an idle menu; queued requests never disturb a transfer.
        if(!mc.player.containerMenu.getCarried().isEmpty())return CompletableFuture.failedFuture(new IllegalStateException("CURSOR_NOT_EMPTY"));
        if(mc.player.containerMenu==mc.player.inventoryMenu&&!(mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>))return CompletableFuture.completedFuture(null);
        return a.close().thenComposeAsync(v->a.await(()->mc.player.containerMenu==mc.player.inventoryMenu&&!(mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>),2000,"CLOSE_PLAYER_MENU"),ex());
    }
    private CompletableFuture<Void> checkBatchCapacity(){
        guard();if(order.packingMode&&!a.quickAvailable())throw new IllegalStateException("QUICKSHULKER_REQUIRED_FOR_PACKING");
        order.request.batchCapacityPending=false;return checkBoxSupply();
    }
    /** Before taking anything: an empty supply fails at once; a short supply is announced and the order continues. */
    private CompletableFuture<Void> checkBoxSupply(){
        guard();int needed=estimatedParcels();
        if(!order.packingMode||needed==0)return CompletableFuture.completedFuture(null);
        BlockPos supply=boxSupply();
        return claim(supply,30).thenComposeAsync(c->a.open(supply),ex()).thenComposeAsync(v->{
            guard();int empty=a.containerSlots().stream().map(i->mc.player.containerMenu.getSlot(i).getItem())
                .filter(s->Items.shulker(s)&&Items.contents(s).stream().allMatch(ItemStack::isEmpty)).mapToInt(ItemStack::getCount).sum();
            order.boxSupplyAtStart=empty;order.boxesEstimated=needed;
            return a.close().thenRunAsync(()->{
                guard();
                if(empty==0)throw new IllegalStateException("EMPTY_SHULKER_BOX_REQUIRED");
                if(empty<needed&&order.request.batchId!=null&&!quiet()&&mc.getConnection()!=null){
                    try{mc.getConnection().sendChat(OrderFailurePolicy.boxSupplyWarning(empty,needed));}catch(Exception ignored){}
                }
            },ex());
        },ex());
    }
    /** Lower bound: requested shulkers travel loose, everything else needs ceil(slots / 27) boxes. */
    private int estimatedParcels(){
        int slots=order.request.items.stream().mapToInt(l->{
            ItemStack stack=new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(l.itemId)));
            return Items.shulker(stack)?0:PackingPolicy.slots(l.count,stack.getMaxStackSize());
        }).sum();
        return (slots+26)/27;
    }
    private CompletableFuture<Void> checkStock(){
        guard();
        String inventoryBlocker=inventoryBlocker();
        if(inventoryBlocker!=null)throw new IllegalStateException(inventoryBlocker);
        if(store.regions().stream().noneMatch(r->r.enabled&&r.world.equals(boundWorld)&&r.dimension.equals(boundDimension)))throw new IllegalStateException("NO_REGION_CONFIGURED");
        return order.request.batchId!=null?state("reserving",null):validateLine(0,new LinkedHashSet<>());
    }
    private CompletableFuture<Void> validateLine(int index,Set<String> missing){
        guard();
        if(index>=order.lines.size()){
            // Shortages no longer stop the task: missing lines are skipped while fetching and reported at the end.
            order.missingItems=new ArrayList<>(missing);
            return state("reserving",null);
        }
        var line=order.lines.get(index);
        if(!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(net.minecraft.resources.Identifier.parse(line.itemId)))throw new IllegalArgumentException("INVALID_ITEM");
        return stockSources(line).thenComposeAsync(candidates->{
            guard();if(line.componentHash==null)line.componentHash=InventoryIndex.unambiguousHash(candidates);
            if(candidates.isEmpty())missing.add(line.itemId);
            return validateLine(index+1,missing);
        },ex());
    }
    private CompletableFuture<List<InventoryIndex.Source>> stockSources(Model.Line line){
        var regions=store.regions();
        var excluded=Set.copyOf(unavailableSources);var missing=Set.copyOf(missingContainers);int lineIndex=line.lineIndex;
        var result=store.sourcesAsync(boundWorld,boundDimension,line.itemId,line.componentHash,s->!missing.contains(s.snapshot().key)&&!excluded.contains(new UnavailableSource(lineIndex,s.snapshot().key,s.nested()?s.outer().componentHash:null))
            &&regions.stream().anyMatch(r->r.enabled&&r.world.equals(s.snapshot().world)&&r.dimension.equals(s.snapshot().dimension)&&r.contains(s.snapshot().pos)));
        scope.track(result);return result;
    }
    private UnavailableSource sourceChoice(InventoryIndex.Source source,Model.Line line){return new UnavailableSource(line.lineIndex,source.snapshot().key,source.nested()?source.outer().componentHash:null);}
    private CompletableFuture<Void> unavailableSource(InventoryIndex.Source source,Model.Line line,boolean missing){
        guard();if(uncertain||borrowed||!mc.player.containerMenu.getCarried().isEmpty())return CompletableFuture.failedFuture(new IllegalStateException("RECONCILIATION_REQUIRED"));
        a.navigation.stop();
        org.slf4j.LoggerFactory.getLogger("SuperWY").info("货源已失效，继续检查其他候选：item={}, position={}, reason={}",line.itemId,source.snapshot().pos,missing?"SOURCE_CONTAINER_MISSING":"SOURCE_CHANGED");
        if(missing){missingContainers.add(source.snapshot().key);return store.deleteSnapshot(source.snapshot().key);}
        unavailableSources.add(sourceChoice(source,line));
        BlockPos parent=Items.block(source.snapshot().pos);scanner.invalidate(parent);
        var stale=source.snapshot();stale.complete=false;
        return store.saveSnapshot(stale).thenComposeAsync(v->refreshCache(parent),ex());
    }
    private CompletableFuture<Void> next(){
        guard();safeWaiting=false;if(order.terminal())return CompletableFuture.completedFuture(null);
        String inventoryBlocker=inventoryBlocker();
        if(inventoryBlocker!=null)throw new IllegalStateException(inventoryBlocker);
        Model.Line line=order.lines.stream().filter(l->l.fetched+l.shortfall<l.requested).findFirst().orElse(null);
        if(order.packingMode){
            if(line==null)return packLoose().thenComposeAsync(v->ship(),ex()).thenComposeAsync(v->finish(),ex());
            if(heldParcels()>=config.maxCarriedParcels)return ship(true).thenComposeAsync(v->next(),ex());
            if(roundBudget<0){
                // Leftovers from earlier tasks would shrink every round to a slot or two: store them first.
                if(emptySlots().size()<config.packingRoundSlots+2&&canClearStray())return clearStray().thenComposeAsync(v->next(),ex());
                // An open box with room needs no slot reserved for taking a new one.
                roundBudget=PackingPolicy.round(emptySlots().size()+(openParcelHasRoom()?1:0),config.packingRoundSlots,firstRound);batchProgress("fetching");
            }
            long looseSlots=order.cargo.stream().filter(c->c.count>0).map(c->c.inventorySlot).distinct().count();
            if(hasLoose()&&(looseSlots>=roundBudget||emptySlots().size()<2))return packLoose().thenComposeAsync(v->next(),ex());
            if(roundBudget==0){if(hasParcels())return ship(true).thenComposeAsync(v->next(),ex());throw new IllegalStateException("FREE_CARGO_SLOTS_REQUIRED");}
        }
        if(line==null)return ship().thenComposeAsync(v->finish(),ex());
        order.currentLineIndex=line.lineIndex;
        currentSource=null;
        var step=scope.call(()->{
            if(!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(net.minecraft.resources.Identifier.parse(line.itemId)))return CompletableFuture.<Void>failedFuture(new IllegalArgumentException("INVALID_ITEM"));
            return stockSources(line).thenComposeAsync(sources->{guard();return chooseSource(line,sources);},ex());
        });
        return step.exceptionallyComposeAsync(error->{
            guard();String reason=Scanner.rootMessage(error);
            if(!reason.equals("OUT_OF_STOCK")&&(order.request.batchId==null||!BatchFailurePolicy.skippable(reason)))return CompletableFuture.failedFuture(error);
            return skipLine(line,reason);
        },ex()).thenComposeAsync(v->next(),ex());
    }
    private CompletableFuture<Void> chooseSource(Model.Line line,List<InventoryIndex.Source> sources){
        if(sources.isEmpty()){
            return shortage(line.itemId);
        }
        if(line.componentHash==null)line.componentHash=InventoryIndex.unambiguousHash(sources);
        var pos=Items.pos(mc.player.blockPosition());
        // A placed shulker is preferred even when a barrel is nearer. Nested boxes come next.
        var free=sources.stream().filter(s->!hooks.busyElsewhere(meshKey(Items.block(s.snapshot().pos)))).toList();
        if(!free.isEmpty())sources=free;
        var source=sources.stream().min(InventoryIndex.sourcePriority(pos)).orElseThrow();
        if(source.nested()&&!a.quickAvailable()){
            var direct=sources.stream().filter(s->!s.nested()).min(InventoryIndex.sourcePriority(pos));
            if(direct.isPresent())source=direct.get();
            else throw new IllegalStateException("QUICKSHULKER_REQUIRED_FOR_NESTED_SOURCE");
        }
        if(source.nested()&&emptySlots().size()==1&&!hasCargo()){
            var direct=sources.stream().filter(s->!s.nested()).min(InventoryIndex.sourcePriority(pos));
            if(direct.isPresent())source=direct.get();
        }
        int needed=source.nested()?2:1;
        if(emptySlots().size()<needed){
            if(order.packingMode&&hasLoose())return packLoose();
            if(canClearStray())return clearStray();
            if(order.packingMode&&hasParcels())return ship(true);
            if(order.request.batchId!=null)throw new IllegalStateException("BATCH_CAPACITY");
            if(hasCargo())return ship();
            throw new IllegalStateException("FREE_CARGO_SLOTS_REQUIRED");
        }
        final var selected=source;
        currentSource=Items.block(selected.snapshot().pos);hooks.using(meshKey(currentSource));
        return state("fetching",null).thenComposeAsync(v->fetchWithRetry(selected,line,order.request.items.get(line.lineIndex),3),ex());
    }
    private String inventoryBlocker(){
        if(!mc.player.containerMenu.getCarried().isEmpty())return "CURSOR_NOT_EMPTY";
        return mc.player.containerMenu!=mc.player.inventoryMenu?"CLOSE_PLAYER_MENU":null;
    }
    private CompletableFuture<Void> fetchWithRetry(InventoryIndex.Source source,Model.Line line,Model.RequestLine request,int retries){
        return fetch(source,line,request).exceptionallyComposeAsync(error->{
            guard();String reason=Scanner.rootMessage(error);
            boolean transientChange=Set.of("MENU_DESYNC","CURSOR_OR_MENU_DESYNC","SOURCE_CHANGED","OPEN_CONTAINER_TIMEOUT","SOURCE_CONTAINER_MISSING").contains(reason);
            if(!transientChange||!mc.player.containerMenu.getCarried().isEmpty())return CompletableFuture.failedFuture(error);
            if(retries==0){
                if(!uncertain&&!borrowed&&pendingTake==null&&(reason.equals("SOURCE_CHANGED")||reason.equals("SOURCE_CONTAINER_MISSING")))return a.close().thenComposeAsync(v->unavailableSource(source,line,reason.equals("SOURCE_CONTAINER_MISSING")),ex());
                return CompletableFuture.failedFuture(error);
            }
            org.slf4j.LoggerFactory.getLogger("SuperWY").info("取货容器变化，等待 1 秒后核实并重新打开：item={}, position={}, reason={}, retriesLeft={}",line.itemId,source.snapshot().pos,reason,retries);
            return a.close().thenComposeAsync(v->a.delay(1000),ex())
                .thenComposeAsync(v->reconcileTake(),ex()).thenComposeAsync(v->repairBorrow(),ex()).thenComposeAsync(v->verifyHeldCargo(),ex())
                .thenComposeAsync(v->save(),ex()).thenComposeAsync(v->{
                    guard();if(line.fetched+line.shortfall>=line.requested)return refreshSkippedSource();
                    return fetchWithRetry(source,line,request,retries-1);
                },ex());
        },ex());
    }
    private record LiveSource(int slot,ItemStack stack,boolean nested){}
    /** The cache locates the block; only the freshly opened menu determines slots and box contents. */
    private LiveSource liveSource(Model.RequestLine request,Model.Line line){
        for(int slot:a.containerSlots()){
            ItemStack stack=mc.player.containerMenu.getSlot(slot).getItem();
            if(Items.matches(stack,request,line.componentHash))return new LiveSource(slot,stack.copy(),false);
        }
        for(int slot:a.containerSlots()){
            ItemStack stack=mc.player.containerMenu.getSlot(slot).getItem();
            if(Items.shulker(stack)&&Items.contents(stack).stream().anyMatch(item->Items.matches(item,request,line.componentHash)))return new LiveSource(slot,stack.copy(),true);
        }
        throw new IllegalStateException("SOURCE_CHANGED");
    }
    private CompletableFuture<Void> fetch(InventoryIndex.Source source,Model.Line line,Model.RequestLine request){
        BlockPos parent=Items.block(source.snapshot().pos);
        return a.open(parent).thenComposeAsync(v->{
            guard();var live=liveSource(request,line);int slot=live.slot();ItemStack target=live.stack();
            scanner.invalidate(parent);
            if(!live.nested())return take(slot,target,line).thenComposeAsync(x->refreshCache(parent),ex());
            if(!a.quickAvailable())throw new IllegalStateException("QUICKSHULKER_REQUIRED_FOR_NESTED_SOURCE");
            if(emptySlots().size()<2){if(hasCargo())return a.close().thenComposeAsync(x->order.packingMode&&hasLoose()?packLoose():ship(),ex());throw new IllegalStateException("FREE_CARGO_SLOTS_REQUIRED");}
            int original=slot,work=emptySlots().getLast();ItemStack box=target.copyWithCount(1);
            borrowContext=new BorrowContext(parent,original,work,box);
            borrowed=true;
            return moveLogged(slot,a.playerSlot(work),1,"borrow_source_box").thenComposeAsync(x->a.close(),ex()).thenComposeAsync(x->a.verifySlot(work,box),ex())
                .thenRunAsync(()->{guard();uncertain=false;},ex())
                .thenComposeAsync(x->a.quick(work),ex()).thenComposeAsync(x->{
                    if(!(mc.player.containerMenu instanceof net.minecraft.world.inventory.ShulkerBoxMenu)||a.containerSlots().size()!=27)throw new IllegalStateException("QUICKSHULKER_MENU_MISMATCH");
                    var contents=a.containerSlots().stream().map(i->mc.player.containerMenu.getSlot(i).getItem().copy()).toList();
                    if(!sameContents(Items.contents(box),contents))throw new IllegalStateException("SOURCE_BOX_CHANGED");
                    int inner=a.containerSlots().stream().filter(i->Items.matches(mc.player.containerMenu.getSlot(i).getItem(),request,line.componentHash)).findFirst().orElseThrow(()->new IllegalStateException("SOURCE_BOX_CHANGED"));
                    ItemStack item=mc.player.containerMenu.getSlot(inner).getItem().copy();
                    int count=withdrawal(item,line);
                    var remaining=new ArrayList<>(contents);remaining.set(a.containerSlots().indexOf(inner),item.copyWithCount(item.getCount()-count));
                    ItemStack expected=box.copy();expected.set(net.minecraft.core.component.DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(remaining));
                    return take(inner,item,line,expected).thenComposeAsync(t->returnSource(parent,original,work,expected),ex());
                },ex());
        },ex());
    }
    private static boolean sameContents(List<ItemStack> first,List<ItemStack> second){
        for(int i=0;i<Math.max(first.size(),second.size());i++){
            ItemStack a=i<first.size()?first.get(i):ItemStack.EMPTY,b=i<second.size()?second.get(i):ItemStack.EMPTY;
            if(a.getCount()!=b.getCount()||!a.isEmpty()&&!Items.same(a,b))return false;
        }return true;
    }
    private CompletableFuture<Void> take(int source,ItemStack item,Model.Line line){return take(source,item,line,null);}
    private CompletableFuture<Void> take(int source,ItemStack item,Model.Line line,ItemStack boxAfter){
        Model.Cargo held=order.request.batchId==null?null:order.cargo.stream().filter(c->c.lineIndex==line.lineIndex&&c.count>0&&c.count<item.getMaxStackSize()&&c.componentHash.equals(Items.hash(item))).findFirst().orElse(null);
        int n=withdrawal(item,line),previous=held==null?0:held.count;
        if(held==null){held=new Model.Cargo();held.inventorySlot=emptySlots().getFirst();held.lineIndex=line.lineIndex;held.itemId=Items.id(item);held.componentHash=Items.hash(item);held.count=n;order.cargo.add(held);}
        Model.Cargo cargo=held;cargo.status="taking";
        List<ItemStack> before=new ArrayList<>();for(int i=0;i<36;i++)before.add(a.inventory(i).copy());
        PendingTake transfer=new PendingTake(cargo,line,previous,n,item.copy(),before,boxAfter==null?null:boxAfter.copy());pendingTake=transfer;
        return save().thenComposeAsync(v->moveLogged(source,a.playerSlot(cargo.inventorySlot),n,"take_order_item"),ex()).thenComposeAsync(v->a.close(),ex())
            .thenComposeAsync(v->a.verifySlot(cargo.inventorySlot,item.copyWithCount(previous+n)),ex()).thenRunAsync(()->{
                guard();commitTake(transfer,true);
            },ex()).exceptionallyComposeAsync(error->{
                // A different amount arrived (another robot took part of the stack): scan and keep what is really here.
                guard();if(!"SERVER_INVENTORY_MISMATCH".equals(Scanner.rootMessage(error))||pendingTake!=transfer)return CompletableFuture.failedFuture(error);
                return a.close().thenComposeAsync(x->reconcileTake(),ex());
            },ex()).thenRunAsync(()->{guard();if(line.fetched>=line.requested)batchProgress("fetching");else parcelProgress(false);},ex()).thenComposeAsync(v->save(),ex());
    }
    private void commitTake(PendingTake transfer,boolean moved){
        transfer.cargo.count=transfer.previous+(moved?transfer.count:0);transfer.cargo.status=transfer.cargo.count>0?"held":"skipped";
        if(moved){transfer.line.fetched+=transfer.count;if(borrowContext!=null&&transfer.boxAfter!=null)borrowContext.expected=transfer.boxAfter.copy();}
        pendingTake=null;uncertain=false;
    }
    /**
     * After an interrupted transfer, the robot reads its real inventory (the same NBT query the scanner uses) and
     * continues from what is actually there instead of stopping the task for manual review.
     */
    private CompletableFuture<Void> reconcileTake(){
        if(pendingTake==null){
            if(uncertain&&!borrowed)return resyncCargo("uncertain_move");
            return CompletableFuture.completedFuture(null);
        }
        PendingTake transfer=pendingTake;
        return a.serverInventory().thenAcceptAsync(actual->{
            guard();boolean before=matchesTransfer(actual,transfer,false),after=matchesTransfer(actual,transfer,true);
            if(before||after){commitTake(transfer,after);return;}
            // Neither expected state: take the real count in the cargo slot, whatever the other robot or machine left.
            ItemStack got=actual.getOrDefault(transfer.cargo.inventorySlot,ItemStack.EMPTY);
            int now=!got.isEmpty()&&Items.id(got).equals(transfer.cargo.itemId)&&Items.hash(got).equals(transfer.cargo.componentHash)?got.getCount():0;
            int gained=Math.max(0,now-transfer.previous);
            org.slf4j.LoggerFactory.getLogger("SuperWY").warn("取货数量与预期不同，已按背包扫描结果继续：order={}, item={}, expected={}, actual={}",order.orderId,transfer.cargo.itemId,transfer.previous+transfer.count,now);
            transfer.cargo.count=now;transfer.cargo.status=now>0?"held":"skipped";transfer.line.fetched+=gained;
            pendingTake=null;uncertain=false;
            syncCargo(actual);
        },ex());
    }
    /** Only the slots this transfer could touch; hotbar tools or books whose data reads differently never matter. */
    private boolean matchesTransfer(Map<Integer,ItemStack> actual,PendingTake transfer,boolean moved){
        int expectedCount=transfer.previous+(moved?transfer.count:0);
        if(!sameStack(actual.getOrDefault(transfer.cargo.inventorySlot,ItemStack.EMPTY),expectedCount==0?ItemStack.EMPTY:transfer.item.copyWithCount(expectedCount)))return false;
        if(borrowContext!=null)return sameStack(actual.getOrDefault(borrowContext.work,ItemStack.EMPTY),moved&&transfer.boxAfter!=null?transfer.boxAfter:borrowContext.expected);
        return true;
    }
    private static boolean sameStack(ItemStack actual,ItemStack expected){
        if(actual.isEmpty()||expected.isEmpty())return actual.isEmpty()&&expected.isEmpty();
        return actual.getCount()==expected.getCount()&&Items.id(actual).equals(Items.id(expected))&&Items.hash(actual).equals(Items.hash(expected));
    }
    /**
     * Refreshing a container's cached contents after using it only keeps the stock index accurate; a busy or slow
     * query must never stop the task. Cancellation still stops it (guard rethrows).
     */
    private CompletableFuture<Void> refreshCache(BlockPos pos){
        return scanner.refresh(pos).exceptionallyComposeAsync(error->{
            guard();org.slf4j.LoggerFactory.getLogger("SuperWY").info("容器缓存刷新失败，不影响取货：position={}, reason={}",pos,Scanner.rootMessage(error));
            return CompletableFuture.completedFuture(null);
        },ex());
    }
    /** Main-inventory items that belong to no cargo or parcel of this order (leftovers from earlier tasks). */
    private boolean strayTried,strayFailed;
    private List<Integer> strays(){var out=new ArrayList<Integer>();for(int i=9;i<36;i++)if(!config.protectedSlots.contains(i)&&!a.inventory(i).isEmpty()&&!reserved(i))out.add(i);return out;}
    private boolean openParcelHasRoom(){
        return openParcel!=null&&openParcel.custody.equals("robot")&&Items.shulker(a.inventory(openParcel.inventorySlot))
            &&Items.contents(a.inventory(openParcel.inventorySlot)).stream().filter(st->!st.isEmpty()).count()<27;
    }
    private boolean canClearStray(){return !strayTried&&!strayFailed&&config.cleanupMainInventory&&!borrowed&&!uncertain&&pendingTake==null&&!strays().isEmpty();}
    /** Store leftovers in the cleanup chest so the task has room; if that fails the task carries on with less room. */
    private CompletableFuture<Void> clearStray(){
        guard();strayTried=true;
        org.slf4j.LoggerFactory.getLogger("SuperWY").info("背包空间不足，先把 {} 格非本单物品存入余物箱：order={}",strays().size(),order.orderId);
        return state("clearing_inventory",null).thenComposeAsync(v->a.close(),ex()).thenComposeAsync(v->clearStraySlot(),ex())
            .thenComposeAsync(v->scanner.refresh(Items.block(config.returnEntry)).exceptionally(e->null),ex())
            .exceptionallyComposeAsync(error->{
                guard();strayFailed=true;
                org.slf4j.LoggerFactory.getLogger("SuperWY").warn("非本单物品存箱失败，继续用剩余空间取货：order={}, reason={}",order.orderId,Scanner.rootMessage(error));
                if(!mc.player.containerMenu.getCarried().isEmpty())return CompletableFuture.failedFuture(new IllegalStateException("CURSOR_NOT_EMPTY"));
                return a.close().thenComposeAsync(v->resyncCargo("stray_clear_failed"),ex());
            },ex());
    }
    private CompletableFuture<Void> clearStraySlot(){
        guard();var slots=strays();if(slots.isEmpty())return CompletableFuture.completedFuture(null);
        int slot=slots.getFirst();ItemStack stack=a.inventory(slot).copy();
        return depositOnce(slot,stack,"clear_stray_item").thenComposeAsync(n->clearStraySlot(),ex());
    }
    /** The visible inventory disagrees with the cargo record: rescan and retry, up to three times per order. */
    private int cargoRetries;
    private CompletableFuture<Void> cargoMismatch(java.util.function.Supplier<CompletableFuture<Void>> retry){
        if(++cargoRetries>3)return CompletableFuture.failedFuture(new IllegalStateException("ORDER_CARGO_CHANGED"));
        return a.delay(250).thenComposeAsync(v->resyncCargo("cargo_mismatch"),ex()).thenComposeAsync(v->retry.get(),ex());
    }
    /** Scan the robot's inventory and make every cargo record match it; anything missing is fetched again later. */
    private CompletableFuture<Void> resyncCargo(String why){
        return a.serverInventory().thenAcceptAsync(actual->{
            guard();org.slf4j.LoggerFactory.getLogger("SuperWY").info("扫描机器人背包核对货物：order={}, reason={}",order.orderId,why);
            pendingTake=null;uncertain=false;syncCargo(actual);
        },ex());
    }
    private void syncCargo(Map<Integer,ItemStack> actual){
        for(var cargo:order.cargo){
            if(cargo.count<=0)continue;
            ItemStack got=actual.getOrDefault(cargo.inventorySlot,ItemStack.EMPTY);
            int now=!got.isEmpty()&&Items.id(got).equals(cargo.itemId)&&Items.hash(got).equals(cargo.componentHash)?got.getCount():0;
            if(now==cargo.count)continue;
            var line=order.lines.get(cargo.lineIndex);
            if(now<cargo.count)line.fetched=Math.max(line.delivered+line.packed,line.fetched-(cargo.count-now));
            else line.fetched=Math.min(line.requested,line.fetched+(now-cargo.count));
            org.slf4j.LoggerFactory.getLogger("SuperWY").warn("货物数量按扫描结果更正：order={}, item={}, slot={}, recorded={}, actual={}",order.orderId,cargo.itemId,cargo.inventorySlot,cargo.count,now);
            cargo.count=now;cargo.status=now>0?"held":"lost";
        }
    }
    private CompletableFuture<Void> repairBorrow(){
        if(!borrowed)return CompletableFuture.completedFuture(null);
        BorrowContext box=borrowContext;if(box==null)throw new IllegalStateException("RECONCILIATION_REQUIRED");
        return a.serverInventory().thenComposeAsync(inventory->{
            guard();ItemStack actual=inventory.getOrDefault(box.work,ItemStack.EMPTY);
            if(actual.isEmpty()){
                return a.open(box.parent).thenRunAsync(()->{guard();if(!returnedBoxPresent(box.expected))throw new IllegalStateException("SOURCE_BOX_RETURN_MISMATCH");},ex())
                    .thenComposeAsync(v->a.close(),ex()).thenRunAsync(()->{guard();borrowed=false;uncertain=false;borrowContext=null;},ex());
            }
            // Source contents can change before QuickShulker opens; preserve the verified box as it is.
            ItemStack identity=actual.copy();var contents=box.expected.get(net.minecraft.core.component.DataComponents.CONTAINER);
            if(contents==null)identity.remove(net.minecraft.core.component.DataComponents.CONTAINER);else identity.set(net.minecraft.core.component.DataComponents.CONTAINER,contents);
            if(!ItemStack.matches(identity,box.expected))throw new IllegalStateException("SOURCE_BOX_CHANGED");
            box.expected=actual.copy();uncertain=false;
            return returnSource(box.parent,box.original,box.work,box.expected);
        },ex());
    }
    /** Held cargo is checked against a scan of the real inventory and corrected rather than failing the task. */
    private CompletableFuture<Void> verifyHeldCargo(){
        return a.serverInventory().thenAcceptAsync(inventory->{guard();syncCargo(inventory);},ex());
    }
    private CompletableFuture<Void> skipLine(Model.Line line,String reason){
        guard();a.navigation.stop();
        if(!mc.player.containerMenu.getCarried().isEmpty())return CompletableFuture.failedFuture(new IllegalStateException("CURSOR_NOT_EMPTY"));
        return a.close().thenComposeAsync(v->reconcileTake(),ex()).thenComposeAsync(v->repairBorrow(),ex()).thenComposeAsync(v->verifyHeldCargo(),ex()).thenComposeAsync(v->refreshSkippedSource(),ex())
            .thenRunAsync(()->{guard();BatchFailurePolicy.skip(line,reason);if(reason.equals("OUT_OF_STOCK")&&!order.missingItems.contains(line.itemId))order.missingItems.add(line.itemId);},ex())
            .thenComposeAsync(v->save(),ex()).thenRunAsync(()->{
                // Shortages are summarised once at the end; other skips are still reported as they happen.
                if(order.request.batchId==null||quiet()||reason.equals("OUT_OF_STOCK"))return;
                guard();String text=line.itemId+" 取货错误："+ErrorMessages.message(reason)+"；已跳过，继续下一项";
                org.slf4j.LoggerFactory.getLogger("SuperWY").warn("批量取货跳过：order={}, item={}, reason={}, fetched={}, requested={}",order.orderId,line.itemId,reason,line.fetched,line.requested);
                try{mc.getConnection().sendChat(text);if(order.request.notifyPlayer!=null)mc.getConnection().sendCommand(dev.superwy.protocol.WhisperProtocol.command(order.request.notifyPlayer,dev.superwy.protocol.BatchProtocol.message(true,order.request.batchId,"skipped",line.lineIndex,reason)));}catch(Exception ignored){}
                batchProgress("fetching");
            },ex());
    }
    private CompletableFuture<Void> refreshSkippedSource(){
        guard();if(currentSource==null)return CompletableFuture.completedFuture(null);
        BlockPos source=currentSource;
        if(Navigation.loaded(source)&&!(mc.level.getBlockEntity(source) instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity))return store.deleteSnapshot(Model.containerKey(boundWorld,boundDimension,Items.pos(source)));
        return refreshCache(source);
    }
    private int withdrawal(ItemStack item,Model.Line line){
        int n=Math.min(Math.min(line.requested-line.fetched,item.getCount()),item.getMaxStackSize());
        if(order.request.batchId!=null){
            var held=order.cargo.stream().filter(c->c.lineIndex==line.lineIndex&&c.count>0&&c.count<item.getMaxStackSize()&&c.componentHash.equals(Items.hash(item))).findFirst().orElse(null);
            if(held!=null)n=Math.min(n,item.getMaxStackSize()-held.count);
        }
        return n;
    }
    private CompletableFuture<Void> returnSource(BlockPos parent,int original,int work,ItemStack expected){
        guard();
        // Verify the updated box against the intended withdrawal, not a possibly stale local stack.
        return a.verifySlot(work,expected).thenComposeAsync(v->a.await(()->ItemStack.matches(a.inventory(work),expected),4000,"SOURCE_BOX_SYNC_TIMEOUT"),ex())
            .thenComposeAsync(v->a.open(parent),ex()).thenComposeAsync(v->{
            guard();int destination=a.containerSlots().stream().filter(slot->mc.player.containerMenu.getSlot(slot).getItem().isEmpty()&&mc.player.containerMenu.getSlot(slot).mayPlace(expected)).min(Comparator.comparingInt(slot->slot==original?-1:slot)).orElseThrow(()->new IllegalStateException("SOURCE_BOX_RETURN_BLOCKED"));
            return moveLogged(a.playerSlot(work),destination,1,"return_source_box").thenComposeAsync(x->a.close(),ex()).thenComposeAsync(x->a.verifySlot(work,ItemStack.EMPTY),ex())
                // Machine storage may move stacks while closed; verify against the reopened layout.
                .thenComposeAsync(x->a.open(parent),ex()).thenRunAsync(()->{
                    guard();if(!returnedBoxPresent(expected))throw new IllegalStateException("SOURCE_BOX_RETURN_MISMATCH");
                },ex()).thenComposeAsync(x->a.close(),ex()).thenRunAsync(()->{guard();borrowed=false;uncertain=false;borrowContext=null;},ex()).thenComposeAsync(x->refreshCache(parent),ex());
        },ex());
    }
    private boolean returnedBoxPresent(ItemStack expected){return a.containerSlots().stream().map(slot->mc.player.containerMenu.getSlot(slot).getItem()).anyMatch(stack->stack.getCount()>=expected.getCount()&&Items.same(stack,expected));}
    private CompletableFuture<Void> moveLogged(int from,int to,int count,String operation){
        guard();String id="op_"+UUID.randomUUID();
        int menu=mc.player.containerMenu.containerId;
        ItemStack source=mc.player.containerMenu.getSlot(from).getItem().copy(),destination=mc.player.containerMenu.getSlot(to).getItem().copy();
        var evidence=Map.of("phase","planned","operation",operation,"menu",mc.player.containerMenu.containerId,"from",from,"to",to,"count",count,"source",Items.node(mc.player.containerMenu.getSlot(from).getItem(),from));
        return store.journal(order.orderId,id,evidence).thenComposeAsync(v->{guard();
            if(mc.player.containerMenu.containerId!=menu)throw new IllegalStateException("MENU_DESYNC");
            if(!ItemStack.matches(mc.player.containerMenu.getSlot(from).getItem(),source)||!ItemStack.matches(mc.player.containerMenu.getSlot(to).getItem(),destination))throw new IllegalStateException("SOURCE_CHANGED");
            uncertain=true;return a.move(from,to,count);
        },ex());
    }
    private Player findRecipient(){return mc.level==null?null:mc.level.players().stream().filter(p->p.getName().getString().equalsIgnoreCase(order.delivery.fakePlayerName)).findFirst().orElse(null);}
    private boolean sameRecipient(){Player p=findRecipient();return p!=null&&recipient!=null&&p.getUUID().equals(recipient.getUUID());}
    private CompletableFuture<Void> clearExistingRecipient(boolean force){
        guard();
        if(!force&&mc.getConnection().getPlayerInfo(order.delivery.fakePlayerName)==null)return CompletableFuture.completedFuture(null);
        order.delivery.stage=force?"clearing_after_spawn_failure":"clearing_existing";
        return save().thenRunAsync(()->{guard();a.command("player "+order.delivery.fakePlayerName+" kill");},ex())
            // If our player list was already empty, allow Carpet's scheduled removal to run first.
            .thenComposeAsync(v->force?a.delay(250):CompletableFuture.completedFuture(null),ex())
            .thenComposeAsync(v->a.await(()->mc.getConnection()!=null&&mc.getConnection().getPlayerInfo(order.delivery.fakePlayerName)==null&&findRecipient()==null,6000,"FAKE_PLAYER_DESPAWN_TIMEOUT"),ex());
    }
    private CompletableFuture<Void> summonRecipient(){
        guard();var p=mc.player.position();
        order.delivery.spawnMode="in_place";order.delivery.spawnPosition=List.of(p.x,p.y,p.z);order.delivery.spawnDimension=boundDimension;order.delivery.stage="summoning";
        return save().thenRunAsync(()->{guard();a.command("player "+order.delivery.fakePlayerName+" spawn at "+p.x+" "+p.y+" "+p.z);},ex())
            .thenComposeAsync(v->a.await(()->findRecipient()!=null,8000,"FAKE_PLAYER_UNAVAILABLE"),ex());
    }
    private CompletableFuture<Void> spawn(){
        guard();
        a.navigation.stop();
        return state("spawning",null).thenComposeAsync(v->clearExistingRecipient(false),ex()).thenComposeAsync(v->summonRecipient(),ex())
                .exceptionallyComposeAsync(error->{
                    guard();
                    if(!"FAKE_PLAYER_UNAVAILABLE".equals(Scanner.rootMessage(error)))return CompletableFuture.failedFuture(error);
                    // Exactly one forced cleanup and retry; the second attempt is outside this handler.
                    return clearExistingRecipient(true).thenComposeAsync(v->summonRecipient(),ex());
                },ex()).thenRunAsync(()->{
                    guard();recipient=findRecipient();if(recipient==mc.player||recipient.isSpectator())throw new IllegalStateException("INVALID_RECIPIENT");
                    spawned=true;if(!order.delivery.recipientNames.contains(order.delivery.fakePlayerName))order.delivery.recipientNames.add(order.delivery.fakePlayerName);order.delivery.fakePlayerUuid=recipient.getUUID().toString();order.delivery.stage="spawned";
                },ex()).thenComposeAsync(x->save(),ex());
    }
    private CompletableFuture<Map<Integer,ItemStack>> recipientInventory(){
        guard();if(!sameRecipient())return CompletableFuture.failedFuture(new IllegalStateException("RECIPIENT_DISAPPEARED"));
        return queries.entity(recipient.getId()).thenApply(Items::playerInventory);
    }
    private CompletableFuture<Void> despawn(){
        guard();
        if(!sameRecipient())return CompletableFuture.failedFuture(new IllegalStateException("RECIPIENT_IDENTITY_LOST"));
        order.delivery.stage="despawning";
        return state("despawning",null).thenRunAsync(()->{guard();a.command("player "+order.delivery.fakePlayerName+" kill");},ex())
            .thenComposeAsync(v->a.await(()->mc.getConnection()!=null&&mc.getConnection().getPlayerInfo(order.delivery.fakePlayerName)==null,6000,"FAKE_PLAYER_DESPAWN_TIMEOUT"),ex())
            .thenRunAsync(()->{guard();spawned=false;recipient=null;order.delivery.offlineObservedAt=Instant.now().toString();},ex());
    }
    private CompletableFuture<Void> ship(){return ship(false);}
    /**
     * keepOpen (projection orders only): a mid-order delivery leaves the partly filled current box with the robot,
     * so every delivered box is full except the last one and the result matches the requester's preview.
     * Falls back to delivering everything when that box is the only cargo.
     */
    private CompletableFuture<Void> ship(boolean keepOpen){
        guard();
        keptParcel=keepOpen&&openParcel!=null&&openParcel.custody.equals("robot")&&!openParcel.lines.isEmpty()
            &&Items.contents(a.inventory(openParcel.inventorySlot)).stream().filter(s->!s.isEmpty()).count()<27
            &&(hasLoose()||order.packages.stream().anyMatch(p->p!=openParcel&&p.custody.equals("robot")&&!p.lines.isEmpty()))?openParcel:null;
        if(!hasCargo()){keptParcel=null;return CompletableFuture.completedFuture(null);}
        if(order.request.batchId!=null)batchProgress("delivering");
        CompletableFuture<Void> ready=spawned?CompletableFuture.completedFuture(null):verifyHeldCargo().thenComposeAsync(v->hasCargo()?lockRecipient().thenComposeAsync(x->spawn(),ex()):CompletableFuture.completedFuture(null),ex());
        return ready.thenComposeAsync(v->hasCargo()?deliverNext():CompletableFuture.completedFuture(null),ex()).thenRunAsync(()->{roundBudget=-1;if(keptParcel==null)openParcel=null;keptParcel=null;if(!spawned)unlockRecipient();},ex());
    }
    /** GCA uses a six-row chest: 0..17 controls/equipment; 18..44 main; 45..53 hotbar. */
    private void validateRecipientMenu(Map<Integer,ItemStack> inv){
        guard();
        if(!sameRecipient()||!(mc.player.containerMenu instanceof ChestMenu menu)||menu.getRowCount()!=6||a.containerSlots().size()!=54)throw new IllegalStateException("GCA_INVENTORY_LAYOUT_UNSUPPORTED");
        for(int i=0;i<36;i++){
            ItemStack expected=inv.getOrDefault(i,ItemStack.EMPTY),got=menu.getSlot(GcaInventory.menuSlot(i)).getItem();
            if(got.getCount()!=expected.getCount()||!got.isEmpty()&&!Items.same(got,expected))throw new IllegalStateException("RECIPIENT_MENU_MISMATCH");
        }
    }
    private CompletableFuture<Void> deliverNext(){
        guard();
        Model.Parcel parcel=order.packages.stream().filter(p->p!=keptParcel&&p.custody.equals("robot")&&!p.lines.isEmpty()).findFirst().orElse(null);
        if(parcel!=null)return deliverParcel(parcel);
        Model.Cargo cargo=order.cargo.stream().filter(c->c.count>0).findFirst().orElse(null);
        if(cargo==null)return despawn();
        ItemStack item=a.inventory(cargo.inventorySlot).copy();
        if(item.getCount()!=cargo.count||!Items.id(item).equals(cargo.itemId)||!Items.hash(item).equals(cargo.componentHash))return cargoMismatch(this::deliverNext);
        return recipientInventory().thenComposeAsync(before->a.openRecipient(recipient).thenComposeAsync(v->{
            validateRecipientMenu(before);
            int destination=-1,n=0;
            for(int i:GcaInventory.storageSlots()){
                var slot=mc.player.containerMenu.getSlot(i);ItemStack current=slot.getItem();
                if(!slot.mayPlace(item)||!current.isEmpty()&&!Items.same(current,item))continue;
                int room=Math.min(item.getMaxStackSize(),slot.getMaxStackSize(item))-current.getCount();
                if(room>0){destination=i;n=Math.min(room,cargo.count);break;}
            }
            if(destination<0)return a.close().thenComposeAsync(x->nextRecipient(),ex()).thenComposeAsync(x->deliverNext(),ex());
            int to=destination,count=n;String key=Items.id(item)+"|"+Items.hash(item);int previous=Items.quantities(before.values()).getOrDefault(key,0);
            order.delivery.stage="transferring";cargo.status="transferring";
            return state("transferring",null).thenComposeAsync(x->{
                if(!sameRecipient())throw new IllegalStateException("RECIPIENT_IDENTITY_LOST");
                deliveryUncertain=true;return moveLogged(a.playerSlot(cargo.inventorySlot),to,count,"deposit_gca_inventory");
            },ex()).thenComposeAsync(x->a.close(),ex()).thenComposeAsync(x->state("verifying",null),ex())
                .thenComposeAsync(x->a.verifySlot(cargo.inventorySlot,item.copyWithCount(item.getCount()-count)),ex()).thenComposeAsync(x->recipientInventory(),ex()).thenAcceptAsync(after->{
                    guard();
                    if(Items.quantities(after.values()).getOrDefault(key,0)!=previous+count)throw new IllegalStateException("DELIVERY_UNCERTAIN");
                    cargo.count-=count;cargo.status=cargo.count==0?"delivered":"held";order.lines.get(cargo.lineIndex).delivered+=count;
                    deliveryUncertain=false;uncertain=false;order.delivery.stage="received";order.delivery.receiptSource="gca_menu_and_entity_nbt";order.delivery.receivedAt=Instant.now().toString();
                },ex()).thenComposeAsync(x->save(),ex()).thenComposeAsync(x->deliverNext(),ex());
        },ex()),ex());
    }
    private int heldParcels(){return (int)order.packages.stream().filter(p->p.custody.equals("robot")).count();}
    private BlockPos boxSupply(){
        var station=config.stations.stream().filter(st->Objects.equals(st.id,order.request.delivery.stationId)&&Objects.equals(st.world,boundWorld)&&Objects.equals(st.dimension,boundDimension)&&st.boxSupply!=null).findFirst().orElse(null);
        return Items.block(station==null?config.boxSupply:station.boxSupply);
    }
    private CompletableFuture<Void> acquireParcel(){
        guard();BlockPos supply=boxSupply();
        if(emptySlots().isEmpty())throw new IllegalStateException("FREE_CARGO_SLOTS_REQUIRED");
        int inventorySlot=emptySlots().getFirst();
        return claim(supply,30).thenComposeAsync(c->a.open(supply),ex()).thenComposeAsync(v->{
            guard();int source=a.containerSlots().stream().filter(i->{var item=mc.player.containerMenu.getSlot(i).getItem();return Items.shulker(item)&&Items.contents(item).stream().allMatch(ItemStack::isEmpty);}).findFirst().orElseThrow(()->new IllegalStateException("EMPTY_SHULKER_BOX_REQUIRED"));
            ItemStack box=mc.player.containerMenu.getSlot(source).getItem().copyWithCount(1);
            var parcel=new Model.Parcel();parcel.inventorySlot=inventorySlot;parcel.batchId=order.orderId;parcel.manifestHash=Items.hash(box);openParcel=parcel;order.packages.add(parcel);
            return save().thenComposeAsync(x->moveLogged(source,a.playerSlot(inventorySlot),1,"take_one_empty_parcel"),ex()).thenComposeAsync(x->a.close(),ex())
                .thenComposeAsync(x->a.verifySlot(inventorySlot,box),ex()).thenComposeAsync(x->a.await(()->ItemStack.matches(a.inventory(inventorySlot),box),4000,"PARCEL_SYNC_TIMEOUT"),ex())
                .thenRunAsync(()->{guard();uncertain=false;},ex()).thenComposeAsync(x->refreshCache(supply),ex());
        },ex());
    }
    private CompletableFuture<Void> packLoose(){
        guard();roundBudget=-1;firstRound=false;strayTried=false;
        if(!hasLoose())return CompletableFuture.completedFuture(null);
        // Minecraft forbids a shulker inside another shulker; requested shulkers travel as loose cargo.
        if(order.cargo.stream().filter(c->c.count>0).anyMatch(c->Items.shulker(a.inventory(c.inventorySlot)))){
            if(!order.request.projectionParcels)return ship();
            // Projection orders: pack everything else into parcels, then hand the requested shulkers over loose.
            boolean moreParcelItems=order.lines.stream().anyMatch(line->line.fetched+line.shortfall<line.requested&&!Items.shulker(new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(line.itemId)))));
            // Once all packable materials are fetched, deliver the final partial parcel before loose shulkers.
            // Retain it only when later materials can still fill it.
            if(order.cargo.stream().filter(c->c.count>0).allMatch(c->Items.shulker(a.inventory(c.inventorySlot))))return ship(moreParcelItems);
            batchProgress("packing");
            return state("packing",null).thenComposeAsync(v->packNextCargo(),ex()).thenComposeAsync(v->ship(moreParcelItems),ex());
        }
        batchProgress("packing");
        return state("packing",null).thenComposeAsync(v->packNextCargo(),ex());
    }
    private record PackMove(Model.Cargo cargo,int menuSlot,int count,ItemStack item){}
    /**
     * Opens the current box once, puts every loose stack that fits into it one after another, closes it and checks
     * box and inventory with a single scan. Same-item stacks in the box are topped up before new slots are used.
     */
    private CompletableFuture<Void> packNextCargo(){
        // Shulkers cannot go inside shulkers; they always stay loose (packLoose ships them first for non-projection orders).
        guard();var loose=order.cargo.stream().filter(c->c.count>0&&!Items.shulker(a.inventory(c.inventorySlot))).toList();
        if(loose.isEmpty()){parcelProgress(true);return save();}
        for(var cargo:loose){ItemStack held=a.inventory(cargo.inventorySlot);if(held.getCount()!=cargo.count||!Items.id(held).equals(cargo.itemId)||!Items.hash(held).equals(cargo.componentHash))return cargoMismatch(this::packNextCargo);}
        ItemStack first=a.inventory(loose.getFirst().inventorySlot).copy();
        if(openParcel!=null&&(!openParcel.custody.equals("robot")||Items.boxCapacity(a.inventory(openParcel.inventorySlot),first)==0))openParcel=null;
        if(openParcel==null){
            if(heldParcels()>=config.maxCarriedParcels)return ship().thenComposeAsync(v->packNextCargo(),ex());
            return acquireParcel().thenComposeAsync(v->packNextCargo(),ex());
        }
        Model.Parcel parcel=openParcel;ItemStack beforeBox=a.inventory(parcel.inventorySlot).copy();
        if(beforeBox.getCount()!=1||!Items.hash(beforeBox).equals(parcel.manifestHash))throw new IllegalStateException("PARCEL_CONTENT_CHANGED");
        return a.quick(parcel.inventorySlot).thenComposeAsync(v->{
            guard();if(!(mc.player.containerMenu instanceof net.minecraft.world.inventory.ShulkerBoxMenu)||a.containerSlots().size()!=27)throw new IllegalStateException("QUICKSHULKER_MENU_MISMATCH");
            var slots=a.containerSlots();var contents=new ArrayList<ItemStack>();for(int i:slots)contents.add(mc.player.containerMenu.getSlot(i).getItem().copy());
            if(!sameContents(Items.contents(beforeBox),contents))throw new IllegalStateException("PARCEL_CONTENT_CHANGED");
            // Plan every move against a simulated copy of the box.
            var planned=new ArrayList<PackMove>();
            for(var cargo:loose){
                ItemStack item=a.inventory(cargo.inventorySlot).copy();int left=cargo.count;
                while(left>0){
                    int dest=-1,room=0;
                    for(int k=0;k<slots.size();k++){
                        var slot=mc.player.containerMenu.getSlot(slots.get(k));var stack=contents.get(k);
                        if(!slot.mayPlace(item)||!stack.isEmpty()&&!Items.same(stack,item))continue;
                        int r=Math.min(item.getMaxStackSize(),slot.getMaxStackSize(item))-stack.getCount();
                        if(r>0){dest=k;room=r;break;}
                    }
                    if(dest<0)break;
                    int n=Math.min(room,left);planned.add(new PackMove(cargo,slots.get(dest),n,item));
                    contents.set(dest,item.copyWithCount(contents.get(dest).getCount()+n));left-=n;
                }
                if(left>0)break;
            }
            if(planned.isEmpty())throw new IllegalStateException("PARCEL_FULL");
            ItemStack expected=beforeBox.copy();expected.set(net.minecraft.core.component.DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.fromItems(contents));
            CompletableFuture<Void> moves=CompletableFuture.completedFuture(null);
            for(var move:planned)moves=moves.thenComposeAsync(x->moveLogged(a.playerSlot(move.cargo().inventorySlot),move.menuSlot(),move.count(),"pack_parcel_item"),ex());
            return moves.thenComposeAsync(x->a.close(),ex()).thenComposeAsync(x->a.serverInventory(),ex())
                .thenAcceptAsync(actual->{guard();settlePacking(parcel,beforeBox,expected,planned,actual);},ex())
                .thenComposeAsync(x->awaitParcelSync(parcel),ex())
                .thenComposeAsync(x->save(),ex()).thenComposeAsync(x->packNextCargo(),ex());
        },ex());
    }
    /** Book the packed amounts. If the scan differs from the plan, book what the box and inventory really hold. */
    private void settlePacking(Model.Parcel parcel,ItemStack beforeBox,ItemStack expected,List<PackMove> planned,Map<Integer,ItemStack> actual){
        ItemStack box=actual.getOrDefault(parcel.inventorySlot,ItemStack.EMPTY);
        if(box.getCount()!=1||!Items.shulker(box))throw new IllegalStateException("PARCEL_CONTENT_CHANGED");
        Map<Model.Cargo,Integer> moved=new LinkedHashMap<>();for(var m:planned)moved.merge(m.cargo(),m.count(),Integer::sum);
        boolean exact=Items.same(box,expected);
        Map<String,Integer> gained=new HashMap<>();
        if(!exact){
            var after=Items.quantities(Items.contents(box));var before=Items.quantities(Items.contents(beforeBox));
            for(var e:after.entrySet())gained.put(e.getKey(),e.getValue()-before.getOrDefault(e.getKey(),0));
        }
        for(var e:moved.entrySet()){
            var cargo=e.getKey();int planCount=e.getValue();var line=order.lines.get(cargo.lineIndex);
            ItemStack rest=actual.getOrDefault(cargo.inventorySlot,ItemStack.EMPTY);
            int remainder=!rest.isEmpty()&&Items.id(rest).equals(cargo.itemId)&&Items.hash(rest).equals(cargo.componentHash)?rest.getCount():0;
            int packed=planCount;
            if(!exact){
                String key=cargo.itemId+"|"+cargo.componentHash;int left=Math.max(0,gained.getOrDefault(key,0));
                packed=Math.min(Math.max(0,cargo.count-remainder),left);gained.put(key,left-packed);
                int lost=Math.max(0,cargo.count-remainder-packed);
                if(lost>0)line.fetched=Math.max(line.delivered+line.packed+packed,line.fetched-lost);
            }else remainder=cargo.count-planCount;
            cargo.count=remainder;cargo.status=remainder==0?"packed":"held";line.packed+=packed;
            if(packed>0){
                var manifest=parcel.lines.stream().filter(l->l.lineIndex==cargo.lineIndex&&l.componentHash.equals(cargo.componentHash)).findFirst().orElse(null);
                if(manifest==null)parcel.lines.add(new Model.PackageLine(cargo.lineIndex,cargo.itemId,cargo.componentHash,packed));else manifest.count+=packed;
            }
        }
        if(!exact)org.slf4j.LoggerFactory.getLogger("SuperWY").warn("装盒结果与计划不同，已按扫描结果记账：order={}, box={}",order.orderId,parcel.inventorySlot);
        // Same basis as before: the hash of the stack as the client sees it. Unknown until the client syncs when the plan was off.
        parcel.manifestHash=exact?Items.hash(expected):null;parcel.status="packed";uncertain=false;
    }
    private CompletableFuture<Void> awaitParcelSync(Model.Parcel parcel){
        if(parcel.manifestHash!=null)return a.await(()->Items.hash(a.inventory(parcel.inventorySlot)).equals(parcel.manifestHash),4000,"PARCEL_SYNC_TIMEOUT");
        return a.delay(750).thenRunAsync(()->{guard();parcel.manifestHash=Items.hash(a.inventory(parcel.inventorySlot));},ex());
    }
    private CompletableFuture<Void> nextRecipient(){
        guard();if(!order.packingMode&&order.request.batchId==null)throw new IllegalStateException("RECIPIENT_FULL");
        return despawn().thenComposeAsync(v->{
            guard();order.delivery.recipientSequence++;
            order.delivery.fakePlayerName=PackingPolicy.recipient(order.delivery.baseFakePlayerName,order.delivery.recipientSequence);
            return save().thenComposeAsync(x->spawn(),ex());
        },ex());
    }
    private CompletableFuture<Void> deliverParcel(Model.Parcel parcel){
        guard();ItemStack box=a.inventory(parcel.inventorySlot).copy();
        if(box.getCount()!=1||!Items.shulker(box)||!Items.hash(box).equals(parcel.manifestHash))throw new IllegalStateException("PARCEL_CONTENT_CHANGED");
        return recipientInventory().thenComposeAsync(before->a.openRecipient(recipient).thenComposeAsync(v->{
            validateRecipientMenu(before);int destination=GcaInventory.storageSlots().stream().filter(i->mc.player.containerMenu.getSlot(i).getItem().isEmpty()&&mc.player.containerMenu.getSlot(i).mayPlace(box)).findFirst().orElse(-1);
            if(destination<0)return a.close().thenComposeAsync(x->nextRecipient(),ex()).thenComposeAsync(x->deliverParcel(parcel),ex());
            String key=Items.id(box)+"|"+Items.hash(box);int previous=Items.quantities(before.values()).getOrDefault(key,0);
            parcel.status="transferring";deliveryUncertain=true;
            return state("transferring",null).thenComposeAsync(x->moveLogged(a.playerSlot(parcel.inventorySlot),destination,1,"deliver_parcel"),ex()).thenComposeAsync(x->a.close(),ex())
                .thenComposeAsync(x->a.verifySlot(parcel.inventorySlot,ItemStack.EMPTY),ex()).thenComposeAsync(x->recipientInventory(),ex()).thenAcceptAsync(after->{
                    guard();if(Items.quantities(after.values()).getOrDefault(key,0)!=previous+1)throw new IllegalStateException("DELIVERY_UNCERTAIN");
                    for(var line:parcel.lines)order.lines.get(line.lineIndex).delivered+=line.count;
                    parcel.status="stored_offline";parcel.custody="recipient";parcel.deliveredTo=order.delivery.fakePlayerName;
                    order.delivery.verifiedPackages++;
                    deliveryUncertain=false;uncertain=false;order.delivery.receiptSource="gca_parcel_and_entity_nbt";order.delivery.receivedAt=Instant.now().toString();
                },ex()).thenComposeAsync(x->save(),ex()).thenComposeAsync(x->deliverNext(),ex());
        },ex()),ex());
    }
    private CompletableFuture<Void> cleanupInventory(){
        guard();if(!config.cleanupMainInventory)return CompletableFuture.completedFuture(null);
        if(hasLoose()||hasParcels()||borrowed||uncertain||deliveryUncertain)throw new IllegalStateException("RECONCILIATION_REQUIRED");
        return a.serverInventory().thenComposeAsync(before->{
            guard();if(java.util.stream.IntStream.range(9,36).allMatch(i->before.getOrDefault(i,ItemStack.EMPTY).isEmpty())){order.cleanupCompleted=true;return CompletableFuture.completedFuture(null);}
            batchProgress("cleanup");
            return state("clearing_inventory",null).thenComposeAsync(v->cleanupSlot(9),ex()).thenComposeAsync(v->a.serverInventory(),ex()).thenAcceptAsync(after->{
                    guard();for(int i=9;i<36;i++)if(!after.getOrDefault(i,ItemStack.EMPTY).isEmpty())throw new IllegalStateException("CLEANUP_INCOMPLETE");
                    var protectedKeys=new HashSet<>(before.keySet());protectedKeys.addAll(after.keySet());for(int i:protectedKeys)if((i<9||i>35)&&!ItemStack.matches(before.getOrDefault(i,ItemStack.EMPTY),after.getOrDefault(i,ItemStack.EMPTY)))throw new IllegalStateException("CLEANUP_PROTECTED_CHANGED");
                    order.cleanupCompleted=true;
                },ex()).thenComposeAsync(v->refreshCache(Items.block(config.returnEntry)),ex());
        },ex());
    }
    private CompletableFuture<Void> cleanupSlot(int slot){
        guard();if(slot>=36)return CompletableFuture.completedFuture(null);
        ItemStack stack=a.inventory(slot).copy();if(stack.isEmpty())return cleanupSlot(slot+1);
        return depositOnce(slot,stack,"cleanup_store_main_inventory").thenComposeAsync(n->cleanupSlot(stack.getCount()==n?slot+1:slot),ex());
    }
    /** One verified move from an inventory slot into the safe (cleanup) chest; returns how many items moved. */
    private CompletableFuture<Integer> depositOnce(int slot,ItemStack stack,String operation){
        guard();BlockPos chest=Items.block(config.returnEntry);
        return claim(chest,30).thenComposeAsync(c->a.open(chest),ex()).thenComposeAsync(v->{
            guard();if(!(mc.player.containerMenu instanceof ChestMenu))throw new IllegalStateException("CLEANUP_STORAGE_REQUIRED");
            int destination=-1,count=0;
            for(int i:a.containerSlots()){
                var target=mc.player.containerMenu.getSlot(i);var existing=target.getItem();
                if(!target.mayPlace(stack)||!existing.isEmpty()&&!Items.same(existing,stack))continue;
                int room=Math.min(stack.getMaxStackSize(),target.getMaxStackSize(stack))-existing.getCount();
                if(room>0){destination=i;count=Math.min(room,stack.getCount());break;}
            }
            if(destination<0)throw new IllegalStateException("CLEANUP_STORAGE_FULL");
            int target=destination,n=count;
            ItemStack expected=stack.copyWithCount(mc.player.containerMenu.getSlot(target).getItem().getCount()+n);
            return moveLogged(a.playerSlot(slot),target,n,operation).thenComposeAsync(x->a.close(),ex())
                .thenComposeAsync(x->a.verifySlot(slot,stack.copyWithCount(stack.getCount()-n)),ex()).thenComposeAsync(x->a.open(chest),ex())
                .thenRunAsync(()->{guard();if(!(mc.player.containerMenu instanceof ChestMenu)||!a.containerSlots().contains(target)||!ItemStack.matches(mc.player.containerMenu.getSlot(target).getItem(),expected))throw new IllegalStateException("CLEANUP_STORAGE_MISMATCH");},ex())
                .thenComposeAsync(x->a.close(),ex()).thenRunAsync(()->{guard();uncertain=false;},ex())
                .thenComposeAsync(x->a.await(()->ItemStack.matches(a.inventory(slot),stack.copyWithCount(stack.getCount()-n)),4000,"CLEANUP_INCOMPLETE"),ex())
                .thenApply(x->n);
        },ex());
    }
    private CompletableFuture<Void> finish(){
        guard();if(order.terminal())return CompletableFuture.completedFuture(null);
        if(order.lines.stream().noneMatch(l->l.delivered>0))return finishRecord();
        order.cleanupRoundCounted=true;
        return save().thenComposeAsync(v->{
            if(!config.cleanupMainInventory||store.cleanupPending(order)<CleanupSchedule.INTERVAL)return finishRecord();
            return cleanupInventory().exceptionallyComposeAsync(error->{
                guard();a.navigation.stop();order.cleanupCompleted=false;order.cleanupErrorCode=Scanner.rootMessage(error);
                order.needsItemReview=uncertain||borrowed||deliveryUncertain||!mc.player.containerMenu.getCarried().isEmpty();
                org.slf4j.LoggerFactory.getLogger("SuperWY").warn("交付已完成，定期存箱清理失败：{}",order.cleanupErrorCode);
                return !order.needsItemReview?a.close():CompletableFuture.completedFuture(null);
            },ex()).thenComposeAsync(x->finishRecord(),ex());
        },ex());
    }
    private CompletableFuture<Void> finishRecord(){
        guard();
        if(order.terminal())return CompletableFuture.completedFuture(null);
        if(order.lines.stream().noneMatch(l->l.delivered>0)){order.errorCode="NOTHING_DELIVERED";order.executionStoppedAt=Instant.now().toString();return state("failed","NOTHING_DELIVERED");}
        order.delivery.stage="stored_offline";order.currentLineIndex=null;
        order.executionStoppedAt=Instant.now().toString();
        return state(order.lines.stream().anyMatch(l->l.delivered<l.requested)?"partial_stored_offline":"stored_offline",null);
    }
}

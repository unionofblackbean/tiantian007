package dev.superwy.mesh;

import dev.superwy.core.*;
import dev.superwy.game.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.function.Consumer;

/**
 * Runs on the authority robot (game thread only). Player orders accepted here are "parent" orders: the local
 * engine never runs them; this class splits them into blocks for all robots and mirrors progress back into them,
 * so the player's HTTP status polling sees queue position, robot count and per-line progress.
 */
public final class MeshAuthority {
    private final Config config;private final Store store;private final MeshScheduler scheduler;private final Consumer<String> chat;
    private final Map<String,String> written=new HashMap<>();
    private final Set<String> cancelled=new HashSet<>();
    public MeshAuthority(Config config,Store store,Consumer<String> chat){
        this.config=config;this.store=store;this.chat=chat;this.scheduler=new MeshScheduler(config.mesh);
        String saved=store.mesh("authority");
        try{scheduler.load(saved==null?null:Jsons.GSON.fromJson(saved,MeshScheduler.State.class));}
        catch(RuntimeException e){LoggerFactory.getLogger("SuperWY").warn("调度状态读取失败，已从空状态开始",e);scheduler.load(null);}
    }
    public MeshScheduler scheduler(){return scheduler;}
    public MeshScheduler.Reply heartbeat(MeshScheduler.Report report){return scheduler.heartbeat(report);}
    /** The player cancelled a parent order; stop its blocks and never write over the cancelled record. */
    public void cancelled(String orderId){if(scheduler.has(orderId)){cancelled.add(orderId);scheduler.cancel(orderId);}}
    public boolean busy(){return scheduler.jobs().stream().anyMatch(MeshScheduler.Job::open);}
    public void tick(){
        ingest();
        scheduler.tick();
        for(var job:List.copyOf(scheduler.jobs()))mirror(job);
        if(scheduler.takeDirty())store.saveMesh("authority",Jsons.GSON.toJson(scheduler.state()));
    }
    private void ingest(){
        for(var order:store.pendingOrders()){
            if(order.request==null||!order.request.meshParent||!order.state.equals("queued")||scheduler.has(order.orderId))continue;
            var job=new MeshScheduler.Job();job.orderId=order.orderId;job.recipient=order.request.delivery.fakePlayerName;
            job.stationId=order.request.delivery.stationId==null?"main":order.request.delivery.stationId;
            job.batch=order.request.batchId!=null;job.parcels=order.request.projectionParcels;
            int slots=0;boolean valid=true;
            for(var line:order.lines){
                var part=new MeshScheduler.JobLine();part.lineIndex=line.lineIndex;part.itemId=line.itemId;part.componentHash=line.componentHash;part.requested=line.requested;
                Identifier id=Identifier.tryParse(line.itemId);
                if(id==null||!BuiltInRegistries.ITEM.containsKey(id)){valid=false;break;}
                ItemStack stack=new ItemStack(BuiltInRegistries.ITEM.getValue(id));part.maxStack=Math.max(1,stack.getMaxStackSize());part.shulker=Items.shulker(stack);
                slots+=PackingPolicy.slots(line.requested,part.maxStack);job.lines.add(part);
            }
            if(!valid){
                order.errorCode="INVALID_ITEM";order.terminationReason="EXECUTION_ERROR";order.executionStoppedAt=java.time.Instant.now().toString();order.state("failed","INVALID_ITEM");store.saveOrder(order);continue;
            }
            job.packing=job.parcels||slots>config.packingRoundSlots;
            scheduler.submit(job);
        }
    }
    /** Copy the job's aggregate into its parent order; finish the parent when the job is done. */
    private void mirror(MeshScheduler.Job job){
        if(cancelled.contains(job.orderId)){
            scheduler.cancel(job.orderId);scheduler.prune(job.orderId);
            if(!scheduler.has(job.orderId)){cancelled.remove(job.orderId);written.remove(job.orderId);}
            return;
        }
        Model.Order parent=store.order(job.orderId);
        if(parent==null){scheduler.cancel(job.orderId);scheduler.prune(job.orderId);return;}
        if(parent.terminal()){
            if(job.open())scheduler.cancel(job.orderId);
            scheduler.prune(job.orderId);written.remove(job.orderId);return;
        }
        for(var line:job.lines){
            if(line.lineIndex<0||line.lineIndex>=parent.lines.size())continue;
            var target=parent.lines.get(line.lineIndex);
            target.fetched=Math.min(line.requested,line.fetched);target.delivered=line.delivered;target.shortfall=line.shortfall;target.skipped=line.skipped;target.errorCode=line.errorCode;
        }
        parent.meshAhead=scheduler.ahead(job);parent.meshRobots=scheduler.robotsOn(job);
        boolean review=scheduler.chunksOf(job).stream().anyMatch(c->c.needsReview);parent.needsItemReview=review;
        if(job.state.equals("done")){
            finish(parent,job);
        }else{
            String next=job.state.equals("queued")?"queued":"fetching";
            if(!parent.state.equals(next)){parent.state(next,null);if(parent.executionStartedAt==null&&next.equals("fetching"))parent.executionStartedAt=java.time.Instant.now().toString();}
        }
        // Only a short hash is kept per job to detect changes, not a copy of the lines.
        String signature=Jsons.hash(Jsons.GSON.toJson(parent.lines)+parent.state+parent.meshAhead+"|"+parent.meshRobots+"|"+review);
        if(!signature.equals(written.get(job.orderId))){written.put(job.orderId,signature);store.saveOrder(parent);}
        if(parent.terminal()){scheduler.prune(job.orderId);written.remove(job.orderId);}
    }
    private void finish(Model.Order parent,MeshScheduler.Job job){
        parent.executionStoppedAt=java.time.Instant.now().toString();parent.currentLineIndex=null;parent.meshAhead=0;parent.meshRobots=0;
        parent.missingItems=new ArrayList<>();
        for(var line:parent.lines)if(line.delivered<line.requested&&"OUT_OF_STOCK".equals(line.errorCode)&&!parent.missingItems.contains(line.itemId))parent.missingItems.add(line.itemId);
        long delivered=parent.lines.stream().mapToLong(l->l.delivered).sum();
        if(delivered==0){parent.errorCode=parent.lines.stream().map(l->l.errorCode).filter(Objects::nonNull).findFirst().orElse("NOTHING_DELIVERED");parent.terminationReason="EXECUTION_ERROR";parent.state("failed",parent.errorCode);}
        else{parent.delivery.stage="stored_offline";parent.state(parent.lines.stream().anyMatch(l->l.delivered<l.requested)?"partial_stored_offline":"stored_offline",null);}
        try{
            for(String message:OrderFailurePolicy.shortageAnnouncements(parent))chat.accept(message);
            if(job.batch&&delivered>0)chat.accept("搞定了，我歇逼了");
        }catch(Exception ignored){/* Chat failure must not keep the job open. */}
    }
}

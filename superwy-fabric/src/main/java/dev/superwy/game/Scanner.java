package dev.superwy.game;

import dev.superwy.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;

public final class Scanner implements AutoCloseable {
    private final Minecraft mc=Minecraft.getInstance();
    private final Store store;private final Queries queries;private final Actions actions;
    private final Supplier<String> world,dimension;
    private final BooleanSupplier enabled;
    private final Supplier<String> protocol;
    private final ServuxScanner servux;
    private final ExecutorService decoding=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"superwy-scan-decode");t.setDaemon(true);return t;});
    private String protocolError;
    private final RegionScanScheduler scheduler=new RegionScanScheduler();
    private final Map<String,Boolean> pauseOverrides=new HashMap<>();
    private final Map<String,ScanProgress> checkpoints=new HashMap<>();
    private final Set<String> loadedCheckpoints=new HashSet<>();
    private final Set<String> checked=new HashSet<>();
    private final Map<String,Map<String,Object>> regionViews=new HashMap<>();
    private TaskScope stepScope;private boolean executorBlocked;
    private Model.Region active;private CompletableFuture<?> pending;
    private ScanProgress progress;
    private volatile List<Model.Pos> remaining=List.of();
    public List<Model.Pos> remaining(){return remaining;}
    private final Map<String,Long> nextDue=new HashMap<>(),revisions=new HashMap<>();
    private int cx,cz,generation;
    private boolean stop;
    public volatile Map<String,Object> status=Map.of("state","idle");
    public Scanner(Store store,Queries queries,Actions actions,Supplier<String> world,Supplier<String> dim){this(store,queries,actions,world,dim,()->true);}
    public Scanner(Store store,Queries queries,Actions actions,Supplier<String> world,Supplier<String> dim,BooleanSupplier enabled){this(store,queries,actions,world,dim,enabled,()->"f3",null);}
    public Scanner(Store store,Queries queries,Actions actions,Supplier<String> world,Supplier<String> dim,BooleanSupplier enabled,Supplier<String> protocol,ServuxScanner servux){this.store=store;this.queries=queries;this.actions=actions;this.world=world;this.dimension=dim;this.enabled=enabled;this.protocol=protocol;this.servux=servux;for(var s:store.snapshots())revisions.put(s.key,s.revision);}
    private boolean useServux(){return "servux".equals(protocol.get());}
    public void protocolChanged(){interruptStep("SCAN_PROTOCOL_CHANGED");protocolError=null;reschedule();}
    private static boolean interrupted(String reason){return Set.of("DISCONNECTED","DISCONNECTED_OR_PAUSED","CONTROL_STOPPED","SCAN_REGION_STOPPED","SCAN_STOPPED","SCAN_PROTOCOL_CHANGED").contains(reason);}
    private boolean storage(BlockPos p){return Model.storageType(BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).toString());}
    public boolean enabled(){return enabled.getAsBoolean();}
    public void reschedule(){nextDue.clear();}
    public boolean regionPaused(String id){return paused(region(id));}
    private Model.Region region(String id){
        if(active!=null&&active.id.equals(id))return active;
        return store.regions().stream().filter(r->r.id.equals(id)&&r.world.equals(world.get())&&r.dimension.equals(dimension.get())).findFirst().orElseThrow(()->new IllegalArgumentException("UNKNOWN_REGION"));
    }
    private boolean paused(Model.Region r){return pauseOverrides.getOrDefault(r.id,r.scanPaused);}
    public void request(String id){if(enabled()){var r=region(id);if(r.enabled&&!paused(r))scheduler.start(id);}}
    public CompletableFuture<Void> startRegion(String id){
        if(!enabled())throw new IllegalArgumentException("SCAN_DISABLED");
        var r=region(id);if(!r.enabled)throw new IllegalArgumentException("REGION_DISABLED");
        r.scanPaused=false;pauseOverrides.put(id,false);scheduler.start(id);protocolError=null;
        return store.saveRegion(r);
    }
    public CompletableFuture<Void> stopRegion(String id){
        var r=region(id);r.scanPaused=true;pauseOverrides.put(id,true);scheduler.stop(id);
        if(active!=null&&active.id.equals(id))interruptStep("SCAN_REGION_STOPPED");
        var saved=store.saveRegion(r);
        var step=active!=null&&active.id.equals(id)&&pending!=null?pending.thenApply(v->(Void)null):CompletableFuture.<Void>completedFuture(null);
        return CompletableFuture.allOf(saved,step).thenRunAsync(()->{
            if(paused(r)){regionViews.put(id,progressView(r,store.scan(id),"stopped"));if(Objects.equals(status.get("regionId"),id))status=regionViews.get(id);refreshRemaining();}
        },actions.executor());
    }
    private void interruptStep(String reason){if(stepScope!=null&&pending!=null&&!pending.isDone()){stepScope.stop(reason);actions.navigation.stop();}}
    public void stop(){stop=true;interruptStep("SCAN_STOPPED");}
    public void forget(String id){
        nextDue.remove(id);scheduler.stop(id);pauseOverrides.remove(id);regionViews.remove(id);checkpoints.remove(id);loadedCheckpoints.remove(id);checked.remove(id);
        if(active==null&&Objects.equals(status.get("regionId"),id))status=Map.of("state","idle");refreshRemaining();
    }
    public CompletableFuture<Void> retryFailed(String id){
        if(!enabled())throw new IllegalArgumentException("SCAN_DISABLED");
        if(busy()||!safe())throw new IllegalArgumentException("EXECUTOR_BUSY");
        var region=store.regions().stream().filter(r->r.id.equals(id)&&r.world.equals(world.get())&&r.dimension.equals(dimension.get())).findFirst().orElseThrow(()->new IllegalArgumentException("UNKNOWN_REGION"));
        return store.saveScan(ScanProgress.start(region)).thenComposeAsync(v->{forget(id);return startRegion(id);},actions.executor());
    }
    public void invalidate(BlockPos p){invalidateSingle(p);BlockPos other=partner(p);if(other!=null)invalidateSingle(other);}
    private void invalidateSingle(BlockPos p){String key=Model.containerKey(world.get(),dimension.get(),Items.pos(p));revisions.merge(key,1L,Long::sum);var s=store.snapshot(key);if(s!=null){s.complete=false;store.saveSnapshot(s);}}
    private BlockPos partner(BlockPos p){if(mc.level==null)return null;var state=mc.level.getBlockState(p);return state.getBlock() instanceof ChestBlock&&state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE?ChestBlock.getConnectedBlockPos(p,state):null;}
    public CompletableFuture<Void> refresh(BlockPos p){
        if(mc.level!=null&&mc.level.hasChunkAt(p)&&!storage(p))return store.deleteSnapshot(Model.containerKey(world.get(),dimension.get(),Items.pos(p)));
        BlockPos other=partner(p);return refreshSingle(p).thenComposeAsync(v->other==null?CompletableFuture.completedFuture(null):refreshSingle(other),actions.executor());
    }
    private CompletableFuture<Void> refreshSingle(BlockPos p){
        String w=world.get(),d=dimension.get(),key=Model.containerKey(w,d,Items.pos(p));long rev=revisions.merge(key,1L,Long::sum);
        String type=BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).toString();
        var context=Items.context();int epoch=generation;var scope=TaskScope.current();var client=actions.executor();
        var decoded=queries.block(p).thenApplyAsync(tag->{if(scope!=null)scope.check();return Items.snapshot(w,d,p,type,tag,rev,context);},decoding);
        if(scope!=null)scope.track(decoded);
        return decoded.thenComposeAsync(snapshot->{
            if(scope!=null)scope.check();
            if(epoch!=generation||!world.get().equals(w)||!dimension.get().equals(d)||revisions.getOrDefault(key,0L)!=rev)return CompletableFuture.completedFuture(null);
            return store.saveSnapshot(snapshot);
        },client).exceptionallyComposeAsync(error->{if(epoch==generation&&world.get().equals(w)&&dimension.get().equals(d)&&revisions.getOrDefault(key,0L)==rev&&!Set.of("SCAN_REGION_STOPPED","SCAN_STOPPED","SCAN_PROTOCOL_CHANGED").contains(rootMessage(error)))invalidate(p);return CompletableFuture.failedFuture(error);},client);
    }
    public void reset(){generation++;if(stepScope!=null)stepScope.stop("DISCONNECTED");active=null;progress=null;pending=null;stepScope=null;scheduler.clear();regionViews.clear();checkpoints.clear();loadedCheckpoints.clear();checked.clear();remaining=List.of();stop=false;protocolError=null;status=Map.of("state","offline");}
    public boolean busy(){return active!=null||!scheduler.isEmpty();}
    public boolean safe(){return pending==null||pending.isDone();}
    public void tick(boolean executorBusy){
        if(mc.level==null||mc.player==null)return;
        if(pending!=null&&!pending.isDone())return;
        if(pending!=null&&pending.isCompletedExceptionally()){publish("storage_error");return;}
        pending=null;active=null;progress=null;stepScope=null;executorBlocked=executorBusy;
        if(stop||!enabled()){
            scheduler.clear();
            if(status.containsKey("regionId")&&(stop||!"stopped".equals(status.get("state")))){var view=new LinkedHashMap<>(status);view.put("state",stop?"stopped":"disabled");view.put("running",false);status=Collections.unmodifiableMap(view);}
            stop=false;
            return;
        }
        if(protocolError!=null)return;
        if(executorBusy){if(!scheduler.isEmpty()){var view=new LinkedHashMap<>(status);view.put("state","paused_for_order");status=Collections.unmodifiableMap(view);}return;}
        long now=System.currentTimeMillis();
        for(var r:store.regions()){
            if(!r.enabled||paused(r)||!r.world.equals(world.get())||!r.dimension.equals(dimension.get()))continue;
            long due=ScanSchedule.nextDue(r,checkpoint(r.id),protocol.get());
            if(now>=nextDue.getOrDefault(r.id,due))scheduler.add(r.id);
        }
        String id=scheduler.next();if(id==null)return;
        active=store.regions().stream().filter(r->r.id.equals(id)&&r.enabled&&!paused(r)&&r.world.equals(world.get())&&r.dimension.equals(dimension.get())).findFirst().orElse(null);
        if(active==null){scheduler.stop(id);return;}
        progress=checkpoint(id);boolean resumed=progress!=null&&progress.matches(active);
        if(!resumed||progress.finished){
            progress=resumed?progress.nextRound(active):ScanProgress.start(active);checkpoints.put(id,progress);invalidateSkipped();checked.add(id);
            pruneExcludedProgress();nextDue.put(id,now+ScanSchedule.intervalSeconds(active,protocol.get())*1000L);publish("discovering");pending=store.saveScan(progress);return;
        }
        progress.nextAttemptAt=0;
        checkpoints.put(id,progress);if(checked.add(id)){progress.resume();pruneExcludedProgress();invalidateSkipped();}
        stepScope=new TaskScope();stepScope.call(()->{advance();return null;});
    }
    private ScanProgress checkpoint(String id){if(loadedCheckpoints.add(id)&&!checkpoints.containsKey(id)){var saved=store.scan(id);if(saved!=null)checkpoints.put(id,saved);}return checkpoints.get(id);}
    private void pruneExcludedProgress(){
        if(progress.containers.isEmpty()&&progress.skippedContainers.isEmpty())return;
        java.util.function.Predicate<Model.Pos> excluded=pos->{var p=Items.block(pos);if(mc.level.hasChunkAt(p))return !storage(p);var known=store.snapshot(Model.containerKey(active.world,active.dimension,pos));return known!=null&&!Model.storageType(known.type);};
        int before=progress.containers.size()+progress.skippedContainers.size();
        progress.containers.removeIf(excluded);progress.skippedContainers.values().removeIf(f->excluded.test(f.position()));
        progress.discovered=Math.max(progress.processed,progress.discovered-before+progress.containers.size()+progress.skippedContainers.size());
    }
    private void invalidateSkipped(){
        if(progress.skippedContainers.isEmpty()&&progress.skippedChunks.isEmpty())return;
        for(var snapshot:store.snapshots())if(snapshot.world.equals(active.world)&&snapshot.dimension.equals(active.dimension)&&active.contains(snapshot.pos)&&(progress.skippedContainer(snapshot.pos)||progress.skippedChunkContaining(snapshot.pos)))invalidate(Items.block(snapshot.pos));
    }
    private void advance(){
        if(!progress.chunks.isEmpty()){discoverChunk();return;}
        Model.Pos pos=progress.containers.peek();
        if(pos==null){
            boolean complete=progress.complete();long now=System.currentTimeMillis();progress.finished=true;progress.nextAttemptAt=now+ScanSchedule.intervalSeconds(active,protocol.get())*1000L;
            active.lastScanAt=now;if(complete)active.lastSuccessAt=now;
            nextDue.put(active.id,progress.nextAttemptAt);scheduler.stop(active.id);publish(complete?"complete":"complete_with_skips");
            pending=store.finishScan(progress,active);
            return;
        }
        BlockPos p=new BlockPos(pos.x(),pos.y(),pos.z());publish("reading");int epoch=generation;
        if(useServux()){
            var chunk=new Model.Pos(Math.floorDiv(pos.x(),16),0,Math.floorDiv(pos.z(),16));
            pending=loadChunk(chunk).thenComposeAsync(v->servuxChunk(chunk,false),actions.executor()).exceptionallyComposeAsync(e->chunkFailure(chunk,false,e),actions.executor());return;
        }
        CompletableFuture<Void> read;
        if(!mc.level.hasChunkAt(p)&&actions.navigation.available())read=actions.go(p).thenComposeAsync(v->readContainer(p),actions.executor());
        else read=readContainer(p);
        pending=read.handleAsync((v,e)->{
            if(epoch!=generation||active==null)return CompletableFuture.<Void>completedFuture(null);
            if(store.failure!=null)return CompletableFuture.<Void>failedFuture(e==null?new IllegalStateException("STORAGE_UNAVAILABLE"):e);
            progress.containers.removeFirst();
            if(e==null)progress.processed++;else{
                String reason=rootMessage(e);
                if(interrupted(reason))progress.containers.addFirst(pos);
                else{progress.skipContainer(pos,reason);invalidate(p);}
            }
            publish("reading");return store.saveScan(progress);
        },actions.executor()).thenCompose(v->v);
    }
    private CompletableFuture<Void> readContainer(BlockPos p){
        if(mc.level==null||!mc.level.hasChunkAt(p))return CompletableFuture.failedFuture(new IllegalStateException("UNLOADED"));
        if(!storage(p))return store.deleteSnapshot(Model.containerKey(world.get(),dimension.get(),Items.pos(p)));
        var be=mc.level.getBlockEntity(p);
        if(!(be instanceof net.minecraft.world.Container))return store.deleteSnapshot(Model.containerKey(world.get(),dimension.get(),Items.pos(p)));
        if(!(be instanceof BaseContainerBlockEntity)){invalidate(p);return CompletableFuture.failedFuture(new IllegalStateException("UNSUPPORTED_CONTAINER"));}
        BlockPos other=partner(p);
        if(other!=null&&!active.contains(Items.pos(other))){invalidate(p);return CompletableFuture.failedFuture(new IllegalStateException("BOUNDARY_CONFLICT"));}
        return refresh(p);
    }
    private void discoverChunk(){
        Model.Pos chunk=progress.chunks.peek();cx=chunk.x();cz=chunk.z();int epoch=generation;
        pending=loadChunk(chunk).thenComposeAsync(v->{
            if(epoch!=generation||active==null)return CompletableFuture.completedFuture(null);
            if(useServux()&&mc.level.getChunk(chunk.x(),chunk.z()).getBlockEntities().keySet().stream().anyMatch(p->active.contains(Items.pos(p))&&storage(p)))return servuxChunk(chunk,true);
            readChunk();progress.coveredChunks++;progress.chunks.removeFirst();publish("discovering");return store.saveScan(progress);
        },actions.executor()).exceptionallyComposeAsync(e->epoch!=generation||active==null?CompletableFuture.completedFuture(null):chunkFailure(chunk,true,e),actions.executor());
    }
    private CompletableFuture<Void> loadChunk(Model.Pos chunk){
        BlockPos anchor=new BlockPos(chunk.x()*16,active.min.y(),chunk.z()*16);
        CompletableFuture<Void> loaded;
        if(mc.level.hasChunkAt(anchor))loaded=CompletableFuture.completedFuture(null);
        else if(actions.navigation.available())loaded=actions.go(new BlockPos(chunk.x()*16+8,mc.player.blockPosition().getY(),chunk.z()*16+8));
        else loaded=CompletableFuture.failedFuture(new IllegalStateException("UNLOADED"));
        return loaded.thenRunAsync(()->{TaskScope.checkCurrent();if(mc.level==null||!mc.level.hasChunkAt(anchor))throw new IllegalStateException("UNLOADED_OR_UNREACHABLE");},actions.executor());
    }
    private CompletableFuture<Void> chunkFailure(Model.Pos chunk,boolean discovering,Throwable error){
        if(active==null||progress==null)return CompletableFuture.completedFuture(null);
        if(store.failure!=null)return CompletableFuture.failedFuture(error);
        String reason=rootMessage(error);
        if(interrupted(reason)){publish("stopped");return store.saveScan(progress);}
        if(reason.startsWith("SERVUX_")){
            protocolError=reason;publish("protocol_error");return store.saveScan(progress);
        }
        if(discovering){progress.skipChunk(chunk,reason);progress.chunks.remove(chunk);}
        else for(var pos:new ArrayList<>(progress.containers))if(inChunk(pos,chunk)){progress.skipContainer(pos,reason);progress.containers.remove(pos);}
        for(var snapshot:store.chunkSnapshots(active.world,active.dimension,chunk.x(),chunk.z()))if(active.contains(snapshot.pos))invalidate(Items.block(snapshot.pos));
        publish("discovering");return store.saveScan(progress);
    }
    private static boolean inChunk(Model.Pos pos,Model.Pos chunk){return Math.floorDiv(pos.x(),16)==chunk.x()&&Math.floorDiv(pos.z(),16)==chunk.z();}
    private record Candidate(Model.Pos pos,String type,long revision,String error){}
    private record Decoded(Candidate candidate,Model.Snapshot snapshot,String error){}
    private CompletableFuture<Void> servuxChunk(Model.Pos chunk,boolean discovering){
        if(servux==null)return CompletableFuture.failedFuture(new IllegalStateException("SERVUX_UNAVAILABLE"));
        var region=active;int epoch=generation;var scope=TaskScope.current();var client=actions.executor();var context=Items.context();
        return servux.chunk(new ChunkPos(chunk.x(),chunk.z()),region.min.y(),region.max.y()).thenComposeAsync(tag->{
            TaskScope.checkCurrent();
            if(epoch!=generation||active!=region)throw new IllegalStateException("DISCONNECTED");
            var candidates=new LinkedHashMap<Model.Pos,Candidate>();
            for(var entry:mc.level.getChunk(chunk.x(),chunk.z()).getBlockEntities().entrySet()){
                var p=entry.getKey();if(!region.contains(Items.pos(p))||!storage(p)||!(entry.getValue() instanceof BaseContainerBlockEntity))continue;
                var pos=Items.pos(p);String key=Model.containerKey(region.world,region.dimension,pos);var other=partner(p);
                candidates.put(pos,new Candidate(pos,BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p).getBlock()).toString(),revisions.merge(key,1L,Long::sum),other!=null&&!region.contains(Items.pos(other))?"BOUNDARY_CONFLICT":null));
            }
            var inputs=List.copyOf(candidates.values());
            var decode=CompletableFuture.supplyAsync(()->{
                if(scope!=null)scope.check();
                // Validate every coordinate before decoding items or mutating any checkpoint.
                Map<Model.Pos,CompoundTag> tags=new HashMap<>();
                for(Tag value:(ListTag)tag.get("TileEntities")){
                    if(!(value instanceof CompoundTag entry))throw new IllegalStateException("SERVUX_RESPONSE_INVALID");
                    int x=entry.getInt("x").orElseThrow(()->new IllegalStateException("SERVUX_RESPONSE_INVALID"));
                    int y=entry.getInt("y").orElseThrow(()->new IllegalStateException("SERVUX_RESPONSE_INVALID"));
                    int z=entry.getInt("z").orElseThrow(()->new IllegalStateException("SERVUX_RESPONSE_INVALID"));
                    var pos=new Model.Pos(x,y,z);if(!inChunk(pos,chunk)||tags.put(pos,entry)!=null)throw new IllegalStateException("SERVUX_RESPONSE_INVALID");
                }
                var results=new ArrayList<Decoded>();
                for(var input:inputs){
                    if(scope!=null)scope.check();
                    try{if(input.error!=null)throw new IllegalStateException(input.error);var data=tags.get(input.pos);if(data==null)throw new IllegalStateException("SERVUX_CONTAINER_MISSING");
                        results.add(new Decoded(input,Items.snapshot(region.world,region.dimension,Items.block(input.pos),input.type,data,input.revision,context),null));
                    }catch(RuntimeException error){results.add(new Decoded(input,null,rootMessage(error)));}
                }return results;
            },decoding);
            if(scope!=null)scope.track(decode);
            return decode.thenComposeAsync(decoded->{
            TaskScope.checkCurrent();if(epoch!=generation||active!=region)throw new IllegalStateException("DISCONNECTED");
            if(discovering)for(var pos:candidates.keySet()){progress.discovered++;if(!progress.skippedContainer(pos)&&!progress.containers.contains(pos))progress.containers.add(pos);}
            var updates=new ArrayList<Model.Snapshot>();var removals=new LinkedHashSet<String>();
            for(var old:store.chunkSnapshots(region.world,region.dimension,chunk.x(),chunk.z()))if(region.contains(old.pos)&&!candidates.containsKey(old.pos))removals.add(old.key);
            var results=new HashMap<Model.Pos,Decoded>();for(var result:decoded)results.put(result.candidate.pos,result);
            for(var pos:new ArrayList<>(progress.containers)){
                if(!inChunk(pos,chunk))continue;
                String key=Model.containerKey(active.world,active.dimension,pos);
                if(!candidates.containsKey(pos)){removals.add(key);progress.containers.remove(pos);continue;}
                if(progress.skippedContainer(pos))continue;
                var result=results.get(pos);
                if(result.error==null){updates.add(result.snapshot);progress.processed++;}
                else{
                    progress.skipContainer(pos,result.error);
                    var old=store.snapshot(key);if(old!=null){old.complete=false;updates.add(old);}
                }
                progress.containers.remove(pos);
            }
            if(discovering){progress.coveredChunks++;progress.chunks.remove(chunk);}
            publish("reading");return store.saveScanBatch(updates,removals,progress);
            },client);
        },client);
    }
    private void readChunk(){
        Set<String> seen=new HashSet<>();
        for(var entry:mc.level.getChunk(cx,cz).getBlockEntities().entrySet()){
            BlockPos p=entry.getKey();if(!active.contains(Items.pos(p))||!storage(p)||!(entry.getValue() instanceof BaseContainerBlockEntity))continue;
            seen.add(Model.containerKey(world.get(),dimension.get(),Items.pos(p)));
            Model.Pos pos=Items.pos(p);
            progress.discovered++;
            if(progress.skippedContainer(pos)){invalidate(p);continue;}
            if(!progress.containers.contains(pos))progress.containers.add(pos);
        }
        for(var old:store.chunkSnapshots(world.get(),dimension.get(),cx,cz))if(active.contains(old.pos)&&!seen.contains(old.key))store.deleteSnapshot(old.key);
    }
    private Map<String,Object> progressView(Model.Region r,ScanProgress p,String state){
        Map<String,Object> view=new LinkedHashMap<>();view.put("state",state);view.put("regionId",r.id);view.put("scanPaused",paused(r));view.put("running",scheduler.contains(r.id));view.put("persistent",true);view.put("totalChunks",r.chunks());
        view.put("scanProtocol",protocol.get());if(protocolError!=null){view.put("protocolError",protocolError);view.put("protocolMessage",ErrorMessages.message(protocolError));}
        view.put("intervalSeconds",ScanSchedule.intervalSeconds(r,protocol.get()));view.put("nextDueAt",ScanSchedule.nextDue(r,p,protocol.get()));
        if(p==null){view.put("remainingContainers",List.of());return Collections.unmodifiableMap(view);}
        var failures=new ArrayList<Map<String,Object>>();
        for(var f:p.skippedContainers.values())failures.add(Map.of("position",f.position(),"reason",f.reason(),"message",ErrorMessages.message(f.reason()),"failedAt",f.failedAt()));
        for(var f:p.skippedChunks.values())failures.add(Map.of("chunk",f.position(),"reason",f.reason(),"message",ErrorMessages.message(f.reason()),"failedAt",f.failedAt()));
        view.put("discovered",p.discovered);view.put("processed",p.processed);view.put("coveredChunks",p.coveredChunks);view.put("remainingContainers",List.copyOf(new LinkedHashSet<>(p.containers)));view.put("remainingChunks",p.chunks.size());view.put("skippedContainers",p.skippedContainers.size());view.put("skippedChunks",p.skippedChunks.size());view.put("failures",List.copyOf(failures));return Collections.unmodifiableMap(view);
    }
    public List<Map<String,Object>> regionStatuses(){
        var result=new ArrayList<Map<String,Object>>();
        for(var r:store.regions())if(r.world.equals(world.get())&&r.dimension.equals(dimension.get())){
            var p=active!=null&&active.id.equals(r.id)?progress:checkpoint(r.id);
            String state=!r.enabled?"disabled":paused(r)?"stopped":!enabled()?"disabled":scheduler.contains(r.id)?executorBlocked?"paused_for_order":"running":p!=null&&p.finished?p.complete()?"complete":"complete_with_skips":"idle";
            if(protocolError!=null&&scheduler.contains(r.id)&&!paused(r))state="protocol_error";
            if(scheduler.contains(r.id)&&active!=null&&active.id.equals(r.id)&&pending!=null&&!pending.isDone()&&!paused(r)&&!executorBlocked)state=String.valueOf(regionViews.getOrDefault(r.id,Map.of("state","running")).get("state"));
            result.add(progressView(r,p,state));
        }return List.copyOf(result);
    }
    private void refreshRemaining(){
        Set<Model.Pos> positions=new LinkedHashSet<>();
        for(var r:store.regions())if(r.world.equals(world.get())&&r.dimension.equals(dimension.get())){
            var p=active!=null&&active.id.equals(r.id)?progress:checkpoint(r.id);
            if(p!=null&&p.matches(r)&&!p.finished)positions.addAll(p.containers);
        }remaining=List.copyOf(positions);
    }
    private void publish(String state){
        if(active==null||progress==null){status=Map.of("state",state);return;}
        status=progressView(active,progress,state);regionViews.put(active.id,status);refreshRemaining();
    }
    public static String rootMessage(Throwable t){while(t.getCause()!=null)t=t.getCause();return t.getMessage()==null?t.getClass().getSimpleName():t.getMessage();}
    public void close(){reset();decoding.shutdownNow();}
}

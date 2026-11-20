package dev.superwy.core;

import java.util.*;

/** Durable work list. Peek before IO; acknowledge only after the snapshot is durable. */
public final class ScanProgress {
    public String regionId, fingerprint;
    public long nextAttemptAt;
    public boolean finished;
    public int discovered, processed, coveredChunks;
    public ArrayDeque<Model.Pos> chunks=new ArrayDeque<>(), containers=new ArrayDeque<>();
    // Kept for reading 0.2.0 checkpoints. These queues are migrated to permanent skips.
    public ArrayDeque<Model.Pos> retryChunks=new ArrayDeque<>(), retryContainers=new ArrayDeque<>();
    public record Failure(Model.Pos position,String reason,long failedAt){}
    public Map<String,Failure> skippedContainers=new LinkedHashMap<>(),skippedChunks=new LinkedHashMap<>();
    private static String key(Model.Pos p){return p.x()+","+p.y()+","+p.z();}
    public boolean skippedContainer(Model.Pos p){return skippedContainers.containsKey(key(p));}
    public boolean skippedChunkContaining(Model.Pos p){return skippedChunks.containsKey(key(new Model.Pos(Math.floorDiv(p.x(),16),0,Math.floorDiv(p.z(),16))));}
    public void skipContainer(Model.Pos p,String reason){skippedContainers.putIfAbsent(key(p),new Failure(p,reason,System.currentTimeMillis()));}
    public void skipChunk(Model.Pos p,String reason){skippedChunks.putIfAbsent(key(p),new Failure(p,reason,System.currentTimeMillis()));}
    public static String fingerprint(Model.Region r){return Jsons.hash(Jsons.GSON.toJsonTree(List.of(r.world,r.dimension,r.min,r.max)));}
    public static ScanProgress start(Model.Region r){
        var p=new ScanProgress();p.regionId=r.id;p.fingerprint=fingerprint(r);
        for(int x=Math.floorDiv(r.min.x(),16);x<=Math.floorDiv(r.max.x(),16);x++)
            for(int z=Math.floorDiv(r.min.z(),16);z<=Math.floorDiv(r.max.z(),16);z++)p.chunks.add(new Model.Pos(x,0,z));
        return p;
    }
    public boolean matches(Model.Region r){return regionId.equals(r.id)&&fingerprint.equals(fingerprint(r));}
    /** Queues/maps are detached on the actor thread before asynchronous JSON encoding. */
    public ScanProgress copy(){
        var p=new ScanProgress();p.regionId=regionId;p.fingerprint=fingerprint;p.nextAttemptAt=nextAttemptAt;p.finished=finished;p.discovered=discovered;p.processed=processed;p.coveredChunks=coveredChunks;
        p.chunks.addAll(chunks);p.containers.addAll(containers);p.retryChunks.addAll(retryChunks);p.retryContainers.addAll(retryContainers);p.skippedContainers.putAll(skippedContainers);p.skippedChunks.putAll(skippedChunks);return p;
    }
    public void resume(){
        for(var p:retryContainers)skipContainer(p,"PREVIOUS_SCAN_FAILED");
        for(var p:retryChunks)skipChunk(p,"PREVIOUS_CHUNK_FAILED");
        retryContainers.clear();retryChunks.clear();
        containers.removeIf(this::skippedContainer);chunks.removeIf(p->skippedChunks.containsKey(key(p)));
    }
    public ScanProgress nextRound(Model.Region r){
        resume();var next=start(r);next.skippedContainers.putAll(skippedContainers);next.skippedChunks.putAll(skippedChunks);next.resume();return next;
    }
    public boolean exhausted(){return chunks.isEmpty()&&containers.isEmpty();}
    public boolean complete(){return exhausted()&&skippedContainers.isEmpty()&&skippedChunks.isEmpty()&&retryChunks.isEmpty()&&retryContainers.isEmpty();}
}

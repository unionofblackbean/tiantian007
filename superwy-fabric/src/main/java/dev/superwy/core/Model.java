package dev.superwy.core;

import java.time.Instant;
import java.util.*;

public final class Model {
    private Model() {}
    public record Pos(int x, int y, int z) {
        public double distance(Pos other) { return Math.sqrt(Math.pow(x-other.x,2)+Math.pow(y-other.y,2)+Math.pow(z-other.z,2)); }
    }
    public static final class Region {
        public String id, world, dimension;
        public Pos min, max;
        public int intervalSeconds = ScanSchedule.DEFAULT_INTERVAL_SECONDS;
        public boolean enabled = true;
        public boolean scanPaused;
        public long lastSuccessAt, lastScanAt;
        public Region() {}
        public Region(String id, String world, String dimension, Pos a, Pos b) {
            this.id=id; this.world=world; this.dimension=dimension;
            min=new Pos(Math.min(a.x,b.x),Math.min(a.y,b.y),Math.min(a.z,b.z));
            max=new Pos(Math.max(a.x,b.x),Math.max(a.y,b.y),Math.max(a.z,b.z));
            if (volume()>2_000_000 || chunks()>1024) throw new IllegalArgumentException("选区过大：最多 200 万方块 / 1024 区块");
        }
        public long volume() { return ((long)max.x-min.x+1)*((long)max.y-min.y+1)*((long)max.z-min.z+1); }
        public long chunks() { return ((long)Math.floorDiv(max.x,16)-Math.floorDiv(min.x,16)+1)*((long)Math.floorDiv(max.z,16)-Math.floorDiv(min.z,16)+1); }
        public boolean contains(Pos p) { return p.x>=min.x&&p.x<=max.x&&p.y>=min.y&&p.y<=max.y&&p.z>=min.z&&p.z<=max.z; }
    }
    public static final class ItemNode {
        public int slot, count, maxStackSize;
        public String itemId, componentHash, variantSummary;
        public com.google.gson.JsonElement components;
        public boolean shulker;
        public List<ItemNode> children = new ArrayList<>();
        public ItemNode() {}
    }
    public static final class Snapshot {
        public String key, world, dimension, type;
        public Pos pos;
        public long revision, observedAt;
        public boolean complete;
        public List<ItemNode> items = new ArrayList<>();
    }
    public static final class Match { public String mode="item_id", componentHash; }
    public static final class RequestLine { public String itemId; public int count; public Match match = new Match(); }
    public static final class DeliveryRequest { public String method="carpet_fake_player", fakePlayerName, stationId="main"; }
    public static final class Options { public boolean allowPartial,batch,projectionParcels; public int expiresInSeconds=86400; }
    public static final class Request {
        // Set only by the authenticated batch inbox, never accepted from HTTP JSON.
        public String batchId,notifyPlayer;
        public boolean batchCapacityPending;
        // Litematica area order: always deliver as shulker parcels, filled in manifest order.
        public boolean projectionParcels;
        // Super-dispatch: a player order the authority splits across robots (never executed directly),
        // or one block of such an order executed by this robot. Set internally, never from HTTP JSON.
        public boolean meshParent,forcePacking;
        public String meshChunkId;
        public String warehouseId="main", clientRequestId;
        public List<RequestLine> items = new ArrayList<>();
        public DeliveryRequest delivery;
        public Options options = new Options();
    }
    public static final class Line {
        public boolean skipped;
        public String errorCode;
        public int lineIndex, requested, fetched, packed, delivered, shortfall;
        public String itemId, componentHash;
        public Line() {}
        public Line(int index, RequestLine req) { lineIndex=index;itemId=req.itemId;requested=req.count;componentHash=req.match.componentHash; }
    }
    public static final class PackageLine {
        public int lineIndex,count; public String itemId,componentHash;
        public PackageLine() {}
        public PackageLine(int i,String id,String hash,int n) {lineIndex=i;itemId=id;componentHash=hash;count=n;}
    }
    public static final class Parcel {
        public String packageId="pkg_"+UUID.randomUUID(),batchId,manifestHash,status="packing",custody="robot";
        public int inventorySlot=-1;
        public String deliveredTo;
        public List<PackageLine> lines=new ArrayList<>();
    }
    public static final class Cargo {
        public int inventorySlot,lineIndex,count;
        public String itemId,componentHash,status="held";
    }
    public static final class Delivery {
        public String baseFakePlayerName;
        public int recipientSequence;
        public List<String> recipientNames=new ArrayList<>();
        public String mode="legacy_shulker";
        public String spawnMode,spawnDimension;
        public List<Double> spawnPosition;
        public String fakePlayerName, fakePlayerUuid, stage="not_started",collectionStatus="unknown",receiptSource,receivedAt,offlineObservedAt;
        public int verifiedPackages;
    }
    public static final class Order {
        public boolean packingMode,cleanupCompleted,cleanupRoundCounted;
        public String cleanupErrorCode;
        public String orderId="ord_"+UUID.randomUUID(),principalId,warehouseId,world,dimension,clientRequestId,state="queued",blockingReason,errorCode;
        public long revision=1;
        public long queueSequence;
        public String createdAt=Instant.now().toString(),updatedAt=createdAt,expiresAt;
        public String executionStartedAt,executionStoppedAt,terminationReason;
        public int executionTimeoutSeconds=TaskScope.TIMEOUT_SECONDS;
        public Integer currentLineIndex;
        public List<Line> lines=new ArrayList<>();
        public List<Parcel> packages=new ArrayList<>();
        public List<Cargo> cargo=new ArrayList<>();
        public List<String> missingItems=new ArrayList<>();
        public boolean needsItemReview;
        // Batch failure salvage: "pack_and_deliver" or "stash_loose"; stashed items went to the safe (cleanup) chest.
        public String salvageMode,salvageErrorCode;
        public long stashedItems;
        public Pos stashedTo;
        public int boxSupplyAtStart=-1,boxesEstimated;
        public Delivery delivery=new Delivery();
        public Request request;
        public boolean cancelRequested;
        // Super-dispatch: queue position and robots working on a parent order; how a block order was interrupted.
        public Integer meshAhead;
        public int meshRobots;
        public String interruptedState;
        public boolean restartInterrupted,meshResumed;
        public Order() {}
        public Order(String principal, Request req) {
            principalId=principal;request=req;warehouseId=req.warehouseId;clientRequestId=req.clientRequestId;
            delivery.fakePlayerName=req.delivery.fakePlayerName;
            expiresAt=Instant.now().plusSeconds(req.options.expiresInSeconds).toString();
            for(int i=0;i<req.items.size();i++) lines.add(new Line(i,req.items.get(i)));
        }
        public boolean terminal() {return Set.of("stored_offline","partial_stored_offline","cancelled","failed").contains(state);}
        public void state(String next,String reason) {state=next;blockingReason=reason;updatedAt=Instant.now().toString();revision++;}
    }
    public static final class Station {
        public String id="main",world,dimension;
        public Pos boxSupply, recovery, delivery;
    }
    public static String containerKey(String world,String dim,Pos p) {return world+"|"+dim+"|"+p.x+","+p.y+","+p.z;}
    public static boolean storageType(String type){return type.equals("minecraft:chest")||type.equals("minecraft:trapped_chest")||type.equals("minecraft:barrel")||type.startsWith("minecraft:")&&type.endsWith("shulker_box");}
}

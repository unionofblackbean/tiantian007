package dev.superwy.core;

import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public final class Store implements AutoCloseable {
    private final Connection db;
    private final ExecutorService writer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"superwy-storage");t.setDaemon(true);return t;});
    private final ExecutorService reader=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"superwy-stock-query");t.setDaemon(true);return t;});
    private final ConcurrentMap<String,String> snapshots=new ConcurrentHashMap<>(),orders=new ConcurrentHashMap<>(),regions=new ConcurrentHashMap<>(),scans=new ConcurrentHashMap<>(),mesh=new ConcurrentHashMap<>();
    private final WarehouseIndex warehouse=new WarehouseIndex();
    private final ConcurrentMap<String,Model.Order> orderIndex=new ConcurrentHashMap<>(),liveOrders=new ConcurrentHashMap<>();
    private final Queue<String> noticeArrivals=new ConcurrentLinkedQueue<>();
    public volatile String failure;
    public Store(Path file) throws Exception {
        Files.createDirectories(file.getParent());Class.forName("org.sqlite.JDBC");
        db=DriverManager.getConnection("jdbc:sqlite:"+file.toAbsolutePath());
        try(Statement s=db.createStatement()){
            s.execute("PRAGMA journal_mode=WAL");s.execute("PRAGMA synchronous=FULL");s.execute("PRAGMA busy_timeout=5000");
            s.execute("CREATE TABLE IF NOT EXISTS documents(kind TEXT NOT NULL,id TEXT NOT NULL,json TEXT NOT NULL,PRIMARY KEY(kind,id))");
            s.execute("CREATE TABLE IF NOT EXISTS idempotency(principal TEXT NOT NULL,key TEXT NOT NULL,hash TEXT NOT NULL,order_id TEXT NOT NULL,PRIMARY KEY(principal,key))");
            s.execute("CREATE TABLE IF NOT EXISTS operations(id TEXT PRIMARY KEY,order_id TEXT NOT NULL,json TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS order_queue(sequence INTEGER PRIMARY KEY AUTOINCREMENT,order_id TEXT NOT NULL UNIQUE)");
            s.execute("PRAGMA user_version=1");
            try(ResultSet rs=s.executeQuery("SELECT kind,id,json FROM documents")){
                while(rs.next()){
                    String kind=rs.getString(1),id=rs.getString(2),json=rs.getString(3);
                    if(kind.equals("snapshot"))warehouse.put(Jsons.GSON.fromJson(json,Model.Snapshot.class));
                    else if(kind.equals("order"))cacheOrder(id,json);
                    else map(kind).put(id,json);
                }
            }
        }
        // Backfill old databases deterministically. SQLite allocates all new acceptance numbers.
        List<Model.Order> migration;
        while(!(migration=ordersNeedingSequence()).isEmpty())for(Model.Order order:migration){
            long sequence;
            try(PreparedStatement q=db.prepareStatement("SELECT sequence FROM order_queue WHERE order_id=?")){
                q.setString(1,order.orderId);try(ResultSet rs=q.executeQuery()){sequence=rs.next()?rs.getLong(1):0;}
            }
            if(sequence==0)try(PreparedStatement q=db.prepareStatement("INSERT INTO order_queue(order_id) VALUES(?) RETURNING sequence")){
                q.setString(1,order.orderId);try(ResultSet rs=q.executeQuery()){rs.next();sequence=rs.getLong(1);}
            }
            if(order.queueSequence!=sequence){order.queueSequence=sequence;saveOrder(order).join();}
        }
        for(Model.Order order:pendingOrders())if(!order.state.equals("queued")&&(order.request==null||!order.request.meshParent)){
            OrderFailurePolicy.interrupted(order,hasOperations(order.orderId));saveOrder(order).join();
        }
    }
    private List<Model.Order> ordersNeedingSequence()throws SQLException{
        synchronized(db){try(Statement s=db.createStatement();ResultSet rs=s.executeQuery("SELECT d.json FROM documents d LEFT JOIN order_queue q ON q.order_id=d.id WHERE d.kind='order' AND (q.sequence IS NULL OR coalesce(json_extract(d.json,'$.queueSequence'),0)<>q.sequence) ORDER BY coalesce(json_extract(d.json,'$.queueSequence'),0),json_extract(d.json,'$.createdAt'),d.id LIMIT 128")){
            var result=new ArrayList<Model.Order>();while(rs.next())result.add(Jsons.GSON.fromJson(rs.getString(1),Model.Order.class));return result;
        }}
    }
    private boolean hasOperations(String orderId)throws SQLException{
        synchronized(db){try(PreparedStatement s=db.prepareStatement("SELECT 1 FROM operations WHERE order_id=? LIMIT 1")){
            s.setString(1,orderId);try(ResultSet rs=s.executeQuery()){return rs.next();}
        }}
    }
    private ConcurrentMap<String,String> map(String kind){return switch(kind){case "snapshot"->snapshots;case "region"->regions;case "scan"->scans;case "order"->orders;case "mesh"->mesh;default->throw new IllegalArgumentException("Unknown document kind: "+kind);};}
    private void indexOrder(String id,String json){var order=Jsons.GSON.fromJson(json,Model.Order.class);if(order.state.equals("queued"))orderIndex.put(id,order);else orderIndex.remove(id);if(order.terminal())liveOrders.remove(id);else liveOrders.put(id,order);}
    /** Terminal records stay durable in SQLite, rather than accumulating in the heap. */
    private void cacheOrder(String id,String json){
        indexOrder(id,json);
        if(liveOrders.containsKey(id))orders.put(id,json);else orders.remove(id);
    }
    private CompletableFuture<Void> write(String kind,String id,String json) {
        return write(kind,id,()->json);
    }
    private CompletableFuture<Void> write(String kind,String id,Supplier<String> encode) {
        return CompletableFuture.runAsync(()->{
            String json=encode.get();
            synchronized(db){try(PreparedStatement s=db.prepareStatement("INSERT INTO documents(kind,id,json) VALUES(?,?,?) ON CONFLICT(kind,id) DO UPDATE SET json=excluded.json")){
                s.setString(1,kind);s.setString(2,id);s.setString(3,json);s.executeUpdate();if(!kind.equals("snapshot")&&!kind.equals("order"))map(kind).put(id,json);
                if(kind.equals("snapshot"))warehouse.put(Jsons.GSON.fromJson(json,Model.Snapshot.class));
                if(kind.equals("order"))cacheOrder(id,json);
            }catch(SQLException e){failure=e.getMessage();throw new CompletionException(e);}}
        },writer);
    }
    public CompletableFuture<Void> saveSnapshot(Model.Snapshot s){var detached=WarehouseIndex.copy(s);return write("snapshot",detached.key,()->Jsons.GSON.toJson(detached));}
    public CompletableFuture<Void> saveRegion(Model.Region r){return write("region",r.id,Jsons.GSON.toJson(r));}
    public CompletableFuture<Void> saveOrder(Model.Order o){return write("order",o.orderId,Jsons.GSON.toJson(o));}
    public CompletableFuture<Void> saveScan(ScanProgress p){var detached=p.copy();return write("scan",detached.regionId,()->Jsons.GSON.toJson(detached));}
    /** Commit a bulk reply and its checkpoint together, with one durable SQLite transaction. */
    public CompletableFuture<Void> saveScanBatch(List<Model.Snapshot> updates,Collection<String> removals,ScanProgress progress){
        var detached=updates.stream().map(WarehouseIndex::copy).toList();
        var deleted=List.copyOf(removals);var scan=progress.copy();String regionId=progress.regionId;
        return CompletableFuture.runAsync(()->{String scanJson=Jsons.GSON.toJson(scan);Map<String,String> saved=new LinkedHashMap<>();for(var snapshot:detached)saved.put(snapshot.key,Jsons.GSON.toJson(snapshot));synchronized(db){
            try{
                db.setAutoCommit(false);
                try(PreparedStatement put=db.prepareStatement("INSERT INTO documents(kind,id,json) VALUES(?,?,?) ON CONFLICT(kind,id) DO UPDATE SET json=excluded.json");
                    PreparedStatement remove=db.prepareStatement("DELETE FROM documents WHERE kind='snapshot' AND id=?")){
                    for(String key:deleted){remove.setString(1,key);remove.addBatch();}remove.executeBatch();
                    for(var entry:saved.entrySet()){put.setString(1,"snapshot");put.setString(2,entry.getKey());put.setString(3,entry.getValue());put.addBatch();}
                    put.setString(1,"scan");put.setString(2,regionId);put.setString(3,scanJson);put.addBatch();put.executeBatch();
                }
                db.commit();for(String key:deleted)warehouse.remove(key);for(var snapshot:detached)warehouse.put(snapshot);scans.put(regionId,scanJson);
            }catch(SQLException e){try{db.rollback();}catch(SQLException rollback){e.addSuppressed(rollback);}failure=e.getMessage();throw new CompletionException(e);}
            finally{try{db.setAutoCommit(true);}catch(SQLException e){failure=e.getMessage();throw new CompletionException(e);}}
        }},writer);
    }
    public CompletableFuture<Void> finishScan(ScanProgress p,Model.Region r){
        var scan=p.copy();String regionJson=Jsons.GSON.toJson(r),id=r.id;
        return CompletableFuture.runAsync(()->{String scanJson=Jsons.GSON.toJson(scan);synchronized(db){
            try{
                db.setAutoCommit(false);
                try(PreparedStatement s=db.prepareStatement("INSERT INTO documents(kind,id,json) VALUES(?,?,?) ON CONFLICT(kind,id) DO UPDATE SET json=excluded.json")){
                    s.setString(1,"scan");s.setString(2,id);s.setString(3,scanJson);s.executeUpdate();
                    s.setString(1,"region");s.setString(3,regionJson);s.executeUpdate();
                }
                db.commit();scans.put(id,scanJson);regions.put(id,regionJson);
            }catch(SQLException e){try{db.rollback();}catch(SQLException rollback){e.addSuppressed(rollback);}failure=e.getMessage();throw new CompletionException(e);}
            finally{try{db.setAutoCommit(true);}catch(SQLException e){failure=e.getMessage();throw new CompletionException(e);}}
        }},writer);
    }
    public ScanProgress scan(String id){String j=scans.get(id);return j==null?null:Jsons.GSON.fromJson(j,ScanProgress.class);}
    public CompletableFuture<Void> deleteScan(String id){return CompletableFuture.runAsync(()->{synchronized(db){try(PreparedStatement s=db.prepareStatement("DELETE FROM documents WHERE kind='scan' AND id=?")){s.setString(1,id);s.executeUpdate();scans.remove(id);}catch(SQLException e){failure=e.getMessage();throw new CompletionException(e);}}},writer);}
    public List<Model.Snapshot> snapshots(){return warehouse.all();}
    public Model.Snapshot snapshot(String key){return warehouse.get(key);}
    public List<Model.Snapshot> chunkSnapshots(String world,String dimension,int x,int z){return warehouse.chunk(world,dimension,x,z);}
    public List<InventoryIndex.Source> sources(String world,String dimension,String item,String hash){return warehouse.sources(world,dimension,item,hash);}
    public CompletableFuture<List<InventoryIndex.Source>> sourcesAsync(String world,String dimension,String item,String hash){return CompletableFuture.supplyAsync(()->sources(world,dimension,item,hash),reader);}
    public CompletableFuture<List<InventoryIndex.Source>> sourcesAsync(String world,String dimension,String item,String hash,Predicate<InventoryIndex.Source> eligible){return CompletableFuture.supplyAsync(()->sources(world,dimension,item,hash).stream().filter(eligible).toList(),reader);}
    public long inventoryRevision(){return warehouse.revision();}
    /** Delivery rounds since the last completed cleanup, counted in SQLite instead of loading every order. */
    public long cleanupPending(Model.Order context){
        String scope=" FROM documents WHERE kind='order' AND json_extract(json,'$.warehouseId') IS ? AND json_extract(json,'$.world') IS ? AND json_extract(json,'$.dimension') IS ? AND json_extract(json,'$.cleanupRoundCounted')=1";
        synchronized(db){
            try(PreparedStatement last=db.prepareStatement("SELECT coalesce(max(json_extract(json,'$.queueSequence')),0)"+scope+" AND json_extract(json,'$.cleanupCompleted')=1");
                PreparedStatement count=db.prepareStatement("SELECT count(*)"+scope+" AND json_extract(json,'$.queueSequence')>?")){
                last.setString(1,context.warehouseId);last.setString(2,context.world);last.setString(3,context.dimension);
                long checkpoint;try(ResultSet rs=last.executeQuery()){rs.next();checkpoint=rs.getLong(1);}
                count.setString(1,context.warehouseId);count.setString(2,context.world);count.setString(3,context.dimension);count.setLong(4,checkpoint);
                try(ResultSet rs=count.executeQuery()){rs.next();return rs.getLong(1);}
            }catch(SQLException e){throw new CompletionException(e);}
        }
    }
    public CompletableFuture<Void> saveMesh(String id,String json){return write("mesh",id,json);}
    public String mesh(String id){return mesh.get(id);}
    /** Mesh parent orders are dispatched by the authority, never executed by the local engine. */
    public Model.Order nextQueued(String world,String dimension){return orderIndex.values().stream().filter(o->o.state.equals("queued")&&(o.request==null||!o.request.meshParent)&&Objects.equals(o.world,world)&&Objects.equals(o.dimension,dimension)).min(OrderQueue.ORDER).map(o->order(o.orderId)).orElse(null);}
    public int queuedCount(Model.Order context){return (int)orderIndex.values().stream().filter(o->o.state.equals("queued")&&!o.orderId.equals(context.orderId)&&OrderQueue.sameQueue(o,context)).count();}
    public List<Model.Region> regions(){return regions.values().stream().map(j->Jsons.GSON.fromJson(j,Model.Region.class)).toList();}
    public List<Model.Order> orders(){
        synchronized(db){try(Statement s=db.createStatement();ResultSet rs=s.executeQuery("SELECT json FROM documents WHERE kind='order'")){
            var result=new ArrayList<Model.Order>();while(rs.next())result.add(Jsons.GSON.fromJson(rs.getString(1),Model.Order.class));result.sort(OrderQueue.ORDER);return result;
        }catch(SQLException e){throw new CompletionException(e);}}
    }
    /** Fetch only the visible history; polling cost no longer scales with all past orders. */
    public List<Model.Order> recentOrders(int historyLimit,String principal){
        if(historyLimit<0||historyLimit>10)throw new IllegalArgumentException("INVALID_REQUEST");
        synchronized(db){
            var result=new ArrayList<>(pendingOrders());if(historyLimit==0)return result;
            try(PreparedStatement s=db.prepareStatement("SELECT json FROM documents WHERE kind='order' AND json_extract(json,'$.state') IN ('stored_offline','partial_stored_offline','cancelled','failed') AND (? IS NULL OR json_extract(json,'$.principalId')=?) ORDER BY json_extract(json,'$.updatedAt') DESC,json_extract(json,'$.queueSequence') DESC,json_extract(json,'$.createdAt') DESC,id DESC LIMIT ?")){
                s.setString(1,principal);s.setString(2,principal);s.setInt(3,historyLimit);
                try(ResultSet rs=s.executeQuery()){while(rs.next())result.add(Jsons.GSON.fromJson(rs.getString(1),Model.Order.class));}return result;
            }catch(SQLException e){throw new CompletionException(e);}
        }
    }
    public Model.Order order(String id){
        synchronized(db){String j=orders.get(id);if(j!=null)return Jsons.GSON.fromJson(j,Model.Order.class);
            try(PreparedStatement s=db.prepareStatement("SELECT json FROM documents WHERE kind='order' AND id=?")){
                s.setString(1,id);try(ResultSet rs=s.executeQuery()){return rs.next()?Jsons.GSON.fromJson(rs.getString(1),Model.Order.class):null;}
            }catch(SQLException e){throw new CompletionException(e);}
        }
    }
    public record Accepted(Model.Order order,boolean replay){}
    public List<Model.Order> pendingOrders(){return liveOrders.values().stream().sorted(OrderQueue.ORDER).toList();}
    public List<String> drainNoticeArrivals(){var ids=new ArrayList<String>();String id;while((id=noticeArrivals.poll())!=null)ids.add(id);return ids;}
    public Accepted accept(String principal,String key,Model.Request request) throws SQLException {
        return accept(principal,key,request,true);
    }
    public Accepted accept(String principal,String key,Model.Request request,boolean canAccept) throws SQLException {
        return accept(principal,key,request,canAccept,"test-world","minecraft:overworld");
    }
    public Accepted accept(String principal,String key,Model.Request request,boolean canAccept,String world,String dimension) throws SQLException {
        return accept(principal,key,request,canAccept,world,dimension,null);
    }
    /** prepare edits the new order before it becomes visible to the executor (used to resume a mesh block). */
    public Accepted accept(String principal,String key,Model.Request request,boolean canAccept,String world,String dimension,java.util.function.Consumer<Model.Order> prepare) throws SQLException {
        var canonical=Jsons.GSON.toJsonTree(request).getAsJsonObject();canonical.remove("batchCapacityPending");String hash=Jsons.hash(canonical);
        synchronized(db){
            try(PreparedStatement s=db.prepareStatement("SELECT hash,order_id FROM idempotency WHERE principal=? AND key=?")){
                s.setString(1,principal);s.setString(2,key);
                try(ResultSet rs=s.executeQuery()){
                    if(rs.next()) {if(!rs.getString(1).equals(hash))throw new IllegalArgumentException("IDEMPOTENCY_CONFLICT");return new Accepted(order(rs.getString(2)),true);}
                }
            }
            if(!canAccept)throw new IllegalArgumentException("CAPABILITY_UNAVAILABLE");
            if(liveOrders.values().stream().filter(o->o.principalId.equals(principal)).count()>=5||liveOrders.size()>=100)throw new IllegalArgumentException("QUEUE_FULL");
            if(request.batchId!=null&&liveOrders.values().stream().anyMatch(o->Objects.equals(o.world,world)&&Objects.equals(o.dimension,dimension)))request.batchCapacityPending=true;
            Model.Order order=new Model.Order(principal,request);order.world=world;order.dimension=dimension;if(prepare!=null)prepare.accept(order);
            db.setAutoCommit(false);
            try(PreparedStatement a=db.prepareStatement("INSERT INTO documents VALUES('order',?,?)");PreparedStatement b=db.prepareStatement("INSERT INTO idempotency VALUES(?,?,?,?)")){
                try(PreparedStatement q=db.prepareStatement("INSERT INTO order_queue(order_id) VALUES(?) RETURNING sequence")){
                    q.setString(1,order.orderId);try(ResultSet rs=q.executeQuery()){rs.next();order.queueSequence=rs.getLong(1);}
                }
                String json=Jsons.GSON.toJson(order);
                a.setString(1,order.orderId);a.setString(2,json);a.executeUpdate();
                b.setString(1,principal);b.setString(2,key);b.setString(3,hash);b.setString(4,order.orderId);b.executeUpdate();db.commit();
                cacheOrder(order.orderId,json);if(request.notifyPlayer!=null)noticeArrivals.add(order.orderId);return new Accepted(order,false);
            }catch(Exception e){db.rollback();throw e;}finally{db.setAutoCommit(true);}
        }
    }
    public CompletableFuture<Void> journal(String orderId,String operationId,Object evidence){
        String json=Jsons.GSON.toJson(evidence);
        return CompletableFuture.runAsync(()->{synchronized(db){try(PreparedStatement s=db.prepareStatement("INSERT OR REPLACE INTO operations VALUES(?,?,?)")){
            s.setString(1,operationId);s.setString(2,orderId);s.setString(3,json);s.executeUpdate();
        }catch(SQLException e){failure=e.getMessage();throw new CompletionException(e);}}},writer);
    }
    public CompletableFuture<Void> deleteRegion(String id){return CompletableFuture.runAsync(()->{synchronized(db){try(PreparedStatement s=db.prepareStatement("DELETE FROM documents WHERE kind IN ('region','scan') AND id=?")){s.setString(1,id);s.executeUpdate();regions.remove(id);scans.remove(id);}catch(SQLException e){throw new CompletionException(e);}}},writer);}
    public CompletableFuture<Void> deleteSnapshot(String key){return CompletableFuture.runAsync(()->{synchronized(db){try(PreparedStatement s=db.prepareStatement("DELETE FROM documents WHERE kind='snapshot' AND id=?")){s.setString(1,key);s.executeUpdate();snapshots.remove(key);warehouse.remove(key);}catch(SQLException e){failure=e.getMessage();throw new CompletionException(e);}}},writer);}
    public void close() throws Exception {reader.shutdownNow();writer.shutdown();writer.awaitTermination(10,TimeUnit.SECONDS);synchronized(db){db.close();}}
}

package dev.superwy.core;

import java.util.*;

/** Private detached snapshots, indexed by item and chunk. Never exposes mutable stored values. */
public final class WarehouseIndex {
    private record Chunk(String world,String dimension,int x,int z){}
    private final Map<String,Model.Snapshot> snapshots=new HashMap<>();
    private final Map<Chunk,Set<String>> chunks=new HashMap<>();
    private final Map<String,Set<String>> items=new HashMap<>();
    private long revision;
    public synchronized long revision(){return revision;}
    public synchronized int size(){return snapshots.size();}
    private static Chunk chunk(Model.Snapshot s){return s.pos==null?null:new Chunk(s.world,s.dimension,Math.floorDiv(s.pos.x(),16),Math.floorDiv(s.pos.z(),16));}
    private static Set<String> itemIds(Model.Snapshot s){var ids=new HashSet<String>();for(var n:s.items){ids.add(n.itemId);for(var child:n.children)ids.add(child.itemId);}return ids;}
    public synchronized void put(Model.Snapshot value){
        remove(value.key);var s=copy(value);snapshots.put(s.key,s);
        var chunk=chunk(s);if(chunk!=null)chunks.computeIfAbsent(chunk,k->new HashSet<>()).add(s.key);
        for(String item:itemIds(s))items.computeIfAbsent(item,k->new HashSet<>()).add(s.key);
        revision++;
    }
    public synchronized void remove(String key){
        var old=snapshots.remove(key);if(old==null)return;
        var chunk=chunk(old);if(chunk!=null)removeFrom(chunks,chunk,key);
        for(String item:itemIds(old))removeFrom(items,item,key);revision++;
    }
    private static <K> void removeFrom(Map<K,Set<String>> index,K key,String value){var set=index.get(key);if(set!=null){set.remove(value);if(set.isEmpty())index.remove(key);}}
    public Model.Snapshot get(String key){Model.Snapshot s;synchronized(this){s=snapshots.get(key);}return s==null?null:copy(s);}
    public List<Model.Snapshot> all(){List<Model.Snapshot> values;synchronized(this){values=List.copyOf(snapshots.values());}return values.stream().map(WarehouseIndex::copy).toList();}
    public List<Model.Snapshot> chunk(String world,String dimension,int x,int z){List<Model.Snapshot> values;synchronized(this){values=chunks.getOrDefault(new Chunk(world,dimension,x,z),Set.of()).stream().map(snapshots::get).toList();}return values.stream().map(WarehouseIndex::copy).toList();}
    public List<InventoryIndex.Source> sources(String world,String dimension,String item,String hash){
        // Inspect the private index first, so unrelated inventories/components are never copied.
        var matches=new ArrayList<Model.Snapshot>();
        synchronized(this){for(String key:items.getOrDefault(item,Set.of())){var s=snapshots.get(key);if(s.complete&&Objects.equals(s.world,world)&&Objects.equals(s.dimension,dimension)&&Model.storageType(s.type))matches.add(s);}}
        return InventoryIndex.sources(matches.stream().map(WarehouseIndex::copy).toList(),world,dimension,item,hash);
    }
    public static Model.Snapshot copy(Model.Snapshot s){
        var c=new Model.Snapshot();c.key=s.key;c.world=s.world;c.dimension=s.dimension;c.pos=s.pos;c.type=s.type;c.revision=s.revision;c.observedAt=s.observedAt;c.complete=s.complete;
        for(var n:s.items)c.items.add(copyNode(n));return c;
    }
    private static Model.ItemNode copyNode(Model.ItemNode n){
        var c=new Model.ItemNode();c.slot=n.slot;c.count=n.count;c.maxStackSize=n.maxStackSize;c.itemId=n.itemId;c.componentHash=n.componentHash;c.variantSummary=n.variantSummary;c.shulker=n.shulker;c.components=n.components==null?null:n.components.deepCopy();
        for(var child:n.children)c.children.add(copyNode(child));return c;
    }
}

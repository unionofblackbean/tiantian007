package dev.superwy.core;
import java.time.Instant;
import java.util.*;

public final class InventoryIndex {
    public record Source(Model.Snapshot snapshot,Model.ItemNode outer,Model.ItemNode item,boolean nested){}
    public static Comparator<Source> sourcePriority(Model.Pos position){
        return Comparator.<Source>comparingInt(s->s.snapshot.type.endsWith("shulker_box")?0:s.nested?1:2)
            .thenComparingDouble(s->s.snapshot.pos.distance(position)).thenComparing(Comparator.comparingLong((Source s)->s.snapshot.observedAt).reversed());
    }
    public static List<Source> sources(List<Model.Snapshot> snapshots,String world,String dimension,String itemId,String hash){
        List<Source> result=new ArrayList<>();
        for(Model.Snapshot s:snapshots)if(s.world.equals(world)&&s.dimension.equals(dimension)&&s.complete&&Model.storageType(s.type)){
            for(Model.ItemNode n:s.items){
                if(n.itemId.equals(itemId)&&(hash==null||n.componentHash.equals(hash)))result.add(new Source(s,n,n,false));
                for(Model.ItemNode child:n.children)if(child.itemId.equals(itemId)&&(hash==null||child.componentHash.equals(hash)))result.add(new Source(s,n,child,true));
            }
        }return result;
    }
    public static List<Map<String,Object>> aggregate(List<Model.Snapshot> snapshots,String world,String dimension){
        Map<String,Map<String,Object>> variants=new TreeMap<>();
        for(Model.Snapshot s:snapshots)if(s.world.equals(world)&&s.dimension.equals(dimension)&&Model.storageType(s.type))for(Model.ItemNode n:s.items){
            add(variants,n,s);for(Model.ItemNode child:n.children)add(variants,child,s);
        }
        return new ArrayList<>(variants.values());
    }
    private static void add(Map<String,Map<String,Object>> rows,Model.ItemNode n,Model.Snapshot s){
        String key=n.itemId+"|"+n.componentHash;
        var row=rows.computeIfAbsent(key,k->{Map<String,Object> r=new LinkedHashMap<>();r.put("itemId",n.itemId);r.put("componentHash",n.componentHash);r.put("variantSummary",n.variantSummary);r.put("maxStackSize",n.maxStackSize);r.put("observedCount",0L);r.put("reservableCount",0L);r.put("reservedCount",0L);r.put("availableCount",0L);r.put("stale",false);r.put("observedAt",Instant.ofEpochMilli(s.observedAt).toString());return r;});
        row.put("observedCount",(long)row.get("observedCount")+n.count);
        boolean usable=s.complete;
        row.put("stale",(boolean)row.get("stale")||!usable);
        if(usable&&Model.storageType(s.type)){row.put("reservableCount",(long)row.get("reservableCount")+n.count);row.put("availableCount",row.get("reservableCount"));}
        if(Instant.parse((String)row.get("observedAt")).toEpochMilli()<s.observedAt)row.put("observedAt",Instant.ofEpochMilli(s.observedAt).toString());
    }
    public static String unambiguousHash(List<Source> sources){var hashes=sources.stream().map(s->s.item.componentHash).distinct().toList();if(hashes.size()>1)throw new IllegalArgumentException("AMBIGUOUS_ITEM");return hashes.isEmpty()?null:hashes.getFirst();}
    public record CapacitySlot(String key,int count,int maxCount,boolean usable){}
    public static int capacity(String key,int max,List<CapacitySlot> slots,int reservedEmpty){
        long sum=0;int empty=0;
        for(var s:slots)if(s.usable){if(s.count==0)empty++;else if(Objects.equals(key,s.key))sum+=Math.max(0,Math.min(max,s.maxCount)-s.count);}
        sum+=(long)Math.max(0,empty-reservedEmpty)*max;return (int)Math.min(Integer.MAX_VALUE,sum);
    }
}

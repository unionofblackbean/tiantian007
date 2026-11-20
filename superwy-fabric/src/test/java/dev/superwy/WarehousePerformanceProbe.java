package dev.superwy;

import dev.superwy.core.*;
import java.util.*;

/** Repeatable synthetic CPU comparison, not a remote-server or FPS benchmark. */
public final class WarehousePerformanceProbe {
    private static volatile Object result;
    public static void main(String[] args){
        var json=new ArrayList<String>();var index=new WarehouseIndex();
        for(int i=0;i<6000;i++){
            var s=new Model.Snapshot();s.key="c"+i;s.world="w";s.dimension="d";s.type="minecraft:barrel";s.pos=new Model.Pos(i,70,0);s.complete=true;
            for(int slot=0;slot<27;slot++){var n=new Model.ItemNode();n.slot=slot;n.itemId=i%997==0?"minecraft:lever":"minecraft:stone";n.componentHash="hash";n.count=64;n.maxStackSize=64;n.variantSummary="Item";s.items.add(n);}
            json.add(Jsons.GSON.toJson(s));index.put(s);
        }
        for(int i=0;i<2;i++){oldQuery(json);newQuery(index);}
        long old=measure(()->oldQuery(json),5),fast=measure(()->newQuery(index),100);
        long common=measure(()->result=index.sources("w","d","minecraft:stone",null),10);
        var positions=new ArrayList<Model.Pos>();for(int x=-100;x<100;x++)for(int z=-100;z<100;z++)positions.add(new Model.Pos(x,70,z));
        var overlay=OverlayPositions.select(positions,new Model.Pos(0,70,0));
        System.out.printf(Locale.ROOT,"SUPERWY_PERFORMANCE: 6000 containers / 162000 stacks; old full JSON+rare source query %.3f ms; indexed rare query %.3f ms; indexed common query %.3f ms (worker); overlay 40000 queued -> %d markers + %d HUD positions; no idle inventory aggregation%n",old/1e6,fast/1e6,common/1e6,overlay.markers().size(),overlay.hud().size());
    }
    private static void oldQuery(List<String> json){result=InventoryIndex.sources(json.stream().map(j->Jsons.GSON.fromJson(j,Model.Snapshot.class)).toList(),"w","d","minecraft:lever",null);}
    private static void newQuery(WarehouseIndex index){result=index.sources("w","d","minecraft:lever",null);}
    private static long measure(Runnable action,int times){long start=System.nanoTime();for(int i=0;i<times;i++)action.run();return (System.nanoTime()-start)/times;}
}

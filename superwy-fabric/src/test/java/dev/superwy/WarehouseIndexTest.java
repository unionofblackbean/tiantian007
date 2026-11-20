package dev.superwy;

import dev.superwy.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseIndexTest {
    @TempDir Path dir;
    private Model.Snapshot snapshot(String key,int x,String item){
        var s=new Model.Snapshot();s.key=key;s.world="w";s.dimension="d";s.type="minecraft:barrel";s.pos=new Model.Pos(x,70,0);s.complete=true;
        var n=new Model.ItemNode();n.itemId=item;n.componentHash="hash";n.count=7;n.maxStackSize=64;s.items.add(n);return s;
    }
    @Test void replacementRemovalAndNegativeChunksRemainConsistent(){
        var index=new WarehouseIndex();index.put(snapshot("a",-1,"minecraft:stone"));index.put(snapshot("b",16,"minecraft:iron_ingot"));
        assertEquals("a",index.chunk("w","d",-1,0).getFirst().key);assertEquals(1,index.sources("w","d","minecraft:stone",null).size());
        long revision=index.revision();index.put(snapshot("a",32,"minecraft:dirt"));assertTrue(index.revision()>revision);
        assertTrue(index.chunk("w","d",-1,0).isEmpty());assertTrue(index.sources("w","d","minecraft:stone",null).isEmpty());
        assertEquals("a",index.sources("w","d","minecraft:dirt",null).getFirst().snapshot().key);
        index.remove("a");assertTrue(index.chunk("w","d",2,0).isEmpty());assertEquals(1,index.size());
    }
    @Test void inputsAndReturnedSnapshotsCannotMutatePrivateIndex(){
        var index=new WarehouseIndex();var s=snapshot("a",0,"minecraft:stone");index.put(s);s.items.clear();
        index.get("a").items.clear();index.all().getFirst().complete=false;index.chunk("w","d",0,0).getFirst().items.getFirst().count=100;
        index.sources("w","d","minecraft:stone",null).getFirst().item().count=100;
        assertEquals(7,index.get("a").items.getFirst().count);assertTrue(index.get("a").complete);
    }
    @Test void nestedIndexRetainsVariantsWorldAndCompletionFiltering(){
        var index=new WarehouseIndex();var s=snapshot("a",0,"minecraft:shulker_box");var inner=new Model.ItemNode();inner.itemId="minecraft:gold_ingot";inner.componentHash="gold";inner.count=4;inner.slot=19;s.items.getFirst().children.add(inner);index.put(s);
        assertEquals(19,index.sources("w","d","minecraft:gold_ingot","gold").getFirst().item().slot);
        assertTrue(index.sources("w","other","minecraft:gold_ingot",null).isEmpty());assertTrue(index.sources("w","d","minecraft:gold_ingot","other").isEmpty());
        s.complete=false;index.put(s);assertTrue(index.sources("w","d","minecraft:gold_ingot",null).isEmpty());
    }
    @Test void asynchronousSnapshotWritesCaptureInputsAndPreserveSubmissionOrder()throws Exception{
        try(var store=new Store(dir.resolve("index.db"))){
            var s=snapshot("a",0,"minecraft:stone");var first=store.saveSnapshot(s);s.items.getFirst().count=3;var second=store.saveSnapshot(s);s.items.clear();
            CompletableFuture.allOf(first,second).join();assertEquals(3,store.snapshot("a").items.getFirst().count);
            var thread=new java.util.concurrent.atomic.AtomicReference<String>();store.sourcesAsync("w","d","minecraft:stone",null,source->{thread.set(Thread.currentThread().getName());return true;}).join();assertEquals("superwy-stock-query",thread.get());
            var progress=ScanProgress.start(new Model.Region("r","w","d",new Model.Pos(0,70,0),new Model.Pos(31,70,0)));
            store.saveScanBatch(List.of(snapshot("b",16,"minecraft:iron_ingot")),List.of("a"),progress).join();
            assertTrue(store.sources("w","d","minecraft:stone",null).isEmpty());assertEquals(1,store.chunkSnapshots("w","d",1,0).size());
        }
        try(var store=new Store(dir.resolve("index.db"))){assertEquals(7,store.sources("w","d","minecraft:iron_ingot",null).getFirst().item().count);store.deleteSnapshot("b").join();assertTrue(store.sources("w","d","minecraft:iron_ingot",null).isEmpty());}
    }
    @Test void fifoLookupDropsTerminalOrdersAndDoesNotLeakMutableInstances()throws Exception{
        try(var store=new Store(dir.resolve("queue.db"))){var r=new Model.Request();r.delivery=new Model.DeliveryRequest();r.delivery.fakePlayerName="sf";var line=new Model.RequestLine();line.itemId="minecraft:stone";line.count=1;r.items.add(line);
            var a=store.accept("alice","one",r).order();var b=store.accept("bob","two",r).order();
            assertEquals(a.orderId,store.nextQueued("test-world","minecraft:overworld").orderId);
            store.nextQueued("test-world","minecraft:overworld").state="failed";assertEquals(a.orderId,store.nextQueued("test-world","minecraft:overworld").orderId);
            a.state("fetching",null);store.saveOrder(a).join();assertEquals(b.orderId,store.nextQueued("test-world","minecraft:overworld").orderId);
            b.state("cancelled",null);store.saveOrder(b).join();assertNull(store.nextQueued("test-world","minecraft:overworld"));
        }
    }
    @Test void denseOverlayIsBoundedAndKeepsNearestHudCoordinates(){
        var positions=new ArrayList<Model.Pos>();for(int x=-100;x<100;x++)for(int z=-100;z<100;z++)positions.add(new Model.Pos(x,70,z));
        var view=OverlayPositions.select(positions,new Model.Pos(0,70,0));assertEquals(128,view.markers().size());assertEquals(6,view.hud().size());assertEquals(new Model.Pos(0,70,0),view.hud().getFirst());
        assertTrue(view.markers().stream().allMatch(p->p.distance(new Model.Pos(0,70,0))<=128));assertEquals(40000,positions.size());
    }
}

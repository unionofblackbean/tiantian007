package dev.superwy;

import dev.superwy.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScanProtocolTest {
    @TempDir Path dir;
    @Test void oldConfigsDefaultToF3AndManualSelectionSurvivesRestart()throws Exception{
        var path=dir.resolve("config.json");var config=Config.load(path);assertEquals("f3",config.scanProtocol);
        var json=Jsons.GSON.toJsonTree(config).getAsJsonObject();json.remove("scanProtocol");Files.writeString(path,json.toString());assertEquals("f3",Config.load(path).scanProtocol);
        config.scanProtocol="servux";config.save(path);assertEquals("servux",Config.load(path).scanProtocol);
        config.scanProtocol="other";config.save(path);assertThrows(IllegalArgumentException.class,()->Config.load(path));
    }
    @Test void legacyHopperAndFurnaceStockCannotAppearInWarehouseInventory(){
        var snapshots=new ArrayList<Model.Snapshot>();
        for(String type:List.of("chest","trapped_chest","barrel","shulker_box","purple_shulker_box","hopper","furnace","dropper","decorated_pot")){
            var s=new Model.Snapshot();s.key=type;s.world="w";s.dimension="d";s.type="minecraft:"+type;s.complete=true;
            var item=new Model.ItemNode();item.itemId="minecraft:stone";item.componentHash="stone";item.count=1;s.items.add(item);snapshots.add(s);
        }
        assertEquals(5L,InventoryIndex.aggregate(snapshots,"w","d").getFirst().get("observedCount"));assertEquals(5,InventoryIndex.sources(snapshots,"w","d","minecraft:stone",null).size());
    }
    @Test void bulkSnapshotAndCheckpointRemainConsistentAfterReopeningDatabase()throws Exception{
        var path=dir.resolve("warehouse.db");var region=new Model.Region("r","w","d",new Model.Pos(0,0,0),new Model.Pos(15,10,15));var progress=ScanProgress.start(region);progress.chunks.clear();progress.processed=2;
        var one=new Model.Snapshot();one.key="one";one.type="minecraft:barrel";var two=new Model.Snapshot();two.key="two";two.type="minecraft:chest";
        try(var store=new Store(path)){var hopper=new Model.Snapshot();hopper.key="hopper";hopper.type="minecraft:hopper";store.saveSnapshot(hopper).join();store.saveScanBatch(List.of(one,two),List.of("hopper"),progress).join();}
        try(var store=new Store(path)){assertEquals(Set.of("one","two"),new HashSet<>(store.snapshots().stream().map(s->s.key).toList()));assertEquals(2,store.scan("r").processed);assertTrue(store.scan("r").chunks.isEmpty());}
    }
}

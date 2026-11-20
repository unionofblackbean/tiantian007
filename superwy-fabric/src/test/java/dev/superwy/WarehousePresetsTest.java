package dev.superwy;

import dev.superwy.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class WarehousePresetsTest {
    @TempDir Path dir;
    @Test void seedsOnlyMatchingWorldAndPersistsWithoutImportingOldTimestamps()throws Exception{
        var c=new Config();try(var s=new Store(dir.resolve("db"))){
            assertEquals(0,WarehousePresets.apply(c,s,"another#default"));assertTrue(s.regions().isEmpty());
            assertEquals(2,WarehousePresets.apply(c,s,"bbbb.icu#default"));
            var bh=s.regions().stream().filter(r->r.id.equals("bh")).findFirst().orElseThrow();assertEquals(new Model.Pos(78,49,-89),bh.min);assertEquals(new Model.Pos(117,53,-6),bh.max);
            assertTrue(s.regions().stream().allMatch(r->r.scanPaused&&r.lastScanAt==0&&r.dimension.equals("minecraft:the_end")));
            s.deleteRegion("bh").join();c.save(dir.resolve("config.json"));
        }
        try(var s=new Store(dir.resolve("db"))){assertEquals(0,WarehousePresets.apply(Config.load(dir.resolve("config.json")),s,"bbbb.icu#default"));assertEquals(1,s.regions().size());}
    }
    @Test void existingRegionAndCheckpointRemainUntouched()throws Exception{
        var c=new Config();try(var s=new Store(dir.resolve("db"))){
            var r=new Model.Region("bh","bbbb.icu#default","minecraft:the_end",new Model.Pos(0,0,0),new Model.Pos(1,1,1));r.lastScanAt=123;s.saveRegion(r).join();var progress=ScanProgress.start(r);progress.processed=7;s.saveScan(progress).join();
            assertEquals(1,WarehousePresets.apply(c,s,r.world));assertEquals(123,s.regions().stream().filter(old->old.id.equals("bh")).findFirst().orElseThrow().lastScanAt);assertEquals(7,s.scan("bh").processed);
        }
    }
}

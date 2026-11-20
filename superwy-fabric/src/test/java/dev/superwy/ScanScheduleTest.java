package dev.superwy;

import dev.superwy.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ScanScheduleTest {
    @TempDir Path dir;
    private Model.Region region(){return new Model.Region("r","world","dimension",new Model.Pos(0,0,0),new Model.Pos(2,3,4));}
    @Test void hourlyDeadlineUsesDurableCompletionTimestamp(){var r=region();r.lastScanAt=1700000000123L;var p=ScanProgress.start(r);p.finished=true;p.nextAttemptAt=7;assertEquals(r.lastScanAt+3600000,ScanSchedule.nextDue(r,p));assertEquals(ScanSchedule.nextDue(r,p),ScanSchedule.nextDue(r,p));}
    @Test void unfinishedWorkResumesAndNeverScannedRegionIsDue(){var r=region();assertEquals(0,ScanSchedule.nextDue(r,null));r.lastScanAt=1700000000123L;assertEquals(0,ScanSchedule.nextDue(r,ScanProgress.start(r)));}
    @Test void legacySuccessTimestampAndCustomIntervalAreSupported(){var r=region();r.lastSuccessAt=200000;r.intervalSeconds=7200;assertEquals(7400000,ScanSchedule.nextDue(r,null));r.lastScanAt=300000;assertEquals(7500000,ScanSchedule.nextDue(r,null));}
    @Test void servuxUsesTenMinutesAndSwitchingBackPreservesF3Interval(){var r=region();r.intervalSeconds=7200;r.lastScanAt=1700000000123L;var p=ScanProgress.start(r);p.finished=true;p.nextAttemptAt=r.lastScanAt+7200000;assertEquals(600,ScanSchedule.intervalSeconds(r,"servux"));assertEquals(r.lastScanAt+600000,ScanSchedule.nextDue(r,p,"servux"));assertEquals(r.lastScanAt+7200000,ScanSchedule.nextDue(r,p,"f3"));assertEquals(7200,r.intervalSeconds);p.finished=false;assertEquals(0,ScanSchedule.nextDue(r,p,"servux"));}
    @Test void servuxDeadlineIsRecomputedAfterConfigAndDatabaseRestart()throws Exception{var file=dir.resolve("servux.db");var configPath=dir.resolve("servux.json");var c=Config.load(configPath);c.scanProtocol="servux";c.save(configPath);try(var store=new Store(file)){var r=region();r.lastScanAt=1700000000123L;r.scanPaused=true;store.saveRegion(r).join();var p=ScanProgress.start(r);p.finished=true;store.saveScan(p).join();}try(var store=new Store(file)){var r=store.regions().getFirst();assertEquals(r.lastScanAt+600000,ScanSchedule.nextDue(r,store.scan(r.id),Config.load(configPath).scanProtocol));assertEquals(3600,r.intervalSeconds);assertTrue(r.scanPaused);}}
    @Test void upgradePreservesTimestampsFailuresAndUnfinishedPositions()throws Exception{
        try(var store=new Store(dir.resolve("db"))){var r=region();r.intervalSeconds=600;r.lastScanAt=123456789;store.saveRegion(r).join();var p=ScanProgress.start(r);p.chunks.clear();p.containers.add(new Model.Pos(1,2,3));p.processed=4;p.skipContainer(new Model.Pos(2,2,3),"UNSUPPORTED_CONTAINER");store.saveScan(p).join();var config=new Config();config.scanEnabled=false;ScanSchedule.upgrade(config,store);assertEquals(3600,store.regions().getFirst().intervalSeconds);assertEquals(r.lastScanAt,store.regions().getFirst().lastScanAt);var loaded=store.scan("r");assertEquals(List.copyOf(p.containers),List.copyOf(loaded.containers));assertEquals(4,loaded.processed);assertEquals(1,loaded.skippedContainers.size());assertFalse(config.scanEnabled);assertEquals(1,config.scanPolicyVersion);}
    }
    @Test void upgradeIsIdempotentAndCompletedDeadlineSurvivesRestart()throws Exception{
        var config=new Config();Path file=dir.resolve("db");
        try(var store=new Store(file)){var r=region();r.intervalSeconds=600;r.lastScanAt=1700000000123L;store.saveRegion(r).join();var p=ScanProgress.start(r);p.finished=true;p.chunks.clear();p.nextAttemptAt=r.lastScanAt+600000;store.saveScan(p).join();ScanSchedule.upgrade(config,store);assertEquals(r.lastScanAt+3600000,store.scan("r").nextAttemptAt);r=store.regions().getFirst();r.intervalSeconds=7200;store.saveRegion(r).join();ScanSchedule.upgrade(config,store);assertEquals(7200,store.regions().getFirst().intervalSeconds);}
        try(var store=new Store(file)){var r=store.regions().getFirst();assertEquals(r.lastScanAt+7200000,ScanSchedule.nextDue(r,store.scan("r")));}
    }
    @Test void scanSwitchSurvivesRestartSeparatelyFromExecutor()throws Exception{Path file=dir.resolve("config.json");var c=Config.load(file);c.scanEnabled=false;c.automationEnabled=true;c.save(file);var loaded=Config.load(file);assertFalse(loaded.scanEnabled);assertTrue(loaded.automationEnabled);assertFalse(loaded.manualControl);}
    @Test void nestedSnapshotsAndRealObservationTimestampsSurviveRestartAndReplacement()throws Exception{
        Path file=dir.resolve("db");long old=System.currentTimeMillis()-30L*86400000;var snapshot=new Model.Snapshot();snapshot.key="box";snapshot.world="world";snapshot.dimension="dimension";snapshot.type="minecraft:barrel";snapshot.pos=new Model.Pos(1,2,3);snapshot.complete=true;snapshot.observedAt=old;snapshot.revision=1;
        var box=new Model.ItemNode();box.itemId="minecraft:shulker_box";box.componentHash="box";box.count=1;box.maxStackSize=1;var item=new Model.ItemNode();item.itemId="minecraft:stone";item.componentHash="stone";item.count=21;item.maxStackSize=64;box.children.add(item);snapshot.items.add(box);
        try(var store=new Store(file)){store.saveSnapshot(snapshot).join();}
        long updated=System.currentTimeMillis();
        try(var store=new Store(file)){var loaded=store.snapshots().getFirst();assertEquals(old,loaded.observedAt);assertEquals(21,loaded.items.getFirst().children.getFirst().count);var row=InventoryIndex.aggregate(store.snapshots(),"world","dimension").stream().filter(r->r.get("itemId").equals("minecraft:stone")).findFirst().orElseThrow();assertEquals(21L,row.get("availableCount"));loaded.items.getFirst().children.getFirst().count=17;loaded.observedAt=updated;loaded.revision++;store.saveSnapshot(loaded).join();}
        try(var store=new Store(file)){var loaded=store.snapshots().getFirst();assertEquals(updated,loaded.observedAt);assertEquals(17,loaded.items.getFirst().children.getFirst().count);}
    }
}

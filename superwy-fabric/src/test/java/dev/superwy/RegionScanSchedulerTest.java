package dev.superwy;

import dev.superwy.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RegionScanSchedulerTest {
    @TempDir Path dir;
    @Test void newRegionRunsOnNextStepWithoutWaitingForOldRegionToComplete(){
        var s=new RegionScanScheduler();s.start("a");assertEquals("a",s.next());s.start("b");
        assertEquals(List.of("b","a","b","a"),List.of(s.next(),s.next(),s.next(),s.next()));
        assertTrue(s.contains("a"));assertTrue(s.contains("b"));
    }
    @Test void stoppingOneRegionDoesNotStopOthersAndRepeatedStartDoesNotDuplicateWork(){
        var s=new RegionScanScheduler();s.add("a");s.add("b");s.add("c");s.start("b");s.start("b");
        assertEquals("a",s.next());s.stop("b");assertEquals("c",s.next());assertEquals("a",s.next());
        s.stop("a");assertEquals("c",s.next());s.stop("c");assertNull(s.next());assertTrue(s.isEmpty());
    }
    @Test void individualStopAndCheckpointSurviveRestartWithoutDisablingHistoricalInventory()throws Exception{
        Path file=dir.resolve("regions.db");var r=new Model.Region("a","w","d",new Model.Pos(0,0,0),new Model.Pos(4,4,4));r.scanPaused=true;
        var p=ScanProgress.start(r);p.chunks.clear();p.containers.add(new Model.Pos(1,2,3));p.processed=7;
        try(var store=new Store(file)){store.saveRegion(r).join();store.saveScan(p).join();}
        try(var store=new Store(file)){var saved=store.regions().getFirst();assertTrue(saved.scanPaused);assertTrue(saved.enabled);assertEquals(7,store.scan("a").processed);assertEquals(new Model.Pos(1,2,3),store.scan("a").containers.peek());assertTrue(store.scan("a").matches(saved));}
    }
}

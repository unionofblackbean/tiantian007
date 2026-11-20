package dev.superwy;
import dev.superwy.core.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class ScanProgressTest {
    @TempDir Path dir;
    private Model.Region region(){return new Model.Region("r","world","dim",new Model.Pos(-17,0,-1),new Model.Pos(16,20,16));}
    @Test void chunkOrderIncludesNegativeCoordinates(){var p=ScanProgress.start(region());assertEquals(12,p.chunks.size());assertEquals(new Model.Pos(-2,0,-1),p.chunks.getFirst());assertEquals(new Model.Pos(1,0,1),p.chunks.getLast());}
    @Test void restartKeepsNextUnscannedAndAcknowledgedWork()throws Exception{
        Path file=dir.resolve("db");var p=ScanProgress.start(region());p.chunks.removeFirst();p.coveredChunks++;
        var first=new Model.Pos(1,2,3);var next=new Model.Pos(4,5,6);p.containers.add(first);p.containers.add(next);
        try(var store=new Store(file)){store.saveScan(p).join();p.containers.removeFirst();p.processed++;store.saveScan(p).join();}
        try(var store=new Store(file)){var loaded=store.scan("r");assertEquals(1,loaded.processed);assertEquals(next,loaded.containers.getFirst());assertFalse(loaded.containers.contains(first));assertEquals(11,loaded.chunks.size());}
    }
    @Test void inFlightPeekDoesNotAcknowledgeWork()throws Exception{try(var store=new Store(dir.resolve("db"))){var p=ScanProgress.start(region());store.saveScan(p).join();p.chunks.removeFirst();assertEquals(12,store.scan("r").chunks.size());}}
    @Test void oldFailedPositionsMigrateToSkipsWithoutRetry()throws Exception{
        Path file=dir.resolve("db");var p=ScanProgress.start(region());var chunk=p.chunks.removeFirst();var container=new Model.Pos(2,4,6);p.retryChunks.add(chunk);p.retryContainers.add(container);
        try(var store=new Store(file)){store.saveScan(p).join();}
        try(var store=new Store(file)){var loaded=store.scan("r");loaded.resume();loaded.resume();assertEquals(11,loaded.chunks.size());assertTrue(loaded.containers.isEmpty());assertTrue(loaded.skippedContainer(container));assertEquals(1,loaded.skippedChunks.size());assertTrue(loaded.retryContainers.isEmpty());assertFalse(loaded.complete());}
    }
    @Test void eightyLegacyFailuresDoNotRemainUnscanned(){
        var p=ScanProgress.start(region());p.chunks.clear();
        for(int i=0;i<80;i++)p.retryContainers.add(new Model.Pos(i,2,3));
        p.resume();assertTrue(p.exhausted());assertTrue(p.containers.isEmpty());assertEquals(80,p.skippedContainers.size());assertFalse(p.complete());
    }
    @Test void skippedPositionsSurviveRestartAndSubsequentRounds()throws Exception{
        var r=region();var p=ScanProgress.start(r);var bad=new Model.Pos(1,2,3);p.containers.add(bad);p.skipContainer(bad,"UNSUPPORTED_CONTAINER");p.skipChunk(new Model.Pos(-2,0,-1),"UNLOADED");p.resume();p.finished=true;Path file=dir.resolve("db");
        try(var store=new Store(file)){store.saveScan(p).join();}
        try(var store=new Store(file)){var loaded=store.scan("r");var next=loaded.nextRound(r);assertTrue(next.skippedContainer(bad));assertTrue(next.skippedChunkContaining(new Model.Pos(-17,12,-1)));assertFalse(next.chunks.contains(new Model.Pos(-2,0,-1)));assertEquals(11,next.chunks.size());assertFalse(next.finished);assertEquals("UNSUPPORTED_CONTAINER",next.skippedContainers.values().iterator().next().reason());}
    }
    @Test void resumeOnlyDiscardsFailedWork(){var p=ScanProgress.start(region());var bad=new Model.Pos(1,2,3);var good=new Model.Pos(2,2,3);p.containers.add(bad);p.containers.add(good);p.skipContainer(bad,"TIMEOUT");p.resume();assertEquals(List.of(good),List.copyOf(p.containers));assertEquals(12,p.chunks.size());}
    @Test void resetCreatesFreshWorkWithoutPermanentSkips(){var r=region();var p=ScanProgress.start(r);p.skipContainer(new Model.Pos(0,0,0),"FAILED");p=ScanProgress.start(r);assertTrue(p.skippedContainers.isEmpty());assertEquals(12,p.chunks.size());}
    @Test void worldDimensionAndBoundsInvalidateCheckpoint(){var r=region();var p=ScanProgress.start(r);r.intervalSeconds=900;assertTrue(p.matches(r));r.world="other";assertFalse(p.matches(r));r=region();r.dimension="other";assertFalse(p.matches(r));r=region();r.max=new Model.Pos(17,20,16);assertFalse(p.matches(r));}
    @Test void deletingRegionRemovesCheckpoint()throws Exception{try(var store=new Store(dir.resolve("db"))){store.saveRegion(region()).join();store.saveScan(ScanProgress.start(region())).join();store.deleteRegion("r").join();assertNull(store.scan("r"));assertTrue(store.regions().isEmpty());}}
    @Test void finishingAndDeletingCannotResurrectRegion()throws Exception{
        Path file=dir.resolve("db");
        try(var store=new Store(file)){var r=region();var p=ScanProgress.start(r);p.finished=true;p.chunks.clear();r.lastScanAt=1234;var finish=store.finishScan(p,r);var delete=store.deleteRegion(r.id);finish.join();delete.join();assertNull(store.scan(r.id));assertTrue(store.regions().isEmpty());}
        try(var store=new Store(file)){assertNull(store.scan("r"));assertTrue(store.regions().isEmpty());}
    }
}

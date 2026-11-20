package dev.superwy;

import dev.superwy.mesh.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MeshSchedulerTest {
    private long now=1_000_000;private MeshSettings settings;private MeshScheduler s;
    @BeforeEach void setup(){settings=new MeshSettings();s=new MeshScheduler(settings,()->now);}
    private static MeshScheduler.Job job(String id,boolean batch,boolean packing,int...counts){
        var j=new MeshScheduler.Job();j.orderId=id;j.batch=batch;j.packing=packing;j.recipient="sf";
        for(int i=0;i<counts.length;i++){var l=new MeshScheduler.JobLine();l.lineIndex=i;l.itemId="minecraft:stone";l.requested=counts[i];l.maxStack=64;j.lines.add(l);}
        return j;
    }
    private MeshScheduler.Reply beat(String robot,MeshScheduler.ChunkReport...chunks){
        var r=new MeshScheduler.Report();r.robot=robot;r.ready=true;r.freeSlots=27;r.chunks.addAll(List.of(chunks));return s.heartbeat(r);
    }
    private static MeshScheduler.ChunkReport progress(MeshScheduler.Chunk c,String state,boolean terminal,int...delivered){
        var cr=new MeshScheduler.ChunkReport();cr.chunkId=c.chunkId;cr.state=state;cr.terminal=terminal;cr.running=true;
        for(int d:delivered){var l=new MeshScheduler.LineReport();l.delivered=d;l.fetched=d;cr.lines.add(l);}
        return cr;
    }
    private static int units(MeshScheduler.Chunk c){return c.lines.stream().mapToInt(l->l.count).sum();}

    @Test void simpleJobsGoFirstToIdleRobots(){
        s.submit(job("batch",true,false,128,64));s.submit(job("simple",false,false,10));
        var a=beat("A").assign;assertEquals("simple",a.orderId);assertFalse(a.batch);
        var b=beat("B").assign;assertEquals("batch",b.orderId);assertEquals(192,units(b));
        assertNull(beat("A",progress(a,"fetching",false,0)).assign,"a robot holding a block gets nothing new");
    }
    @Test void batchesQueueInOrderAndReportWhatIsAhead(){
        var first=job("b1",true,false,64);var second=job("b2",true,false,64);var simple1=job("s1",false,false,1);var simple2=job("s2",false,false,1);
        s.submit(first);s.submit(second);s.submit(simple1);s.submit(simple2);
        assertEquals(0,s.ahead(first));assertEquals(1,s.ahead(second));assertEquals(0,s.ahead(simple1));assertEquals(1,s.ahead(simple2));
        assertEquals("s1",beat("A").assign.orderId);assertEquals(0,s.ahead(simple2));
        assertEquals("s2",beat("B").assign.orderId);
        assertEquals("b1",beat("C").assign.orderId);assertEquals(1,s.ahead(second),"the second batch waits while the first is open");
    }
    @Test void packingBatchesAreCutLazilyIntoWholeBoxBlocks(){
        s.submit(job("big",true,true,64*100));
        var a=beat("A").assign;var b=beat("B").assign;var c=beat("C").assign;
        assertEquals(54*64,units(a));assertEquals(46*64,units(b));assertNull(c);assertTrue(a.packing);
    }
    @Test void simpleJobJumpsAheadAtTheNextBlockBoundary(){
        s.submit(job("big",true,true,64*200));
        var block=beat("A").assign;
        s.submit(job("simple",false,false,5));
        var reply=beat("A",progress(block,"stored_offline",true,block.lines.getFirst().count));
        assertTrue(reply.forget.contains(block.chunkId));assertEquals("simple",reply.assign.orderId);
    }
    @Test void offlineRobotKeepsItsBlockForTwoMinutesThenOthersTakeTheRemainder(){
        var j=job("big",true,true,640);s.submit(j);
        var block=beat("A").assign;beat("A",progress(block,"fetching",false,64));
        now+=20_000;s.tick();assertEquals(0,s.remaining(j),"still within the wait");
        now+=100_000;s.tick();assertEquals(0,s.remaining(j));
        now+=21_000;s.tick();assertEquals(576,s.remaining(j),"undelivered part handed back after 2 minutes");
        var other=beat("B").assign;assertEquals(576,units(other));
        // A comes back: it only delivers what it still carries.
        var cr=progress(block,"failed",true,64);cr.interrupted=true;cr.resumable=true;
        var reply=beat("A",cr);assertEquals(1,reply.resume.size());assertTrue(reply.resume.getFirst().heldOnly);
    }
    @Test void robotBackWithinTheWaitContinuesTheWholeBlock(){
        var j=job("big",true,true,640);s.submit(j);var block=beat("A").assign;
        now+=60_000;s.tick();
        var cr=progress(block,"failed",true,0);cr.interrupted=true;cr.resumable=true;
        var reply=beat("A",cr);assertFalse(reply.resume.getFirst().heldOnly);assertEquals(0,s.remaining(j));
    }
    @Test void unsafeInterruptionHandsTheRemainderToOthers(){
        var j=job("big",true,true,640);s.submit(j);var block=beat("A").assign;
        var cr=progress(block,"failed",true,128);cr.interrupted=true;cr.resumable=false;
        var reply=beat("A",cr);assertTrue(reply.forget.contains(block.chunkId));assertTrue(reply.resume.isEmpty());
        // The undelivered 512 went back to the pool; the same (now idle) robot takes it as a fresh block.
        assertEquals(512,units(reply.assign));assertEquals(0,s.remaining(j));
    }
    @Test void recipientLockIsExclusiveAndCaseInsensitive(){
        var a=new MeshScheduler.Report();a.robot="A";a.lockWanted="sf";assertTrue(s.heartbeat(a).lockGranted);
        var b=new MeshScheduler.Report();b.robot="B";b.lockWanted="SF";var denied=s.heartbeat(b);assertFalse(denied.lockGranted);assertEquals("A",denied.lockHolder);
        a.lockWanted=null;s.heartbeat(a);assertTrue(s.heartbeat(b).lockGranted);
    }
    @Test void lockOfAnOfflineRobotIsReleased(){
        var a=new MeshScheduler.Report();a.robot="A";a.lockWanted="sf";s.heartbeat(a);
        now+=16_000;var b=new MeshScheduler.Report();b.robot="B";b.lockWanted="sf";assertTrue(s.heartbeat(b).lockGranted);
    }
    @Test void cancelStopsRunningBlocksAndHandsOutNothingMore(){
        var j=job("big",true,true,64*100);s.submit(j);var block=beat("A").assign;
        s.cancel("big");assertTrue(beat("A",progress(block,"fetching",false,0)).cancel.contains(block.chunkId));
        assertNull(beat("B").assign);
    }
    @Test void finishedJobAggregatesShortfallAndOtherRobotsAvoidBusyContainers(){
        var j=job("one",true,false,100);s.submit(j);var block=beat("A").assign;
        var r=new MeshScheduler.Report();r.robot="B";r.using="minecraft:overworld|1,2,3";s.heartbeat(r);
        var reply=beat("A",progress(block,"partial_stored_offline",true,60));
        assertTrue(reply.avoid.contains("minecraft:overworld|1,2,3"));
        s.tick();assertEquals("done",j.state);assertEquals(60,j.lines.getFirst().delivered);assertEquals(40,j.lines.getFirst().shortfall);
        s.prune("one");assertFalse(s.has("one"));
    }
    @Test void assignmentIsResentUntilTheRobotAcknowledgesIt(){
        s.submit(job("one",false,false,1));var first=beat("A").assign;
        assertEquals(first.chunkId,beat("A").assign.chunkId);
        assertNull(beat("A",progress(first,"fetching",false,0)).assign);
    }
    @Test void stateSurvivesSaveAndLoad(){
        s.submit(job("one",true,true,64*80));var block=beat("A").assign;
        var json=dev.superwy.core.Jsons.GSON.toJson(s.state());
        var restored=new MeshScheduler(settings,()->now);restored.load(dev.superwy.core.Jsons.GSON.fromJson(json,MeshScheduler.State.class));
        var r=new MeshScheduler.Report();r.robot="A";r.ready=true;r.freeSlots=27;r.chunks.add(progress(block,"fetching",false,0));
        assertNull(restored.heartbeat(r).assign);assertEquals(26*64,restored.remaining(restored.job("one")));
    }
    @Test void managementActions(){
        var first=job("b1",true,false,64);var second=job("b2",true,false,64);s.submit(first);s.submit(second);
        assertTrue(s.prioritize("b2"));assertEquals(0,s.ahead(second));assertEquals(1,s.ahead(first));assertEquals("b2",beat("A").assign.orderId);
        var big=job("big",true,true,640);s.submit(big);var block=beat("B").assign;
        assertFalse(s.reassignNow(block.chunkId),"an online robot keeps its block");assertFalse(s.forgetRobot("B"));
        now+=20_000;assertTrue(s.reassignNow(block.chunkId));assertTrue(s.forgetRobot("B"));
        assertTrue(s.events().stream().anyMatch(e->e.contains("改派")));
        var a=new MeshScheduler.Report();a.robot="C";a.lockWanted="sf";s.heartbeat(a);assertEquals(1,s.locks().size());s.clearLocks();assertTrue(s.locks().isEmpty());
        assertEquals(3,s.jobViews().size());
    }
    @Test void staleRobotsAreForgottenAndEventsStayBounded(){
        for(int i=0;i<300;i++)beat("R"+i);
        assertTrue(s.events().size()<=200);
        now+=700_000;s.tick();assertTrue(s.robots().isEmpty());
    }
    @Test void robotErrorsHandTheRemainderOutAgainTwiceThenCountAsShort(){
        var j=job("one",true,false,64);s.submit(j);var block=beat("A").assign;
        for(int attempt=0;attempt<2;attempt++){
            var cr=progress(block,"failed",true,0);cr.errorCode="RECONCILIATION_REQUIRED";
            block=beat("A",cr).assign;assertNotNull(block,"handed out again after attempt "+attempt);assertEquals(64,units(block));
        }
        var cr=progress(block,"failed",true,10);cr.errorCode="RECONCILIATION_REQUIRED";assertNull(beat("A",cr).assign);
        assertEquals(0,s.remaining(j));s.tick();assertEquals("done",j.state);assertEquals(54,j.lines.getFirst().shortfall);
    }
    @Test void outOfStockLinesAreNotRetried(){
        var j=job("one",true,false,64);s.submit(j);var block=beat("A").assign;
        var cr=progress(block,"failed",true,0);cr.errorCode="NOTHING_DELIVERED";cr.lines.getFirst().skipped=true;cr.lines.getFirst().errorCode="OUT_OF_STOCK";
        beat("A",cr);assertEquals(0,s.remaining(j));
    }
}

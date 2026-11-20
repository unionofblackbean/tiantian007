package dev.superwy;

import dev.superwy.core.*;
import dev.superwy.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class BatchOrderTest {
    @TempDir Path dir;
    private Model.Request request(){var r=new Model.Request();r.delivery=new Model.DeliveryRequest();r.delivery.fakePlayerName="Cargo";var l=new Model.RequestLine();l.itemId="minecraft:stone";l.count=64;r.items.add(l);return r;}
    @Test void activeBatchAcceptsSingleAndBatchWithFifoAndDeferredCapacity()throws Exception{
        try(var store=new Store(dir.resolve("db"))){
            var first=request();first.batchId=WhisperProtocol.newId();var active=store.accept("alice","first",first).order();active.state("fetching",null);store.saveOrder(active).join();
            var single=store.accept("bob","single",request()).order();var next=request();next.batchId=WhisperProtocol.newId();var batch=store.accept("alice","batch",next).order();
            assertEquals(1,OrderQueue.position(single,store.pendingOrders()).ahead());assertEquals(2,OrderQueue.position(batch,store.pendingOrders()).ahead());assertTrue(batch.request.batchCapacityPending);
            assertTrue(store.accept("alice","batch",next).replay());assertEquals(3,store.pendingOrders().size());
            active.state("stored_offline",null);store.saveOrder(active).join();assertEquals(0,OrderQueue.position(single,store.pendingOrders()).ahead());assertEquals(1,OrderQueue.position(batch,store.pendingOrders()).ahead());
        }
    }
    @Test void capacityAndPrivateSystemFormats(){
        assertEquals(22,BatchProtocol.limit(27,36));assertEquals(7,BatchProtocol.limit(27,12));assertEquals(0,BatchProtocol.limit(4,36));
        String body=BatchProtocol.message(false,WhisperProtocol.newId(),"begin","Cargo","-");
        assertEquals("begin",BatchProtocol.parse(body,false).action());
        assertEquals(body,SystemWhisperFormat.parse("Alice悄悄地对你说："+body).body());
        assertNull(SystemWhisperFormat.parse("<Bob> Alice悄悄地对你说："+body));
        assertThrows(IllegalArgumentException.class,()->BatchProtocol.parse(body+"\nkill",false));
    }
    @Test void sameOwnerMayQueueFiveMixedTasksButLimitsStillApply()throws Exception{
        try(var store=new Store(dir.resolve("limits"))){for(int i=0;i<5;i++){var r=request();if(i%2==0)r.batchId=WhisperProtocol.newId();store.accept("alice","task-"+i,r);}assertEquals("QUEUE_FULL",assertThrows(IllegalArgumentException.class,()->store.accept("alice","overflow",request())).getMessage());assertDoesNotThrow(()->store.accept("bob","other",request()));}
    }
}

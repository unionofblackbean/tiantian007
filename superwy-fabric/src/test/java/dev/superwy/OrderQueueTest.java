package dev.superwy;
import dev.superwy.core.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderQueueTest {
    @TempDir Path dir;
    private Model.Request request(String name){var r=new Model.Request();r.delivery=new Model.DeliveryRequest();r.delivery.fakePlayerName=name;var l=new Model.RequestLine();l.itemId="minecraft:stone";l.count=1;r.items.add(l);return r;}
    @Test void backlogReportsEveryCompletionThroughZeroAndResets(){
        var context=new Model.Order("a",request("cargo"));var progress=new OrderQueue.Progress();
        progress.observe(context,3);assertNull(progress.completed(context,2));
        progress.observe(context,4);
        for(int remaining:new int[]{3,2,1})assertEquals("还有"+remaining+"个订单在队列",progress.completed(context,remaining));
        assertEquals("都取完了，我歇逼了",progress.completed(context,0));
        progress.observe(context,2);assertNull(progress.completed(context,1));assertNull(progress.completed(context,0));
    }
    @Test void arrivalsDuringAnOrderEnableReportsAndOtherQueuesDoNotInheritThem(){
        var context=new Model.Order("a",request("cargo"));var progress=new OrderQueue.Progress();
        progress.observe(context,1);progress.observe(context,4);assertEquals("还有3个订单在队列",progress.completed(context,3));
        var other=new Model.Order("b",request("other"));other.world="other-world";
        progress.observe(other,2);assertNull(progress.completed(other,1));
    }
    @Test void lastMomentArrivalsTriggerAndCancelledBacklogDoesNotLeakIntoNextBatch(){
        var context=new Model.Order("a",request("cargo"));var progress=new OrderQueue.Progress();
        progress.observe(context,1);assertEquals("还有3个订单在队列",progress.completed(context,3));
        progress.idle();progress.observe(context,1);assertNull(progress.completed(context,0));
    }
    @Test void remainingCountExcludesCurrentTerminalAndOtherQueues()throws Exception{
        try(var store=new Store(dir.resolve("counts.db"))){
            var current=store.accept("a","one",request("a")).order();var next=store.accept("b","two",request("b")).order();
            store.accept("c","world",request("c"),true,"other-world",current.dimension);
            store.accept("d","dimension",request("d"),true,current.world,"other-dimension");
            assertEquals(1,store.queuedCount(current));
            current.state("fetching",null);store.saveOrder(current).join();assertEquals(1,store.queuedCount(current));
            next.state("cancelled",null);store.saveOrder(next).join();assertEquals(0,store.queuedCount(current));
        }
    }
    @Test void concurrentPrincipalsGetUniqueDurableFifoNumbers()throws Exception{
        Path file=dir.resolve("db");List<String> accepted;
        try(var store=new Store(file);var pool=Executors.newFixedThreadPool(8)){
            var jobs=new ArrayList<Future<Store.Accepted>>();
            for(int i=0;i<24;i++){int n=i;jobs.add(pool.submit(()->store.accept("user"+n,"concurrent-"+n,request("cargo"+n))));}
            for(var job:jobs)job.get();var orders=store.orders();assertEquals(24,orders.size());
            for(int i=0;i<24;i++){assertEquals(i+1L,orders.get(i).queueSequence);var p=OrderQueue.position(orders.get(i),orders);assertEquals(i+1,p.position());assertEquals(i,p.ahead());}
            accepted=orders.stream().map(o->o.orderId).toList();
        }
        try(var store=new Store(file)){assertEquals(accepted,store.orders().stream().map(o->o.orderId).toList());assertEquals(25,store.accept("new","new-order",request("newcargo")).order().queueSequence);}
    }
    @Test void orderingIgnoresTimestampTiesAndClockChanges()throws Exception{try(var store=new Store(dir.resolve("db"))){var a=store.accept("a","first",request("a")).order();var b=store.accept("b","second",request("b")).order();a.createdAt="2099-01-01T00:00:00Z";b.createdAt="2000-01-01T00:00:00Z";store.saveOrder(a).join();store.saveOrder(b).join();assertEquals(a.orderId,store.orders().getFirst().orderId);assertEquals(2,OrderQueue.position(b,store.orders()).position());}}
    @Test void replayDoesNotConsumeAQueueNumberOrSecondSlot()throws Exception{try(var store=new Store(dir.resolve("db"))){var a=store.accept("a","same-key",request("a")).order();var replay=store.accept("a","same-key",request("a"));assertTrue(replay.replay());assertEquals(a.queueSequence,replay.order().queueSequence);assertEquals(2,store.accept("b","other-key",request("b")).order().queueSequence);}}
    @Test void terminalOrdersLeaveQueueAndWaitingHeadKeepsItsPlace()throws Exception{try(var store=new Store(dir.resolve("db"))){var a=store.accept("a","first",request("a")).order();var b=store.accept("b","second",request("b")).order();a.state("waiting_stock","OUT_OF_STOCK");store.saveOrder(a).join();assertTrue(OrderQueue.position(a,store.orders()).active());assertEquals(2,OrderQueue.position(b,store.orders()).position());a.state("cancelled",null);store.saveOrder(a).join();assertEquals(0,OrderQueue.position(a,store.orders()).position());assertEquals(1,OrderQueue.position(b,store.orders()).position());}}
    @Test void queuesAreIsolatedByWorldAndDimension()throws Exception{try(var store=new Store(dir.resolve("db"))){store.accept("a","one",request("a"),true,"world-a","dim-a");var b=store.accept("b","two",request("b"),true,"world-b","dim-a").order();store.accept("c","three",request("c"),true,"world-b","dim-b");assertEquals(1,OrderQueue.position(b,store.orders()).position());assertEquals(1,OrderQueue.position(b,store.orders()).totalPending());}}
    @Test void legacyDatabaseGetsStableNumbers()throws Exception{
        Path file=dir.resolve("legacy");Class.forName("org.sqlite.JDBC");
        try(var db=DriverManager.getConnection("jdbc:sqlite:"+file);var schema=db.createStatement()){
            schema.execute("CREATE TABLE documents(kind TEXT,id TEXT,json TEXT,PRIMARY KEY(kind,id))");
            for(String id:List.of("ord_z","ord_a")){var o=new Model.Order("legacy",request("cargo"));o.orderId=id;o.createdAt="2026-01-01T00:00:00Z";try(var s=db.prepareStatement("INSERT INTO documents VALUES('order',?,?)")){s.setString(1,id);s.setString(2,Jsons.GSON.toJson(o));s.executeUpdate();}}
        }
        try(var store=new Store(file)){assertEquals("ord_a",store.orders().getFirst().orderId);assertEquals(1,store.order("ord_a").queueSequence);assertEquals(2,store.order("ord_z").queueSequence);}
        try(var store=new Store(file)){assertEquals(1,store.order("ord_a").queueSequence);assertEquals(2,store.order("ord_z").queueSequence);}
    }
    @Test void perPrincipalLimitDoesNotBlockOtherPlayers()throws Exception{try(var store=new Store(dir.resolve("db"))){for(int i=0;i<5;i++)store.accept("a","a-"+i,request("a"));assertEquals("QUEUE_FULL",assertThrows(IllegalArgumentException.class,()->store.accept("a","sixth",request("a"))).getMessage());assertEquals(6,store.accept("b","b-first",request("b")).order().queueSequence);}}
}

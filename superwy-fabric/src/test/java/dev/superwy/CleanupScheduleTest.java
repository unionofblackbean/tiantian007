package dev.superwy;

import dev.superwy.core.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CleanupScheduleTest {
    @TempDir Path dir;
    private Model.Request request(){var r=new Model.Request();r.delivery=new Model.DeliveryRequest();r.delivery.fakePlayerName="sf";var line=new Model.RequestLine();line.itemId="minecraft:stone";line.count=1;r.items.add(line);return r;}
    @Test void fiftyRoundBoundaryPersistsAndOnlyVerifiedCleanupResetsIt()throws Exception{
        Path file=dir.resolve("cleanup.db");String last;
        try(Store store=new Store(file)){
            for(int i=1;i<=49;i++){var order=store.accept("a","round-"+i,request()).order();order.cleanupRoundCounted=true;order.state("stored_offline",null);store.saveOrder(order).join();assertFalse(CleanupSchedule.due(store.orders(),order));assertEquals(CleanupSchedule.pending(store.orders(),order),store.cleanupPending(order));}
        }
        try(Store store=new Store(file)){
            var order=store.accept("a","round-50",request()).order();last=order.orderId;
            assertEquals(49,CleanupSchedule.pending(store.orders(),order));assertEquals(49,store.cleanupPending(order),"SQLite count matches");
            order.cleanupRoundCounted=true;order.cleanupErrorCode="CLEANUP_STORAGE_FULL";order.state("stored_offline",null);store.saveOrder(order).join();
            assertTrue(CleanupSchedule.due(store.orders(),order));assertEquals(CleanupSchedule.pending(store.orders(),order),store.cleanupPending(order));
            // Re-saving the same order must not count the round twice.
            store.saveOrder(order).join();assertEquals(50,CleanupSchedule.pending(store.orders(),order));assertEquals(50,store.cleanupPending(order),"SQLite count matches");
        }
        try(Store store=new Store(file)){
            var order=store.order(last);assertTrue(CleanupSchedule.due(store.orders(),order));assertEquals(CleanupSchedule.pending(store.orders(),order),store.cleanupPending(order));
            order.cleanupErrorCode=null;order.cleanupCompleted=true;store.saveOrder(order).join();assertEquals(0,CleanupSchedule.pending(store.orders(),order));assertEquals(0,store.cleanupPending(order),"SQLite count matches");
            var next=store.accept("a","round-51",request()).order();next.cleanupRoundCounted=true;store.saveOrder(next).join();assertEquals(1,CleanupSchedule.pending(store.orders(),next));assertEquals(1,store.cleanupPending(next),"SQLite count matches");
        }
    }
    @Test void oldHistoryAndOtherWarehousesWorldsOrDimensionsDoNotCount(){
        var context=new Model.Order();context.world="world";context.dimension="dim";context.warehouseId="main";
        var orders=new ArrayList<Model.Order>();
        for(int i=1;i<=60;i++){var o=new Model.Order();o.queueSequence=i;o.world="other";o.dimension="dim";o.warehouseId="main";o.cleanupRoundCounted=true;orders.add(o);}
        var old=new Model.Order();old.world="world";old.dimension="dim";old.warehouseId="main";old.cleanupCompleted=true;old.queueSequence=99;orders.add(old);
        assertEquals(0,CleanupSchedule.pending(orders,context));
        for(var o:orders){o.world="world";o.dimension="other";}assertEquals(0,CleanupSchedule.pending(orders,context));
        for(var o:orders){o.dimension="dim";o.warehouseId="other";}assertEquals(0,CleanupSchedule.pending(orders,context));
    }
    @Test void oldEntranceMigratesOnceAndSubsequentChestEditsPersist()throws Exception{
        Path file=dir.resolve("config.json");var c=new Config();c.returnEntry=new Model.Pos(100,49,0);Files.writeString(file,Jsons.GSON.toJson(c));
        c=Config.load(file);assertEquals(new Model.Pos(80,50,-23),c.returnEntry);assertEquals(1,c.cleanupPolicyVersion);
        c.returnEntry=new Model.Pos(1,2,3);c.save(file);assertEquals(new Model.Pos(1,2,3),Config.load(file).returnEntry);
    }
}

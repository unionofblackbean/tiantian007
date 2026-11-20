package dev.superwy;
import dev.superwy.core.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class StoreTest {
    @TempDir Path dir;
    private Model.Request request(){var r=new Model.Request();r.delivery=new Model.DeliveryRequest();r.delivery.fakePlayerName="sf";var l=new Model.RequestLine();l.itemId="minecraft:stone";l.count=13;r.items.add(l);return r;}
    @Test void historyStaysOnDiskAndPollingLoadsOnlyVisibleRecords()throws Exception{
        Path file=dir.resolve("history.db");String oldest=null;
        try(Store store=new Store(file)){
            for(int i=0;i<150;i++){
                var order=store.accept(i%2==0?"alice":"bob","history-"+i,request()).order();if(i==0)oldest=order.orderId;
                order.state("stored_offline",null);store.saveOrder(order).join();
            }
            var active=store.accept("alice","active-request",request()).order();
            for(String name:List.of("orders","liveOrders","orderIndex")){
                var field=Store.class.getDeclaredField(name);field.setAccessible(true);assertEquals(1,((Map<?,?>)field.get(store)).size(),name);
            }
            assertEquals(151,store.orders().size());assertTrue(store.order(oldest).terminal());
            assertEquals(oldest,store.accept("alice","history-0",request(),false).order().orderId);
            var visible=store.recentOrders(10,"alice");assertEquals(11,visible.size());assertEquals(active.orderId,visible.getFirst().orderId);
            assertTrue(visible.stream().allMatch(o->o.principalId.equals("alice")));
            assertEquals(149,visible.get(1).queueSequence);assertEquals(1,store.recentOrders(0,null).size());
        }
        try(Store store=new Store(file)){
            assertEquals(1,store.pendingOrders().size());assertTrue(store.order(oldest).terminal());assertEquals(11,store.recentOrders(10,null).size());
            var field=Store.class.getDeclaredField("orders");field.setAccessible(true);assertEquals(1,((Map<?,?>)field.get(store)).size());
        }
    }
    @Test void forcedTerminalOrdersRetainCargoAndDoNotFreezeQueueAfterRestart()throws Exception{
        Path file=dir.resolve("forced.db");List<String> ended=new ArrayList<>();String next;
        try(Store store=new Store(file)){
            for(String reason:List.of("USER_CANCELLED","ORDER_TIMEOUT")){
                var order=store.accept("alice",reason,request()).order();ended.add(order.orderId);
                order.executionStartedAt=order.createdAt;order.executionStoppedAt=java.time.Instant.now().toString();order.terminationReason=reason;order.errorCode=reason.equals("ORDER_TIMEOUT")?reason:null;
                order.lines.getFirst().fetched=4;order.lines.getFirst().delivered=2;
                var cargo=new Model.Cargo();cargo.inventorySlot=9;cargo.count=2;cargo.itemId="minecraft:stone";cargo.status="held";order.cargo.add(cargo);
                order.state(reason.equals("USER_CANCELLED")?"cancelled":"failed",reason);store.saveOrder(order).join();
            }
            next=store.accept("bob","next",request()).order().orderId;
        }
        try(Store store=new Store(file)){
            assertEquals("cancelled",store.order(ended.get(0)).state);assertEquals("failed",store.order(ended.get(1)).state);
            for(String id:ended){var order=store.order(id);assertTrue(order.terminal());assertEquals(2,order.cargo.getFirst().count);assertEquals(2,order.lines.getFirst().delivered);assertEquals(500,order.executionTimeoutSeconds);assertNotNull(order.executionStoppedAt);}
            assertEquals("queued",store.order(next).state);assertEquals(1,OrderQueue.position(store.order(next),store.orders()).position());
        }
    }
    @Test void persistedCargoPreventsCleanWaitReplayEvenBeforeFetchedCounter()throws Exception{
        Path file=dir.resolve("cargo.db");String id;
        try(Store store=new Store(file)){var order=store.accept("alice","cargo-001",request()).order();id=order.orderId;
            var cargo=new Model.Cargo();cargo.inventorySlot=9;cargo.lineIndex=0;cargo.itemId="minecraft:stone";cargo.componentHash="hash";cargo.count=13;cargo.status="taking";order.cargo.add(cargo);
            order.state("waiting_inventory","FREE_CARGO_SLOTS_REQUIRED");store.saveOrder(order).join();
        }
        try(Store store=new Store(file)){var order=store.order(id);assertEquals("failed",order.state);assertEquals("FREE_CARGO_SLOTS_REQUIRED",order.blockingReason);assertTrue(order.needsItemReview);assertEquals(13,order.cargo.getFirst().count);assertEquals(9,order.cargo.getFirst().inventorySlot);assertEquals("taking",order.cargo.getFirst().status);}
    }
    @Test void concurrentIdempotencyAndWorldBinding()throws Exception{
        try(Store store=new Store(dir.resolve("test.db"));var pool=Executors.newFixedThreadPool(4)){
            var tasks=new ArrayList<Future<Store.Accepted>>();for(int i=0;i<4;i++)tasks.add(pool.submit(()->store.accept("alice","same-key-001",request(),true,"server-a","overworld")));
            var ids=new HashSet<String>();for(var f:tasks)ids.add(f.get().order().orderId);assertEquals(1,ids.size());assertEquals(1,store.orders().size());assertEquals("server-a",store.orders().getFirst().world);
        }
    }
    @Test void conflictingRequestDoesNotCreateSecondOrder()throws Exception{try(Store store=new Store(dir.resolve("test.db"))){store.accept("alice","same-key",request());var changed=request();changed.delivery.fakePlayerName="other";assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(IllegalArgumentException.class,()->store.accept("alice","same-key",changed)).getMessage());assertEquals(1,store.orders().size());}}
    @Test void existingRequestCanReplayWhileOffline()throws Exception{try(Store store=new Store(dir.resolve("test.db"))){var first=store.accept("alice","same-key",request());assertTrue(store.accept("alice","same-key",request(),false).replay());assertEquals("CAPABILITY_UNAVAILABLE",assertThrows(IllegalArgumentException.class,()->store.accept("alice","new-key-001",request(),false)).getMessage());assertEquals(first.order().orderId,store.orders().getFirst().orderId);}}
    @Test void interruptedOrderEndsButQueuedRemainsQueued()throws Exception{Path file=dir.resolve("test.db");String started,queued;try(Store store=new Store(file)){var o=store.accept("alice","started-001",request()).order();started=o.orderId;o.state("fetching",null);store.saveOrder(o).join();queued=store.accept("alice","queued-001",request()).order().orderId;}try(Store store=new Store(file)){assertEquals("failed",store.order(started).state);assertEquals("queued",store.order(queued).state);}}
    @Test void snapshotsReplaceRatherThanAccumulateAndEmptyClears()throws Exception{try(Store store=new Store(dir.resolve("test.db"))){var s=new Model.Snapshot();s.key="container";s.world="w";s.dimension="d";s.type="minecraft:chest";s.pos=new Model.Pos(1,2,3);s.complete=true;s.observedAt=System.currentTimeMillis();var n=new Model.ItemNode();n.itemId="minecraft:stone";n.componentHash="h";n.variantSummary="Stone";n.maxStackSize=64;n.count=64;s.items.add(n);for(int i=0;i<3;i++){s.revision++;store.saveSnapshot(s).join();}assertEquals(64L,InventoryIndex.aggregate(store.snapshots(),"w","d").getFirst().get("observedCount"));s.items.clear();s.revision++;store.saveSnapshot(s).join();assertTrue(InventoryIndex.aggregate(store.snapshots(),"w","d").isEmpty());}}
    @Test void principalKeysAreIsolated()throws Exception{try(Store store=new Store(dir.resolve("test.db"))){assertNotEquals(store.accept("alice","same-key",request()).order().orderId,store.accept("bob","same-key",request()).order().orderId);}}
    @Test void oldAbnormalOrdersEndAndRetainCustodyEvidence()throws Exception{
        Path file=dir.resolve("migration.db");String clean,journaled,fetched,spawned;
        try(Store store=new Store(file)){
            var a=store.accept("alice","clean-001",request()).order();clean=a.orderId;a.errorCode="CLEAR_MANAGED_INVENTORY_FIRST";a.state("recovering",a.errorCode);store.saveOrder(a).join();
            var b=store.accept("alice","journal-001",request()).order();journaled=b.orderId;b.errorCode=a.errorCode;b.state("recovering",a.errorCode);store.saveOrder(b).join();store.journal(b.orderId,"op-1",Map.of("operation","store_preexisting_item")).join();
            var c=store.accept("alice","fetched-001",request()).order();fetched=c.orderId;c.errorCode=a.errorCode;c.lines.getFirst().fetched=1;c.state("recovering",a.errorCode);store.saveOrder(c).join();
            var d=store.accept("alice","spawned-001",request()).order();spawned=d.orderId;d.errorCode=a.errorCode;d.delivery.stage="spawned";d.state("recovering",a.errorCode);store.saveOrder(d).join();
        }
        try(Store store=new Store(file)){
            assertEquals("failed",store.order(clean).state);assertEquals("CLEAR_MANAGED_INVENTORY_FIRST",store.order(clean).errorCode);assertFalse(store.order(clean).needsItemReview);
            for(String id:List.of(journaled,fetched,spawned)){assertEquals("failed",store.order(id).state);assertTrue(store.order(id).needsItemReview);}
        }
    }
    @Test void oldInventoryWaitEndsWithoutBlockingFifo()throws Exception{
        Path file=dir.resolve("wait.db");String waiting,queued;
        try(Store store=new Store(file)){
            var a=store.accept("alice","waiting-001",request()).order();waiting=a.orderId;a.state("waiting_inventory","RECOVERY_STORAGE_FULL");store.saveOrder(a).join();
            queued=store.accept("bob","queued-002",request()).order().orderId;
        }
        try(Store store=new Store(file)){assertEquals("failed",store.order(waiting).state);assertEquals(1,OrderQueue.position(store.order(queued),store.orders()).position());}
    }
}

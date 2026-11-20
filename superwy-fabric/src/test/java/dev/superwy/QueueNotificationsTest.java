package dev.superwy;
import dev.superwy.core.*;
import dev.superwy.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class QueueNotificationsTest {
    @TempDir Path dir;
    private Model.Request request(String player,boolean batch){var r=new Model.Request();r.clientRequestId=WhisperProtocol.newId();r.notifyPlayer=player;if(batch)r.batchId=r.clientRequestId;r.delivery=new Model.DeliveryRequest();r.delivery.fakePlayerName="Cargo";var l=new Model.RequestLine();l.itemId="minecraft:stone";l.count=1;r.items.add(l);return r;}
    @Test void immediateFailureBeforeFirstTickStillFinishesTheClientTask()throws Exception{
        try(var store=new Store(dir.resolve("fast"))){var order=store.accept("a","fast",request("Alice",false)).order();order.state("failed","OUT_OF_STOCK");store.saveOrder(order).join();var messages=new ArrayList<String>();var notify=new QueueNotifications(store,(name,body)->messages.add(body));notify.tick(order.world,order.dimension);assertEquals(1,messages.size());assertTrue(messages.getFirst().contains(" 0 failed "+order.orderId));notify.tick(order.world,order.dimension);assertEquals(1,messages.size());}
    }
    @Test void activeMixedQueueChangesArePrivateAndUnchangedPositionsAreSilent()throws Exception{
        try(var store=new Store(dir.resolve("db"))){
            var a=store.accept("a","a",request("Alice",true)).order();a.state("fetching",null);store.saveOrder(a).join();
            var b=store.accept("b","b",request("Bob",false)).order();var c=store.accept("c","c",request("Carol",true)).order();
            var messages=new ArrayList<String>();var names=new ArrayList<String>();var notify=new QueueNotifications(store,(name,body)->{names.add(name);messages.add(body);});
            notify.tick(a.world,a.dimension);assertEquals(List.of("Alice","Bob","Carol"),names);assertEquals("2",BatchProtocol.parse(messages.getLast(),true).args()[0]);
            messages.clear();names.clear();notify.tick(a.world,a.dimension);assertTrue(messages.isEmpty());
            b.state("cancelled",null);store.saveOrder(b).join();notify.tick(a.world,a.dimension);
            assertEquals(2,messages.size());int carol=names.indexOf("Carol");var packet=BatchProtocol.parse(messages.get(carol),true);assertEquals(c.clientRequestId,packet.id());assertEquals(List.of("1","queued",c.orderId),List.of(packet.args()));assertFalse(messages.get(carol).contains(a.orderId));assertFalse(messages.get(carol).contains(b.orderId));
            a.state("stored_offline",null);store.saveOrder(a).join();messages.clear();notify.tick(a.world,a.dimension);assertTrue(messages.stream().anyMatch(m->m.contains(" 0 queued "+c.orderId)));
            c.state("fetching",null);store.saveOrder(c).join();messages.clear();notify.tick(a.world,a.dimension);assertTrue(messages.getFirst().contains(" 0 active "+c.orderId));
        }
    }
}

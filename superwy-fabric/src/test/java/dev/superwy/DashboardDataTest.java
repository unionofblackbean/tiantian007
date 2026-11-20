package dev.superwy;

import dev.superwy.core.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DashboardDataTest {
    private Model.Order order(int n,String state){var o=new Model.Order();o.orderId="order-"+n;o.queueSequence=n;o.state=state;o.updatedAt=Instant.ofEpochSecond(n).toString();return o;}
    @Test void retainsEveryOpenOrderButOnlyTenMostRecentlyFinishedOrders(){
        var all=new ArrayList<Model.Order>();for(int n=1;n<=100;n++)all.add(order(n,"stored_offline"));
        all.add(order(0,"fetching"));all.add(order(101,"queued"));
        var rows=DashboardData.orders(all);assertEquals(12,rows.size());assertEquals("order-0",rows.get(0).get("orderId"));assertEquals("order-101",rows.get(1).get("orderId"));
        assertEquals("order-100",rows.get(2).get("orderId"));assertEquals("order-91",rows.getLast().get("orderId"));assertEquals(102,all.size());
    }
    @Test void historyUsesFinishTimeAndDoesNotExposeLargeTransferEvidence(){
        var old=order(1,"failed");old.updatedAt=Instant.ofEpochSecond(200).toString();var line=new Model.Line();line.itemId="minecraft:stone";line.requested=3;line.fetched=2;old.lines.add(line);old.cargo.add(new Model.Cargo());
        var rows=DashboardData.orders(List.of(old,order(2,"cancelled")));var row=rows.getFirst();assertEquals("order-1",row.get("orderId"));assertEquals(3L,row.get("requested"));assertEquals(2L,row.get("fetched"));
        for(String field:List.of("lines","request","cargo","packages"))assertFalse(row.containsKey(field));
    }
    @Test void scanSummaryBoundsCoordinatesAndFailuresWithoutChangingScannerState(){
        var positions=new ArrayList<Model.Pos>();for(int i=0;i<4000;i++)positions.add(new Model.Pos(i,70,0));
        var failures=new ArrayList<String>();for(int i=0;i<100;i++)failures.add("failure-"+i);
        var original=Map.<String,Object>of("regionId","warehouse","remainingContainers",positions,"failures",failures);
        var compact=DashboardData.scan(original);assertFalse(compact.containsKey("remainingContainers"));assertEquals(4000,compact.get("remainingContainerCount"));assertEquals(failures.subList(92,100),compact.get("failures"));assertEquals(4000,positions.size());assertEquals(100,failures.size());
    }
}

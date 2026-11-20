package dev.superwy;

import dev.superwy.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OrderFailurePolicyTest {
    @Test void repeatedLinesUseOneStockPoolAndMissingIdsAreDeduplicated(){
        var missing=OrderFailurePolicy.missing(List.of(new OrderFailurePolicy.Demand("minecraft:stone","a",40,64),new OrderFailurePolicy.Demand("minecraft:stone","a",40,64),new OrderFailurePolicy.Demand("minecraft:iron_ingot","b",1,0),new OrderFailurePolicy.Demand("minecraft:stone","other",1,0)));
        assertEquals(List.of("minecraft:stone","minecraft:iron_ingot"),missing);
    }
    @Test void distinctComponentsCannotBorrowFromEachOther(){
        assertEquals(List.of("minecraft:potion"),OrderFailurePolicy.missing(List.of(new OrderFailurePolicy.Demand("minecraft:potion","a",2,1),new OrderFailurePolicy.Demand("minecraft:potion","b",1,64))));
        assertTrue(OrderFailurePolicy.missing(List.of(new OrderFailurePolicy.Demand("minecraft:stone","a",64,64))).isEmpty());
    }
    @Test void stockBroadcastUsesExactItemIdsAndIsOncePerItem(){
        var order=new Model.Order();order.missingItems.addAll(List.of("minecraft:gray_carpet","minecraft:stone","minecraft:stone"));
        var carpet=new Model.Line();carpet.itemId="minecraft:gray_carpet";carpet.requested=64;carpet.fetched=54;carpet.delivered=54;order.lines.add(carpet);
        var stone=new Model.Line();stone.itemId="minecraft:stone";stone.requested=10;order.lines.add(stone);
        var duplicate=new Model.Line();duplicate.itemId="minecraft:stone";duplicate.requested=5;duplicate.delivered=2;order.lines.add(duplicate);
        assertEquals(List.of("数量不足：gray_carpet 缺10（已交付54）、stone 缺13（已交付2）"),OrderFailurePolicy.announcements(order,"OUT_OF_STOCK"));
    }
    @Test void fetchedButUnverifiedItemsAreNeverAnnouncedAsDelivered(){
        var order=new Model.Order();order.missingItems.add("minecraft:stone");
        var line=new Model.Line();line.itemId="minecraft:stone";line.requested=64;line.fetched=54;order.lines.add(line);
        assertEquals(List.of("完全缺货：stone×64"),OrderFailurePolicy.shortageAnnouncements(order));
        order.missingItems.clear();assertTrue(OrderFailurePolicy.shortageAnnouncements(order).isEmpty());
    }
    @Test void rawErrorsAndInvalidItemIdsNeverBecomePublicChat(){
        var order=new Model.Order();var line=new Model.Line();line.itemId="minecraft:stone\nplayer victim kill";order.lines.add(line);order.missingItems.add(line.itemId);
        assertEquals(List.of("取货任务 取货失败：发生异常，任务已停止，请检查机器人日志"),OrderFailurePolicy.announcements(order,"bad\nsay another"));
    }
    @Test void ambiguousShulkerAndTimeoutAnnouncementsArePlainChatText(){
        var order=new Model.Order();var line=new Model.Line();line.itemId="minecraft:white_shulker_box";order.lines.add(line);
        assertEquals(List.of("minecraft:white_shulker_box 取货失败：该物品存在多个不同组件版本，请指定准确版本"),OrderFailurePolicy.announcements(order,"AMBIGUOUS_ITEM"));
        assertEquals(List.of("minecraft:white_shulker_box 取货失败："+ErrorMessages.message("ORDER_TIMEOUT")),OrderFailurePolicy.announcements(order,"ORDER_TIMEOUT"));
    }
    @Test void restartedAbnormalOrdersEndButKeepCargoEvidence(){
        var order=new Model.Order();order.state("waiting_stock","OUT_OF_STOCK");var cargo=new Model.Cargo();cargo.count=3;order.cargo.add(cargo);
        OrderFailurePolicy.interrupted(order,true);assertTrue(order.terminal());assertEquals("failed",order.state);assertEquals("OUT_OF_STOCK",order.errorCode);assertEquals(3,order.cargo.getFirst().count);assertTrue(order.needsItemReview);
    }
    @Test void finalReportSeparatesNothingDeliveredShortAndOtherReasons(){
        var order=new Model.Order();order.missingItems.addAll(List.of("minecraft:stone","minecraft:dirt"));
        var stone=new Model.Line();stone.itemId="minecraft:stone";stone.requested=64;stone.errorCode="OUT_OF_STOCK";stone.skipped=true;order.lines.add(stone);
        var dirt=new Model.Line();dirt.itemId="minecraft:dirt";dirt.requested=100;dirt.delivered=40;dirt.errorCode="OUT_OF_STOCK";order.lines.add(dirt);
        var glass=new Model.Line();glass.itemId="minecraft:glass";glass.requested=10;glass.errorCode="PATH_UNREACHABLE";glass.skipped=true;order.lines.add(glass);
        var sand=new Model.Line();sand.itemId="minecraft:sand";sand.requested=5;sand.delivered=5;order.lines.add(sand);
        var report=OrderFailurePolicy.shortageAnnouncements(order);
        assertEquals("完全缺货：stone×64",report.get(0));assertEquals("数量不足：dirt 缺60（已交付40）",report.get(1));
        assertTrue(report.get(2).startsWith("未取到：glass×10（"));assertEquals(3,report.size());
    }
    @Test void longReportsSplitBetweenEntriesUnderTheChatLimit(){
        var order=new Model.Order();
        for(int i=0;i<60;i++){var l=new Model.Line();l.itemId="minecraft:item_number_"+i;l.requested=64;order.lines.add(l);order.missingItems.add(l.itemId);}
        var report=OrderFailurePolicy.shortageAnnouncements(order);
        assertTrue(report.size()>1);assertTrue(report.stream().allMatch(m->m.length()<=256&&!m.endsWith("…")));assertTrue(report.get(1).startsWith("完全缺货（续）："));
        assertEquals(60,report.stream().mapToInt(m->m.split("、").length).sum());
    }
}

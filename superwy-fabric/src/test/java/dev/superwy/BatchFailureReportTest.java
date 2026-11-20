package dev.superwy;

import dev.superwy.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BatchFailureReportTest {
    private static Model.Line line(int index,String id,int requested,int delivered){var l=new Model.Line();l.lineIndex=index;l.itemId=id;l.requested=requested;l.delivered=delivered;return l;}
    private static Model.Parcel parcel(String custody){var p=new Model.Parcel();p.custody=custody;return p;}

    @Test void boxShortageReportsBoxesHandedOverAndItemsStashed(){
        var order=new Model.Order();
        order.lines.add(line(0,"minecraft:stone",2304,1728));order.lines.add(line(1,"minecraft:gold_ingot",128,0));
        order.packages.add(parcel("recipient"));order.packages.add(parcel("recipient"));
        order.delivery.recipientNames.addAll(List.of("sf","sf_1"));
        order.salvageMode="stash_loose";order.stashedItems=704;order.stashedTo=new Model.Pos(80,50,-23);
        assertEquals(List.of(
            "批量取货失败：取盒点没有空潜影盒，请补充空盒；已交付1728/2432件",
            "已把2盒交给 sf、sf_1",
            "剩余704件已存回安全箱 80,50,-23",
            "未交付：stone×576、gold_ingot×128"),OrderFailurePolicy.batchFailureReport(order,"EMPTY_SHULKER_BOX_REQUIRED"));
    }
    @Test void otherFailureReportsPackedDeliveryAndSalvageProblems(){
        var order=new Model.Order();order.lines.add(line(0,"minecraft:stone",64,64));
        order.packages.add(parcel("recipient"));order.delivery.recipientNames.add("sf");
        order.salvageMode="pack_and_deliver";
        assertEquals(List.of("批量取货失败："+ErrorMessages.message("PATH_UNREACHABLE")+"；已交付64/64件","已把1盒交给 sf"),
            OrderFailurePolicy.batchFailureReport(order,"PATH_UNREACHABLE"));
        order.salvageErrorCode="FAKE_PLAYER_UNAVAILABLE";order.lines.getFirst().delivered=0;order.packages.clear();
        var report=OrderFailurePolicy.batchFailureReport(order,"PATH_UNREACHABLE");
        assertTrue(report.contains("收尾未完成："+ErrorMessages.message("FAKE_PLAYER_UNAVAILABLE")+"；剩余物品留在机器人身上，请核对"),report.toString());
        assertEquals("未交付：stone×64",report.getLast());
    }
    @Test void reportNeverExceedsChatLimitOrLeaksRawText(){
        var order=new Model.Order();
        for(int i=0;i<128;i++)order.lines.add(line(i,"minecraft:very_long_material_name_number_"+i,1000,0));
        order.lines.add(line(128,"bad\nsay hi",5,0));
        order.delivery.recipientNames.addAll(List.of("sf","bad name; /op x"));
        for(var message:OrderFailurePolicy.batchFailureReport(order,"weird\nraw"))
            {assertTrue(message.length()<=256,message);assertFalse(message.contains("\n"),message);assertFalse(message.contains("bad"),message);}
        assertTrue(OrderFailurePolicy.batchFailureReport(order,"X").getLast().endsWith("等128项"));
    }
    @Test void supplyWarningNamesBothCounts(){
        assertEquals("取盒点只有2个空潜影盒，本单预计需要5个；空盒用完时会先交付已装好的盒子，剩余物品存入安全箱",OrderFailurePolicy.boxSupplyWarning(2,5));
        assertTrue(OrderFailurePolicy.boxShortage("EMPTY_SHULKER_BOX_REQUIRED"));assertFalse(OrderFailurePolicy.boxShortage("OUT_OF_STOCK"));
    }
}

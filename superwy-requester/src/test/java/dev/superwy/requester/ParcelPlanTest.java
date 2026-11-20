package dev.superwy.requester;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ParcelPlanTest {
    private static ParcelPlan.Line line(String id,int count){return new ParcelPlan.Line(id,count,64,false);}
    private static List<Integer> counts(ParcelPlan.Box box){return box.slots().stream().map(ParcelPlan.Stack::count).toList();}

    @Test void groupsMaterialsAndPutsShulkersLast(){
        var ordered=ParcelPlan.order(List.of(line("minecraft:stone_bricks",5),new ParcelPlan.Line("minecraft:red_shulker_box",2,1,true),line("minecraft:oak_log",3),line("minecraft:oak_planks",9),line("minecraft:stone_bricks",7)));
        assertEquals(List.of("minecraft:oak_log","minecraft:oak_planks","minecraft:stone_bricks","minecraft:red_shulker_box"),ordered.stream().map(ParcelPlan.Line::itemId).toList());
        assertEquals(12,ordered.get(2).count(),"duplicate lines are merged");
    }
    @Test void fullBoxThenOverflow(){
        var exact=ParcelPlan.plan(List.of(line("minecraft:stone",64*27)),"sf");
        assertEquals(1,exact.boxes().size());assertEquals(27,exact.boxes().get(0).slots().size());
        var over=ParcelPlan.plan(List.of(line("minecraft:stone",64*27+1)),"sf");
        assertEquals(2,over.boxes().size());assertEquals(List.of(1),counts(over.boxes().get(1)));
    }
    @Test void mixedItemsShareABoxSlotBySlot(){
        var plan=ParcelPlan.plan(List.of(line("minecraft:stone",100),line("minecraft:dirt",30)),"sf");
        assertEquals(1,plan.boxes().size());
        var box=plan.boxes().get(0);
        assertEquals(List.of(64,36,30),counts(box));
        assertEquals(Map.of("minecraft:stone",100,"minecraft:dirt",30),box.totals());
        assertEquals(130,box.units());
    }
    @Test void lineContinuesInNextBox(){
        var plan=ParcelPlan.plan(List.of(line("minecraft:andesite",64*26),line("minecraft:diorite",64*3)),"sf");
        assertEquals(2,plan.boxes().size());
        assertEquals(26,plan.boxes().get(0).totals().get("minecraft:andesite")/64);
        assertEquals(64,(int)plan.boxes().get(0).totals().get("minecraft:diorite"));
        assertEquals(List.of(64,64),counts(plan.boxes().get(1)));
    }
    @Test void smallStackSizesUseMoreSlots(){
        var plan=ParcelPlan.plan(List.of(new ParcelPlan.Line("minecraft:oak_sign",20,16,false),new ParcelPlan.Line("minecraft:iron_sword",30,1,false)),"sf");
        assertEquals(2,plan.boxes().size());
        assertEquals(List.of(16,4),counts(plan.boxes().get(0)).subList(0,2));
        assertEquals(27,plan.boxes().get(0).slots().size());
        assertEquals(5,plan.boxes().get(1).slots().size(),"2 signs slots + 25 swords in box 1, 5 swords in box 2");
    }
    @Test void shulkerItemsTravelLooseAndRecipientsOverflow(){
        var lines=ParcelPlan.order(List.of(line("minecraft:stone",64*27*40),new ParcelPlan.Line("minecraft:white_shulker_box",2,1,true)));
        var plan=ParcelPlan.plan(lines,"sf");
        assertEquals(40,plan.boxes().size());assertEquals(2,plan.loose().size());
        assertEquals(List.of(new ParcelPlan.Recipient("sf",36,0),new ParcelPlan.Recipient("sf_1",4,2)),plan.recipients());
        assertEquals(2,plan.looseUnits());
    }
    @Test void recipientNamesMatchRobot(){
        assertEquals("sf",ParcelPlan.recipientName("sf",0));
        assertEquals("sf_2",ParcelPlan.recipientName("sf",2));
        assertEquals("abcdefghijklmn_1",ParcelPlan.recipientName("abcdefghijklmnop",1));
        assertEquals(16,ParcelPlan.recipientName("abcdefghijklmnop",12).length());
    }
    @Test void limitsMatchBatchProtocol(){
        var many=new ArrayList<ParcelPlan.Line>();for(int i=0;i<129;i++)many.add(line("minecraft:item_"+i,1));
        assertThrows(IllegalArgumentException.class,()->ParcelPlan.plan(many,"sf"));
        assertThrows(IllegalArgumentException.class,()->ParcelPlan.plan(List.of(line("minecraft:stone",1_000_001)),"sf"));
        assertThrows(IllegalArgumentException.class,()->ParcelPlan.plan(List.of(),"sf"));
        assertEquals(5,ParcelPlan.plan(many.subList(0,128),"sf").boxes().size(),"128 single items take 128 slots = 5 boxes");
    }
}

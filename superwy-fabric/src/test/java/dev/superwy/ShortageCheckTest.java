package dev.superwy;

import com.google.gson.*;
import dev.superwy.core.ShortageCheck;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ShortageCheckTest {
    private static JsonObject body(Object... pairs){
        var items=new JsonArray();for(int i=0;i<pairs.length;i+=2){var o=new JsonObject();o.addProperty("itemId",(String)pairs[i]);o.addProperty("count",(Integer)pairs[i+1]);items.add(o);}
        var b=new JsonObject();b.add("items",items);return b;
    }
    @Test void listsOnlyItemsTheWarehouseCannotCoverLargestFirst(){
        var stock=Map.of("minecraft:stone",100L,"minecraft:dirt",10L,"minecraft:glass",5000L);
        var r=ShortageCheck.compute(body("minecraft:stone",64,"minecraft:dirt",4687,"minecraft:glass",5007,"minecraft:tnt",1),ids->stock);
        assertEquals(4,r.get("checked"));
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)r.get("items");
        assertEquals(List.of("minecraft:dirt","minecraft:glass","minecraft:tnt"),items.stream().map(m->m.get("itemId")).toList());
        assertEquals(4677L,items.get(0).get("shortage"));assertEquals(7L,items.get(1).get("shortage"));assertEquals(1L,items.get(2).get("shortage"));assertEquals(0L,items.get(2).get("available"));
    }
    @Test void mergesDuplicateLines(){
        var r=ShortageCheck.compute(body("minecraft:sand",10,"minecraft:sand",20),ids->Map.of("minecraft:sand",25L));
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)r.get("items");
        assertEquals(1,r.get("checked"));assertEquals(5L,items.getFirst().get("shortage"));
    }
    @Test void rejectsBadInput(){
        assertThrows(IllegalArgumentException.class,()->ShortageCheck.compute(new JsonObject(),ids->Map.of()));
        assertThrows(IllegalArgumentException.class,()->ShortageCheck.compute(body("石头",1),ids->Map.of()));
        assertThrows(IllegalArgumentException.class,()->ShortageCheck.compute(body("minecraft:stone",0),ids->Map.of()));
    }
}

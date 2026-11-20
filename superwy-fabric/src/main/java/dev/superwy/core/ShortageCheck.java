package dev.superwy.core;

import com.google.gson.*;
import java.util.*;
import java.util.function.Function;

/** Web shortage check: projection "missing" counts against warehouse stock; keeps only items the warehouse cannot cover. */
public final class ShortageCheck {
    public static final int MAX_LINES=4096;
    private ShortageCheck(){}
    public static Map<String,Object> compute(JsonObject body,Function<Collection<String>,Map<String,Long>> stock){
        Requests.keys(body,"items");
        if(!body.has("items")||!body.get("items").isJsonArray())throw new IllegalArgumentException("INVALID_REQUEST");
        var lines=body.getAsJsonArray("items");if(lines.isEmpty()||lines.size()>MAX_LINES)throw new IllegalArgumentException("INVALID_REQUEST");
        Map<String,Long> wanted=new LinkedHashMap<>();
        for(JsonElement value:lines){
            if(!value.isJsonObject())throw new IllegalArgumentException("INVALID_REQUEST");var line=value.getAsJsonObject();Requests.keys(line,"itemId","count");
            String id=Requests.text(line,"itemId");if(!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("INVALID_REQUEST");
            wanted.merge(id,(long)Requests.integer(line,"count",1,100_000_000),Long::sum);
        }
        var have=stock.apply(wanted.keySet());var items=new ArrayList<Map<String,Object>>();
        for(var e:wanted.entrySet()){long available=have.getOrDefault(e.getKey(),0L),lack=e.getValue()-available;if(lack>0)items.add(Map.of("itemId",e.getKey(),"needed",e.getValue(),"available",available,"shortage",lack));}
        items.sort(Comparator.comparingLong((Map<String,Object> m)->(Long)m.get("shortage")).reversed());
        Map<String,Object> result=new LinkedHashMap<>();result.put("checked",wanted.size());result.put("items",items);return result;
    }
}

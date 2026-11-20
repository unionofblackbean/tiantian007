package dev.superwy.core;

import com.google.gson.*;
import java.math.BigDecimal;
import java.util.*;

public final class Requests {
    private Requests(){}
    public static Model.Request parse(JsonObject body,Config.Principal principal){
        keys(body,"warehouseId","clientRequestId","items","delivery","options");
        if(!text(body,"warehouseId").equals("main"))throw new IllegalArgumentException("FORBIDDEN");
        if(body.has("clientRequestId")&&text(body,"clientRequestId").length()>128)invalid();
        if(!body.has("items")||!body.get("items").isJsonArray())invalid();
        JsonArray items=body.getAsJsonArray("items");if(items.isEmpty()||items.size()>128)invalid();
        long total=0;
        for(JsonElement value:items){
            if(!value.isJsonObject())invalid();JsonObject line=value.getAsJsonObject();keys(line,"itemId","count","match");
            String id=text(line,"itemId");if(id.length()>256||!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))invalid();
            total+=integer(line,"count",1,1_000_000_000);
            if(line.has("match")){
                JsonObject match=object(line,"match");keys(match,"mode","componentHash");String mode=text(match,"mode");
                if(mode.equals("exact_components")){if(!text(match,"componentHash").matches("[a-f0-9]{64}"))invalid();}
                else if(!mode.equals("item_id")||match.has("componentHash"))invalid();
            }
        }
        if(total>1_000_000)throw new IllegalArgumentException("REQUEST_LIMIT_EXCEEDED");
        JsonObject delivery=object(body,"delivery");keys(delivery,"method","fakePlayerName","stationId");
        if(!text(delivery,"method").equals("carpet_fake_player"))invalid();String name=text(delivery,"fakePlayerName");
        if(!name.matches("[A-Za-z0-9_]{1,16}"))throw new IllegalArgumentException("INVALID_FAKE_PLAYER_NAME");
        if(!principal.permits(name))throw new IllegalArgumentException("FORBIDDEN");
        if(delivery.has("stationId")&&!text(delivery,"stationId").matches("[A-Za-z0-9_-]{1,64}"))invalid();
        if(body.has("options")){
            JsonObject options=object(body,"options");keys(options,"allowPartial","expiresInSeconds","batch","projectionParcels");
            for(String flag:List.of("allowPartial","batch","projectionParcels"))if(options.has(flag)&&(!options.get(flag).isJsonPrimitive()||!options.get(flag).getAsJsonPrimitive().isBoolean()))invalid();
            if(options.has("expiresInSeconds"))integer(options,"expiresInSeconds",60,86400);
        }
        Model.Request result=Jsons.GSON.fromJson(body,Model.Request.class);
        result.delivery.fakePlayerName=name;
        // HTTP batches (the player mod's manifests and projection orders) use the batch rules: skip missing lines, pack, salvage.
        if(result.options.batch||result.options.projectionParcels){
            result.projectionParcels=result.options.projectionParcels;
            String seed=principal.id()+"|"+(result.clientRequestId==null?Jsons.hash(body):result.clientRequestId);
            result.batchId=Jsons.hash(seed).substring(0,32);
        }
        return result;
    }
    public static void keys(JsonObject object,String...allowed){Set<String> valid=Set.of(allowed);for(String key:object.keySet())if(!valid.contains(key))invalid();}
    public static String text(JsonObject object,String field){JsonElement value=object.get(field);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw new IllegalArgumentException("INVALID_REQUEST");return value.getAsString();}
    public static int integer(JsonObject object,String field,int min,int max){
        JsonElement value=object.get(field);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("INVALID_REQUEST");
        try{int n=new BigDecimal(value.getAsString()).intValueExact();if(n<min||n>max)invalid();return n;}catch(ArithmeticException e){throw new IllegalArgumentException("INVALID_REQUEST");}
    }
    public static JsonObject object(JsonObject parent,String field){JsonElement value=parent.get(field);if(value==null||!value.isJsonObject())throw new IllegalArgumentException("INVALID_REQUEST");return value.getAsJsonObject();}
    private static void invalid(){throw new IllegalArgumentException("INVALID_REQUEST");}
}

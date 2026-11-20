package dev.superwy.core;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Only seed the matching server once; never replace an existing region/checkpoint. */
public final class WarehousePresets {
    private WarehousePresets(){}
    public static int apply(Config config,Store store,String world){
        if(config.presetWorlds.contains(world))return 0;
        int added=0;boolean matched=false;
        try(var stream=WarehousePresets.class.getResourceAsStream("/warehouse-presets.json")){
            if(stream==null)return 0;
            var regions=Jsons.GSON.fromJson(new InputStreamReader(stream,StandardCharsets.UTF_8),Model.Region[].class);
            for(var region:regions)if(region.world.equals(world)){
                matched=true;
                if(store.regions().stream().noneMatch(old->old.id.equals(region.id))){store.saveRegion(region).join();added++;}
            }
        }catch(IOException e){throw new IllegalStateException("仓库预设读取失败",e);}
        if(matched)config.presetWorlds.add(world);
        return added;
    }
}

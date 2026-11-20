package dev.superwy.requester;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import java.lang.reflect.*;

/** Optional public-API adapter pinned to Litematica LTS/26.1 (0.27.14). No upstream code is bundled. */
public final class ProjectionBridge {
    public record Target(ItemStack item,BlockPos pos){}
    private Method trace,world,cache,item;
    public ProjectionBridge()throws ReflectiveOperationException{
        if(!FabricLoader.getInstance().isModLoaded("litematica"))return;
        trace=Class.forName("fi.dy.masa.litematica.util.RayTraceUtils").getMethod("traceToSchematicWorld",Entity.class,double.class,boolean.class,boolean.class);
        world=Class.forName("fi.dy.masa.litematica.world.SchematicWorldHandler").getMethod("getSchematicWorld");
        Class<?> material=Class.forName("fi.dy.masa.litematica.materials.MaterialCache");
        cache=material.getMethod("getInstance");item=material.getMethod("getRequiredBuildItemForState",BlockState.class,Level.class,BlockPos.class);
    }
    public boolean available(){return trace!=null;}
    public Target pick(Minecraft mc,double range)throws ReflectiveOperationException{
        if(!available()||mc.player==null)return null;
        Level schematic=(Level)world.invoke(null);if(schematic==null)return null;
        BlockHitResult hit=(BlockHitResult)trace.invoke(null,mc.player,range,true,false);
        if(hit==null||hit.getType()!=HitResult.Type.BLOCK||!schematic.hasChunkAt(hit.getBlockPos()))return null;
        BlockPos pos=hit.getBlockPos().immutable();BlockState state=schematic.getBlockState(pos);
        if(state.isAir())return null;
        ItemStack stack=(ItemStack)item.invoke(cache.invoke(null),state,schematic,pos);
        return stack==null||stack.isEmpty()?null:new Target(stack.copyWithCount(1),pos);
    }
}

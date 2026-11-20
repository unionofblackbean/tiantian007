package dev.superwy.requester;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.selection.AreaSelection;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/**
 * Counts the blocks inside the current Litematica area selection, either from the projection (schematic world)
 * or from the real, already built world. Only referenced when Litematica is loaded. Work is split per chunk
 * column so a large selection is spread over several client ticks instead of freezing the game.
 */
public final class AreaMaterials {
    /** Where the materials are read from. */
    public enum Source {
        PROJECTION("投影"),WORLD("已建建筑");
        public final String label;
        Source(String label){this.label=label;}
        public Source next(){return this==PROJECTION?WORLD:PROJECTION;}
    }
    private record Unit(int box,int minX,int maxX,int minY,int maxY,int minZ,int maxZ){}
    private final List<int[]> boxes=new ArrayList<>();private final ArrayDeque<Unit> units=new ArrayDeque<>();
    private final int totalUnits;private final boolean skipPlaced;
    public final Source source;
    public final String selectionName;
    public final Map<Item,Long> counts=new LinkedHashMap<>();
    /** unloadedWorldColumns: projection mode = placed blocks not checked; world mode = blocks not counted. */
    public long blocks,placedSkipped;public int unloadedWorldColumns;
    private AreaMaterials(String name,List<Box> selection,Source source,boolean skipPlaced){
        this.selectionName=name;this.source=source;this.skipPlaced=skipPlaced&&source==Source.PROJECTION;
        for(int[] box:bounds(selection)){
            int index=boxes.size();boxes.add(box);
            for(int cx=box[0]>>4;cx<=box[3]>>4;cx++)for(int cz=box[2]>>4;cz<=box[5]>>4;cz++)
                units.add(new Unit(index,Math.max(box[0],cx<<4),Math.min(box[3],(cx<<4)+15),box[1],box[4],Math.max(box[2],cz<<4),Math.min(box[5],(cz<<4)+15)));
        }
        if(boxes.isEmpty())throw new IllegalArgumentException("当前选区没有有效的子区域");
        totalUnits=units.size();
    }
    private static List<int[]> bounds(Collection<Box> selection){
        var out=new ArrayList<int[]>();
        for(Box b:selection){
            if(b==null)continue;BlockPos p1=b.getPos1(),p2=b.getPos2();if(p1==null||p2==null)continue;
            out.add(new int[]{Math.min(p1.getX(),p2.getX()),Math.min(p1.getY(),p2.getY()),Math.min(p1.getZ(),p2.getZ()),Math.max(p1.getX(),p2.getX()),Math.max(p1.getY(),p2.getY()),Math.max(p1.getZ(),p2.getZ())});
        }
        return out;
    }
    private static AreaSelection selection(){
        AreaSelection selection=DataManager.getSelectionManager().getCurrentSelection();
        if(selection==null)throw new IllegalArgumentException("请先用 Litematica 圈选一片区域");
        return selection;
    }
    /**
     * Picks the projection when an enabled schematic placement overlaps the selection, otherwise the built world.
     * The screen still offers a toggle because a selection can overlap both.
     */
    public static Source detect(){
        try{
            AreaSelection selection=DataManager.getSelectionManager().getCurrentSelection();
            if(selection==null||SchematicWorldHandler.getSchematicWorld()==null)return Source.WORLD;
            var area=bounds(selection.getAllSubRegionBoxes());
            for(SchematicPlacement placement:DataManager.getSchematicPlacementManager().getAllSchematicsPlacements()){
                if(!placement.isEnabled())continue;
                var enclosing=bounds(Collections.singletonList(placement.getEclosingBox()));
                for(int[] p:enclosing)for(int[] a:area)if(intersects(p,a))return Source.PROJECTION;
            }
        }catch(RuntimeException|LinkageError ignored){}
        return Source.WORLD;
    }
    static boolean intersects(int[] a,int[] b){return a[0]<=b[3]&&b[0]<=a[3]&&a[1]<=b[4]&&b[1]<=a[4]&&a[2]<=b[5]&&b[2]<=a[5];}
    /** @throws IllegalArgumentException when there is no usable selection or (projection mode) projection world. */
    public static AreaMaterials start(Source source,boolean skipPlaced){
        AreaSelection selection=selection();
        if(source==Source.PROJECTION&&SchematicWorldHandler.getSchematicWorld()==null)throw new IllegalArgumentException("没有已加载的投影；如果要统计已建好的建筑，请把来源切换为「已建建筑」");
        return new AreaMaterials(selection.getName(),selection.getAllSubRegionBoxes(),source,skipPlaced);
    }
    public boolean done(){return units.isEmpty();}
    public float progress(){return totalUnits==0?1:1f-(float)units.size()/totalUnits;}
    /** Processes chunk columns until the time budget is used. Must run on the client thread. */
    public void step(Minecraft mc,long budgetNanos){
        Level world=mc.level;
        Level schematic=source==Source.PROJECTION?SchematicWorldHandler.getSchematicWorld():world;
        if(schematic==null||world==null)throw new IllegalStateException(source==Source.PROJECTION?"投影或世界已卸载，请重新打开":"世界已卸载，请重新打开");
        MaterialCache cache=MaterialCache.getInstance();long end=System.nanoTime()+budgetNanos;
        BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
        while(!units.isEmpty()&&System.nanoTime()<end){
            Unit u=units.poll();int cx=u.minX>>4,cz=u.minZ>>4;
            boolean worldLoaded=world.hasChunk(cx,cz);
            if(source==Source.WORLD){if(!worldLoaded){unloadedWorldColumns++;continue;}}
            else{if(!schematic.hasChunk(cx,cz))continue;if(skipPlaced&&!worldLoaded)unloadedWorldColumns++;}
            for(int x=u.minX;x<=u.maxX;x++)for(int z=u.minZ;z<=u.maxZ;z++)for(int y=u.minY;y<=u.maxY;y++){
                if(coveredEarlier(u.box,x,y,z))continue;
                pos.set(x,y,z);BlockState state=schematic.getBlockState(pos);
                if(state.isAir())continue;
                if(skipPlaced&&worldLoaded&&world.getBlockState(pos)==state){placedSkipped++;continue;}
                ItemStack stack=cache.getRequiredBuildItemForState(state,schematic,pos);
                if(stack==null||stack.isEmpty())continue;
                blocks++;counts.merge(stack.getItem(),(long)stack.getCount(),Long::sum);
            }
        }
    }
    /** Overlapping sub-regions are counted once. */
    private boolean coveredEarlier(int box,int x,int y,int z){
        for(int i=0;i<box;i++){int[] b=boxes.get(i);if(x>=b[0]&&x<=b[3]&&y>=b[1]&&y<=b[4]&&z>=b[2]&&z<=b[5])return true;}
        return false;
    }
}

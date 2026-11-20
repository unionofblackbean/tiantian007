package dev.superwy.game;

import com.google.gson.*;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import dev.superwy.core.*;
import java.util.*;

public final class Items {
    private Items(){}
    /** Capture registries on the client thread; background decoding never reads Minecraft.level. */
    public record Context(com.mojang.serialization.DynamicOps<JsonElement> json,com.mojang.serialization.DynamicOps<Tag> nbt){}
    public static Context context(){var registries=Minecraft.getInstance().level.registryAccess();return new Context(registries.createSerializationContext(JsonOps.INSTANCE),registries.createSerializationContext(NbtOps.INSTANCE));}
    public static Model.Pos pos(BlockPos p){return new Model.Pos(p.getX(),p.getY(),p.getZ());}
    public static BlockPos block(Model.Pos p){return new BlockPos(p.x(),p.y(),p.z());}
    public static boolean shulker(ItemStack stack){return stack.getItem() instanceof BlockItem b&&b.getBlock() instanceof ShulkerBoxBlock;}
    public static List<ItemStack> contents(ItemStack stack){var value=stack.get(DataComponents.CONTAINER);return value==null?List.of():value.allItemsCopyStream().toList();}
    public static String hash(ItemStack stack){
        if(stack.isEmpty())return "empty";
        return Jsons.hash(components(stack));
    }
    public static JsonElement components(ItemStack stack){
        return components(stack,context());
    }
    private static JsonElement components(ItemStack stack,Context context){return Jsons.canonical(DataComponentMap.CODEC.encodeStart(context.json,stack.getComponents()).getOrThrow());}
    public static String id(ItemStack stack){return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();}
    public static Model.ItemNode node(ItemStack stack,int slot){return node(stack,slot,0,context());}
    private static Model.ItemNode node(ItemStack stack,int slot,int depth,Context context){
        Model.ItemNode n=new Model.ItemNode();n.slot=slot;n.itemId=id(stack);n.count=stack.getCount();n.maxStackSize=stack.getMaxStackSize();
        n.components=components(stack,context);n.componentHash=Jsons.hash(n.components);n.variantSummary=stack.getHoverName().getString();n.shulker=shulker(stack);
        if(n.components.isJsonObject())for(String key:List.of("minecraft:damage","minecraft:enchantments","minecraft:stored_enchantments","minecraft:potion_contents")){
            JsonElement value=n.components.getAsJsonObject().get(key);if(value!=null&&!value.toString().equals("0")&&!value.toString().equals("{}")){String text=value.toString();n.variantSummary+=" · "+key.substring(10)+": "+text.substring(0,Math.min(160,text.length()));}
        }
        if(n.shulker&&depth==0){var inner=contents(stack);for(int i=0;i<inner.size();i++)if(!inner.get(i).isEmpty())n.children.add(node(inner.get(i),i,depth+1,context));}
        return n;
    }
    public static Map<Integer,ItemStack> readInventory(CompoundTag tag,String field){
        return readInventory(tag,field,context());
    }
    private static Map<Integer,ItemStack> readInventory(CompoundTag tag,String field,Context context){
        Tag value=tag.get(field);
        if(!(value instanceof ListTag list))throw new IllegalStateException("INCOMPLETE_NBT_"+field);
        if(list.size()>256)throw new IllegalStateException("NBT_TOO_LARGE");
        Map<Integer,ItemStack> result=new TreeMap<>();
        var ops=context.nbt;
        for(Tag entry:list){
            if(!(entry instanceof CompoundTag item))throw new IllegalStateException("MALFORMED_ITEM");
            int slot=item.getInt("Slot").orElse(-1);if(slot<0&&slot>=-128&&item.contains("Slot"))slot&=255;
            if(slot<0||slot>255||result.containsKey(slot))throw new IllegalStateException("MALFORMED_SLOT");
            ItemStack stack=ItemStack.OPTIONAL_CODEC.parse(ops,item).getOrThrow();
            if(!stack.isEmpty())result.put(slot,stack);
        }return result;
    }
    public static Map<Integer,ItemStack> playerInventory(CompoundTag tag){return readInventory(tag,tag.contains("Inventory")?"Inventory":"inventory");}
    public static Model.Snapshot snapshot(String world,String dim,BlockPos pos,String type,CompoundTag tag,long revision){
        return snapshot(world,dim,pos,type,tag,revision,context());
    }
    public static Model.Snapshot snapshot(String world,String dim,BlockPos pos,String type,CompoundTag tag,long revision,Context context){
        if(tag.contains("LootTable"))throw new IllegalStateException("UNGENERATED_LOOT");
        Model.Snapshot s=new Model.Snapshot();s.world=world;s.dimension=dim;s.pos=Items.pos(pos);s.key=Model.containerKey(world,dim,s.pos);s.type=type;s.observedAt=System.currentTimeMillis();s.revision=revision;s.complete=true;
        // Vanilla shulkers omit Items when empty. The scanner already verified the block type;
        // an incomplete response or a malformed Items field for other containers still fails closed.
        boolean emptyShulker=!tag.contains("Items")&&type.startsWith("minecraft:")&&type.endsWith("shulker_box");
        if(!emptyShulker)readInventory(tag,"Items",context).forEach((slot,item)->s.items.add(node(item,slot,0,context)));return s;
    }
    public static boolean matches(ItemStack stack,Model.RequestLine line,String resolvedHash){return !stack.isEmpty()&&id(stack).equals(line.itemId)&&(resolvedHash==null||hash(stack).equals(resolvedHash));}
    public static boolean same(ItemStack a,ItemStack b){return ItemStack.isSameItemSameComponents(a,b);}
    public static int boxCapacity(ItemStack box,ItemStack item){
        if(shulker(item))return 0;
        List<ItemStack> slots=contents(box);int capacity=0;
        for(int i=0;i<27;i++){ItemStack s=i<slots.size()?slots.get(i):ItemStack.EMPTY;
            capacity+=s.isEmpty()?item.getMaxStackSize():same(s,item)?Math.max(0,item.getMaxStackSize()-s.getCount()):0;
        }return capacity;
    }
    public static Map<String,Integer> quantities(Collection<ItemStack> stacks){Map<String,Integer> counts=new TreeMap<>();for(ItemStack s:stacks)if(!s.isEmpty())counts.merge(id(s)+"|"+hash(s),s.getCount(),Integer::sum);return counts;}
}

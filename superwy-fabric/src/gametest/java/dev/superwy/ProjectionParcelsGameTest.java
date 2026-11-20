package dev.superwy;

import com.google.gson.*;
import dev.superwy.core.*;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Real requester -> private protocol -> real warehouse -> QuickShulker -> GCA/NBT receipts. */
public final class ProjectionParcelsGameTest {
    private final WarehouseGameTest owner;
    private final ClientGameTestContext context;
    private final TestSingleplayerContext single;
    private record Material(Item item,int count){}
    private ProjectionParcelsGameTest(WarehouseGameTest owner,ClientGameTestContext context,TestSingleplayerContext single){this.owner=owner;this.context=context;this.single=single;}
    public static void run(WarehouseGameTest owner,ClientGameTestContext context,TestSingleplayerContext single)throws Exception{new ProjectionParcelsGameTest(owner,context,single).run();}
    public static void runSalvage(WarehouseGameTest owner,ClientGameTestContext context,TestSingleplayerContext single)throws Exception{new ProjectionParcelsGameTest(owner,context,single).salvage();}
    private static final BlockPos SAFE_CHEST=new BlockPos(4,70,3);
    private void setSupply(int boxes)throws Exception{
        single.getServer().runOnServer(server->{var supply=(BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(2,70,2));supply.clearContent();if(boxes>0){var stack=new ItemStack(Items.SHULKER_BOX,boxes);stack.set(DataComponents.MAX_STACK_SIZE,64);supply.setItem(0,stack);}supply.setChanged();});context.waitTicks(4);
    }
    private Map<Item,Integer> safeChest()throws Exception{
        var counts=new HashMap<Item,Integer>();
        single.getServer().runOnServer(server->{var chest=(BarrelBlockEntity)server.overworld().getBlockEntity(SAFE_CHEST);for(int i=0;i<chest.getContainerSize();i++){var s=chest.getItem(i);if(!s.isEmpty())counts.merge(s.getItem(),s.getCount(),Integer::sum);}});
        return counts;
    }
    private static Map<String,Integer> totals(List<List<String>> boxes){
        var out=new TreeMap<String,Integer>();for(var box:boxes)for(var slot:box){var kv=slot.substring(slot.indexOf(':')+1).split("=");out.merge(kv[0],Integer.parseInt(kv[1]),Integer::sum);}return out;
    }
    private void robotEmpty(String label)throws Exception{
        single.getServer().runOnServer(server->{var inv=server.getPlayerList().getPlayers().getFirst().getInventory();for(int i=9;i<36;i++)if(!inv.getItem(i).isEmpty())throw new AssertionError(label+": robot kept "+inv.getItem(i)+" in slot "+i);});
    }
    private void awaitChat(String... parts)throws Exception{
        var field=WarehouseGameTest.class.getDeclaredField("normalChatAnnouncements");field.setAccessible(true);
        try{context.waitFor(mc->{try{var messages=(List<?>)field.get(owner);for(String part:parts)if(messages.stream().noneMatch(m->m.toString().contains(part)))return false;return true;}catch(Exception e){throw new RuntimeException(e);}},200);}
        catch(Throwable e){throw new AssertionError("Missing public chat "+Arrays.toString(parts)+" in "+field.get(owner),e);}
    }
    private void salvage()throws Exception{
        single.getServer().runCommand("carpet openFakePlayerInventory true");call("admin/scan/disable","POST","{}");call("admin/resume","POST","{}");
        call("admin/workflow","PUT","{\"returnEntry\":{\"x\":4,\"y\":70,\"z\":3},\"boxSupply\":{\"x\":2,\"y\":70,\"z\":2},\"packingRoundSlots\":25,\"maxCarriedParcels\":25}");
        call("admin/regions/projection","PUT","{\"min\":{\"x\":2,\"y\":70,\"z\":0},\"max\":{\"x\":6,\"y\":70,\"z\":0}}");
        single.getServer().runOnServer(server->{server.overworld().setBlockAndUpdate(SAFE_CHEST,Blocks.BARREL.defaultBlockState());((BarrelBlockEntity)server.overworld().getBlockEntity(SAFE_CHEST)).clearContent();});

        // S0: no empty box at all -> fail before taking anything.
        var tiny=List.of(new Material(Items.STONE,64));prepare(tiny);setSupply(0);
        JsonObject done=await(submit(plan(tiny)),"failed");
        if(!done.get("errorCode").getAsString().equals("EMPTY_SHULKER_BOX_REQUIRED")||done.getAsJsonArray("lines").get(0).getAsJsonObject().get("fetched").getAsInt()!=0)throw new AssertionError("Empty supply did not fail before fetching: "+done);
        robotEmpty("S0");awaitChat("批量取货失败："+ErrorMessages.message("EMPTY_SHULKER_BOX_REQUIRED")+"；已交付0/64件");
        System.out.println("SUPERWY_SALVAGE_S0_OK: empty box supply refused before any withdrawal, public report sent");

        // S1: supply has 1 box, order needs 2 -> warn, deliver the full box, stash the rest in the safe chest.
        var two=List.of(new Material(Items.STONE,2560));prepare(two);setSupply(1);
        done=await(submit(plan(two)),"failed");
        if(!done.get("errorCode").getAsString().equals("EMPTY_SHULKER_BOX_REQUIRED")||!"\"stash_loose\"".equals(String.valueOf(done.get("salvageMode")))||(done.has("salvageErrorCode")&&!done.get("salvageErrorCode").isJsonNull())||done.get("needsItemReview").getAsBoolean())throw new AssertionError("Shortage salvage state wrong: "+done);
        var got=totals(receipt("sf"));if(!got.equals(Map.of("minecraft:stone",1728)))throw new AssertionError("Shortage: sf should hold one full stone box (1728): "+got);
        var chest=safeChest();if(!chest.equals(Map.of(Items.STONE,832))||done.get("stashedItems").getAsLong()!=832)throw new AssertionError("Shortage: safe chest should hold 832 stone: "+chest+" "+done.get("stashedItems"));
        robotEmpty("S1");
        awaitChat(OrderFailurePolicy.boxSupplyWarning(1,2),"批量取货失败："+ErrorMessages.message("EMPTY_SHULKER_BOX_REQUIRED")+"；已交付1728/2560件","已把1盒交给 sf","剩余832件已存回安全箱 4,70,3","未交付：stone×832");
        System.out.println("SUPERWY_SALVAGE_S1_OK: short supply warned; 1 full box (1728) to sf, 832 loose stone back in safe chest, robot empty, report sent");
        single.getServer().runOnServer(server->((BarrelBlockEntity)server.overworld().getBlockEntity(SAFE_CHEST)).clearContent());

        // S2: unrelated failure after one full box and loose leftovers -> box the leftovers too and hand everything over.
        var mixed=List.of(new Material(Items.STONE,2304),new Material(Items.GOLD_INGOT,200),new Material(Items.YELLOW_WOOL,64));prepare(mixed);setSupply(64);
        BatchSalvageFaults.failItem="minecraft:yellow_wool"; // Plan sorts by name: fail on the last line.
        done=await(submit(plan(mixed)),"failed");BatchSalvageFaults.failItem=null;
        if(!done.get("errorCode").getAsString().equals("INJECTED_FAILURE")||!"\"pack_and_deliver\"".equals(String.valueOf(done.get("salvageMode")))||(done.has("salvageErrorCode")&&!done.get("salvageErrorCode").isJsonNull())||done.get("needsItemReview").getAsBoolean())throw new AssertionError("Generic salvage state wrong: "+done);
        var boxes=receipt("sf");got=totals(boxes);
        if(boxes.size()!=2||!got.equals(Map.of("minecraft:stone",2304,"minecraft:gold_ingot",200)))throw new AssertionError("Generic salvage: expected 2 boxes with all stone and gold: "+boxes);
        if(!safeChest().isEmpty())throw new AssertionError("Generic salvage must not use the safe chest");
        robotEmpty("S2");
        awaitChat("批量取货失败："+ErrorMessages.message("INJECTED_FAILURE")+"；已交付2504/2568件","已把2盒交给 sf","未交付：yellow_wool×64");
        System.out.println("SUPERWY_SALVAGE_S2_OK: injected failure; held box + 15 loose stacks packed into 2 boxes and delivered to sf, robot empty, report sent");
        System.out.println("SUPERWY_BATCH_SALVAGE_OK: S0/S1/S2 verified against real inventories and public chat");
    }
    private Object invoke(String name,Class<?>[] types,Object...args)throws Exception{var m=WarehouseGameTest.class.getDeclaredMethod(name,types);m.setAccessible(true);try{return m.invoke(owner,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Error error)throw error;if(e.getCause() instanceof Exception error)throw error;throw e;}}
    private JsonObject call(String path,String method,String body)throws Exception{return (JsonObject)invoke("call",new Class[]{String.class,String.class,String.class},path,method,body);}
    private Store store()throws Exception{return (Store)invoke("store",new Class[]{});}
    private void waitRecipient(String name)throws Exception{invoke("waitForRecipient",new Class[]{String.class},name);}
    private void command(String text)throws Exception{invoke("clientCommand",new Class[]{String.class},text);}
    private JsonObject await(JsonObject order,String state)throws Exception{return (JsonObject)invoke("awaitOrder",new Class[]{JsonObject.class,String.class,boolean.class},order,state,false);}
    private Object plan(List<Material> materials)throws Exception{
        Class<?> type=Class.forName("dev.superwy.requester.ParcelPlan"),line=Class.forName("dev.superwy.requester.ParcelPlan$Line");
        var lines=new ArrayList<Object>();for(var material:materials){var stack=new ItemStack(material.item);lines.add(line.getConstructor(String.class,int.class,int.class,boolean.class).newInstance(BuiltInRegistries.ITEM.getKey(material.item).toString(),material.count,stack.getMaxStackSize(),dev.superwy.game.Items.shulker(stack)));}
        return type.getMethod("plan",List.class,String.class).invoke(null,type.getMethod("order",Collection.class).invoke(null,lines),"sf");
    }
    private void prepare(List<Material> materials)throws Exception{
        for(String name:List.of("sf","sf_1","sf_2")){
            single.getServer().runCommand("player "+name+" spawn at 0 70 -3");waitRecipient(name);
            single.getServer().runOnServer(server->server.getPlayerList().getPlayerByName(name).getInventory().clearContent());
            single.getServer().runCommand("player "+name+" kill");context.waitTicks(4);
        }
        single.getServer().runOnServer(server->{
            var level=server.overworld();var inv=server.getPlayerList().getPlayers().getFirst().getInventory();inv.clearContent();
            for(int x=2;x<=6;x++){var pos=new BlockPos(x,70,0);level.setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());((BarrelBlockEntity)level.getBlockEntity(pos)).clearContent();}
            int slot=0;for(var material:materials){int left=material.count;while(left>0){if(slot>=135)throw new AssertionError("Fixture source overflow");int count=Math.min(left,new ItemStack(material.item).getMaxStackSize());var barrel=(BarrelBlockEntity)level.getBlockEntity(new BlockPos(2+slot/27,70,0));barrel.setItem(slot++%27,new ItemStack(material.item,count));barrel.setChanged();left-=count;}}
            var supply=(BarrelBlockEntity)level.getBlockEntity(new BlockPos(2,70,2));supply.clearContent();var boxes=new ItemStack(Items.SHULKER_BOX,64);boxes.set(DataComponents.MAX_STACK_SIZE,64);supply.setItem(0,boxes);supply.setChanged();
        });context.waitTicks(10);
        for(int x=2;x<=6;x++){int column=x;Object future=context.computeOnClient(mc->{var scanner=invoke("scanner",new Class[]{});return scanner.getClass().getMethod("refresh",BlockPos.class).invoke(scanner,new BlockPos(column,70,0));});invoke("awaitAction",new Class[]{CompletableFuture.class},future);}
    }
    private JsonObject submit(Object plan)throws Exception{
        String bot=context.computeOnClient(mc->mc.player.getName().getString());command("swrequest bot "+bot);command("swrequest recipient sf");command("swrequest key off");command("swrequest mode chat");
        Object client=Class.forName("dev.superwy.requester.RequesterClient").getField("INSTANCE").get(null);
        var tasks=client.getClass().getDeclaredField("tasks");tasks.setAccessible(true);var old=context.computeOnClient(mc->new HashSet<>(((Map<?,?>)tasks.get(client)).keySet()));
        context.runOnClient(mc->{var method=client.getClass().getDeclaredMethod("submitProjection",plan.getClass());method.setAccessible(true);method.invoke(client,plan);});
        String id=context.computeOnClient(mc->((Map<?,?>)tasks.get(client)).keySet().stream().filter(key->!old.contains(key)).findFirst().orElseThrow().toString());
        Store db=context.computeOnClient(mc->store());context.waitFor(mc->db.orders().stream().anyMatch(order->id.equals(order.request.batchId)),600);
        var order=db.orders().stream().filter(o->id.equals(o.request.batchId)).findFirst().orElseThrow();if(!order.request.projectionParcels)throw new AssertionError("Projection flag lost");
        return Jsons.GSON.toJsonTree(order).getAsJsonObject();
    }
    private List<List<String>> receipt(String name)throws Exception{
        single.getServer().runCommand("player "+name+" spawn at 0 70 -3");waitRecipient(name);var boxes=new ArrayList<List<String>>();
        single.getServer().runOnServer(server->{var inv=server.getPlayerList().getPlayerByName(name).getInventory();for(int slot=0;slot<36;slot++){var stack=inv.getItem(slot);if(stack.isEmpty())continue;if(!dev.superwy.game.Items.shulker(stack))throw new AssertionError("Unexpected loose receipt "+stack);if(stack.getCount()!=1)throw new AssertionError("Receipt shulker count is not one");var contents=stack.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY).allItemsCopyStream().toList();var data=new ArrayList<String>();for(int i=0;i<contents.size();i++){var item=contents.get(i);if(!item.isEmpty())data.add(i+":"+BuiltInRegistries.ITEM.getKey(item.getItem())+"="+item.getCount());}boxes.add(data);}});
        single.getServer().runCommand("player "+name+" kill");context.waitTicks(6);return boxes;
    }
    private void assertPlan(Object plan,List<List<String>> actual)throws Exception{
        var expected=new ArrayList<List<String>>();for(Object box:(List<?>)plan.getClass().getMethod("boxes").invoke(plan)){var contents=new ArrayList<String>();for(Object item:(List<?>)box.getClass().getMethod("slots").invoke(box))contents.add(contents.size()+":"+item.getClass().getMethod("itemId").invoke(item)+"="+item.getClass().getMethod("count").invoke(item));expected.add(contents);}
        for(Object item:(List<?>)plan.getClass().getMethod("loose").invoke(plan))expected.add(List.of());
        if(!expected.equals(actual))throw new AssertionError("Actual per-slot parcels differ from ParcelPlan: expected="+expected+" actual="+actual);
    }
    private void run()throws Exception{
        single.getServer().runCommand("carpet openFakePlayerInventory true");call("admin/scan/disable","POST","{}");call("admin/resume","POST","{}");
        call("admin/workflow","PUT","{\"returnEntry\":{\"x\":0,\"y\":69,\"z\":4},\"boxSupply\":{\"x\":2,\"y\":70,\"z\":2},\"packingRoundSlots\":25,\"maxCarriedParcels\":2}");
        call("admin/regions/projection","PUT","{\"min\":{\"x\":2,\"y\":70,\"z\":0},\"max\":{\"x\":6,\"y\":70,\"z\":0}}");
        var small=List.of(new Material(Items.STONE,1),new Material(Items.SHULKER_BOX,2));prepare(small);Object smallPlan=plan(small);await(submit(smallPlan),"stored_offline");assertPlan(smallPlan,receipt("sf"));
        System.out.println("SUPERWY_PROJECTION_C_SMALL_OK: one partial stone parcel must arrive before the two requested empty shulkers");
        var materials=List.of(new Material(Items.STONE,2304),new Material(Items.SHULKER_BOX,2),new Material(Items.ENDER_PEARL,33),new Material(Items.GOLD_INGOT,1200),new Material(Items.DIAMOND_SWORD,3));
        prepare(materials);Object estimate=plan(materials);
        var observations=new CopyOnWriteArrayList<String>();
        net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.ALLOW_COMMAND.register(text->{if(text.startsWith("player sf spawn at ")){try{var order=store().orders().stream().filter(o->o.request.projectionParcels&&!o.terminal()).findFirst().orElse(null);if(order!=null){boolean unfinished=order.lines.stream().anyMatch(line->line.fetched+line.shortfall<line.requested);boolean partial=order.packages.stream().filter(p->p.custody.equals("robot")).anyMatch(p->dev.superwy.game.Items.contents(net.minecraft.client.Minecraft.getInstance().player.getInventory().getItem(p.inventorySlot)).stream().filter(s->!s.isEmpty()).count()<27);observations.add("unfinished="+unfinished+",partial="+partial);}}catch(Exception e){throw new RuntimeException(e);}}return true;});
        JsonObject done=await(submit(estimate),"stored_offline");assertPlan(estimate,receipt("sf"));
        if(!done.get("packingMode").getAsBoolean()||done.getAsJsonArray("packages").size()!=3)throw new AssertionError("Expected forced three-box projection packing");
        if(observations.stream().noneMatch(s->s.equals("unfinished=true,partial=true")))throw new AssertionError("Did not exercise a partial parcel retained during mid-order delivery: "+observations);
        single.getServer().runOnServer(server->{if(!server.getPlayerList().getPlayers().getFirst().getInventory().isEmpty())throw new AssertionError("Projection cargo left on robot");});
        System.out.println("SUPERWY_PROJECTION_ABC_E_OK: 3 real parcels plus 2 loose requested shulkers; exact ParcelPlan slot order/counts, 16/1 stack sizes, partial parcel survives intermediate delivery: "+observations);
        // Fresh empty recipients: 73 loose requested shulkers require exactly sf, sf_1 and sf_2.
        var loose=List.of(new Material(Items.SHULKER_BOX,73));prepare(loose);Object loosePlan=plan(loose);done=await(submit(loosePlan),"stored_offline");
        var recipientNames=done.getAsJsonObject("delivery").getAsJsonArray("recipientNames");if(!recipientNames.toString().equals("[\"sf\",\"sf_1\",\"sf_2\"]"))throw new AssertionError("Wrong fresh courier rollover: "+recipientNames);
        for(var entry:List.of(Map.entry("sf",36),Map.entry("sf_1",36),Map.entry("sf_2",1))){var got=receipt(entry.getKey());if(got.size()!=entry.getValue()||got.stream().anyMatch(box->!box.isEmpty()))throw new AssertionError("Loose shulker rollover receipt incorrect: "+entry+" "+got);}
        var recipients=(List<?>)loosePlan.getClass().getMethod("recipients").invoke(loosePlan);if(recipients.size()!=3)throw new AssertionError("Rollover differs from preview");
        System.out.println("SUPERWY_PROJECTION_D_OK: actual fresh sf/sf_1/sf_2 inventories 36/36/1 match ParcelPlan");
        prepare(List.of(new Material(Items.STONE,5)));Object shortage=plan(List.of(new Material(Items.STONE,10),new Material(Items.GOLD_INGOT,2)));
        done=await(submit(shortage),"partial_stored_offline");var got=receipt("sf");if(!got.equals(List.of(List.of("0:minecraft:stone=5"))))throw new AssertionError("Partial projection receipt: "+got);
        for(var line:done.getAsJsonArray("lines")){var value=line.getAsJsonObject();int fetched=value.get("itemId").getAsString().equals("minecraft:stone")?5:0;if(value.get("delivered").getAsInt()!=fetched||value.get("shortfall").getAsInt()!=value.get("requested").getAsInt()-fetched)throw new AssertionError("Shortage ledger mismatch: "+line);}
        var field=WarehouseGameTest.class.getDeclaredField("normalChatAnnouncements");field.setAccessible(true);context.waitFor(mc->{try{var messages=(List<?>)field.get(owner);return messages.stream().anyMatch(m->m.toString().contains("minecraft:stone 缺货5个，已交付5个"))&&messages.stream().anyMatch(m->m.toString().contains("minecraft:gold_ingot 缺货2个，已交付0个"));}catch(Exception e){throw new RuntimeException(e);}},100);
        System.out.println("SUPERWY_PROJECTION_F_OK: exact 5 stone parcel, shortage 5 stone/2 gold; actual public shortage messages");
        prepare(List.of(new Material(Items.STONE,5)));var normal=(JsonObject)invoke("submit",new Class[]{String.class,String.class},"SW_delivery","[{\"itemId\":\"minecraft:stone\",\"count\":1}]");done=await(normal,"stored_offline");if(done.get("packingMode").getAsBoolean()||!done.getAsJsonArray("packages").isEmpty())throw new AssertionError("Ordinary single was forced into a parcel");invoke("verifyLooseReceipt",new Class[]{TestSingleplayerContext.class,String.class,Map.class},single,"SW_delivery",Map.of(Items.STONE,1));
        var batch=(JsonObject)invoke("submitBatchFixture",new Class[]{String.class,String.class},"SW_batches","[{\"itemId\":\"minecraft:stone\",\"count\":2}]");done=await(batch,"stored_offline");if(done.get("packingMode").getAsBoolean()||!done.getAsJsonArray("packages").isEmpty())throw new AssertionError("Ordinary batch was forced into a parcel");invoke("verifyLooseReceipt",new Class[]{TestSingleplayerContext.class,String.class,Map.class},single,"SW_batches",Map.of(Items.STONE,2));
        System.out.println("SUPERWY_PROJECTION_G_OK: normal single and normal batch remain loose with actual 1/2 stone receipts");
        System.out.println("SUPERWY_PROJECTION_PARCELS_OK: A/B/C/D/E/F/G real client integration and persisted recipient inventories verified");
    }
}

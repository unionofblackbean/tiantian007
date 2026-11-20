package dev.superwy.requester;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.selection.*;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** Real Litematica selection editor, projection scanning, inventory comparison and legacy wire response. */
public final class ProjectionAreaGameTest {
    private static Object field(Object owner,String name)throws Exception{var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static void set(Object owner,String name,Object value)throws Exception{var f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static ProjectionOrderScreen screen(net.minecraft.client.Minecraft mc){return (ProjectionOrderScreen)mc.screen;}
    private static void click(ClientGameTestContext context,String prefix){context.runOnClient(mc->mc.screen.children().stream().filter(c->c instanceof Button b&&b.getMessage().getString().startsWith(prefix)).map(c->(Button)c).findFirst().orElseThrow().onPress(null));context.waitTicks(2);}
    private static void waitScan(ClientGameTestContext context){context.waitFor(mc->{try{return field(screen(mc),"finished")!=null;}catch(Exception e){throw new RuntimeException(e);}},400);}
    private static List<?> rows(ProjectionOrderScreen screen)throws Exception{return (List<?>)field(screen,"rows");}
    private static Object row(ProjectionOrderScreen screen,Item item)throws Exception{for(Object row:rows(screen))if(field(row,"item")==item)return row;throw new AssertionError("Missing material "+item);}
    private static long need(ProjectionOrderScreen screen,Item item)throws Exception{return (long)field(row(screen,item),"need");}
    private static void snapshot(ClientGameTestContext context,String name)throws Exception{Path source=context.takeScreenshot(name);Path target=Path.of(System.getProperty("superwy.projectionEvidenceDir","verification/projection"),name+".png");Files.createDirectories(target.getParent());Files.copy(source,target,StandardCopyOption.REPLACE_EXISTING);System.out.println("SUPERWY_PROJECTION_SCREENSHOT "+target.toAbsolutePath());}
    private static void forceProjection(ProjectionOrderScreen screen)throws Exception{
        set(screen,"source",AreaMaterials.Source.PROJECTION);var m=ProjectionOrderScreen.class.getDeclaredMethod("startScan");m.setAccessible(true);m.invoke(screen);
    }
    private static Button button(net.minecraft.client.Minecraft mc,String prefix){return mc.screen.children().stream().filter(c->c instanceof Button b&&b.getMessage().getString().startsWith(prefix)).map(c->(Button)c).findFirst().orElseThrow();}
    @SuppressWarnings("unchecked")
    private static List<ButtonBase> buttons(GuiBase gui)throws Exception{var f=GuiBase.class.getDeclaredField("buttons");f.setAccessible(true);return (List<ButtonBase>)f.get(gui);}
    private static String label(ButtonBase button)throws Exception{var f=ButtonBase.class.getDeclaredField("displayString");f.setAccessible(true);return (String)f.get(button);}
    private static ButtonBase pickup(GuiBase gui)throws Exception{for(var button:buttons(gui))if(label(button).equals("机器人取货"))return button;throw new AssertionError("Area editor pickup button missing");}
    private static void assertLayout(GuiBase gui)throws Exception{
        ButtonBase pickup=pickup(gui);check(pickup.getX()>=0&&pickup.getY()>=0&&pickup.getX()+pickup.getWidth()<=gui.width&&pickup.getY()+pickup.getHeight()<=gui.height,"Pickup button outside viewport");
        for(var other:buttons(gui))if(other!=pickup)check(!(pickup.getX()<other.getX()+other.getWidth()&&other.getX()<pickup.getX()+pickup.getWidth()&&pickup.getY()<other.getY()+other.getHeight()&&other.getY()<pickup.getY()+pickup.getHeight()),"Pickup overlaps original button "+label(other));
    }
    public static void run(ClientGameTestContext context,TestSingleplayerContext single)throws Exception{
        context.runOnClient(mc->{
            mc.setScreen(null);mc.options.pauseOnLostFocus=false;var manager=DataManager.getSelectionManager();var mode=SelectionManager.class.getDeclaredField("mode");mode.setAccessible(true);mode.set(manager,SelectionMode.SIMPLE);
            var selection=manager.getCurrentSelection();selection.setName("Projection acceptance");var selected=selection.getAllSubRegionBoxes().getFirst();selected.setPos1(new BlockPos(0,80,0));selected.setPos2(new BlockPos(15,80,0));
            var world=SchematicWorldHandler.getSchematicWorld();world.getChunkProvider().loadChunk(0,0);
            var states=List.of(Blocks.STONE,Blocks.DIRT,Blocks.OAK_PLANKS,Blocks.GLASS);for(int x=0;x<16;x++)world.setBlock(new BlockPos(x,80,0),states.get(x/4).defaultBlockState(),3);
            var export=fi.dy.masa.litematica.schematic.LitematicaSchematic.createFromWorld(world,selection,new fi.dy.masa.litematica.schematic.LitematicaSchematic.SchematicSaveInfo(false,false,true,true),"SuperWY local acceptance",message->{});
            Path output=Path.of(System.getProperty("superwy.projectionEvidenceDir","verification/projection"));Files.createDirectories(output);check(export!=null&&export.writeToFile(output,"projection-fixture",true),"Cannot export real Litematica visual fixture");
        });
        single.getServer().runOnServer(server->{var player=server.getPlayerList().getPlayers().getFirst();player.getInventory().clearContent();player.getInventory().setItem(0,new ItemStack(Items.STONE,1));player.getInventory().setItem(10,new ItemStack(Items.DIRT,2));player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,new ItemStack(Items.OAK_PLANKS,3));var box=new ItemStack(Items.SHULKER_BOX);box.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(new ItemStack(Items.GLASS,1))));player.getInventory().setItem(11,box);server.overworld().setBlockAndUpdate(new BlockPos(0,80,0),Blocks.STONE.defaultBlockState());});context.waitTicks(15);
        context.getInput().resizeWindow(1600,1000);context.waitTicks(5);
        for(int scale:new int[]{2,3,4}){
            context.runOnClient(mc->{mc.options.guiScale().set(scale);mc.resizeGui();check(mc.getWindow().getGuiScale()==scale,"Requested GUI scale did not take effect: "+scale);mc.getToastManager().clear();DataManager.getSelectionManager().openEditGui(null);assertLayout((GuiBase)mc.screen);});context.getInput().setCursorPos(0,0);context.waitTicks(3);snapshot(context,"area-editor-scale-"+scale);
        }
        context.getInput().resizeWindow(854,480);context.waitTicks(5);
        context.runOnClient(mc->{mc.options.guiScale().set(2);mc.resizeGui();DataManager.getSelectionManager().openEditGui(null);assertLayout((GuiBase)mc.screen);});snapshot(context,"area-editor-width-854");
        context.runOnClient(mc->{var gui=(GuiBase)mc.screen;var button=pickup(gui);gui.mouseClicked(new MouseButtonEvent(button.getX()+2,button.getY()+2,new MouseButtonInfo(0,0)),false);check(mc.screen instanceof ProjectionOrderScreen,"Real area button failed to open order screen");});waitScan(context);
        context.runOnClient(mc->{var screen=screen(mc);var finished=(AreaMaterials)field(screen,"finished");check(finished.source==AreaMaterials.Source.WORLD,"No placement overlaps: auto source must be the built world");check(rows(screen).size()==1&&need(screen,Items.STONE)==1,"World source must count only the real placed stone");check(!button(mc,"已放置方块：").active,"Placed-block toggle must be disabled for world source");});snapshot(context,"materials-world-source");
        click(context,"来源：");waitScan(context);context.runOnClient(mc->check(((AreaMaterials)field(screen(mc),"finished")).source==AreaMaterials.Source.PROJECTION&&button(mc,"已放置方块：").active,"Source toggle did not switch to projection"));
        System.out.println("SUPERWY_SOURCE_TOGGLE_OK: auto world source counted real blocks; toggle switched to projection");
        context.runOnClient(mc->{var screen=screen(mc);check(need(screen,Items.STONE)==3,"Placed stone not deducted");for(var entry:List.of(Map.entry(Items.STONE,1L),Map.entry(Items.DIRT,2L),Map.entry(Items.OAK_PLANKS,3L),Map.entry(Items.GLASS,1L)))check((long)field(row(screen,entry.getKey()),"have")==entry.getValue(),"Inventory column incorrect for "+entry);});snapshot(context,"materials-columns");
        click(context,"已放置方块：");waitScan(context);context.runOnClient(mc->check(need(screen(mc),Items.STONE)==4,"Do-not-subtract toggle failed"));
        click(context,"取货数量：");context.runOnClient(mc->{var m=ProjectionOrderScreen.class.getDeclaredMethod("orderLines");m.setAccessible(true);var lines=(List<ParcelPlan.Line>)m.invoke(screen(mc));check(lines.stream().mapToInt(ParcelPlan.Line::count).sum()==16,"All-quantity toggle failed");var screen=screen(mc);int left=(int)field(screen,"left"),top=(int)field(screen,"listTop");var excluded=rows(screen).getFirst();screen.mouseClicked(new MouseButtonEvent(left+30,top+8,new MouseButtonInfo(0,0)),false);check(!(boolean)field(excluded,"included"),"Material row exclusion failed");});snapshot(context,"materials-excluded");
        int[] edited=new int[1];
        context.runOnClient(mc->{var screen=screen(mc);int left=(int)field(screen,"left"),top=(int)field(screen,"listTop");var target=rows(screen).get(1);edited[0]=2*(int)field(target,"stackSize")+5;screen.mouseClicked(new MouseButtonEvent(left+30,top+28,new MouseButtonInfo(1,0)),false);check(mc.screen instanceof MaterialAmountScreen,"Right-click did not open amount editor");});context.waitTicks(2);snapshot(context,"material-amount-editor");
        context.runOnClient(mc->{var box=(net.minecraft.client.gui.components.EditBox)mc.screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.EditBox).findFirst().orElseThrow();box.setValue("2组+5");button(mc,"确定").onPress(null);check(mc.screen instanceof ProjectionOrderScreen,"Amount editor did not return");var screen=screen(mc);var target=rows(screen).get(1);check((long)field(target,"custom")==edited[0],"Manual amount not stored");var m=ProjectionOrderScreen.class.getDeclaredMethod("orderLines");m.setAccessible(true);var lines=(List<ParcelPlan.Line>)m.invoke(screen);String id=(String)field(target,"id");check(lines.stream().anyMatch(l->l.itemId().equals(id)&&l.count()==edited[0]),"Manual amount not used in order");});snapshot(context,"materials-manual-amount");
        System.out.println("SUPERWY_MANUAL_AMOUNT_OK: right-click editor stored 2组+5 and order used it");
        click(context,"确认清单");context.runOnClient(mc->{var plan=(ParcelPlan.Plan)field(screen(mc),"plan");check(plan!=null&&plan.boxes().size()==1&&plan.recipients().size()==1,"Preview box/courier count incorrect");var tooltip=new ParcelTooltip.Client(new ParcelTooltip(List.of(new ItemStack(Items.STONE))));check(tooltip.getWidth(mc.font)==164&&tooltip.getHeight(mc.font)==58,"Tooltip is not 9 by 3");});
        double[] hover=context.computeOnClient(mc->new double[]{((int)field(screen(mc),"left")+12)*mc.getWindow().getGuiScale(),((int)field(screen(mc),"listTop")+12)*mc.getWindow().getGuiScale()});context.getInput().setCursorPos(hover[0],hover[1]);context.waitTicks(3);snapshot(context,"parcel-tooltip-width-854");
        // Simulated pre-0.5.6 robot: its begin arity check rejects the third 'parcels' argument.
        var rejected=new AtomicReference<String>();var outbound=new ArrayList<String>();String legacyName=context.computeOnClient(mc->mc.player.getName().getString());String prefix="tell "+legacyName+" ";
        net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.ALLOW_COMMAND.register(command->{if(command.startsWith(prefix)){outbound.add(command);String body=command.substring(prefix.length());var packet=dev.superwy.protocol.BatchProtocol.parse(body,false);if(packet.action().equals("begin")){check(packet.args().length==3&&packet.args()[2].equals("parcels"),"Actual projection begin flag missing");rejected.set(packet.id());}return false;}return true;});
        context.runOnClient(mc->{var cfg=(RequesterConfig)field(RequesterClient.INSTANCE,"config");cfg.botName=legacyName;cfg.fakePlayerName="sf";cfg.shortKey="";});
        click(context,"下发任务");check(rejected.get()!=null&&!outbound.isEmpty(),"Send did not emit actual projection order");String id=rejected.get();
        single.getServer().runOnServer(server->server.getPlayerList().getPlayers().getFirst().sendSystemMessage(net.minecraft.network.chat.Component.literal(legacyName+"悄悄地对你说：swbatchreply1 "+id+" error INVALID_REQUEST")));
        context.waitFor(mc->{try{var tasks=(Map<?,?>)field(RequesterClient.INSTANCE,"tasks");var task=tasks.get(id);var draft=(BatchDraft)task.getClass().getField("batch").get(task);return draft.status.contains("请把机器人更新到 0.5.6");}catch(Exception e){throw new RuntimeException(e);}},100);
        System.out.println("SUPERWY_PROJECTION_H_OK: actual outgoing parcels begin; simulated legacy arity rejection received over real system-chat packet; update-to-0.5.6 message verified");
        // Exercise send-before-validation guard through the real confirmation button and rows.
        for(boolean kinds:new boolean[]{true,false}){
            context.runOnClient(mc->{RequesterClient.INSTANCE.openProjectionOrder(null);});waitScan(context);
            context.runOnClient(mc->{var screen=screen(mc);var rows=(List<Object>)field(screen,"rows");rows.clear();Class<?> row=Class.forName("dev.superwy.requester.ProjectionOrderScreen$Row");var constructor=row.getDeclaredConstructor(Item.class,long.class,long.class);constructor.setAccessible(true);int n=kinds?129:1;var items=net.minecraft.core.registries.BuiltInRegistries.ITEM.stream().filter(item->item!=Items.AIR).limit(n).toList();for(var item:items)rows.add(constructor.newInstance(item,kinds?1L:1_000_001L,0L));});
            int before=outbound.size();click(context,"确认清单");context.runOnClient(mc->{check(field(screen(mc),"plan")==null&&field(screen(mc),"error").toString().contains("缩小选区"),"Oversize order not blocked with readable error");});check(outbound.size()==before,"Oversize manifest sent before validation");snapshot(context,kinds?"limit-129-materials":"limit-million-items");
        }
        System.out.println("SUPERWY_PROJECTION_I_OK: real confirmation blocks 129 kinds and 1000001 units before sending, with shrink-selection error");
        // 128 x 128 x 64 = 1048576 real schematic blocks. Setup time is separate from scan latency.
        var scanStarted=new java.util.concurrent.atomic.AtomicLong();
        context.runOnClient(mc->{mc.setScreen(null);var selection=DataManager.getSelectionManager().getCurrentSelection();var selected=selection.getAllSubRegionBoxes().getFirst();selected.setPos1(new BlockPos(0,80,0));selected.setPos2(new BlockPos(127,143,127));var world=SchematicWorldHandler.getSchematicWorld();var palette=List.of(Blocks.STONE,Blocks.DIRT,Blocks.OAK_PLANKS,Blocks.GLASS,Blocks.COBBLESTONE,Blocks.ANDESITE,Blocks.DIORITE,Blocks.GRANITE,Blocks.SANDSTONE,Blocks.BRICKS,Blocks.GOLD_BLOCK,Blocks.IRON_BLOCK,Blocks.DIAMOND_BLOCK,Blocks.EMERALD_BLOCK,Blocks.OBSIDIAN,Blocks.QUARTZ_BLOCK);for(int cx=0;cx<8;cx++)for(int cz=0;cz<8;cz++)world.getChunkProvider().loadChunk(cx,cz);for(int x=0;x<128;x++)for(int z=0;z<128;z++)for(int y=80;y<144;y++)world.setBlock(new BlockPos(x,y,z),palette.get((x+z+y)%palette.size()).defaultBlockState(),3);scanStarted.set(System.nanoTime());RequesterClient.INSTANCE.openProjectionOrder(null);forceProjection(screen(mc));});
        long start=scanStarted.get();var timings=new ArrayList<Double>();var fps=new ArrayList<Integer>();
        while(context.computeOnClient(mc->field(screen(mc),"finished")==null)){
            long tick=System.nanoTime();context.waitTick();timings.add((System.nanoTime()-tick)/1_000_000.0);fps.add(context.computeOnClient(mc->mc.getFps()));check(timings.size()<400,"Large scan exceeded 400 ticks");
        }
        double elapsed=(System.nanoTime()-start)/1_000_000.0;
        context.runOnClient(mc->{var finished=(AreaMaterials)field(screen(mc),"finished");check(finished.blocks==1048575&&finished.placedSkipped==1,"Large scan must count all 1048576 blocks minus exactly the placed stone: "+finished.blocks+" skipped="+finished.placedSkipped);check(finished.counts.size()==16,"Large scan material kinds incorrect");});
        snapshot(context,"large-selection-result");System.out.println("SUPERWY_PROJECTION_PERFORMANCE blocks=1048576 kinds=16 elapsedMs="+elapsed+" observedWaitTickMaxMs="+timings.stream().mapToDouble(Double::doubleValue).max().orElse(0)+" minReportedFps="+fps.stream().mapToInt(Integer::intValue).min().orElse(-1)+" samples="+timings.size());
        System.out.println("SUPERWY_PROJECTION_REQUESTER_OK: real area editor button/layout, required/have/missing columns, hotbar/main/offhand/nested inventory, toggles/exclusion, box/courier preview, 9x3 tooltip, outgoing order, H/I and large actual schematic scan");
    }
}

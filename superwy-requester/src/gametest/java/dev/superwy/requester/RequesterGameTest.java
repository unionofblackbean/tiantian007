package dev.superwy.requester;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.lwjgl.glfw.GLFW;
import java.net.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

/** Real client + actual Litematica/MaLiLib; HTTP recipient is an isolated fixture. */
public final class RequesterGameTest implements FabricClientGameTest {
    public void runTest(ClientGameTestContext context){
        HttpServer endpoint=null;
        try{
            context.runOnClient(mc->GLFW.glfwHideWindow(mc.getWindow().handle()));
            var received=new AtomicReference<JsonObject>();var requestCount=new AtomicInteger();var queueCalls=new AtomicInteger();var response=new AtomicInteger(202);endpoint=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            endpoint.createContext("/api/v1/orders",x->{if(x.getRequestMethod().equals("GET")){byte[] data="{\"orderId\":\"ord_projection_fixture\",\"state\":\"queued\",\"queue\":{\"ahead\":1}}".getBytes();x.sendResponseHeaders(200,data.length);x.getResponseBody().write(data);x.close();return;}int status=response.get();received.set(JsonParser.parseString(new String(x.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject());byte[] body="{\"orderId\":\"ord_projection_fixture\",\"state\":\"queued\",\"queue\":{\"sequence\":22,\"position\":2,\"ahead\":1,\"totalPending\":2,\"active\":false}}".getBytes();x.sendResponseHeaders(status,body.length);x.getResponseBody().write(body);x.close();requestCount.incrementAndGet();});
            endpoint.createContext("/api/v1/queue",x->{queueCalls.incrementAndGet();x.sendResponseHeaders(500,-1);x.close();});endpoint.start();
            int port=endpoint.getAddress().getPort();
            try(TestSingleplayerContext single=context.worldBuilder().adjustSettings(settings->settings.setAllowCommands(true)).create()){
                single.getClientLevel().waitForChunksDownload();
                if(Boolean.getBoolean("superwy.projectionParcelsTest")){single.getServer().runCommand("fill -12 69 -12 12 69 12 minecraft:stone");single.getServer().runCommand("tp @a 0.5 70 0.5");ProjectionAreaGameTest.run(context,single);return;}
                single.getServer().runCommand("fill -12 69 -12 12 69 12 minecraft:stone");single.getServer().runCommand("tp @a 0.5 70 0.5");
                context.waitTicks(10);
                context.runOnClient(mc->{var field=RequesterClient.class.getDeclaredField("config");field.setAccessible(true);field.set(RequesterClient.INSTANCE,new RequesterConfig());});
                context.getInput().pressKey(GLFW.GLFW_KEY_SLASH);context.getInput().typeChars("swrequest config");context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);context.waitTicks(3);
                context.runOnClient(mc->{
                    if(!(mc.screen instanceof RequesterSettingsScreen screen))throw new AssertionError("Game settings screen not opened");
                    var f=RequesterSettingsScreen.class.getDeclaredField("fields");f.setAccessible(true);
                    @SuppressWarnings("unchecked") var fields=(java.util.Map<String,net.minecraft.client.gui.components.EditBox>)f.get(screen);
                    fields.get("bot").setValue("WarehouseBot");fields.get("recipient").setValue("BuilderCargo");fields.get("key").setValue("Ab123");fields.get("item").setValue("minecraft:stone_slab");fields.get("count").setValue("12");
                });
                System.out.println("REQUESTER_SETTINGS_SCREENSHOT "+context.takeScreenshot("requester-settings"));
                context.runOnClient(mc->{
                    var screen=mc.screen;screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button b&&b.getMessage().getString().equals("保存")).map(c->(net.minecraft.client.gui.components.Button)c).findFirst().orElseThrow().onPress(null);
                    var saved=RequesterConfig.load(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("superwy-requester/config.json"));
                    if(!saved.transport.equals("chat")||!saved.botName.equals("WarehouseBot")||!saved.shortKey.equals("Ab123")||saved.defaultCount!=12||!saved.fakePlayerName.equals("BuilderCargo"))throw new AssertionError("Game settings did not persist");
                });
                System.out.println("SUPERWY_REQUESTER_SETTINGS_OK: game-local settings window, private default, bot/recipient/material/count/key saved to disk");
                context.getInput().pressKey(GLFW.GLFW_KEY_SLASH);context.getInput().typeChars("swrequest mode http");context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);context.waitTicks(2);
                context.runOnClient(mc->{var saved=RequesterConfig.load(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("superwy-requester/config.json"));if(!saved.transport.equals("http"))throw new AssertionError("In-game HTTP transport switch did not persist");});
                System.out.println("SUPERWY_REQUESTER_HTTP_SWITCH_OK: game command switches and persists HTTP transport before projection submission");
                context.runOnClient(mc->{
                    mc.options.pauseOnLostFocus=false;
                    var focused=mc.getWindow().getClass().getDeclaredField("focused");focused.setAccessible(true);focused.setBoolean(mc.getWindow(),true);mc.mouseHandler.grabMouse();
                    var schematic=SchematicWorldHandler.getSchematicWorld();schematic.getChunkProvider().loadChunk(0,0);
                    schematic.setBlock(new BlockPos(0,71,3),Blocks.STONE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE,SlabType.DOUBLE),3);
                    var config=new RequesterConfig();config.transport="http";config.baseUrl="http://127.0.0.1:"+port;config.token="fixture-principal-token-12345678901234567890";config.fakePlayerName="BuilderCargo";
                    var field=RequesterClient.class.getDeclaredField("config");field.setAccessible(true);field.set(RequesterClient.INSTANCE,config);
                });
                context.getInput().lookAt(new BlockPos(0,71,3));context.waitTicks(2);
                context.runOnClient(mc->{
                    var picked=new ProjectionBridge().pick(mc,8);if(picked==null||!picked.item().is(Items.STONE_SLAB))throw new AssertionError("Projection material adapter failed: "+picked);
                });
                context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_MIDDLE);context.waitTick();
                context.runOnClient(mc->{if(!RequesterClient.INSTANCE.prompt.isOpen()||mc.screen!=null)throw new AssertionError("Middle click failed to open non-modal prompt");});
                context.getInput().pressKey(GLFW.GLFW_KEY_1);context.getInput().pressKey(GLFW.GLFW_KEY_2);
                context.runOnClient(mc->{if(RequesterClient.INSTANCE.prompt.quantity()!=12)throw new AssertionError("Digit mixin failed");});
                context.getInput().holdKey(GLFW.GLFW_KEY_W);
                context.runOnClient(mc->{if(!mc.options.keyUp.isDown()||mc.screen!=null)throw new AssertionError("Prompt captured movement");});
                context.getInput().releaseKey(GLFW.GLFW_KEY_W);
                System.out.println("REQUESTER_OVERLAY_SCREENSHOT "+context.takeScreenshot("requester-quantity"));
                context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
                context.waitFor(mc->received.get()!=null,100);
                var request=received.get();var item=request.getAsJsonArray("items").get(0).getAsJsonObject();
                if(!item.get("itemId").getAsString().equals("minecraft:stone_slab")||item.get("count").getAsInt()!=12||!request.getAsJsonObject("delivery").get("fakePlayerName").getAsString().equals("BuilderCargo"))throw new AssertionError("Wrong API request: "+request);
                context.runOnClient(mc->{if(RequesterClient.INSTANCE.prompt.isOpen())throw new AssertionError("Prompt should close after submission");});
                // The server still reports queued; another order must be submitted immediately.
                context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
                context.runOnClient(mc->{if(!RequesterClient.INSTANCE.prompt.isOpen())throw new AssertionError("Second prompt did not open");});
                context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
                context.waitFor(mc->requestCount.get()==2,100);
                response.set(503);
                context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_MIDDLE);context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
                context.waitFor(mc->requestCount.get()==3,100);context.waitTicks(5);
                response.set(202);
                context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_MIDDLE);context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
                context.waitFor(mc->requestCount.get()==4,100);context.waitTicks(5);
                if(queueCalls.get()!=0)throw new AssertionError("Requester polled the server queue");
                System.out.println("SUPERWY_REQUESTER_INDEPENDENT_REQUESTS_OK: repeated submissions while server queued; new submission after HTTP 503; no queue polling");
                String ownName=context.computeOnClient(mc->mc.player.getName().getString());
                clientCommand(context,"swrequest bot "+ownName);clientCommand(context,"swrequest recipient BuilderCargo");clientCommand(context,"swrequest key off");clientCommand(context,"swrequest mode chat");clientCommand(context,"swrequest request minecraft:stone 1");
                String pending=context.computeOnClient(mc->{
                    var f=RequesterClient.class.getDeclaredField("awaiting");f.setAccessible(true);return (String)((java.util.Map<?,?>)f.get(RequesterClient.INSTANCE)).keySet().iterator().next();
                });
                String receipt="swreply1 "+pending+" accepted ord_system_fixture 1";
                single.getServer().runOnServer(server->server.getPlayerList().getPlayers().getFirst().sendSystemMessage(net.minecraft.network.chat.Component.literal("<Other> "+ownName+"悄悄地对你说："+receipt)));
                context.waitTicks(5);
                context.runOnClient(mc->{var f=RequesterClient.class.getDeclaredField("lastChatOrder");f.setAccessible(true);if(f.get(RequesterClient.INSTANCE)!=null)throw new AssertionError("Public text forged a receipt");});
                single.getServer().runOnServer(server->server.getPlayerList().getPlayers().getFirst().sendSystemMessage(net.minecraft.network.chat.Component.literal(ownName+"悄悄地对你说："+receipt)));
                context.waitTicks(6);
                context.runOnClient(mc->{var f=RequesterClient.class.getDeclaredField("lastChatOrder");f.setAccessible(true);if(!"ord_system_fixture".equals(f.get(RequesterClient.INSTANCE)))throw new AssertionError("System private receipt was not accepted");});
                System.out.println("SUPERWY_REQUESTER_SYSTEM_RECEIPT_OK: server system-packet Chinese receipt resolves pending private request; public prefix cannot forge receipt");
                clientCommand(context,"swrequest config");
                context.runOnClient(mc->{var screen=(RequesterSettingsScreen)mc.screen;screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button b&&b.getMessage().getString().startsWith("取货：")).map(c->(net.minecraft.client.gui.components.Button)c).findFirst().orElseThrow().onPress(null);});
                context.getInput().pressKey(GLFW.GLFW_KEY_G);
                context.runOnClient(mc->{var screen=(RequesterSettingsScreen)mc.screen;screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button b&&b.getMessage().getString().startsWith("确认：")).map(c->(net.minecraft.client.gui.components.Button)c).findFirst().orElseThrow().onPress(null);});
                context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_4);
                context.runOnClient(mc->{var screen=(RequesterSettingsScreen)mc.screen;screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button b&&b.getMessage().getString().startsWith("队列：")).map(c->(net.minecraft.client.gui.components.Button)c).findFirst().orElseThrow().onPress(null);});
                context.getInput().pressKey(GLFW.GLFW_KEY_H);
                System.out.println("REQUESTER_HOTKEY_SCREENSHOT "+context.takeScreenshot("requester-hotkeys"));
                context.runOnClient(mc->{mc.screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button b&&b.getMessage().getString().equals("保存")).map(c->(net.minecraft.client.gui.components.Button)c).findFirst().orElseThrow().onPress(null);
                    var saved=RequesterConfig.load(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("superwy-requester/config.json"));if(saved.pickupKey!=GLFW.GLFW_KEY_G||saved.confirmKey!=RequestHotkey.mouse(GLFW.GLFW_MOUSE_BUTTON_4)||saved.queueKey!=GLFW.GLFW_KEY_H)throw new AssertionError("Custom hotkeys were not captured and persisted");});
                context.getInput().lookAt(new BlockPos(0,71,3));context.getInput().pressKey(GLFW.GLFW_KEY_G);
                context.runOnClient(mc->{if(!RequesterClient.INSTANCE.prompt.isOpen())throw new AssertionError("Custom pickup keyboard did not open projection prompt");});
                context.getInput().pressKey(GLFW.GLFW_KEY_2);context.getInput().pressKey(GLFW.GLFW_KEY_H);
                context.runOnClient(mc->{var f=RequesterClient.class.getDeclaredField("batch");f.setAccessible(true);var b=(BatchDraft)f.get(RequesterClient.INSTANCE);if(b.items.size()!=1||b.items.getFirst().count()!=2||RequesterClient.INSTANCE.prompt.isOpen())throw new AssertionError("Queue key did not add projection count and close prompt");b.capacity(22);});
                context.getInput().pressKey(GLFW.GLFW_KEY_G);context.getInput().pressKey(GLFW.GLFW_KEY_3);context.getInput().pressKey(GLFW.GLFW_KEY_H);
                verifyDraftEditor(context);
                System.out.println("REQUESTER_QUEUE_SCREENSHOT "+context.takeScreenshot("requester-queue"));
                context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_4);
                context.runOnClient(mc->{var f=RequesterClient.class.getDeclaredField("batch");f.setAccessible(true);var b=(BatchDraft)f.get(RequesterClient.INSTANCE);if(!b.submitted||b.items.size()!=2||b.items.get(1).count()!=3||mc.screen!=null||RequesterClient.INSTANCE.prompt.isOpen())throw new AssertionError("Custom mouse confirmation without prompt failed");f.set(RequesterClient.INSTANCE,null);
                    var cfg=RequesterClient.class.getDeclaredField("config");cfg.setAccessible(true);var c=(RequesterConfig)cfg.get(RequesterClient.INSTANCE);c.pickupKey=-3;c.confirmKey=257;c.queueKey=92;var save=RequesterClient.class.getDeclaredMethod("saveSettings",RequesterConfig.class);save.setAccessible(true);save.invoke(RequesterClient.INSTANCE,c);});
                System.out.println("SUPERWY_REQUESTER_CUSTOM_KEYS_QUEUE_OK: real settings captures keyboard pickup, mouse confirmation and keyboard queue; persisted; Litematica targets and quantities added; custom mouse confirms two-line manifest without a Screen or quantity prompt");
                context.getInput().lookAt(new BlockPos(0,71,3));context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_MIDDLE);
                context.runOnClient(mc->{if(!RequesterClient.INSTANCE.prompt.isOpen())throw new AssertionError("Movement prompt did not open");});
                double before=context.computeOnClient(mc->mc.player.getZ());
                context.getInput().holdKey(GLFW.GLFW_KEY_W);context.waitFor(mc->!RequesterClient.INSTANCE.prompt.isOpen(),100);context.getInput().releaseKey(GLFW.GLFW_KEY_W);
                context.runOnClient(mc->{if(Math.abs(mc.player.getZ()-before)<.1)throw new AssertionError("Player did not actually move");});
                System.out.println("SUPERWY_REQUESTER_GAME_TEST_OK: Litematica material, middle click, quantity, movement passthrough, API request, actual moving auto-close");
                verifyVanillaBindings(context,single);
            }
        }catch(Exception e){throw new RuntimeException(e);}finally{if(endpoint!=null)endpoint.stop(0);}
    }
    private BatchDraft currentBatch()throws Exception{
        var f=RequesterClient.class.getDeclaredField("batch");f.setAccessible(true);return (BatchDraft)f.get(RequesterClient.INSTANCE);
    }
    private void clickDraft(ClientGameTestContext context,String control,int row)throws Exception{
        double[] point=context.computeOnClient(mc->{
            var f=RequesterClient.class.getDeclaredField("batchPage");f.setAccessible(true);int page=f.getInt(RequesterClient.INSTANCE);
            var tasks=RequesterClient.class.getDeclaredField("tasks");tasks.setAccessible(true);int rows=((java.util.Map<?,?>)tasks.get(RequesterClient.INSTANCE)).isEmpty()?6:4;
            int visible=Math.min(rows,currentBatch().items.size()-page*rows),w=mc.getWindow().getGuiScaledWidth(),h=mc.getWindow().getGuiScaledHeight();
            int panelWidth=Math.min(280,w-16),x=w-panelWidth-8,y=Math.max(8,h-80-visible*18-36);
            double mx=control.equals("delete")?w-35:control.equals("next")?x+80:control.equals("prev")?x+28:w-55;
            double my=control.equals("delete")?y+28+row*18:h-76;
            return new double[]{mx*mc.getWindow().getWidth()/w,my*mc.getWindow().getHeight()/h};
        });
        context.getInput().setCursorPos(point[0],point[1]);context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);context.waitTick();
    }
    private void verifyDraftEditor(ClientGameTestContext context)throws Exception{
        var original=context.computeOnClient(mc->currentBatch());
        context.runOnClient(mc->{
            var draft=new BatchDraft();draft.id="e".repeat(32);draft.bot=mc.player.getName().getString();draft.recipient="DraftCargo";draft.key="";draft.deadline=Long.MAX_VALUE;
            for(int i=1;i<=7;i++)draft.add("minecraft:stone",i,64);
            draft.capacity(0);var f=RequesterClient.class.getDeclaredField("batch");f.setAccessible(true);f.set(RequesterClient.INSTANCE,draft);
            var page=RequesterClient.class.getDeclaredField("batchPage");page.setAccessible(true);page.setInt(RequesterClient.INSTANCE,0);
        });
        context.getInput().pressKey(GLFW.GLFW_KEY_T);context.waitTick();
        context.runOnClient(mc->{if(!(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen))throw new AssertionError("T did not open vanilla chat");});
        System.out.println("REQUESTER_DRAFT_EDITOR_SCREENSHOT "+context.takeScreenshot("requester-draft-editor"));
        clickDraft(context,"delete",1);
        context.runOnClient(mc->{var b=currentBatch();if(b.items.size()!=6||b.items.get(1).count()!=3||b.submitted)throw new AssertionError("Chat click did not remove only the selected row");});
        clickDraft(context,"next",0);clickDraft(context,"delete",0);
        context.runOnClient(mc->{var b=currentBatch();if(b.items.size()!=5||b.items.getLast().count()!=7)throw new AssertionError("Delete on second page chose wrong material");});
        clickDraft(context,"prev",0);clickDraft(context,"cancel",0);
        context.runOnClient(mc->{if(currentBatch()!=null||!(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen))throw new AssertionError("Cancel did not clear local draft while preserving chat");});
        context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
        var previous=context.computeOnClient(mc->{
            var field=RequesterClient.class.getDeclaredField("tasks");field.setAccessible(true);
            @SuppressWarnings("unchecked") var tasks=(java.util.Map<String,RequestTask>)field.get(RequesterClient.INSTANCE);
            var copy=new java.util.LinkedHashMap<>(tasks);tasks.clear();
            var draft=new BatchDraft();draft.id="f".repeat(32);draft.add("minecraft:stone",128,64);draft.submitted=true;draft.accepted=true;draft.requestedUnits=128;draft.fetchedUnits=64;
            var task=new RequestTask(draft.id,"批量","Bot");task.batch=draft;task.update(0,"active",System.currentTimeMillis());tasks.put(task.id,task);
            var f=RequesterClient.class.getDeclaredField("batch");f.setAccessible(true);f.set(RequesterClient.INSTANCE,draft);return copy;
        });
        context.waitTick();System.out.println("REQUESTER_ACTIVE_LINE_SCREENSHOT "+context.takeScreenshot("requester-active-line"));
        context.runOnClient(mc->{var field=RequesterClient.class.getDeclaredField("tasks");field.setAccessible(true);var tasks=(java.util.Map<String,RequestTask>)field.get(RequesterClient.INSTANCE);tasks.values().iterator().next().update(3,"queued",System.currentTimeMillis());});
        context.waitTick();System.out.println("REQUESTER_QUEUED_LINE_SCREENSHOT "+context.takeScreenshot("requester-queued-line"));
        context.runOnClient(mc->{var field=RequesterClient.class.getDeclaredField("tasks");field.setAccessible(true);var tasks=(java.util.Map<String,RequestTask>)field.get(RequesterClient.INSTANCE);tasks.clear();tasks.putAll(previous);var f=RequesterClient.class.getDeclaredField("batch");f.setAccessible(true);f.set(RequesterClient.INSTANCE,original);});
        System.out.println("SUPERWY_REQUESTER_DRAFT_EDITOR_OK: T opens chat; actual left clicks delete selected row, paginate and cancel whole draft; legacy zero capacity keeps manifest");
    }
    private void selectVanillaBinding(ClientGameTestContext context,String name,boolean reset)throws Exception{
        context.runOnClient(mc->{
            var screen=(net.minecraft.client.gui.screens.options.controls.KeyBindsScreen)mc.screen;
            var f=screen.getClass().getDeclaredField("keyBindsList");f.setAccessible(true);var list=(net.minecraft.client.gui.screens.options.controls.KeyBindsList)f.get(screen);
            for(var entry:list.children())if(entry instanceof net.minecraft.client.gui.screens.options.controls.KeyBindsList.KeyEntry){
                var field=entry.getClass().getDeclaredField("key");field.setAccessible(true);var mapping=(net.minecraft.client.KeyMapping)field.get(entry);
                if(mapping.getName().equals("key.superwy_requester."+name)){var button=entry.getClass().getDeclaredField(reset?"resetButton":"changeButton");button.setAccessible(true);((net.minecraft.client.gui.components.Button)button.get(entry)).onPress(null);return;}
            }
            throw new AssertionError("Vanilla key binding row missing: "+name);
        });
    }
    private RequesterConfig saved()throws Exception{return RequesterConfig.load(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("superwy-requester/config.json"));}
    private void verifyVanillaBindings(ClientGameTestContext context,TestSingleplayerContext single)throws Exception{
        var reload=context.computeOnClient(mc->{mc.options.languageCode="zh_cn";mc.getLanguageManager().setSelected("zh_cn");return mc.reloadResourcePacks();});context.waitFor(mc->reload.isDone(),400);reload.join();context.waitFor(mc->mc.getOverlay()==null,200);
        context.runOnClient(mc->{
            if(java.util.Arrays.stream(mc.options.keyMappings).filter(k->k.getName().startsWith("key.superwy_requester.")).count()!=3)throw new AssertionError("Three native mappings not registered");
            var category=net.minecraft.client.KeyMapping.get("key.superwy_requester.pickup").getCategory();if(!category.label().getString().equals("SuperWY 取货端"))throw new AssertionError("Native category not localized: "+category.label().getString());
            mc.setScreen(new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(new net.minecraft.client.gui.screens.options.controls.ControlsScreen(null,mc.options),mc.options));
            var f=mc.screen.getClass().getDeclaredField("keyBindsList");f.setAccessible(true);var list=(net.minecraft.client.gui.screens.options.controls.KeyBindsList)f.get(mc.screen);list.setScrollAmount(Double.MAX_VALUE);
        });context.waitTicks(3);
        System.out.println("REQUESTER_NATIVE_BINDINGS_SCREENSHOT "+context.takeScreenshot("requester-native-bindings"));
        selectVanillaBinding(context,"pickup",false);context.getInput().pressKey(GLFW.GLFW_KEY_J);
        selectVanillaBinding(context,"confirm",false);context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_5);
        selectVanillaBinding(context,"queue",false);context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);context.waitTicks(3);
        context.runOnClient(mc->{if(saved().queueKey!=RequestHotkey.UNBOUND)throw new AssertionError("Native unbind was not persisted");});
        selectVanillaBinding(context,"queue",false);context.getInput().pressKey(GLFW.GLFW_KEY_K);context.waitTicks(3);
        context.runOnClient(mc->{var c=saved();if(c.pickupKey!=GLFW.GLFW_KEY_J||c.confirmKey!=RequestHotkey.mouse(GLFW.GLFW_MOUSE_BUTTON_5)||c.queueKey!=GLFW.GLFW_KEY_K||!c.nativeHotkeysInitialized)throw new AssertionError("Native keyboard/mouse edits did not persist to config");});
        for(String action:new String[]{"pickup","confirm","queue"})selectVanillaBinding(context,action,true);context.waitTicks(3);
        context.runOnClient(mc->{var c=saved();if(c.pickupKey!=-3||c.confirmKey!=257||c.queueKey!=92)throw new AssertionError("Native per-key reset did not restore defaults");});
        selectVanillaBinding(context,"pickup",false);context.getInput().pressKey(GLFW.GLFW_KEY_J);
        selectVanillaBinding(context,"confirm",false);context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_5);
        selectVanillaBinding(context,"queue",false);context.getInput().pressKey(GLFW.GLFW_KEY_K);context.waitTicks(3);
        context.runOnClient(mc->{
            mc.options.save();for(String action:new String[]{"pickup","confirm","queue"})net.minecraft.client.KeyMapping.get("key.superwy_requester."+action).setKey(com.mojang.blaze3d.platform.InputConstants.UNKNOWN);
            mc.options.load();net.minecraft.client.KeyMapping.resetMapping();
            var pickup=net.minecraft.client.KeyMapping.get("key.superwy_requester.pickup");
            if(net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.getBoundKeyOf(pickup).getValue()!=GLFW.GLFW_KEY_J)throw new AssertionError("Native options reload lost edited mapping");
            mc.setScreen(null);var focus=mc.getWindow().getClass().getDeclaredField("focused");focus.setAccessible(true);focus.setBoolean(mc.getWindow(),true);mc.mouseHandler.grabMouse();
        });
        single.getServer().runCommand("tp @a 0.5 70 0.5");context.waitTicks(5);context.getInput().lookAt(new BlockPos(0,71,3));context.getInput().pressKey(GLFW.GLFW_KEY_J);
        context.runOnClient(mc->{if(!RequesterClient.INSTANCE.prompt.isOpen())throw new AssertionError("Native pickup key did not take effect");});
        context.getInput().pressKey(GLFW.GLFW_KEY_1);context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_5);
        context.runOnClient(mc->{if(RequesterClient.INSTANCE.prompt.isOpen())throw new AssertionError("Native mouse confirmation did not submit");});
        context.getInput().pressKey(GLFW.GLFW_KEY_K);
        context.runOnClient(mc->{var f=RequesterClient.class.getDeclaredField("batch");f.setAccessible(true);var b=(BatchDraft)f.get(RequesterClient.INSTANCE);if(b==null||b.items.size()!=1)throw new AssertionError("Native queue key did not add schematic material");var abort=RequesterClient.class.getDeclaredMethod("abortBatch");abort.setAccessible(true);abort.invoke(RequesterClient.INSTANCE);});
        clientCommand(context,"swrequest config");
        context.runOnClient(mc->{
            var screen=mc.screen;boolean mirrored=screen.children().stream().anyMatch(c->c instanceof net.minecraft.client.gui.components.Button b&&b.getMessage().getString().equals("取货：J"));if(!mirrored)throw new AssertionError("Mod settings did not mirror native key edit");screen.onClose();
        });
        System.out.println("SUPERWY_REQUESTER_NATIVE_BINDINGS_OK: actual vanilla Controls/KeyBindsScreen lists three localized mappings; keyboard and mouse edits, Escape unbind, individual reset, options reload and config persistence; actual projection pickup, confirmation and queue follow native edits; mod settings mirror values");
    }
    private void clientCommand(ClientGameTestContext context,String command){context.getInput().pressKey(GLFW.GLFW_KEY_SLASH);context.getInput().typeChars(command);context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);context.waitTicks(2);}
}

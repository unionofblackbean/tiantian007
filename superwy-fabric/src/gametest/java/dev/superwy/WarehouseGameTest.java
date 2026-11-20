package dev.superwy;

import com.google.gson.*;
import dev.superwy.core.*;
import dev.superwy.protocol.WhisperProtocol;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import org.lwjgl.glfw.GLFW;
import java.net.*;
import java.net.http.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

/** Isolated real-client/integrated-server fixture. Never connects to an external server. */
@SuppressWarnings("UnstableApiUsage")
public final class WarehouseGameTest implements FabricClientGameTest {
    private ClientGameTestContext context;private String token,base;private final HttpClient http=HttpClient.newHttpClient();
    private final Set<String> verifiedSpawnPositions=new HashSet<>();
    private final List<String> publicAnnouncements=new CopyOnWriteArrayList<>();
    private final List<String> normalChatAnnouncements=new CopyOnWriteArrayList<>();
    public void runTest(ClientGameTestContext context){
        this.context=context;
        if(Boolean.getBoolean("superwy.servuxScanTest"))try{dev.superwy.testutil.ServuxFixture.register();}catch(Exception e){throw new RuntimeException(e);}
        net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents.CHAT.register((message,signed,sender,type,time)->{if(type.chatType().is(net.minecraft.network.chat.ChatType.SAY_COMMAND)||type.chatType().is(net.minecraft.network.chat.ChatType.CHAT))publicAnnouncements.add(message.getString());if(type.chatType().is(net.minecraft.network.chat.ChatType.CHAT))normalChatAnnouncements.add(message.getString());});
        try{
            context.runOnClient(mc->GLFW.glfwHideWindow(mc.getWindow().handle()));
            Config config=Jsons.GSON.fromJson(Files.readString(FabricLoader.getInstance().getConfigDir().resolve("superwy/config.json")),Config.class);
            token=config.adminToken;
            context.runOnClient(mc->config().cleanupMainInventory=Boolean.getBoolean("superwy.packingTest")||Boolean.getBoolean("superwy.cleanupChestTest"));
            int port=context.computeOnClient(mc->{var client=FabricLoader.getInstance().getEntrypoints("client",net.fabricmc.api.ClientModInitializer.class).stream().filter(e->e instanceof SuperwyClient).findFirst().orElseThrow();var f=SuperwyClient.class.getDeclaredField("web");f.setAccessible(true);return ((dev.superwy.web.WebServer)f.get(client)).port();});
            base="http://127.0.0.1:"+port+"/api/v1/";
            try(TestSingleplayerContext single=context.worldBuilder().adjustSettings(settings->settings.setAllowCommands(true)).create()){
                single.getClientLevel().waitForChunksDownload();
                single.getServer().runCommand("fill -12 69 -12 12 69 12 minecraft:stone");
                single.getServer().runCommand("tp @a 0.5 70 0.5");
                single.getServer().runCommand("gamemode survival @a");
                single.getServer().runCommand("carpet commandPlayer true");
                single.getServer().runCommand("carpet allowSpawningOfflinePlayers true");
                seedOfflineProfiles(single);
                single.getServer().runOnServer(server->{
                    var player=server.getPlayerList().getPlayers().getFirst();server.getPlayerList().op(player.nameAndId(),Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER),Optional.of(true));
                    if(!player.permissions().hasPermission(net.minecraft.server.permissions.Permissions.COMMANDS_GAMEMASTER))throw new AssertionError("Fixture lacks NBT permissions");
                    var level=server.overworld();
                    for(BlockPos p:List.of(new BlockPos(2,70,0),new BlockPos(2,70,2),new BlockPos(-2,70,0)))level.setBlockAndUpdate(p,Blocks.BARREL.defaultBlockState());
                    var source=(BarrelBlockEntity)level.getBlockEntity(new BlockPos(2,70,0));
                    source.setItem(0,new ItemStack(Items.STONE,64));
                    ItemStack nested=new ItemStack(Items.SHULKER_BOX);nested.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(new ItemStack(Items.IRON_INGOT,21))));source.setItem(2,nested);source.setChanged();
                    var boxes=(BarrelBlockEntity)level.getBlockEntity(new BlockPos(2,70,2));boxes.setItem(0,new ItemStack(Items.SHULKER_BOX));boxes.setItem(1,new ItemStack(Items.SHULKER_BOX));boxes.setChanged();
                    server.getPlayerList().getPlayers().getFirst().getInventory().clearContent();
                });
                context.waitTicks(20);
                verifyNbtQueryDecode(single);
                if(Boolean.getBoolean("superwy.projectionParcelsTest")){verifyProjectionParcels(single);return;}
                if(Boolean.getBoolean("superwy.batchSalvageTest")){ProjectionParcelsGameTest.runSalvage(this,context,single);return;}
                if(Boolean.getBoolean("superwy.nbtDecodeTest"))return;
                if(Boolean.getBoolean("superwy.servuxScanTest")){verifyScanProtocols(single);return;}
                if(Boolean.getBoolean("superwy.sourceFallbackTest")){verifySourceFallback(single);return;}
                if(Boolean.getBoolean("superwy.restockRetryTest")){verifyRestockRetry(single);return;}
                if(Boolean.getBoolean("superwy.failureChatTest")){verifyFailureChat(single);return;}
                if(Boolean.getBoolean("superwy.partialStockTest")){verifyPartialStock(single);return;}
                if(Boolean.getBoolean("superwy.cleanupChestTest")){verifyCleanupChest(single);return;}
                if(Boolean.getBoolean("superwy.autoCloseMenuTest")){verifyAutoCloseMenu(single);return;}
                if(Boolean.getBoolean("superwy.packingTest")){verifyPacking(single);return;}
                if(Boolean.getBoolean("superwy.crampedPackingTest")){verifyCrampedPacking(single);return;}
                if(Boolean.getBoolean("superwy.parcelCapTest")){verifyParcelCap(single);return;}
                if(Boolean.getBoolean("superwy.taskQueueTest")){verifyTaskQueue(single);return;}
                if(Boolean.getBoolean("superwy.batchSkipTest")){verifyBatchSkip(single);return;}
                if(Boolean.getBoolean("superwy.batchPickupTest")){verifyBatchPickup(single);return;}
                if(Boolean.getBoolean("superwy.deliveryQueueTest")){verifyDeliveryQueue(single);return;}
                if(Boolean.getBoolean("superwy.spawnFailureTest")){verifySpawnFailureCleanup(single);return;}
                if(Boolean.getBoolean("superwy.scanBackgroundTest")){verifyScanBackground(single);return;}
                if(Boolean.getBoolean("superwy.forcedStopsTest")){
                    single.getServer().runCommand("carpet openFakePlayerInventory true");
                    call("admin/regions/fixture","PUT","{\"min\":{\"x\":2,\"y\":70,\"z\":0},\"max\":{\"x\":2,\"y\":70,\"z\":0}}");
                    call("admin/scan","POST","{}");waitForScan("complete");call("admin/resume","POST","{}");context.waitTicks(10);
                    verifyForcedStops(single);return;
                }
                if(Boolean.getBoolean("superwy.containerPacketTest")){verifyOverheadContainerPacket(single);verifyContainerGoal(single);return;}
                if(Boolean.getBoolean("superwy.directBaritoneTest")){
                    for(boolean antiCheat:new boolean[]{true,false})for(boolean background:new boolean[]{false,true})for(boolean freeLook:new boolean[]{false,true})verifyDirectBaritone(single,freeLook,background,antiCheat);
                    return;
                }
                if(!Boolean.getBoolean("superwy.directDeliveryTest")){
                verifyControlOwnership(single);
                verifyPathTurning(single,false);
                verifyPathTurning(single,true);
                verifyManualPathInput(single);
                verifyJumpAndReach(single);
                verifyPersistentScan(single);
                verifySkipFailures(single);
                }
                verifyRegionControls(single);
                verifyOverheadContainerPacket(single);verifyContainerGoal(single);
                verifyDirectDelivery(single);

            }
        }catch(Exception e){throw new RuntimeException(e);}
    }
    @SuppressWarnings("unchecked")
    private void seedOfflineProfiles(TestSingleplayerContext single)throws ReflectiveOperationException{
        single.getServer().runOnServer(server->{
            // Seed normal identity caches for fixture-only offline names. Real Carpet spawning,
            // GCA menus, inventory clicks and NBT receipts remain exercised end to end.
            var resolver=server.services().profileResolver();
            var byId=resolver.getClass().getDeclaredField("profileCacheById");byId.setAccessible(true);
            var byName=resolver.getClass().getDeclaredField("profileCacheByName");byName.setAccessible(true);
            var idCache=(com.google.common.cache.Cache<UUID,Optional<com.mojang.authlib.GameProfile>>)byId.get(resolver);
            var nameCache=(com.google.common.cache.Cache<String,Optional<com.mojang.authlib.GameProfile>>)byName.get(resolver);
            for(String name:List.of("sf","sf_1","sf_2","SW_cap","SW_pack","SW_pack_1","SW_clean","SW_spawn_fail","SW_deliveryQ","SW_link_gui","SW_link_edit","SW_nested","SW_delivery","SW_full","SW_batches","SW_queueA","SW_queueB","SW_history","SW_hist_missing","SW_missing","SW_after_cancel","SW_timeout","SW_timeout_next","SW_cancel_queue","SW_mid_click","SW_after_click","SW_msg_sender","SW_msg_free","SW_msg_key","SW_msg_gui","SW_msg_sys","SW_msg_wrap","SW_fb_missing","SW_fb_empty","SW_fb_partial","SW_fb_low","SW_fb_none","SW_fb_variant","SW_fb_next")){
                var identity=net.minecraft.server.players.NameAndId.createOffline(name);server.services().nameToIdCache().add(identity);
                var profile=Optional.of(new com.mojang.authlib.GameProfile(identity.id(),name));idCache.put(identity.id(),profile);nameCache.put(name.toLowerCase(Locale.ROOT),profile);
            }
        });
    }
    private void verifyProjectionParcels(TestSingleplayerContext single)throws Exception{
        ProjectionParcelsGameTest.run(this,context,single);
    }
    private void verifyDirectDelivery(TestSingleplayerContext single)throws Exception{
        single.getServer().runCommand("carpet openFakePlayerInventory true");
        single.getServer().runOnServer(server->{
            var level=server.overworld();level.setBlockAndUpdate(new BlockPos(8,70,0),Blocks.SHULKER_BOX.defaultBlockState());
            ((net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity)level.getBlockEntity(new BlockPos(8,70,0))).setItem(0,new ItemStack(Items.STONE,64));
            var inv=server.getPlayerList().getPlayers().getFirst().getInventory();
            for(int i=0;i<9;i++)inv.setItem(i,new ItemStack(Items.DIAMOND,i+1));
            inv.setItem(12,new ItemStack(Items.DIRT,17));inv.setItem(34,new ItemStack(Items.COBBLESTONE,3));inv.setItem(35,new ItemStack(Items.OAK_LOG,2));
        });context.waitTicks(10);
        call("admin/regions/fixture","PUT","{\"min\":{\"x\":2,\"y\":70,\"z\":0},\"max\":{\"x\":8,\"y\":70,\"z\":0},\"intervalSeconds\":3600}");
        verifyStationPersistence();
        context.runOnClient(mc->{var c=config();c.stations.clear();c.save(FabricLoader.getInstance().getConfigDir().resolve("superwy/config.json"));});
        call("admin/probe","POST","{}");call("admin/scan","POST","{}");waitForScan("complete");
        call("admin/resume","POST","{}");context.waitTicks(20);
        if(!call("capabilities","GET",null).get("canAcceptOrders").getAsBoolean())throw new AssertionError("In-place delivery still requires a station");
        // A distant legacy platform must not affect the actual spawn location or route.
        call("admin/station","PUT","{\"delivery\":{\"x\":10000,\"y\":70,\"z\":10000}}");
        startBackgroundScan(single);
        var order=submit("SW_delivery","[{\"itemId\":\"minecraft:stone\",\"count\":13},{\"itemId\":\"minecraft:iron_ingot\",\"count\":9}]");
        order=awaitOrder(order,"stored_offline",true);
        var spawnedAt=order.getAsJsonObject("delivery").getAsJsonArray("spawnPosition");
        var actualSpawn=new net.minecraft.world.phys.Vec3(spawnedAt.get(0).getAsDouble(),spawnedAt.get(1).getAsDouble(),spawnedAt.get(2).getAsDouble());
        if(actualSpawn.distanceTo(new net.minecraft.world.phys.Vec3(10000,70,10000))<1000||!verifiedSpawnPositions.contains(order.get("orderId").getAsString()))throw new AssertionError("Order used distant legacy platform: "+order);
        if(order.getAsJsonArray("packages").size()!=0||!order.getAsJsonObject("delivery").get("receiptSource").getAsString().equals("gca_menu_and_entity_nbt"))throw new AssertionError("Not direct GCA delivery: "+order);
        for(var line:order.getAsJsonArray("lines"))if(line.getAsJsonObject().get("packed").getAsInt()!=0)throw new AssertionError("Loose items reported as packed");
        single.getServer().runOnServer(server->{
            var level=server.overworld();var source=(BarrelBlockEntity)level.getBlockEntity(new BlockPos(2,70,0));
            if(source.getItem(0).getCount()!=64)throw new AssertionError("Nearer barrel was used before placed shulker");
            if(((net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity)level.getBlockEntity(new BlockPos(8,70,0))).getItem(0).getCount()!=51)throw new AssertionError("Shulker source not withdrawn exactly");
            if(source.getItem(2).get(DataComponents.CONTAINER).nonEmptyItemCopyStream().mapToInt(ItemStack::getCount).sum()!=12)throw new AssertionError("Nested source not returned");
            var inv=server.getPlayerList().getPlayers().getFirst().getInventory();
            for(int i=0;i<9;i++)if(!inv.getItem(i).is(Items.DIAMOND)||inv.getItem(i).getCount()!=i+1)throw new AssertionError("Hotbar changed");
            if(inv.getItem(12).getCount()!=17||inv.getItem(34).getCount()!=3||inv.getItem(35).getCount()!=2)throw new AssertionError("Personal main inventory moved");
            for(var entity:level.getAllEntities())if(entity instanceof net.minecraft.world.entity.item.ItemEntity)throw new AssertionError("Delivery dropped items");
            var boxes=(BarrelBlockEntity)level.getBlockEntity(new BlockPos(2,70,2));if(boxes.getItem(0).getCount()!=1||boxes.getItem(1).getCount()!=1)throw new AssertionError("Empty supply boxes consumed");
            var recovery=(BarrelBlockEntity)level.getBlockEntity(new BlockPos(-2,70,0));if(!recovery.isEmpty())throw new AssertionError("Recovery was used");
        });
        verifyLooseReceipt(single,"SW_delivery",Map.of(Items.STONE,13,Items.IRON_INGOT,9));
        System.out.println("SUPERWY_IN_PLACE_DELIVERY_OK: no station required; distant legacy platform ignored; recipient observed at exact pickup coordinate; GCA loose receipt persisted offline");
        System.out.println("SUPERWY_DIRECT_GCA_OK: placed shulker priority; nested borrow/return; no supply or recovery; personal inventory untouched; real GCA right-click GUI; NBT-confirmed loose receipt persisted offline");
        verifyNestedSourceCycle(single);verifyMissingStockStopsAndSays(single);verifyMultiPlayerQueue(single);verifyHistoricalMode(single);
        verifyConnectionImport(single);verifyWhisperOrders(single);
        verifyBatchesAndFullRecipient(single);
        verifyForcedStops(single);verifyCompactDashboard();
    }
    private void verifyNestedSourceCycle(TestSingleplayerContext single)throws Exception{
        var saved=single.getServer().computeOnServer(server->{
            var inv=server.getPlayerList().getPlayers().getFirst().getInventory();var items=new ArrayList<ItemStack>();for(int i=0;i<36;i++)items.add(inv.getItem(i).copy());return items;
        });
        single.getServer().runOnServer(server->{
            var level=server.overworld();
            level.setBlockAndUpdate(new BlockPos(4,70,0),Blocks.CHEST.defaultBlockState());level.setBlockAndUpdate(new BlockPos(5,70,0),Blocks.CHEST.defaultBlockState());
            var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)level.getBlockEntity(new BlockPos(5,70,0));
            ItemStack box=new ItemStack(Items.PURPLE_SHULKER_BOX);box.set(DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Nested source keep me"));
            var contents=new ArrayList<ItemStack>();for(int i=0;i<27;i++)contents.add(ItemStack.EMPTY);
            contents.set(3,new ItemStack(Items.GOLD_INGOT,64));contents.set(19,new ItemStack(Items.GOLD_INGOT,10));contents.set(26,new ItemStack(Items.DIRT,7));
            box.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(contents));chest.setItem(13,box);chest.setChanged();
            var inv=server.getPlayerList().getPlayers().getFirst().getInventory();for(int i=9;i<36;i++)inv.setItem(i,new ItemStack(Items.DIAMOND,1));inv.setItem(9,ItemStack.EMPTY);inv.setItem(33,ItemStack.EMPTY);
        });context.waitTicks(10);
        awaitAction(context.computeOnClient(mc->{
            var client=FabricLoader.getInstance().getEntrypoints("client",net.fabricmc.api.ClientModInitializer.class).stream().filter(e->e instanceof SuperwyClient).findFirst().orElseThrow();
            var field=SuperwyClient.class.getDeclaredField("scanner");field.setAccessible(true);return ((dev.superwy.game.Scanner)field.get(client)).refresh(new BlockPos(5,70,0));
        }));
        awaitOrder(submit("SW_nested","[{\"itemId\":\"minecraft:gold_ingot\",\"count\":65}]"),"stored_offline",false);
        single.getServer().runOnServer(server->{
            var level=server.overworld();var source=(net.minecraft.world.level.block.entity.ChestBlockEntity)level.getBlockEntity(new BlockPos(5,70,0));var box=source.getItem(13);
            if(!box.is(Items.PURPLE_SHULKER_BOX)||!box.getHoverName().getString().equals("Nested source keep me"))throw new AssertionError("Source box identity or original slot changed");
            var contents=box.get(DataComponents.CONTAINER).allItemsCopyStream().toList();
            if(!contents.get(3).isEmpty()||!contents.get(19).is(Items.GOLD_INGOT)||contents.get(19).getCount()!=9||contents.get(26).getCount()!=7)throw new AssertionError("Nested source withdrew unrelated items or wrong counts");
            for(int i=0;i<27;i++)if(i!=13&&!source.getItem(i).isEmpty())throw new AssertionError("Source returned to a different slot");
            var inv=server.getPlayerList().getPlayers().getFirst().getInventory();for(int i=9;i<36;i++)if(i!=9&&i!=33&&(!inv.getItem(i).is(Items.DIAMOND)||inv.getItem(i).getCount()!=1))throw new AssertionError("Nested cycle moved personal items");
            if(!inv.getItem(9).isEmpty()||!inv.getItem(33).isEmpty())throw new AssertionError("Source box or cargo left on robot");
        });
        verifyLooseReceipt(single,"SW_nested",Map.of(Items.GOLD_INGOT,65));
        // The compatibility payload has exactly the same bytes as the real server packet.
        var buf=new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        try{
            dev.superwy.game.QuickShulkerBridge.Open.CODEC.encode(buf,new dev.superwy.game.QuickShulkerBridge.Open(33));
            if(buf.readableBytes()!=4)throw new AssertionError("QuickShulker protocol must contain one int");
            var packet=net.kyrptonaught.quickshulker.network.OpenShulkerPacket.CODEC.decode(buf);
            if(packet.invSlot()!=33||buf.isReadable())throw new AssertionError("Compatibility payload disagrees with real QuickShulker decoder");
        }finally{buf.release();}
        single.getServer().runOnServer(server->{var inv=server.getPlayerList().getPlayers().getFirst().getInventory();for(int i=0;i<36;i++)inv.setItem(i,saved.get(i).copy());});context.waitTicks(5);
        System.out.println("SUPERWY_NESTED_SOURCE_CYCLE_OK: real QuickShulker; named purple box in double chest original slot; sparse multi-stack 65-item request; two free slots and batching; source returns with exactly 9 gold and 7 unrelated dirt; compatibility bytes decoded by real server codec");
    }
    private void verifyConnectionImport(TestSingleplayerContext single)throws Exception{
        Class<?> requester;try{requester=Class.forName("dev.superwy.requester.RequesterClient");}catch(ClassNotFoundException absent){return;}
        var client=context.computeOnClient(mc->(SuperwyClient)FabricLoader.getInstance().getEntrypoints("client",net.fabricmc.api.ClientModInitializer.class).stream().filter(e->e instanceof SuperwyClient).findFirst().orElseThrow());
        var field=SuperwyClient.class.getDeclaredField("frp");field.setAccessible(true);var original=(dev.superwy.network.FrpTunnel)field.get(client);var oldSettings=config().frp;
        var settings=new dev.superwy.network.FrpSettings();settings.serverAddr="127.0.0.1";settings.authToken="fixture-only-frp-token";settings.minPort=settings.maxPort=URI.create(base).getPort();
        var dir=FabricLoader.getInstance().getConfigDir().resolve("superwy/frp-fixture");
        try(var fake=new dev.superwy.network.FrpTunnel(settings,settings.minPort,dir,(exe,cfg)->new dev.superwy.network.FrpTunnel.Child(){
            boolean alive=true;public java.io.InputStream output(){return new java.io.ByteArrayInputStream("start proxy success\n".getBytes());}public boolean alive(){return alive;}public void stop(){alive=false;}
        },()->dir.resolve("fake.exe"))){
            context.runOnClient(mc->{field.set(client,fake);config().frp=settings;});
            call("admin/frp/start","POST","{}");context.waitFor(mc->fake.status().get("state").equals("ready"),100);
            var status=call("admin/network","GET",null);if(status.has("authToken")||status.has("token"))throw new AssertionError("Network status leaks credentials");
            var generated=call("admin/connect","POST","{}");String command=generated.get("command").getAsString();
            var link=dev.superwy.protocol.ConnectionCode.decode(command.substring("/swrequest connect ".length()));
            if(config().authenticate(link.token()).admin()||!config().authenticate(link.token()).permits("AnotherCargo")||!link.recipient().isEmpty())throw new AssertionError("Connection must let requester choose a name without admin rights");
            String before=context.computeOnClient(mc->Files.readString(FabricLoader.getInstance().getConfigDir().resolve("superwy-requester/config.json")));
            clientCommand(command.substring(1));
            context.runOnClient(mc->{
                var screen=mc.screen;if(!screen.getClass().getSimpleName().equals("ConnectionSetupScreen"))throw new AssertionError("Connection popup missing");
                var f=screen.getClass().getDeclaredField("recipient");f.setAccessible(true);var input=(net.minecraft.client.gui.components.EditBox)f.get(screen);
                if(!input.getValue().equals("sf"))throw new AssertionError("Connection default must be sf");
                input.setValue("");screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button b&&b.getMessage().getString().equals("保存连接")).map(c->(net.minecraft.client.gui.components.Button)c).findFirst().orElseThrow().onPress(null);
                if(mc.screen!=screen||!Files.readString(FabricLoader.getInstance().getConfigDir().resolve("superwy-requester/config.json")).equals(before))throw new AssertionError("Invalid recipient committed connection");
                screen.onClose();
            });
            if(!context.computeOnClient(mc->Files.readString(FabricLoader.getInstance().getConfigDir().resolve("superwy-requester/config.json"))).equals(before))throw new AssertionError("Cancelled import changed configuration");
            clientCommand(command.substring(1));
            System.out.println("SUPERWY_CONNECTION_POPUP_SCREENSHOT "+context.takeScreenshot("connection-recipient"));
            context.runOnClient(mc->{
                var screen=mc.screen;var f=screen.getClass().getDeclaredField("recipient");f.setAccessible(true);((net.minecraft.client.gui.components.EditBox)f.get(screen)).setValue("SW_link_gui");
                screen.children().stream().filter(c->c instanceof net.minecraft.client.gui.components.Button b&&b.getMessage().getString().equals("保存连接")).map(c->(net.minecraft.client.gui.components.Button)c).findFirst().orElseThrow().onPress(null);
                var saved=JsonParser.parseString(Files.readString(FabricLoader.getInstance().getConfigDir().resolve("superwy-requester/config.json"))).getAsJsonObject();
                if(!saved.get("transport").getAsString().equals("http")||!saved.get("fakePlayerName").getAsString().equals("SW_link_gui")||!saved.get("token").getAsString().equals(link.token()))throw new AssertionError("Connection command did not save requester config");
            });
            var db=store();clientCommand("swrequest request minecraft:stone 1");context.waitFor(mc->db.orders().stream().anyMatch(o->o.delivery.fakePlayerName.equals("SW_link_gui")),200);
            var order=store().orders().stream().filter(o->o.delivery.fakePlayerName.equals("SW_link_gui")).findFirst().orElseThrow();awaitOrder(Jsons.GSON.toJsonTree(order).getAsJsonObject(),"stored_offline",true);verifyLooseReceipt(single,"SW_link_gui",Map.of(Items.STONE,1));
            clientCommand("swrequest recipient SW_link_edit");
            context.runOnClient(mc->{var saved=JsonParser.parseString(Files.readString(FabricLoader.getInstance().getConfigDir().resolve("superwy-requester/config.json"))).getAsJsonObject();if(!saved.get("fakePlayerName").getAsString().equals("SW_link_edit"))throw new AssertionError("Recipient change not saved");});
            clientCommand("swrequest request minecraft:stone 1");context.waitFor(mc->db.orders().stream().anyMatch(o->o.delivery.fakePlayerName.equals("SW_link_edit")),200);
            var edited=store().orders().stream().filter(o->o.delivery.fakePlayerName.equals("SW_link_edit")).findFirst().orElseThrow();awaitOrder(Jsons.GSON.toJsonTree(edited).getAsJsonObject(),"stored_offline",true);verifyLooseReceipt(single,"SW_link_edit",Map.of(Items.STONE,1));
            clientCommand("superwy connect");String clipboard=context.computeOnClient(mc->mc.keyboardHandler.getClipboard());
            var newLink=dev.superwy.protocol.ConnectionCode.decode(clipboard.substring("/swrequest connect ".length()));if(!newLink.url().equals(link.url())||!newLink.recipient().isEmpty()||newLink.token().equals(link.token()))throw new AssertionError("New connection credential not independent");
            clientCommand(clipboard.substring(1));context.runOnClient(mc->{if(!mc.screen.getClass().getSimpleName().equals("ConnectionSetupScreen"))throw new AssertionError("Reconnect popup missing");mc.screen.onClose();mc.keyboardHandler.setClipboard("");});
            call("admin/frp/stop","POST","{}");if(!fake.status().get("state").equals("stopped"))throw new AssertionError("FRP stop was not immediate");
            System.out.println("SUPERWY_CONNECTION_IMPORT_OK: name chosen in real requester popup, default sf, invalid/cancel leaves config intact, confirmed import persists; changed recipient delivers with same token; reconnect opens popup, independent credentials; tunnel start/stop isolated from tasks");
        }finally{context.runOnClient(mc->{field.set(client,original);config().frp=oldSettings;config().save(FabricLoader.getInstance().getConfigDir().resolve("superwy/config.json"));});}
    }
    private JsonObject scanRegion(String id){
        for(var row:call("admin/status","GET",null).getAsJsonArray("scanRegions"))if(row.getAsJsonObject().get("regionId").getAsString().equals(id))return row.getAsJsonObject();
        throw new AssertionError("Missing per-region scan state: "+id);
    }
    private JsonObject awaitRegion(String id,java.util.function.Predicate<JsonObject> test){
        JsonObject state=null;for(int i=0;i<200;i++){context.waitTicks(2);state=scanRegion(id);if(test.test(state))return state;}
        throw new AssertionError("Region did not reach expected state: "+state);
    }
    private void verifyRegionControls(TestSingleplayerContext single)throws Exception{
        call("admin/pause","POST","{}");context.waitTicks(10);
        single.getServer().runOnServer(server->{for(int x=-12;x<=-4;x++)for(int z=-12;z<=11;z++)if(z<=-4||z>=3)server.overworld().setBlockAndUpdate(new BlockPos(x,70,z),Blocks.BARREL.defaultBlockState());});context.waitTicks(10);
        call("admin/regions/control_a","PUT","{\"min\":{\"x\":-12,\"y\":70,\"z\":-12},\"max\":{\"x\":-4,\"y\":70,\"z\":-4}}");
        call("admin/regions/control_b","PUT","{\"min\":{\"x\":-12,\"y\":70,\"z\":3},\"max\":{\"x\":-4,\"y\":70,\"z\":11}}");
        call("admin/scan/stop","POST","{\"regionId\":\"control_a\"}");call("admin/scan/stop","POST","{\"regionId\":\"control_b\"}");
        call("admin/resume","POST","{}");context.waitTicks(10);
        if(!scanRegion("control_a").get("scanPaused").getAsBoolean())throw new AssertionError("Individual stop was undone by resume");
        var started=call("admin/scan/start","POST","{\"regionId\":\"control_a\"}");if(!started.get("started").getAsBoolean()||started.has("queued"))throw new AssertionError("Start is still queued");
        awaitRegion("control_a",v->v.has("processed")&&v.get("processed").getAsInt()>=3);
        call("admin/scan","POST","{\"regionId\":\"control_b\"}");
        awaitRegion("control_b",v->v.has("processed")&&v.get("processed").getAsInt()>=3);
        if(scanRegion("control_a").get("processed").getAsInt()>=81)throw new AssertionError("B waited for all of A to finish");
        call("admin/scan/stop","POST","{\"regionId\":\"control_a\"}");var a=scanRegion("control_a");int stopped=a.get("processed").getAsInt(),b=scanRegion("control_b").get("processed").getAsInt();
        var next=a.getAsJsonArray("remainingContainers").get(0).deepCopy();context.waitTicks(40);
        if(scanRegion("control_a").get("processed").getAsInt()!=stopped||scanRegion("control_b").get("processed").getAsInt()<=b)throw new AssertionError("Stopping A stopped B or A kept scanning");
        clientCommand("superwy scan stop control_b");context.waitTicks(5);
        if(!scanRegion("control_b").get("scanPaused").getAsBoolean())throw new AssertionError("Game command did not stop B");
        clientCommand("superwy scan off");clientCommand("superwy scan on");context.waitTicks(10);
        if(!scanRegion("control_a").get("scanPaused").getAsBoolean()||!scanRegion("control_b").get("scanPaused").getAsBoolean())throw new AssertionError("Global toggle restarted individually stopped regions");
        var saved=context.computeOnClient(mc->store().scan("control_a"));
        if(saved.processed!=stopped||!Jsons.GSON.toJsonTree(saved.containers.peek()).equals(next))throw new AssertionError("Stop did not preserve next unscanned position");
        call("admin/scan/start","POST","{\"regionId\":\"control_a\"}");
        awaitRegion("control_a",v->v.get("state").getAsString().equals("complete"));
        if(!scanRegion("control_b").get("scanPaused").getAsBoolean())throw new AssertionError("Resuming A affected B");
        clientCommand("superwy scan start control_b");awaitRegion("control_b",v->v.get("state").getAsString().equals("complete"));
        context.runOnClient(mc->{for(String id:List.of("control_a","control_b")){var region=store().regions().stream().filter(r->r.id.equals(id)).findFirst().orElseThrow();if(!region.enabled||region.scanPaused||store().scan(id).processed!=81||!store().scan(id).complete())throw new AssertionError("Region did not resume to an exact completed checkpoint");}});
        context.waitTicks(5);call("admin/regions/control_a","DELETE",null);call("admin/regions/control_b","DELETE",null);
        single.getServer().runOnServer(server->{for(int x=-12;x<=-4;x++)for(int z=-12;z<=11;z++)if(z<=-4||z>=3)server.overworld().setBlockAndUpdate(new BlockPos(x,70,z),Blocks.AIR.defaultBlockState());});context.waitTicks(5);
        context.runOnClient(mc->{
            var client=FabricLoader.getInstance().getEntrypoints("client",net.fabricmc.api.ClientModInitializer.class).stream().filter(e->e instanceof SuperwyClient).findFirst().orElseThrow();
            var field=SuperwyClient.class.getDeclaredField("web");field.setAccessible(true);var web=field.get(client);var limits=web.getClass().getDeclaredField("limits");limits.setAccessible(true);
            synchronized(web){((Map<?,?>)limits.get(web)).clear();}
        });
        System.out.println("SUPERWY_REGION_CONTROLS_OK: two 81-container regions advance before either completes; independent stop persists and preserves next position; other region continues; global toggle respects individual stops; HTTP and game commands resume both exactly");
    }
    private void verifyCompactDashboard(){
        var dashboard=call("admin/dashboard","GET",null);var full=call("admin/status","GET",null);
        if(dashboard.has("containers")||dashboard.has("stations")||dashboard.getAsJsonObject("scan").has("remainingContainers"))throw new AssertionError("Dashboard still contains warehouse trees or coordinate dumps");
        int finished=0;var ids=new HashSet<String>();for(var row:dashboard.getAsJsonArray("orders")){
            var order=row.getAsJsonObject();ids.add(order.get("orderId").getAsString());if(Set.of("stored_offline","partial_stored_offline","cancelled","failed").contains(order.get("state").getAsString()))finished++;
            if(order.has("cargo")||order.has("lines")||order.has("request"))throw new AssertionError("Dashboard sends full order evidence instead of summaries");
        }
        long totalFinished=0;for(var row:full.getAsJsonArray("orders")){var order=row.getAsJsonObject();if(Set.of("stored_offline","partial_stored_offline","cancelled","failed").contains(order.get("state").getAsString()))totalFinished++;else if(!ids.contains(order.get("orderId").getAsString()))throw new AssertionError("Dashboard dropped a live order");}
        if(finished!=Math.min(10,totalFinished)||dashboard.get("historyLimit").getAsInt()!=10)throw new AssertionError("Dashboard history is not limited to latest ten");
        for(var row:dashboard.getAsJsonArray("orders")){var order=row.getAsJsonObject();if(order.has("errorCode")&&!order.get("errorCode").isJsonNull()){
            if(!order.has("errorMessage")||order.get("errorMessage").isJsonNull()||order.get("errorMessage").getAsString().contains(order.get("errorCode").getAsString()))throw new AssertionError("Dashboard leaks untranslated failure code as explanation");
        }}
        System.out.println("SUPERWY_COMPACT_DASHBOARD_OK: no inventory trees or coordinate dumps, only order summaries, latest ten terminal orders, every live order retained");
    }
    private void verifyNbtQueryDecode(TestSingleplayerContext single)throws Exception{
        call("admin/pause","POST","{}");context.waitTicks(8);call("admin/scan/enable","POST","{}");
        context.runOnClient(mc->dev.superwy.testutil.NbtWireTransport.verifyPacketDecoder(mc.level.registryAccess()));
        single.getServer().runOnServer(server->{
            var level=server.overworld();for(int x=4;x<=7;x++)level.setBlockAndUpdate(new BlockPos(x,70,0),Blocks.BARREL.defaultBlockState());
            for(int x=4;x<=7;x++){
                var named=new ItemStack(Items.BARREL);named.set(DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("SW_nbt_"+x));
                level.getBlockEntity(new BlockPos(x,70,0)).applyComponentsFromItemStack(named);
            }
            var source=(BarrelBlockEntity)level.getBlockEntity(new BlockPos(4,70,0));
            var extra=new net.minecraft.nbt.CompoundTag();extra.putString("note","x".repeat(1600));
            ItemStack rich=new ItemStack(Items.STONE,64);rich.set(DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(extra));
            var contents=new ArrayList<ItemStack>();for(int i=0;i<27;i++)contents.add(rich.copy());
            ItemStack box=new ItemStack(Items.SHULKER_BOX);box.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(contents));for(int i=0;i<27;i++)source.setItem(i,box.copy());source.setChanged();
            for(int x=5;x<=7;x++){var b=(BarrelBlockEntity)level.getBlockEntity(new BlockPos(x,70,0));b.setItem(0,new ItemStack(Items.STONE,10));b.setChanged();}
        });context.waitTicks(8);
        call("admin/regions/nbt_fixture","PUT","{\"min\":{\"x\":4,\"y\":70,\"z\":0},\"max\":{\"x\":7,\"y\":70,\"z\":0}}");
        var scanner=context.computeOnClient(mc->{var client=FabricLoader.getInstance().getEntrypoints("client",net.fabricmc.api.ClientModInitializer.class).stream().filter(e->e instanceof SuperwyClient).findFirst().orElseThrow();var f=SuperwyClient.class.getDeclaredField("scanner");f.setAccessible(true);return (dev.superwy.game.Scanner)f.get(client);});
        for(int x=5;x<=6;x++){int pos=x;awaitAction(context.computeOnClient(mc->scanner.refresh(new BlockPos(pos,70,0))));}
        dev.superwy.testutil.NbtWireTransport.enabled=true;dev.superwy.testutil.NbtWireTransport.injectFailures=true;
        try{
            call("admin/resume","POST","{}");call("admin/scan/start","POST","{\"regionId\":\"nbt_fixture\"}");
            var result=awaitRegion("nbt_fixture",v->v.get("state").getAsString().equals("complete_with_skips"));
            if(result.get("processed").getAsInt()!=2||result.get("skippedContainers").getAsInt()!=2||dev.superwy.testutil.NbtWireTransport.vanillaLimited<1)throw new AssertionError("NBT fixture did not read rich source and skip bad replies: "+result);
            context.runOnClient(mc->{
                var snapshots=store().snapshots();var rich=snapshots.stream().filter(t->t.pos.equals(new Model.Pos(4,70,0))).findFirst().orElseThrow();
                if(!rich.complete||rich.items.size()!=27||rich.items.stream().mapToInt(t->t.children.size()).sum()!=729)throw new AssertionError("Large nested source lost cached content");
                for(int x=5;x<=6;x++){var pos=new Model.Pos(x,70,0);var failed=snapshots.stream().filter(t->t.pos.equals(pos)).findFirst().orElseThrow();if(failed.complete||failed.items.isEmpty()||failed.items.getFirst().count!=10)throw new AssertionError("Invalid reply replaced history with empty cache");}
                if(mc.getConnection()==null||mc.level==null)throw new AssertionError("Query decoding disconnected client");
            });
            int lateBefore=dev.superwy.testutil.NbtWireTransport.lateDiscarded;
            var cancelled=context.computeOnClient(mc->{var scope=new TaskScope();var future=scope.call(()->new dev.superwy.game.Queries().block(new BlockPos(5,70,0)));scope.stop("USER_CANCELLED");return future;});
            try{awaitAction(cancelled.thenAccept(tag->{}));throw new AssertionError("Cancelled query completed normally");}catch(CompletionException e){if(!dev.superwy.game.Scanner.rootMessage(e).equals("USER_CANCELLED"))throw e;}
            context.waitFor(mc->dev.superwy.testutil.NbtWireTransport.lateDiscarded>lateBefore,100);
            awaitAction(context.computeOnClient(mc->new dev.superwy.game.Queries().entity(mc.player.getId()).thenAccept(dev.superwy.game.Items::playerInventory)));
            System.out.println("SUPERWY_NBT_DECODE_OK: real rich 27x27 nested inventory reproduces vanilla 2MiB allocation limit and scans completely through wire codec; over-budget and malformed replies skip without disconnect or empty cache; cancelled late reply discarded; subsequent entity query succeeds");
        }finally{
            dev.superwy.testutil.NbtWireTransport.enabled=false;dev.superwy.testutil.NbtWireTransport.injectFailures=false;
            call("admin/scan/stop","POST","{\"regionId\":\"nbt_fixture\"}");context.waitTicks(5);call("admin/regions/nbt_fixture","DELETE",null);
            single.getServer().runOnServer(server->{for(int x=4;x<=7;x++){
                var pos=new BlockPos(x,70,0);if(server.overworld().getBlockEntity(pos) instanceof net.minecraft.world.Container container)container.clearContent();
                server.overworld().setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
            }});
            context.runOnClient(mc->{for(var snapshot:store().snapshots())if(snapshot.pos.y()==70&&snapshot.pos.z()==0&&snapshot.pos.x()>=4&&snapshot.pos.x()<=7)store().deleteSnapshot(snapshot.key).join();});context.waitTicks(5);
        }
    }
    private void clientCommand(String text){
        context.getInput().pressKey(GLFW.GLFW_KEY_SLASH);context.getInput().typeChars(text);context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);context.waitTicks(2);
    }
    private void verifyWhisperOrders(TestSingleplayerContext single)throws Exception{
        var db=context.computeOnClient(mc->store());String bot=context.computeOnClient(mc->mc.player.getName().getString());
        clientCommand("superwy whisper on");clientCommand("superwy whisper key off");
        context.runOnClient(mc->{if(!config().whisperEnabled||!config().whisperKey.isEmpty())throw new AssertionError("Game commands did not disable optional verification");});
        single.getServer().runCommand("player SW_msg_sender spawn at -4 70 -4");waitForRecipient("SW_msg_sender");
        String id=WhisperProtocol.newId();String body=WhisperProtocol.encode("SW_msg_free",List.of(new WhisperProtocol.Line("minecraft:stone",1),new WhisperProtocol.Line("minecraft:iron_ingot",1)),"",id);
        single.getServer().runCommand("execute as SW_msg_sender run say "+body);context.waitTicks(8);
        if(db.orders().stream().anyMatch(o->id.equals(o.clientRequestId)))throw new AssertionError("Public chat was accepted as a private order");
        single.getServer().runCommand("execute as SW_msg_sender run tell "+bot+" "+body);
        context.waitFor(mc->db.orders().stream().anyMatch(o->id.equals(o.clientRequestId)),200);
        var accepted=db.orders().stream().filter(o->id.equals(o.clientRequestId)).findFirst().orElseThrow();
        awaitOrder(Jsons.GSON.toJsonTree(accepted).getAsJsonObject(),"stored_offline",true);
        int before=db.orders().size();single.getServer().runCommand("execute as SW_msg_sender run tell "+bot+" "+body);context.waitTicks(10);
        if(db.orders().size()!=before)throw new AssertionError("Repeated private request ID duplicated order");
        verifyLooseReceipt(single,"SW_msg_free",Map.of(Items.STONE,1,Items.IRON_INGOT,1));
        // Reproduce servers that send /tell as a system packet instead of native chat.
        String systemId=WhisperProtocol.newId();String systemBody=WhisperProtocol.encode("SW_msg_sys",List.of(new WhisperProtocol.Line("minecraft:stone",1)),"",systemId);
        single.getServer().runOnServer(server->server.getPlayerList().getPlayers().getFirst().sendSystemMessage(net.minecraft.network.chat.Component.literal("<Other> sw_msg_sender悄悄地对你说："+systemBody)));
        context.waitTicks(6);if(db.orders().stream().anyMatch(o->systemId.equals(o.clientRequestId)))throw new AssertionError("Public prefix accepted by system whisper fallback");
        single.getServer().runOnServer(server->server.getPlayerList().getPlayers().getFirst().sendSystemMessage(net.minecraft.network.chat.Component.literal("sw_msg_sender悄悄地对你说："+systemBody)));
        context.waitFor(mc->db.orders().stream().anyMatch(o->systemId.equals(o.clientRequestId)),200);
        var systemOrder=db.orders().stream().filter(o->systemId.equals(o.clientRequestId)).findFirst().orElseThrow();
        awaitOrder(Jsons.GSON.toJsonTree(systemOrder).getAsJsonObject(),"stored_offline",true);verifyLooseReceipt(single,"SW_msg_sys",Map.of(Items.STONE,1));
        String wrappedId=WhisperProtocol.newId();String wrappedBody=WhisperProtocol.encode("SW_msg_wrap",List.of(new WhisperProtocol.Line("minecraft:stone",1)),"",wrappedId);
        single.getServer().runOnServer(server->server.getPlayerList().getPlayers().getFirst().sendSystemMessage(net.minecraft.network.chat.Component.empty().append(net.minecraft.network.chat.Component.translatable("commands.message.display.incoming",net.minecraft.network.chat.Component.literal("☷SW_msg_sender").withStyle(style->style.withInsertion("SW_msg_sender")),net.minecraft.network.chat.Component.literal(wrappedBody)))));
        context.waitFor(mc->db.orders().stream().anyMatch(o->wrappedId.equals(o.clientRequestId)),200);
        var wrapped=db.orders().stream().filter(o->wrappedId.equals(o.clientRequestId)).findFirst().orElseThrow();
        awaitOrder(Jsons.GSON.toJsonTree(wrapped).getAsJsonObject(),"stored_offline",true);verifyLooseReceipt(single,"SW_msg_wrap",Map.of(Items.STONE,1));
        System.out.println("SUPERWY_SYSTEM_WHISPER_OK: reported Chinese system-packet format and wrapped translated component both create real orders; public prefix rejected; actual GCA receipts verified");
        clientCommand("superwy whisper key Ab123");
        var saved=Jsons.GSON.fromJson(Files.readString(FabricLoader.getInstance().getConfigDir().resolve("superwy/config.json")),Config.class);
        if(!saved.whisperKey.equals("Ab123"))throw new AssertionError("Bot key setting not persisted");
        String secureId=WhisperProtocol.newId();String wrong=WhisperProtocol.encode("SW_msg_key",List.of(new WhisperProtocol.Line("minecraft:stone",1)),"",secureId);
        single.getServer().runCommand("execute as SW_msg_sender run tell "+bot+" "+wrong);context.waitTicks(8);
        if(db.orders().stream().anyMatch(o->secureId.equals(o.clientRequestId)))throw new AssertionError("Missing key bypassed verification");
        String correct=WhisperProtocol.encode("SW_msg_key",List.of(new WhisperProtocol.Line("minecraft:stone",1)),"Ab123",secureId);
        single.getServer().runCommand("execute as SW_msg_sender run tell "+bot+" "+correct);
        context.waitFor(mc->db.orders().stream().anyMatch(o->secureId.equals(o.clientRequestId)),200);
        var secure=db.orders().stream().filter(o->secureId.equals(o.clientRequestId)).findFirst().orElseThrow();
        awaitOrder(Jsons.GSON.toJsonTree(secure).getAsJsonObject(),"stored_offline",true);verifyLooseReceipt(single,"SW_msg_key",Map.of(Items.STONE,1));
        clientCommand("superwy whisper off");String disabledId=WhisperProtocol.newId();
        single.getServer().runCommand("execute as SW_msg_sender run tell "+bot+" "+WhisperProtocol.encode("SW_msg_key",List.of(new WhisperProtocol.Line("minecraft:stone",1)),"Ab123",disabledId));context.waitTicks(8);
        if(db.orders().stream().anyMatch(o->disabledId.equals(o.clientRequestId)))throw new AssertionError("Disabled inbox accepted request");
        clientCommand("superwy whisper on");clientCommand("superwy whisper key off");single.getServer().runCommand("player SW_msg_sender kill");context.waitTicks(5);
        System.out.println("SUPERWY_WHISPER_INBOX_OK: real tell creates list order; public say ignored; request ID deduplication; optional key and enabled switch persisted; real GCA in-place receipt");
        Class<?> requester;try{requester=Class.forName("dev.superwy.requester.RequesterClient");}catch(ClassNotFoundException absent){return;}
        Object instance=requester.getField("INSTANCE").get(null);var last=requester.getDeclaredField("lastChatOrder");last.setAccessible(true);
        clientCommand("swrequest bot "+bot);clientCommand("swrequest recipient SW_msg_gui");clientCommand("swrequest key off");clientCommand("swrequest item minecraft:stone");clientCommand("swrequest count 1");clientCommand("swrequest mode chat");clientCommand("swrequest send");
        String orderId=null;for(int i=0;i<100;i++){context.waitTicks(2);orderId=context.computeOnClient(mc->(String)last.get(instance));if(orderId!=null)break;}
        if(orderId==null)throw new AssertionError("Requester did not receive real private acceptance receipt");
        var requested=db.order(orderId);if(requested==null||!requested.principalId.startsWith("msg_")||requested.lines.getFirst().requested!=1)throw new AssertionError("Requester did not use private transport");
        awaitOrder(Jsons.GSON.toJsonTree(requested).getAsJsonObject(),"stored_offline",true);verifyLooseReceipt(single,"SW_msg_gui",Map.of(Items.STONE,1));
        System.out.println("SUPERWY_REQUESTER_WHISPER_E2E_OK: both real mods; game commands persisted; tell request and acceptance receipt round trip; queued items delivered through GCA");
    }
    private Config config()throws ReflectiveOperationException{
        var client=(SuperwyClient)FabricLoader.getInstance().getEntrypoints("client",net.fabricmc.api.ClientModInitializer.class).stream().filter(e->e instanceof SuperwyClient).findFirst().orElseThrow();
        var field=SuperwyClient.class.getDeclaredField("config");field.setAccessible(true);return (Config)field.get(client);
    }
    private Store store()throws ReflectiveOperationException{
        var client=(SuperwyClient)FabricLoader.getInstance().getEntrypoints("client",net.fabricmc.api.ClientModInitializer.class).stream().filter(e->e instanceof SuperwyClient).findFirst().orElseThrow();
        var field=SuperwyClient.class.getDeclaredField("store");field.setAccessible(true);return (Store)field.get(client);
    }
    private JsonObject submit(String name,String lines){return call("orders","POST","{\"warehouseId\":\"main\",\"items\":"+lines+",\"delivery\":{\"method\":\"carpet_fake_player\",\"fakePlayerName\":\""+name+"\"}}","direct-"+UUID.randomUUID());}
    private JsonObject awaitOrder(JsonObject order,String expected,boolean requireAllFetchedBeforeSpawn){
        String id=order.get("orderId").getAsString(),last="";
        for(int i=0;i<(Boolean.getBoolean("superwy.packingTest")||Boolean.getBoolean("superwy.crampedPackingTest")||Boolean.getBoolean("superwy.projectionParcelsTest")||Boolean.getBoolean("superwy.batchSalvageTest")?2000:500);i++){
            context.waitTicks(5);order=call("orders/"+id,"GET",null);String state=order.get("state").getAsString();
            var delivery=order.getAsJsonObject("delivery");
            if(!verifiedSpawnPositions.contains(id)&&delivery.has("spawnPosition")){
                var xyz=delivery.getAsJsonArray("spawnPosition");String name=delivery.get("fakePlayerName").getAsString();
                var expectedSpawn=new net.minecraft.world.phys.Vec3(xyz.get(0).getAsDouble(),xyz.get(1).getAsDouble(),xyz.get(2).getAsDouble());
                boolean observed=context.computeOnClient(mc->{
                    var recipient=mc.level.players().stream().filter(p->p.getName().getString().equals(name)).findFirst();
                    if(recipient.isEmpty())return false;
                    if(recipient.get().position().distanceTo(expectedSpawn)>1.25||mc.player.position().distanceTo(expectedSpawn)>1.25||!delivery.get("spawnMode").getAsString().equals("in_place"))throw new AssertionError("Recipient did not spawn at robot's recorded in-place coordinate: "+name);
                    return true;
                });if(observed)verifiedSpawnPositions.add(id);
            }
            if(!last.equals(state)){System.out.println("SUPERWY_DIRECT_STAGE "+state+" "+order.get("blockingReason"));last=state;}
            if(requireAllFetchedBeforeSpawn&&Set.of("spawning","transferring","verifying","despawning").contains(state))for(var line:order.getAsJsonArray("lines"))if(line.getAsJsonObject().get("fetched").getAsInt()!=line.getAsJsonObject().get("requested").getAsInt())throw new AssertionError("Spawned before fetching all small-order lines: "+order);
            if(state.equals(expected))return order;
            if(Set.of("failed","recovering","delivery_uncertain","cancelled","stored_offline").contains(state))throw new AssertionError("Unexpected order end: "+order);
        }
        throw new AssertionError("Order did not reach "+expected+": "+order);
    }
    private void verifyLooseReceipt(TestSingleplayerContext single,String name,Map<net.minecraft.world.item.Item,Integer> expected){
        single.getServer().runCommand("player "+name+" spawn at 0 70 -3");waitForRecipient(name);
        single.getServer().runOnServer(server->{
            var recipient=server.getPlayerList().getPlayerByName(name);if(recipient==null)throw new AssertionError("Cannot inspect receipt");
            Map<net.minecraft.world.item.Item,Integer> actual=new HashMap<>();
            for(int i=0;i<36;i++){var item=recipient.getInventory().getItem(i);if(!item.isEmpty())actual.merge(item.getItem(),item.getCount(),Integer::sum);}
            if(!actual.equals(expected))throw new AssertionError("Loose receipt mismatch: "+actual+" expected "+expected);
        });single.getServer().runCommand("player "+name+" kill");context.waitTicks(8);
    }
    private void verifyBatchesAndFullRecipient(TestSingleplayerContext single)throws Exception{
        // One free main slot forces two direct deliveries without moving any personal item.
        single.getServer().runOnServer(server->{var inv=server.getPlayerList().getPlayers().getFirst().getInventory();for(int i=10;i<36;i++)inv.setItem(i,new ItemStack(Items.DIAMOND,1));});context.waitTicks(10);
        awaitOrder(submit("SW_batches","[{\"itemId\":\"minecraft:stone\",\"count\":80}]"),"stored_offline",false);
        verifyLooseReceipt(single,"SW_batches",Map.of(Items.STONE,80));
        context.runOnClient(mc->{
            var snapshot=store().snapshots().stream().filter(s->s.pos.equals(new Model.Pos(8,70,0))).findFirst().orElseThrow();
            if(!snapshot.complete||!snapshot.items.isEmpty())throw new AssertionError("Empty placed shulker did not clear cached inventory");
        });
        single.getServer().runOnServer(server->{var inv=server.getPlayerList().getPlayers().getFirst().getInventory();for(int i=10;i<36;i++)if(!inv.getItem(i).is(Items.DIAMOND)||inv.getItem(i).getCount()!=1)throw new AssertionError("Batching moved personal items");});
        System.out.println("SUPERWY_DIRECT_BATCHES_OK: single free slot, 80 items delivered in batches, no overflow or personal-item moves");
        single.getServer().runCommand("player SW_full spawn at 0 70 -3");waitForRecipient("SW_full");
        single.getServer().runOnServer(server->{var inv=server.getPlayerList().getPlayerByName("SW_full").getInventory();for(int i=0;i<36;i++)inv.setItem(i,new ItemStack(Items.DIRT,64));});single.getServer().runCommand("player SW_full kill");context.waitTicks(10);
        var order=awaitOrder(submit("SW_full","[{\"itemId\":\"minecraft:stone\",\"count\":1}]"),"failed",true);
        if(order.getAsJsonArray("lines").get(0).getAsJsonObject().get("fetched").getAsInt()!=1)throw new AssertionError("Recipient spawned before withdrawal");
        single.getServer().runOnServer(server->server.getPlayerList().getPlayers().getFirst().getInventory().setItem(10,ItemStack.EMPTY));context.waitTicks(5);
        var following=submit("SW_after_cancel","[{\"itemId\":\"minecraft:stone\",\"count\":1}]");
        if(!order.get("errorCode").getAsString().equals("RECIPIENT_FULL")||!order.get("needsItemReview").getAsBoolean())throw new AssertionError("Full recipient did not fail immediately with retained evidence: "+order);
        context.waitFor(mc->publicAnnouncements.stream().anyMatch(t->t.contains("取货失败：收货假人物品栏已满")&&!t.contains("RECIPIENT_FULL")),100);
        if(!order.get("errorMessage").getAsString().contains("收货假人物品栏已满"))throw new AssertionError("Order explanation is not localized");
        awaitOrder(following,"stored_offline",true);verifyLooseReceipt(single,"SW_after_cancel",Map.of(Items.STONE,1));
        single.getServer().runOnServer(server->{var inv=server.getPlayerList().getPlayers().getFirst().getInventory();if(!inv.getItem(9).is(Items.STONE)||inv.getItem(9).getCount()!=1)throw new AssertionError("Cancelled cargo lost");if(server.getPlayerList().getPlayerByName("SW_full")==null)throw new AssertionError("Cancellation unexpectedly removed recipient");});
        System.out.println("SUPERWY_DIRECT_FULL_FAIL_OK: recipient full fails immediately; cargo and recipient retained; following FIFO order fulfils normally");
        single.getServer().runCommand("player SW_full kill");context.waitTicks(8);
    }
    private void waitForRecipient(String name){context.waitFor(mc->mc.level!=null&&mc.level.players().stream().anyMatch(p->p.getName().getString().equals(name)),400);}
    private void verifyForcedStops(TestSingleplayerContext single)throws Exception{
        var actions=actions();
        single.getServer().runOnServer(server->{var inv=server.getPlayerList().getPlayers().getFirst().getInventory();for(int i=9;i<36;i++)inv.setItem(i,ItemStack.EMPTY);});context.waitTicks(5);
        single.getServer().runCommand("tp @a -10.5 70 0.5");context.waitTicks(5);
        var timed=submit("SW_timeout","[{\"itemId\":\"minecraft:stone\",\"count\":1}]");
        context.waitTicks(2);
        var next=submit("SW_timeout_next","[{\"itemId\":\"minecraft:stone\",\"count\":1}]");
        var queued=submit("SW_cancel_queue","[{\"itemId\":\"minecraft:stone\",\"count\":1}]");
        var queuedCancel=call("orders/"+queued.get("orderId").getAsString()+"/cancel","POST",null);
        if(!queuedCancel.get("state").getAsString().equals("cancelled")||queuedCancel.has("executionStartedAt"))throw new AssertionError("Queued cancellation started work");
        String timedId=timed.get("orderId").getAsString();
        context.runOnClient(mc->{
            var client=(SuperwyClient)FabricLoader.getInstance().getEntrypoints("client",net.fabricmc.api.ClientModInitializer.class).stream().filter(e->e instanceof SuperwyClient).findFirst().orElseThrow();
            var f=SuperwyClient.class.getDeclaredField("engine");f.setAccessible(true);var engine=(dev.superwy.game.OrderEngine)f.get(client);
            var scopeField=dev.superwy.game.OrderEngine.class.getDeclaredField("scope");scopeField.setAccessible(true);var scope=(TaskScope)scopeField.get(engine);
            // Advance only this run's monotonic start, avoiding a real 500-second test delay.
            var start=TaskScope.class.getDeclaredField("started");start.setAccessible(true);start.setLong(scope,start.getLong(scope)-TimeUnit.SECONDS.toNanos(501));
            if(!engine.busy()||!timedId.equals(engine.activeId()))throw new AssertionError("Timeout fixture was not active");
            engine.watchdog();if(actions.navigation.active())throw new AssertionError("Timed-out task kept navigation active");
            if(!mc.player.containerMenu.getCarried().isEmpty())throw new AssertionError("Timeout fixture should stop before clicking");
            if(mc.player.containerMenu!=mc.player.inventoryMenu)mc.player.closeContainer();
        });
        timed=awaitOrder(timed,"failed",false);
        if(!timed.get("errorCode").getAsString().equals("ORDER_TIMEOUT")||timed.get("executionTimeoutSeconds").getAsInt()!=500)throw new AssertionError("Missing 500-second timeout evidence: "+timed);
        awaitOrder(next,"stored_offline",true);verifyLooseReceipt(single,"SW_timeout_next",Map.of(Items.STONE,1));
        System.out.println("SUPERWY_FORCE_TIMEOUT_OK: queued cancel is immediate; simulated monotonic 501 seconds stops fetching order; next order receives correct items");
        var moving=submit("SW_mid_click","[{\"itemId\":\"minecraft:stone\",\"count\":13}]");
        context.waitFor(mc->mc.player.containerMenu!=mc.player.inventoryMenu&&!mc.player.containerMenu.getCarried().isEmpty(),400);
        var after=submit("SW_after_click","[{\"itemId\":\"minecraft:iron_ingot\",\"count\":1}]");
        var result=call("orders/"+moving.get("orderId").getAsString()+"/cancel","POST",null);
        if(!result.get("state").getAsString().equals("cancelled"))throw new AssertionError("Mid-click cancel not immediate");
        context.waitTicks(5);
        int carried=context.computeOnClient(mc->mc.player.containerMenu.getCarried().getCount());
        var position=context.computeOnClient(mc->mc.player.position());context.waitTicks(45);
        context.runOnClient(mc->{if(mc.player.containerMenu.getCarried().getCount()!=carried||mc.player.position().distanceToSqr(position)>.01||actions.navigation.active())throw new AssertionError("Cancelled order continued clicking or walking");});
        var waiting=call("orders/"+after.get("orderId").getAsString(),"GET",null);
        if(!waiting.get("state").getAsString().equals("failed")||!waiting.get("blockingReason").getAsString().equals("CURSOR_NOT_EMPTY"))throw new AssertionError("Next order mishandled retained cursor: "+waiting);
        if(!call("orders/"+moving.get("orderId").getAsString(),"GET",null).get("state").getAsString().equals("cancelled"))throw new AssertionError("Stale callback overwrote cancellation");
        System.out.println("SUPERWY_MID_CLICK_CANCEL_OK: no further clicks or movement; cargo/cursor retained; next order fails without touching cursor; terminal state stable");
    }
    private void verifyManualPathInput(TestSingleplayerContext single)throws Exception{
        single.getServer().runCommand("fill -12 70 -5 12 72 -5 minecraft:stone");
        single.getServer().runCommand("tp @a -8.5 70 -8.5 0 0");context.waitTicks(6);var a=actions();
        var travel=context.computeOnClient(mc->a.go(new BlockPos(9,70,-9)));context.waitTicks(6);
        context.runOnClient(mc->{mc.player.setYRot(0);mc.player.setXRot(12);});
        var before=context.computeOnClient(mc->mc.player.position());context.getInput().holdKey(GLFW.GLFW_KEY_W);context.waitTicks(640);context.getInput().releaseKey(GLFW.GLFW_KEY_W);
        var after=context.computeOnClient(mc->mc.player.position());if(after.z-before.z<.15)throw new AssertionError("Manual direction did not override motion: "+before+" -> "+after);
        context.runOnClient(mc->{if(!a.navigation.active()||Math.abs(mc.player.getYRot())>.1)throw new AssertionError("Manual input interrupted path or camera");});awaitAction(travel);
        if(context.computeOnClient(mc->mc.player.blockPosition().distSqr(new BlockPos(9,70,-9)))>2)throw new AssertionError("Manual release did not resume destination");
        single.getServer().runCommand("fill -12 70 -5 12 72 -5 minecraft:air");single.getServer().runCommand("tp @a 0.5 70 0.5");context.waitTicks(5);
        System.out.println("SUPERWY_SHARED_PATH_INPUT_OK: 32-second camera-relative manual move against wall, no watchdog cancellation, release resumes goal");
    }
    private void verifyStationPersistence()throws Exception{
        for(String kind:List.of("box","recovery","delivery"))context.runOnClient(mc->{
            var client=(SuperwyClient)FabricLoader.getInstance().getEntrypoints("client",net.fabricmc.api.ClientModInitializer.class).stream().filter(e->e instanceof SuperwyClient).findFirst().orElseThrow();
            BlockPos pos=kind.equals("box")?new BlockPos(2,70,2):kind.equals("recovery")?new BlockPos(-2,70,0):mc.player.blockPosition();
            mc.hitResult=new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos),net.minecraft.core.Direction.UP,pos,false);
            var method=SuperwyClient.class.getDeclaredMethod("stationHere",String.class);method.setAccessible(true);method.invoke(client,kind);
            var saved=Jsons.GSON.fromJson(Files.readString(FabricLoader.getInstance().getConfigDir().resolve("superwy/config.json")),Config.class).stations.getFirst();
            var actual=kind.equals("box")?saved.boxSupply:kind.equals("recovery")?saved.recovery:saved.delivery;
            if(!dev.superwy.game.Items.pos(pos).equals(actual))throw new AssertionError("Station not persisted synchronously: "+kind);
        });
        call("admin/station","PUT","{\"boxSupply\":{\"x\":2,\"y\":70,\"z\":2},\"delivery\":{\"x\":0,\"y\":70,\"z\":-3},\"recovery\":{\"x\":-2,\"y\":70,\"z\":0}}");
        System.out.println("SUPERWY_STATIONS_DURABLE_OK: box, recovery and delivery immediately read back from disk");
    }
    /** Drive Baritone's own command without Actions.go or SuperWY navigation ownership. */
    private void verifyDirectBaritone(TestSingleplayerContext single,boolean freeLook,boolean background,boolean antiCheat)throws Exception{
        single.getServer().runCommand("fill -2 70 -12 10 72 -1 minecraft:stone");
        single.getServer().runCommand("fill 0 70 -10 1 71 -5 minecraft:air");
        single.getServer().runCommand("fill 0 70 -6 8 71 -5 minecraft:air");
        single.getServer().runCommand("fill 7 70 -6 8 71 -2 minecraft:air");
        single.getServer().runCommand("tp @a 0.5 70 -9.5 180 0");context.waitTicks(10);
        var actions=actions();Object api=Class.forName("baritone.api.BaritoneAPI").getMethod("getProvider").invoke(null);
        Object baritone=api.getClass().getMethod("getPrimaryBaritone").invoke(api),manager=baritone.getClass().getMethod("getCommandManager").invoke(baritone);
        var execute=manager.getClass().getMethod("execute",String.class);Object settings=Class.forName("baritone.api.BaritoneAPI").getMethod("getSettings").invoke(null);
        Map<Object,Object> originals=new IdentityHashMap<>();boolean oldPause=context.computeOnClient(mc->mc.options.pauseOnLostFocus);
        try{
            context.runOnClient(mc->{
                if(actions.navigation.active())throw new AssertionError("Direct Baritone test unexpectedly owns SuperWY navigation");
                for(String name:List.of("allowBreak","allowPlace","freeLook","smoothLook","remainWithExistingLookDirection","antiCheatCompatibility","allowDiagonalAscend")){
                    Object setting=settings.getClass().getField(name).get(settings);var value=setting.getClass().getField("value");originals.put(setting,value.get(setting));value.set(setting,switch(name){case "freeLook"->freeLook;case "antiCheatCompatibility"->antiCheat;case "remainWithExistingLookDirection","allowDiagonalAscend"->true;default->false;});
                }
                mc.options.pauseOnLostFocus=false;mc.setScreen(null);var focus=mc.getWindow().getClass().getDeclaredField("focused");focus.setAccessible(true);focus.setBoolean(mc.getWindow(),!background);
                if(background)mc.mouseHandler.releaseMouse();else mc.mouseHandler.grabMouse();
                execute.invoke(manager,"cancel");execute.invoke(manager,"goto 8 70 -2");
            });
            boolean travelledEast=false,travelledSouth=false,turned=false,arrived=false;
            for(int i=0;i<300;i++){
                context.waitTicks(1);
                if(i==30||i==120)context.runOnClient(mc->{
                    Class<?> eventClass=Class.forName("baritone.api.event.events.RotationMoveEvent"),type=Class.forName("baritone.api.event.events.RotationMoveEvent$Type");
                    Object event=eventClass.getConstructor(type,float.class,float.class).newInstance(type.getField("MOTION_UPDATE").get(null),mc.player.getYRot(),mc.player.getXRot());
                    Object handler=baritone.getClass().getMethod("getGameEventHandler").invoke(baritone);handler.getClass().getMethod("onPlayerRotationMove",eventClass).invoke(handler,event);
                    Object behavior=baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);
                    System.out.println("DIRECT_BARITONE_DIAGNOSTIC: yaw="+mc.player.getYRot()+", plannedYaw="+eventClass.getMethod("getYaw").invoke(event)+", pathing="+behavior.getClass().getMethod("isPathing").invoke(behavior)+", input="+mc.player.input.getClass().getName()+", keys="+mc.player.input.keyPresses+", paused="+mc.isPaused());
                });
                var position=context.computeOnClient(mc->mc.player.position());float yaw=context.computeOnClient(mc->mc.player.getYRot());
                travelledEast|=position.x>6&&position.z<-4;travelledSouth|=position.x>6&&position.z>-4;turned|=Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw-180))>45;
                if(position.distanceToSqr(new net.minecraft.world.phys.Vec3(8.5,70,-1.5))<1.3){arrived=true;break;}
            }
            System.out.println("SUPERWY_DIRECT_BARITONE_RESULT: freeLook="+freeLook+", background="+background+", antiCheat="+antiCheat+", arrived="+arrived+", east="+travelledEast+", south="+travelledSouth+", cameraTurned="+turned+", position="+context.computeOnClient(mc->mc.player.position()));
            if(!arrived||!travelledEast||!travelledSouth||(!freeLook&&!turned))throw new AssertionError("Direct Baritone failed its two-corner corridor");
            System.out.println("SUPERWY_DIRECT_BARITONE_OK: freeLook="+freeLook+", background="+background+", antiCheat="+antiCheat+", no SuperWY steering ownership");
        }finally{
            context.runOnClient(mc->{execute.invoke(manager,"cancel");mc.options.pauseOnLostFocus=oldPause;mc.setScreen(null);var focus=mc.getWindow().getClass().getDeclaredField("focused");focus.setAccessible(true);focus.setBoolean(mc.getWindow(),true);mc.mouseHandler.grabMouse();for(var e:originals.entrySet())e.getKey().getClass().getField("value").set(e.getKey(),e.getValue());});
            single.getServer().runCommand("fill -2 70 -12 10 72 -1 minecraft:air");single.getServer().runCommand("tp @a 0.5 70 0.5");context.waitTicks(5);
        }
    }
    private void verifyControlOwnership(TestSingleplayerContext single)throws Exception{
        single.getServer().runOnServer(server->{for(int x=4;x<12;x++)for(int z=4;z<12;z++)server.overworld().setBlockAndUpdate(new BlockPos(x,70,z),Blocks.BARREL.defaultBlockState());});context.waitTicks(10);
        context.runOnClient(mc->{var f=mc.getWindow().getClass().getDeclaredField("focused");f.setAccessible(true);f.setBoolean(mc.getWindow(),true);mc.mouseHandler.grabMouse();});
        call("admin/regions/control_fixture","PUT","{\"min\":{\"x\":4,\"y\":70,\"z\":4},\"max\":{\"x\":11,\"y\":70,\"z\":11}}");call("admin/scan","POST","{\"regionId\":\"control_fixture\"}");
        context.waitFor(mc->dev.superwy.game.ControlGate.active(),100);
        double before=context.computeOnClient(mc->mc.player.getZ());
        context.getInput().holdKey(GLFW.GLFW_KEY_W);context.waitTicks(4);
        context.runOnClient(mc->{if(!mc.options.keyUp.isDown()||Math.abs(mc.player.getZ()-before)<.1)throw new AssertionError("Physical movement blocked during scan");});
        var status=call("admin/status","GET",null);if(status.get("paused").getAsBoolean())throw new AssertionError("Manual key still auto-paused the robot");
        context.getInput().releaseKey(GLFW.GLFW_KEY_W);
        context.getInput().pressKey(GLFW.GLFW_KEY_SLASH);context.getInput().typeChars("superwy stop");
        System.out.println("CONTROL_LOCK_SCREENSHOT "+context.takeScreenshot("control-stop-command"));
        context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);context.waitFor(mc->!dev.superwy.game.ControlGate.active(),100);
        status=call("admin/status","GET",null);if(!status.get("paused").getAsBoolean())throw new AssertionError("Typed stop command was not executed locally");
        double manualBefore=context.computeOnClient(mc->mc.player.getZ());context.getInput().holdKeyFor(GLFW.GLFW_KEY_W,5);context.runOnClient(mc->{if(Math.abs(mc.player.getZ()-manualBefore)<.1)throw new AssertionError("Control was not returned after stop");});
        single.getServer().runCommand("tp @a 0.5 70 0.5");
        context.runOnClient(mc->{
            var client=FabricLoader.getInstance().getEntrypoints("client",net.fabricmc.api.ClientModInitializer.class).stream().filter(e->e instanceof SuperwyClient).findFirst().orElseThrow();var f=SuperwyClient.class.getDeclaredField("actions");f.setAccessible(true);var actions=(dev.superwy.game.Actions)f.get(client);
            try{actions.go(new BlockPos(1,70,1));throw new AssertionError("Stopped automation accepted stale movement");}catch(IllegalStateException expected){if(!expected.getMessage().equals("CONTROL_STOPPED"))throw expected;}
        });
        call("admin/regions/control_fixture","DELETE",null);call("admin/resume","POST","{}");context.waitTicks(3);
        if(context.computeOnClient(mc->dev.superwy.game.ControlGate.active()))throw new AssertionError("Idle state did not allow manual controls");
        System.out.println("SUPERWY_CONTROL_OWNERSHIP_OK: running movement allowed, stop typed in vanilla chat, manual movement preserved, stale automation rejected");
    }
    private void verifyPathTurning(TestSingleplayerContext single,boolean background)throws Exception{
        single.getServer().runCommand("fill -2 70 -12 10 72 -1 minecraft:stone");
        single.getServer().runCommand("fill 0 70 -10 1 71 -5 minecraft:air");
        single.getServer().runCommand("fill 0 70 -6 8 71 -5 minecraft:air");
        single.getServer().runCommand("fill 7 70 -6 8 71 -2 minecraft:air");
        single.getServer().runCommand("tp @a 0.5 70 -9.5 180 0");context.waitTicks(10);
        var client=context.computeOnClient(mc->(SuperwyClient)FabricLoader.getInstance().getEntrypoints("client",net.fabricmc.api.ClientModInitializer.class).stream().filter(e->e instanceof SuperwyClient).findFirst().orElseThrow());
        var actionsField=SuperwyClient.class.getDeclaredField("actions");actionsField.setAccessible(true);var actions=(dev.superwy.game.Actions)actionsField.get(client);
        Object settings=Class.forName("baritone.api.BaritoneAPI").getMethod("getSettings").invoke(null);
        Map<Object,Object> originals=new IdentityHashMap<>();
        for(String name:List.of("freeLook","smoothLook","remainWithExistingLookDirection")){Object setting=settings.getClass().getField(name).get(settings);var value=setting.getClass().getField("value");originals.put(setting,value.get(setting));value.set(setting,true);}
        boolean oldPause=context.computeOnClient(mc->mc.options.pauseOnLostFocus);
        try{
            CompletableFuture<Void> travel=context.computeOnClient(mc->{
                mc.options.pauseOnLostFocus=true;mc.mouseHandler.releaseMouse();
                var focused=mc.getWindow().getClass().getDeclaredField("focused");focused.setAccessible(true);focused.setBoolean(mc.getWindow(),!background);
                mc.setScreen(background?new net.minecraft.client.gui.screens.PauseScreen(true):null);if(!background)mc.mouseHandler.grabMouse();
                Object baritone=Class.forName("baritone.api.BaritoneAPI").getMethod("getProvider").invoke(null);baritone=baritone.getClass().getMethod("getPrimaryBaritone").invoke(baritone);Object manager=baritone.getClass().getMethod("getCommandManager").invoke(baritone);manager.getClass().getMethod("execute",String.class).invoke(manager,"pause");
                dev.superwy.NavigationFaults.overrideYaw=true;dev.superwy.NavigationFaults.writes=0;
                var future=actions.go(new BlockPos(8,70,-2));dev.superwy.game.ControlGate.update();return future;
            });
            for(String name:List.of("freeLook","smoothLook","remainWithExistingLookDirection")){Object setting=settings.getClass().getField(name).get(settings);if(!Boolean.valueOf(name.equals("freeLook")).equals(setting.getClass().getField("value").get(setting)))throw new AssertionError("Navigation did not override "+name);}
            float initial=context.computeOnClient(mc->mc.player.getYRot());boolean turned=false,travelledEast=false,travelledSouth=false;
            for(int i=0;i<400&&!travel.isDone();i++){
                context.waitTicks(2);
                float yaw=context.computeOnClient(mc->{if(actions.navigation.active()&&(mc.isWindowActive()==background||(background&&mc.mouseHandler.isMouseGrabbed())||mc.isPaused()||mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen))throw new AssertionError("Path task state: focused="+mc.isWindowActive()+", grabbed="+mc.mouseHandler.isMouseGrabbed()+", paused="+mc.isPaused()+", screen="+mc.screen+", locked="+dev.superwy.game.ControlGate.active()+", position="+mc.player.position());return mc.player.getYRot();});
                if(Math.abs(net.minecraft.util.Mth.wrapDegrees(yaw-initial))>45)turned=true;
                travelledEast|=context.computeOnClient(mc->mc.player.getX()>6&&mc.player.getZ()<-4);
                travelledSouth|=context.computeOnClient(mc->mc.player.getX()>6&&mc.player.getZ()>-4);
            }
            if(!travel.isDone())throw new AssertionError("Background path did not finish");travel.join();
            if(!travelledEast||!travelledSouth)throw new AssertionError("Background corridor did not verify turning: "+turned+"/"+travelledEast+"/"+travelledSouth);
            if(dev.superwy.NavigationFaults.writes<10)throw new AssertionError("Yaw conflict fixture did not exercise movement steering");
            for(String name:List.of("freeLook","smoothLook","remainWithExistingLookDirection")){Object setting=settings.getClass().getField(name).get(settings);if(!Boolean.TRUE.equals(setting.getClass().getField("value").get(setting)))throw new AssertionError("Navigation did not restore "+name);}
            System.out.println((background?"SUPERWY_BACKGROUND_TURN_OK":"SUPERWY_FOREGROUND_TURN_OK")+": persistent Baritone pause cleared; free camera yaw; two corridor corners reached; movement steering applied; settings restored");
        }finally{
            context.runOnClient(mc->{dev.superwy.NavigationFaults.overrideYaw=false;actions.navigation.stop();mc.options.pauseOnLostFocus=oldPause;mc.setScreen(null);var focused=mc.getWindow().getClass().getDeclaredField("focused");focused.setAccessible(true);focused.setBoolean(mc.getWindow(),true);mc.mouseHandler.grabMouse();});
            for(var entry:originals.entrySet())entry.getKey().getClass().getField("value").set(entry.getKey(),entry.getValue());
            single.getServer().runCommand("fill -2 70 -12 10 72 -1 minecraft:air");single.getServer().runCommand("tp @a 0.5 70 0.5");context.waitTicks(5);
        }
    }
    private dev.superwy.game.Actions actions()throws ReflectiveOperationException{return context.computeOnClient(mc->{var client=(SuperwyClient)FabricLoader.getInstance().getEntrypoints("client",net.fabricmc.api.ClientModInitializer.class).stream().filter(e->e instanceof SuperwyClient).findFirst().orElseThrow();var field=SuperwyClient.class.getDeclaredField("actions");field.setAccessible(true);return (dev.superwy.game.Actions)field.get(client);});}
    private void awaitAction(CompletableFuture<Void> future){for(int i=0;i<400&&!future.isDone();i++)context.waitTicks(2);if(!future.isDone())throw new AssertionError("Action timed out");future.join();}
    private void verifyOverheadContainerPacket(TestSingleplayerContext single)throws Exception{
        var actions=actions();BlockPos target=new BlockPos(0,74,-10);
        single.getServer().runCommand("tp @a 0.5 70 -9.5 123 15");
        single.getServer().runCommand("setblock 0 73 -10 minecraft:stone");
        single.getServer().runCommand("setblock 0 74 -10 minecraft:chest");
        single.getServer().runOnServer(server->{
            var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)server.overworld().getBlockEntity(target);
            chest.setItem(0,new ItemStack(Items.STONE,7));chest.setChanged();
            server.getPlayerList().getPlayers().getFirst().getInventory().setItem(0,new ItemStack(Items.OBSIDIAN,3));
        });context.waitTicks(10);
        try{
            context.runOnClient(mc->{
                if(dev.superwy.game.Navigation.hit(target)!=null)throw new AssertionError("Fixture should have blocked line of sight");
                var hit=dev.superwy.game.Navigation.containerHit(target,10);
                if(hit==null||!hit.getBlockPos().equals(target)||hit.getDirection()!=net.minecraft.core.Direction.DOWN)throw new AssertionError("Overhead target did not generate a bottom-face hit");
                if(dev.superwy.game.Navigation.containerHit(target.above(20),10)!=null)throw new AssertionError("Out of range target accepted");
            });
            awaitAction(context.computeOnClient(mc->actions.open(target)));
            context.runOnClient(mc->{
                if(mc.player.containerMenu==mc.player.inventoryMenu||!mc.player.containerMenu.getSlot(0).getItem().is(Items.STONE)||mc.player.containerMenu.getSlot(0).getItem().getCount()!=7)throw new AssertionError("Server did not open requested overhead chest");
                if(mc.player.position().distanceToSqr(new net.minecraft.world.phys.Vec3(.5,70,-9.5))>.01||Math.abs(mc.player.getYRot()-123)>.1)throw new AssertionError("Direct opening moved player or camera");
            });awaitAction(context.computeOnClient(mc->actions.close()));
            // Server-blocked lid must time out rather than claiming the packet was an open menu.
            single.getServer().runCommand("setblock 0 73 -10 minecraft:air");
            single.getServer().runCommand("setblock 0 75 -10 minecraft:stone");context.waitTicks(5);
            var rejected=context.computeOnClient(mc->actions.open(target));
            try{awaitAction(rejected);throw new AssertionError("Server-blocked chest incorrectly reported open");}
            catch(CompletionException e){if(!dev.superwy.game.Scanner.rootMessage(e).equals("OPEN_CONTAINER_TIMEOUT"))throw e;}
            context.runOnClient(mc->{if(mc.player.containerMenu!=mc.player.inventoryMenu)throw new AssertionError("Rejected chest produced a menu");});
            single.getServer().runCommand("setblock 0 75 -10 minecraft:air");context.waitTicks(5);
            awaitAction(context.computeOnClient(mc->actions.open(target)));awaitAction(context.computeOnClient(mc->actions.close()));
            single.getServer().runOnServer(server->{
                var inv=server.getPlayerList().getPlayers().getFirst().getInventory();
                if(!inv.getItem(0).is(Items.OBSIDIAN)||inv.getItem(0).getCount()!=3)throw new AssertionError("Held blocks were used while opening chest");
                var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)server.overworld().getBlockEntity(target);
                if(chest.getItem(0).getCount()!=7)throw new AssertionError("Opening alone changed target stock");
            });
            single.getServer().runCommand("setblock 0 74 -10 minecraft:shulker_box");
            single.getServer().runCommand("setblock 0 73 -10 minecraft:stone");
            single.getServer().runOnServer(server->{
                var box=(net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity)server.overworld().getBlockEntity(target);
                box.setItem(0,new ItemStack(Items.IRON_INGOT,5));box.setChanged();
            });context.waitTicks(5);
            context.runOnClient(mc->{if(dev.superwy.game.Navigation.hit(target)!=null)throw new AssertionError("Overhead shulker should be occluded");});
            awaitAction(context.computeOnClient(mc->actions.open(target)));
            context.runOnClient(mc->{if(!mc.player.containerMenu.getSlot(0).getItem().is(Items.IRON_INGOT)||mc.player.containerMenu.getSlot(0).getItem().getCount()!=5)throw new AssertionError("Wrong overhead shulker menu");});
            awaitAction(context.computeOnClient(mc->actions.close()));
            System.out.println("SUPERWY_OVERHEAD_PACKET_OK: occluded overhead chest and shulker open at exact coordinate without camera/movement or held-item prediction; blocked lid times out; later interaction still succeeds");
        }finally{
            single.getServer().runCommand("setblock 0 74 -10 minecraft:air");single.getServer().runCommand("setblock 0 73 -10 minecraft:air");single.getServer().runCommand("setblock 0 75 -10 minecraft:air");
            single.getServer().runOnServer(server->server.getPlayerList().getPlayers().getFirst().getInventory().setItem(0,ItemStack.EMPTY));
            single.getServer().runCommand("tp @a 0.5 70 0.5");context.waitTicks(5);
        }
    }
    private void verifyContainerGoal(TestSingleplayerContext single)throws Exception{
        var a=actions();BlockPos target=new BlockPos(10,74,-10);
        single.getServer().runCommand("tp @a 0.5 70 -9.5 123 15");
        single.getServer().runCommand("attribute @p minecraft:block_interaction_range base set 4.5");
        single.getServer().runCommand("fill 4 70 -12 12 70 -8 minecraft:stone_slab[type=bottom]");
        single.getServer().runCommand("fill 4 73 -12 12 73 -8 minecraft:stone");
        single.getServer().runCommand("setblock 10 74 -10 minecraft:chest");
        single.getServer().runOnServer(server->{var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)server.overworld().getBlockEntity(target);chest.setItem(0,new ItemStack(Items.WAXED_EXPOSED_COPPER_BULB,3));chest.setItem(1,new ItemStack(Items.VERDANT_FROGLIGHT,5));chest.setChanged();});context.waitTicks(10);
        try{
            awaitAction(context.computeOnClient(mc->a.open(target)));
            context.runOnClient(mc->{
                if(mc.player.containerMenu==mc.player.inventoryMenu||!mc.player.containerMenu.getSlot(0).getItem().is(Items.WAXED_EXPOSED_COPPER_BULB)||mc.player.containerMenu.getSlot(1).getItem().getCount()!=5)throw new AssertionError("Wrong or missing target container after approach");
                if(dev.superwy.game.Navigation.hit(target,4.5)!=null)throw new AssertionError("Fixture must keep the overhead container occluded");
                if(dev.superwy.game.Navigation.containerHit(target,4.5)==null||mc.player.getX()<4||Math.abs(mc.player.getY()-70.5)>.1)throw new AssertionError("Did not approach within server reach on bottom slabs");
                if(Math.abs(mc.player.getYRot()-123)>.1)throw new AssertionError("Container approach rotated the player's camera");
            });awaitAction(context.computeOnClient(mc->a.close()));
            var scope=new TaskScope();var remote=context.computeOnClient(mc->{BlockPos far=new BlockPos(1000,70,1000);if(dev.superwy.game.Navigation.loaded(far))throw new AssertionError("Remote fixture unexpectedly loaded");return scope.call(()->a.approach(far));});
            context.waitTicks(1);context.runOnClient(mc->{if(!a.navigation.active()||remote.isCompletedExceptionally())throw new AssertionError("Unloaded cached container rejected before pathing");scope.stop("USER_CANCELLED");a.navigation.stop();});
            try{awaitAction(remote);throw new AssertionError("Cancelled remote approach kept running");}catch(CompletionException e){if(!dev.superwy.game.Scanner.rootMessage(e).equals("USER_CANCELLED"))throw e;}
            System.out.println("SUPERWY_CONTAINER_GOAL_OK: no stations configured; distant overhead container opens through opaque shelf after server reach fallback; bottom slabs accepted, camera preserved; unloaded cached target starts pathing rather than stand-list failure");
        }finally{
            context.runOnClient(mc->a.navigation.stop());single.getServer().runCommand("setblock 10 74 -10 minecraft:air");single.getServer().runCommand("fill 4 73 -12 12 73 -8 minecraft:air");single.getServer().runCommand("fill 4 70 -12 12 70 -8 minecraft:air");single.getServer().runCommand("tp @a 0.5 70 0.5");context.waitTicks(5);
        }
    }
    private void verifyJumpAndReach(TestSingleplayerContext single)throws Exception{
        var actions=actions();
        single.getServer().runCommand("fill -6 70 -2 -4 70 2 minecraft:stone");single.getServer().runCommand("tp @a -9.5 70 0.5");context.waitTicks(10);
        try{
            var jump=context.computeOnClient(mc->actions.go(new BlockPos(-5,71,0)));boolean airborne=false;
            for(int i=0;i<300&&!jump.isDone();i++){context.waitTicks(1);airborne|=context.computeOnClient(mc->!mc.player.onGround()&&mc.player.getY()>70.2);}
            if(!jump.isDone())throw new AssertionError("Step path timed out");jump.join();
            if(!airborne||context.computeOnClient(mc->mc.player.getY())<70.95)throw new AssertionError("Bot did not jump onto one-block platform");
            System.out.println("SUPERWY_JUMP_OK: actual airborne motion and arrival on one-block platform");
            single.getServer().runCommand("fill -11 70 -9 -3 72 -7 minecraft:stone");single.getServer().runCommand("fill -10 70 -8 -4 72 -8 minecraft:air");single.getServer().runCommand("setblock -7 69 -8 minecraft:air");single.getServer().runCommand("tp @a -9.5 70 -7.5");context.waitTicks(8);
            var gap=context.computeOnClient(mc->actions.go(new BlockPos(-4,70,-8)));boolean crossedAir=false;
            for(int i=0;i<300&&!gap.isDone();i++){context.waitTicks(1);crossedAir|=context.computeOnClient(mc->mc.player.getX()>-8&&mc.player.getX()<-6&&!mc.player.onGround()&&mc.player.getY()>70.1);}
            if(!gap.isDone())throw new AssertionError("Gap path timed out");gap.join();if(!crossedAir)throw new AssertionError("Bot did not jump over forced gap");
            single.getServer().runCommand("fill -11 70 -9 -3 72 -7 minecraft:air");single.getServer().runCommand("setblock -7 69 -8 minecraft:stone");
            single.getServer().runCommand("setblock -8 70 -3 minecraft:stone_slab[type=bottom]");single.getServer().runCommand("tp @a -10.5 70 -2.5");context.waitTicks(8);
            awaitAction(context.computeOnClient(mc->actions.go(new BlockPos(-8,70,-3))));
            context.runOnClient(mc->{if(Math.abs(mc.player.getY()-70.5)>.05)throw new AssertionError("Bottom slab standing height was not accepted");});
            System.out.println("SUPERWY_GAP_SLAB_OK: forced gap jumped; Baritone feet on bottom slab accepted without false timeout");
            single.getServer().runCommand("setblock 10 70 -10 minecraft:barrel");single.getServer().runCommand("tp @a 0.5 70 -9.5");single.getServer().runCommand("attribute @p minecraft:block_interaction_range base set 10");context.waitTicks(10);
            var distant=context.computeOnClient(mc->{if(mc.player.blockInteractionRange()<9.9)throw new AssertionError("Server reach attribute did not sync");if(dev.superwy.game.Navigation.hit(new BlockPos(10,70,-10))==null)throw new AssertionError("10-block ray missed visible container");return actions.open(new BlockPos(10,70,-10));});awaitAction(distant);
            context.runOnClient(mc->{if(mc.player.containerMenu==mc.player.inventoryMenu||Math.abs(mc.player.getX()-.5)>.1)throw new AssertionError("Supported reach did not open without walking");});awaitAction(context.computeOnClient(mc->actions.close()));
            single.getServer().runCommand("attribute @p minecraft:block_interaction_range base set 4.5");context.waitTicks(8);
            awaitAction(context.computeOnClient(mc->actions.open(new BlockPos(10,70,-10))));
            context.runOnClient(mc->{if(mc.player.containerMenu==mc.player.inventoryMenu||mc.player.getX()<4)throw new AssertionError("Rejected long reach did not walk closer and open");});awaitAction(context.computeOnClient(mc->actions.close()));
            System.out.println("SUPERWY_REACH_OK: visible container near 10 blocks opens without moving; vanilla range rejection walks closer and succeeds");
        }finally{
            context.runOnClient(mc->actions.navigation.stop());single.getServer().runCommand("attribute @p minecraft:block_interaction_range base set 4.5");single.getServer().runCommand("fill -6 70 -2 -4 70 2 minecraft:air");single.getServer().runCommand("fill -11 70 -9 -3 72 -7 minecraft:air");single.getServer().runCommand("setblock -7 69 -8 minecraft:stone");single.getServer().runCommand("setblock -8 70 -3 minecraft:air");single.getServer().runCommand("setblock 10 70 -10 minecraft:air");single.getServer().runCommand("tp @a 0.5 70 0.5");context.waitTicks(5);
        }
    }
    private void verifyScanBackground(TestSingleplayerContext single)throws Exception{
        var scan=scanner();var field=scan.getClass().getDeclaredField("decoding");field.setAccessible(true);var worker=(ExecutorService)field.get(scan);
        var contextValue=context.computeOnClient(mc->dev.superwy.game.Items.context());
        var tag=single.getServer().computeOnServer(server->server.overworld().getBlockEntity(new BlockPos(2,70,0)).saveWithFullMetadata(server.registryAccess()));
        var decoded=CompletableFuture.supplyAsync(()->{
            if(net.minecraft.client.Minecraft.getInstance().isSameThread())throw new AssertionError("Decode on render thread");
            if(!Thread.currentThread().getName().equals("superwy-scan-decode"))throw new AssertionError("Wrong decode worker");
            return dev.superwy.game.Items.snapshot("w","d",new BlockPos(2,70,0),"minecraft:barrel",tag,1,contextValue);
        },worker);
        context.waitFor(mc->decoded.isDone(),100);var snapshot=decoded.join();
        if(snapshot.items.stream().flatMap(n->n.children.stream()).noneMatch(n->n.itemId.equals("minecraft:iron_ingot")&&n.count==21))throw new AssertionError("Background decode lost nested contents");
        call("admin/regions/background_decode","PUT","{\"min\":{\"x\":2,\"y\":70,\"z\":0},\"max\":{\"x\":2,\"y\":70,\"z\":0}}");
        var release=new CountDownLatch(1);var entered=new CountDownLatch(1);
        worker.execute(()->{entered.countDown();try{release.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});
        if(!entered.await(2,TimeUnit.SECONDS))throw new AssertionError("Decode worker did not start");
        try{
            call("admin/scan/start","POST","{\"regionId\":\"background_decode\"}");
            waitForScan("reading");context.waitTicks(8);
            // Stop must persist and return while its decoding worker is deliberately blocked.
            call("admin/scan/stop","POST","{\"regionId\":\"background_decode\"}");
            var stopped=call("admin/status","GET",null).getAsJsonObject("scan");
            if(!stopped.get("state").getAsString().equals("stopped")||stopped.getAsJsonArray("remainingContainers").size()!=1)throw new AssertionError("Async cancellation lost pending work: "+stopped);
        }finally{release.countDown();}
        context.waitTicks(10);
        String key=Model.containerKey(baseWorld(),"minecraft:overworld",new Model.Pos(2,70,0));
        if(store().snapshot(key)!=null)throw new AssertionError("Cancelled background decode wrote stale inventory");
        call("admin/scan/start","POST","{\"regionId\":\"background_decode\"}");waitForScan("complete");
        if(store().snapshot(key)==null)throw new AssertionError("Restart after cancellation did not persist snapshot");
        call("admin/scan/disable","POST","{}");

        var synthetic=new ArrayList<Model.Snapshot>();
        for(int i=0;i<6000;i++){var s=new Model.Snapshot();s.key="perf"+i;s.world="synthetic";s.dimension="d";s.type="minecraft:barrel";s.pos=new Model.Pos(i,70,0);s.complete=true;
            for(int slot=0;slot<27;slot++){var n=new Model.ItemNode();n.slot=slot;n.itemId="minecraft:stone";n.componentHash="hash";n.count=64;n.maxStackSize=64;s.items.add(n);}synthetic.add(s);}
        var saved=store().saveScanBatch(synthetic,List.of(),ScanProgress.start(new Model.Region("synthetic","synthetic","d",new Model.Pos(0,70,0),new Model.Pos(1,70,0))));
        context.waitFor(mc->saved.isDone(),400);saved.join();
        long elapsed=context.computeOnClient(mc->{var client=FabricLoader.getInstance().getEntrypoints("client",net.fabricmc.api.ClientModInitializer.class).stream().filter(e->e instanceof SuperwyClient).findFirst().orElseThrow();var update=client.getClass().getDeclaredMethod("updateView");update.setAccessible(true);long start=System.nanoTime();for(int i=0;i<100;i++)update.invoke(client);return System.nanoTime()-start;});
        System.out.printf(java.util.Locale.ROOT,"SUPERWY_BACKGROUND_SCAN_OK: production decode on worker with nested contents; blocked worker cancellation returns without stale write; restart resumes; 6000 cached containers / 162000 stacks, 100 real main-thread updateView calls %.3f ms total%n",elapsed/1e6);
    }
    private String baseWorld(){return call("admin/status","GET",null).get("world").getAsString();}
    private void verifyAutoCloseMenu(TestSingleplayerContext single)throws Exception{
        single.getServer().runCommand("carpet openFakePlayerInventory true");
        call("admin/scan/disable","POST","{}");call("admin/resume","POST","{}");context.waitTicks(10);
        call("admin/regions/auto_close","PUT","{\"min\":{\"x\":2,\"y\":70,\"z\":0},\"max\":{\"x\":2,\"y\":70,\"z\":0}}");
        single.getServer().runOnServer(server->{var source=(BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(2,70,0));source.setItem(3,new ItemStack(Items.DIRT,5));source.setChanged();});
        awaitAction(context.computeOnClient(mc->scanner().refresh(new BlockPos(2,70,0))));context.waitTicks(25);
        String lines="[{\"itemId\":\"minecraft:stone\",\"count\":1}]";
        var a=actions();awaitAction(context.computeOnClient(mc->a.open(new BlockPos(2,70,0))));
        context.runOnClient(mc->{if(mc.player.containerMenu==mc.player.inventoryMenu)throw new AssertionError("Fixture barrel not open");});
        awaitOrder(submit("SW_clean",lines),"stored_offline",true);
        verifyLooseReceipt(single,"SW_clean",Map.of(Items.STONE,1));
        // GCA uses a different container; batch execution must close it too.
        single.getServer().runCommand("player SW_full spawn at 0 70 -1");waitForRecipient("SW_full");
        awaitAction(context.computeOnClient(mc->a.openRecipient(mc.level.players().stream().filter(p->p.getName().getString().equals("SW_full")).findFirst().orElseThrow())));
        String world=baseWorld();
        var batch=context.computeOnClient(mc->{var request=Requests.parse(JsonParser.parseString("{\"warehouseId\":\"main\",\"items\":"+lines+",\"delivery\":{\"method\":\"carpet_fake_player\",\"fakePlayerName\":\"SW_batches\"}}").getAsJsonObject(),new Config.Principal("auto-close",true,List.of(),List.of()));request.batchId=WhisperProtocol.newId();request.notifyPlayer=mc.player.getName().getString();return Jsons.GSON.toJsonTree(store().accept("auto-close",UUID.randomUUID().toString(),request,true,world,"minecraft:overworld").order()).getAsJsonObject();});
        awaitOrder(batch,"stored_offline",true);single.getServer().runCommand("player SW_full kill");context.waitTicks(5);
        verifyLooseReceipt(single,"SW_batches",Map.of(Items.STONE,1));
        context.runOnClient(mc->mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player)));
        awaitOrder(submit("SW_clean",lines),"stored_offline",true);
        verifyLooseReceipt(single,"SW_clean",Map.of(Items.STONE,2));
        // A genuine cursor stack must remain untouched, including its server-side container.
        awaitAction(context.computeOnClient(mc->a.open(new BlockPos(2,70,0))));
        context.runOnClient(mc->mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,3,0,net.minecraft.world.inventory.ContainerInput.PICKUP,mc.player));
        context.waitFor(mc->mc.player.containerMenu.getCarried().is(Items.DIRT)&&mc.player.containerMenu.getCarried().getCount()==5,100);
        int menu=context.computeOnClient(mc->mc.player.containerMenu.containerId);
        var blocked=awaitOrder(submit("SW_clean",lines),"failed",false);
        if(!blocked.get("errorCode").getAsString().equals("CURSOR_NOT_EMPTY")||blocked.getAsJsonArray("lines").get(0).getAsJsonObject().get("fetched").getAsInt()!=0)throw new AssertionError("Cursor was not protected: "+blocked);
        context.runOnClient(mc->{if(mc.player.containerMenu.containerId!=menu||!mc.player.containerMenu.getCarried().is(Items.DIRT)||mc.player.containerMenu.getCarried().getCount()!=5)throw new AssertionError("Auto-close changed carried stack");mc.gameMode.handleContainerInput(menu,3,0,net.minecraft.world.inventory.ContainerInput.PICKUP,mc.player);});
        context.waitFor(mc->mc.player.containerMenu.getCarried().isEmpty(),100);awaitAction(context.computeOnClient(mc->a.close()));
        awaitOrder(submit("SW_clean",lines),"stored_offline",true);verifyLooseReceipt(single,"SW_clean",Map.of(Items.STONE,3));
        single.getServer().runOnServer(server->{var source=(BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(2,70,0));if(source.getItem(0).getCount()!=60||!source.getItem(3).is(Items.DIRT)||source.getItem(3).getCount()!=5)throw new AssertionError("Menu preflight lost or duplicated items");});
        System.out.println("SUPERWY_AUTO_CLOSE_MENU_OK: real barrel before HTTP single, GCA menu before batch, and inventory screen close automatically before collection; exact receipts; nonempty real cursor stays untouched and blocks before fetching; following order succeeds after cursor is resolved");
    }
    private void seedCleanupRounds(int count)throws Exception{
        var db=context.computeOnClient(mc->store());String world=baseWorld();
        for(int i=0;i<count;i++){
            var request=new Model.Request();request.delivery=new Model.DeliveryRequest();request.delivery.fakePlayerName="SW_clean";
            var item=new Model.RequestLine();item.itemId="minecraft:stone";item.count=1;request.items.add(item);
            var previous=db.accept("cleanup-history",UUID.randomUUID().toString(),request,true,world,"minecraft:overworld").order();
            previous.cleanupRoundCounted=true;previous.lines.getFirst().fetched=previous.lines.getFirst().delivered=1;previous.state("stored_offline",null);db.saveOrder(previous).join();
        }
    }
    private void verifyCleanupChest(TestSingleplayerContext single)throws Exception{
        single.getServer().runCommand("carpet openFakePlayerInventory true");call("admin/scan/disable","POST","{}");call("admin/resume","POST","{}");
        single.getServer().runCommand("fill 70 49 -30 86 49 -15 minecraft:stone");single.getServer().runCommand("tp @a 74.5 50 -23.5");
        single.getServer().runOnServer(server->{
            var level=server.overworld();level.setBlockAndUpdate(new BlockPos(76,50,-23),Blocks.BARREL.defaultBlockState());level.setBlockAndUpdate(new BlockPos(80,50,-23),Blocks.CHEST.defaultBlockState());
            ((net.minecraft.world.Container)level.getBlockEntity(new BlockPos(76,50,-23))).setItem(0,new ItemStack(Items.STONE,64));
            ((net.minecraft.world.Container)level.getBlockEntity(new BlockPos(80,50,-23))).setItem(0,new ItemStack(Items.DIRT,63));
            var player=server.getPlayerList().getPlayers().getFirst();for(int i=0;i<9;i++)player.getInventory().setItem(i,new ItemStack(Items.DIAMOND,i+1));
            for(int i=9;i<35;i++)player.getInventory().setItem(i,new ItemStack(Items.DIRT,1));
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,new ItemStack(Items.DIAMOND_HELMET));player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,new ItemStack(Items.DIAMOND_CHESTPLATE));player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS,new ItemStack(Items.DIAMOND_LEGGINGS));player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,new ItemStack(Items.DIAMOND_BOOTS));player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,new ItemStack(Items.SHIELD));
        });context.waitTicks(15);
        call("admin/regions/cleanup_chest","PUT","{\"min\":{\"x\":76,\"y\":50,\"z\":-23},\"max\":{\"x\":76,\"y\":50,\"z\":-23}}");
        awaitAction(context.computeOnClient(mc->scanner().refresh(new BlockPos(76,50,-23))));context.waitTicks(25);
        seedCleanupRounds(48); // Fixture history only; boundary orders use real collection, delivery and storage.
        String lines="[{\"itemId\":\"minecraft:stone\",\"count\":1}]";
        var result=awaitOrder(submit("SW_clean",lines),"stored_offline",true);
        if(result.get("cleanupCompleted").getAsBoolean())throw new AssertionError("49th round cleaned early");
        single.getServer().runOnServer(server->{var inv=server.getPlayerList().getPlayers().getFirst().getInventory();for(int i=9;i<35;i++)if(!inv.getItem(i).is(Items.DIRT))throw new AssertionError("49th round moved leftovers");});
        result=awaitOrder(submit("SW_clean",lines),"stored_offline",true);
        if(!result.get("cleanupCompleted").getAsBoolean()||result.get("needsItemReview").getAsBoolean())throw new AssertionError("50th round cleanup incomplete: "+result);
        single.getServer().runOnServer(server->{
            var player=server.getPlayerList().getPlayers().getFirst();for(int i=9;i<36;i++)if(!player.getInventory().getItem(i).isEmpty())throw new AssertionError("Main inventory not cleared");
            for(int i=0;i<9;i++)if(!player.getInventory().getItem(i).is(Items.DIAMOND)||player.getInventory().getItem(i).getCount()!=i+1)throw new AssertionError("Hotbar changed");
            if(!player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET)||!player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE)||!player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS).is(Items.DIAMOND_LEGGINGS)||!player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET).is(Items.DIAMOND_BOOTS)||!player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND).is(Items.SHIELD))throw new AssertionError("Equipment or offhand changed");
            var chest=(net.minecraft.world.Container)server.overworld().getBlockEntity(new BlockPos(80,50,-23));int dirt=0;for(int i=0;i<chest.getContainerSize();i++)if(chest.getItem(i).is(Items.DIRT))dirt+=chest.getItem(i).getCount();if(dirt!=89)throw new AssertionError("Chest must contain original 63 plus 26 dirt: "+dirt);
            if(!server.overworld().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,player.getBoundingBox().inflate(15)).isEmpty())throw new AssertionError("Cleanup dropped items on ground");
            player.getInventory().setItem(9,new ItemStack(Items.DIRT,5));
        });
        result=awaitOrder(submit("SW_clean",lines),"stored_offline",true);if(result.get("cleanupCompleted").getAsBoolean())throw new AssertionError("51st round cleaned again");
        seedCleanupRounds(48);
        single.getServer().runOnServer(server->{var chest=(net.minecraft.world.Container)server.overworld().getBlockEntity(new BlockPos(80,50,-23));for(int i=0;i<chest.getContainerSize();i++)chest.setItem(i,new ItemStack(Items.COBBLESTONE,64));});context.waitTicks(5);
        result=awaitOrder(submit("SW_clean",lines),"stored_offline",true);
        if(!result.get("cleanupErrorCode").getAsString().equals("CLEANUP_STORAGE_FULL")||result.get("cleanupCompleted").getAsBoolean()||result.getAsJsonArray("lines").get(0).getAsJsonObject().get("delivered").getAsInt()!=1)throw new AssertionError("Full cleanup chest misreported delivery: "+result);
        context.waitFor(mc->normalChatAnnouncements.stream().anyMatch(t->t.contains("本轮已完成交付；背包存箱清理失败")),100);
        single.getServer().runOnServer(server->{var player=server.getPlayerList().getPlayers().getFirst();if(player.getInventory().getItem(9).getCount()!=5)throw new AssertionError("Full chest lost leftovers");((net.minecraft.world.Container)server.overworld().getBlockEntity(new BlockPos(80,50,-23))).clearContent();});context.waitTicks(5);
        result=awaitOrder(submit("SW_clean",lines),"stored_offline",true);if(!result.get("cleanupCompleted").getAsBoolean())throw new AssertionError("Cleanup did not retry after chest space restored");
        single.getServer().runOnServer(server->{var chest=(net.minecraft.world.Container)server.overworld().getBlockEntity(new BlockPos(80,50,-23));if(!chest.getItem(0).is(Items.DIRT)||chest.getItem(0).getCount()!=5)throw new AssertionError("Cleanup retry lost or duplicated leftovers");});
        verifyLooseReceipt(single,"SW_clean",Map.of(Items.STONE,5));
        System.out.println("SUPERWY_CLEANUP_CHEST_OK: 48 history records then real rounds 49/50/51; only 50 cleans 26 main slots into actual chest 80,50,-23, merges with 63 existing dirt, no ground drops; hotbar, armor and offhand preserved; full chest retains leftovers and completed delivery, retries after space restored; five delivered stone verified offline");
    }
    private void verifyPartialStock(TestSingleplayerContext single)throws Exception{
        single.getServer().runCommand("carpet openFakePlayerInventory true");
        call("admin/scan/disable","POST","{}");call("admin/resume","POST","{}");context.waitTicks(10);
        call("admin/regions/partial_stock","PUT","{\"min\":{\"x\":2,\"y\":70,\"z\":0},\"max\":{\"x\":2,\"y\":70,\"z\":0}}");
        single.getServer().runOnServer(server->{var source=(BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(2,70,0));source.setItem(0,new ItemStack(Items.STONE,54));source.setChanged();});
        awaitAction(context.computeOnClient(mc->scanner().refresh(new BlockPos(2,70,0))));
        context.waitTicks(25); // Allow the capability snapshot to observe the fixture region.
        var result=awaitOrder(submit("SW_fb_partial","[{\"itemId\":\"minecraft:stone\",\"count\":64}]"),"partial_stored_offline",false);
        var line=result.getAsJsonArray("lines").get(0).getAsJsonObject();
        if(line.get("fetched").getAsInt()!=54||line.get("delivered").getAsInt()!=54||line.get("shortfall").getAsInt()!=10||result.get("needsItemReview").getAsBoolean())throw new AssertionError("Partial stock was not delivered: "+result);
        String message="minecraft:stone 缺货10个，已交付54个";
        context.waitFor(mc->normalChatAnnouncements.stream().anyMatch(t->t.contains(message)),100);
        verifyLooseReceipt(single,"SW_fb_partial",Map.of(Items.STONE,54));
        single.getServer().runOnServer(server->{if(!server.getPlayerList().getPlayers().getFirst().getInventory().isEmpty())throw new AssertionError("Partial cargo remained on robot");});
        context.waitTicks(10);if(normalChatAnnouncements.stream().filter(t->t.contains(message)).count()!=1)throw new AssertionError("Partial summary repeated");
        // Both shortages must survive; the borrowed source shulker must be returned before delivery.
        single.getServer().runOnServer(server->{var source=(BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(2,70,0));source.setItem(0,new ItemStack(Items.STONE,7));source.setChanged();});
        awaitAction(context.computeOnClient(mc->scanner().refresh(new BlockPos(2,70,0))));
        result=awaitOrder(submit("SW_nested","[{\"itemId\":\"minecraft:stone\",\"count\":64},{\"itemId\":\"minecraft:iron_ingot\",\"count\":64}]"),"partial_stored_offline",false);
        if(result.getAsJsonArray("missingItems").size()!=2)throw new AssertionError("Earlier shortage was lost: "+result);
        context.waitFor(mc->normalChatAnnouncements.stream().anyMatch(t->t.contains("minecraft:stone 缺货57个，已交付7个"))&&normalChatAnnouncements.stream().anyMatch(t->t.contains("minecraft:iron_ingot 缺货43个，已交付21个")),100);
        verifyLooseReceipt(single,"SW_nested",Map.of(Items.STONE,7,Items.IRON_INGOT,21));
        single.getServer().runOnServer(server->{var source=(BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(2,70,0));if(!source.getItem(2).is(Items.SHULKER_BOX)||source.getItem(2).get(DataComponents.CONTAINER).nonEmptyItemCopyStream().findAny().isPresent())throw new AssertionError("Borrowed box not returned empty");if(!server.getPlayerList().getPlayers().getFirst().getInventory().isEmpty())throw new AssertionError("Nested partial cargo remained on robot");});
        result=awaitOrder(submit("SW_fb_none","[{\"itemId\":\"minecraft:diamond_block\",\"count\":64}]"),"failed",false);
        if(!result.get("errorCode").getAsString().equals("OUT_OF_STOCK")||result.getAsJsonObject("delivery").has("spawnPosition"))throw new AssertionError("Empty shortage spawned courier: "+result);
        context.waitFor(mc->normalChatAnnouncements.stream().anyMatch(t->t.contains("minecraft:diamond_block 缺货64个，已交付0个")),100);
        single.getServer().runOnServer(server->{var source=(BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(2,70,0));source.setItem(0,new ItemStack(Items.GOLD_INGOT,3));source.setChanged();});
        awaitAction(context.computeOnClient(mc->scanner().refresh(new BlockPos(2,70,0))));
        awaitOrder(submit("SW_fb_next","[{\"itemId\":\"minecraft:gold_ingot\",\"count\":3}]"),"stored_offline",true);
        verifyLooseReceipt(single,"SW_fb_next",Map.of(Items.GOLD_INGOT,3));
        System.out.println("SUPERWY_PARTIAL_STOCK_OK: requested 64, fetched and verified delivery 54, public CHAT shortage 10 delivered 54 exactly once; two partial lines and nested source box returned; no cargo remains on robot; zero stock delivers 0 without spawn; following full order succeeds");
    }
    private void verifyFailureChat(TestSingleplayerContext single)throws Exception{
        call("admin/scan/disable","POST","{}");call("admin/resume","POST","{}");context.waitTicks(10);
        call("admin/regions/failure_chat","PUT","{\"min\":{\"x\":2,\"y\":70,\"z\":0},\"max\":{\"x\":2,\"y\":70,\"z\":0}}");
        single.getServer().runOnServer(server->{
            var barrel=(BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(2,70,0));barrel.clearContent();
            barrel.setItem(0,new ItemStack(Items.WHITE_SHULKER_BOX));
            var filled=new ItemStack(Items.WHITE_SHULKER_BOX);filled.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE,8))));
            barrel.setItem(1,filled);barrel.setChanged();
        });context.waitTicks(8);
        awaitAction(context.computeOnClient(mc->scanner().refresh(new BlockPos(2,70,0))));
        context.waitTicks(25); // Let the once-per-second capability view observe the newly saved region.
        String ambiguousText="minecraft:white_shulker_box 取货失败："+ErrorMessages.message("AMBIGUOUS_ITEM");
        var ambiguous=awaitOrder(submit("SW_fb_none","[{\"itemId\":\"minecraft:white_shulker_box\",\"count\":1}]"),"failed",false);
        if(!ambiguous.get("errorCode").getAsString().equals("AMBIGUOUS_ITEM"))throw new AssertionError("Did not reproduce component conflict: "+ambiguous);
        context.waitFor(mc->normalChatAnnouncements.stream().anyMatch(t->t.contains(ambiguousText)),100);
        var shortage=awaitOrder(submit("SW_fb_none","[{\"itemId\":\"minecraft:diamond_block\",\"count\":1}]"),"failed",false);
        if(!shortage.get("errorCode").getAsString().equals("OUT_OF_STOCK"))throw new AssertionError("Next order did not reach shortage: "+shortage);
        String shortageText="minecraft:diamond_block 缺货";
        context.waitFor(mc->normalChatAnnouncements.stream().anyMatch(t->t.contains(shortageText)),100);context.waitTicks(10);
        for(String text:List.of(ambiguousText,shortageText)){
            if(normalChatAnnouncements.stream().filter(t->t.contains(text)).count()!=1||publicAnnouncements.stream().filter(t->t.contains(text)).count()!=1)throw new AssertionError("Failure announcement repeated or used /say: "+text);
        }
        single.getServer().runOnServer(server->{
            var barrel=(BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(2,70,0));
            if(!barrel.getItem(0).is(Items.WHITE_SHULKER_BOX)||!barrel.getItem(1).has(DataComponents.CONTAINER)||!server.getPlayerList().getPlayers().getFirst().getInventory().isEmpty())throw new AssertionError("Failed validation moved inventory");
        });
        System.out.println("SUPERWY_FAILURE_CHAT_OK: real white shulker component conflict and following shortage each arrive once as ChatType.CHAT; no /say; inventory unchanged");
    }
    private void hideCourierFromClient(TestSingleplayerContext single,String name){
        single.getServer().runOnServer(server->{
            var recipient=server.getPlayerList().getPlayerByName(name);
            var bot=server.getPlayerList().getPlayers().getFirst();
            bot.connection.send(new net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket(recipient.getId()));
            bot.connection.send(new net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket(List.of(recipient.getUUID())));
        });
        context.waitFor(mc->mc.getConnection().getPlayerInfo(name)==null&&mc.level.players().stream().noneMatch(p->p.getName().getString().equals(name)),100);
    }
    private void verifyPacking(TestSingleplayerContext single)throws Exception{
        single.getServer().runCommand("carpet openFakePlayerInventory true");call("admin/scan/disable","POST","{}");call("admin/resume","POST","{}");context.waitTicks(10);
        call("admin/workflow","PUT","{\"returnEntry\":{\"x\":0,\"y\":69,\"z\":4},\"boxSupply\":{\"x\":2,\"y\":70,\"z\":2},\"packingRoundSlots\":25,\"maxCarriedParcels\":2}");
        single.getServer().runOnServer(server->{
            var level=server.overworld();for(int x=2;x<=4;x++){var p=new BlockPos(x,70,0);level.setBlockAndUpdate(p,Blocks.BARREL.defaultBlockState());((BarrelBlockEntity)level.getBlockEntity(p)).clearContent();}
            for(int i=0;i<60;i++){var barrel=(BarrelBlockEntity)level.getBlockEntity(new BlockPos(2+i/27,70,0));barrel.setItem(i%27,new ItemStack(Items.STONE,64));barrel.setChanged();}
            var supply=(BarrelBlockEntity)level.getBlockEntity(new BlockPos(2,70,2));supply.clearContent();ItemStack empties=new ItemStack(Items.SHULKER_BOX,64);empties.set(DataComponents.MAX_STACK_SIZE,64);supply.setItem(0,empties);supply.setChanged();
            level.setBlockAndUpdate(new BlockPos(0,68,4),Blocks.BARREL.defaultBlockState());level.setBlockAndUpdate(new BlockPos(0,69,4),Blocks.HOPPER.defaultBlockState());
            var player=server.getPlayerList().getPlayers().getFirst();for(int i=0;i<9;i++)player.getInventory().setItem(i,new ItemStack(Items.DIAMOND,i+1));
            player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD,new ItemStack(Items.DIAMOND_HELMET));player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST,new ItemStack(Items.DIAMOND_CHESTPLATE));player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.LEGS,new ItemStack(Items.DIAMOND_LEGGINGS));player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.FEET,new ItemStack(Items.DIAMOND_BOOTS));player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND,new ItemStack(Items.SHIELD));
        });context.waitTicks(10);
        call("admin/regions/packing","PUT","{\"min\":{\"x\":2,\"y\":70,\"z\":0},\"max\":{\"x\":4,\"y\":70,\"z\":0}}");for(int x=2;x<=4;x++){int source=x;awaitAction(context.computeOnClient(mc->scanner().refresh(new BlockPos(source,70,0))));}
        single.getServer().runCommand("player SW_pack spawn at 8 70 8");waitForRecipient("SW_pack");single.getServer().runOnServer(server->{var inv=server.getPlayerList().getPlayerByName("SW_pack").getInventory();for(int i=0;i<36;i++)inv.setItem(i,new ItemStack(Items.DIRT,64));});single.getServer().runCommand("player SW_pack kill");context.waitTicks(8);
        String bot=context.computeOnClient(mc->mc.player.getName().getString());clientCommand("swrequest bot "+bot);clientCommand("swrequest recipient SW_pack");clientCommand("swrequest mode chat");
        context.runOnClient(mc->{mc.options.pauseOnLostFocus=false;var f=mc.getWindow().getClass().getDeclaredField("focused");f.setAccessible(true);f.setBoolean(mc.getWindow(),true);mc.mouseHandler.grabMouse();Object client=requester();client.getClass().getMethod("key",int.class,int.class,int.class).invoke(client,GLFW.GLFW_PRESS,GLFW.GLFW_KEY_BACKSLASH,0);});
        context.waitFor(mc->{try{Object d=currentDraft();return d!=null&&(boolean)d.getClass().getMethod("editing").invoke(d);}catch(Exception e){throw new RuntimeException(e);}},200);
        Object draft=context.computeOnClient(mc->currentDraft());String id=(String)draft.getClass().getField("id").get(draft);
        context.runOnClient(mc->{draft.getClass().getMethod("add",String.class,int.class,int.class).invoke(draft,"minecraft:stone",3840,64);Object client=requester();var method=client.getClass().getDeclaredMethod("commitBatch");method.setAccessible(true);method.invoke(client);});
        var workflow=call("admin/dashboard","GET",null).getAsJsonObject("workflow");if(workflow==null||workflow.get("maxCarriedParcels").getAsInt()!=2||workflow.getAsJsonObject("boxSupply").get("x").getAsInt()!=2)throw new AssertionError("Dashboard workflow settings missing");
        var db=context.computeOnClient(mc->store());context.waitFor(mc->db.orders().stream().anyMatch(o->id.equals(o.request.batchId)),200);var started=db.orders().stream().filter(o->id.equals(o.request.batchId)).findFirst().orElseThrow();
        context.waitFor(mc->db.order(started.orderId).packages.size()>0,1800);System.out.println("SUPERWY_PACKING_SCREENSHOT "+context.takeScreenshot("packing-progress"));
        var done=awaitOrder(Jsons.GSON.toJsonTree(started).getAsJsonObject(),"stored_offline",false);
        if(!done.get("packingMode").getAsBoolean()||done.get("executionTimeoutSeconds").getAsInt()!=86400||done.getAsJsonArray("packages").size()!=3)throw new AssertionError("Wrong packing/round result: "+done);
        var line=done.getAsJsonArray("lines").get(0).getAsJsonObject();if(line.get("fetched").getAsInt()!=3840||line.get("packed").getAsInt()!=3840||line.get("delivered").getAsInt()!=3840)throw new AssertionError("Packing ledger lost items: "+line);
        if(!done.getAsJsonObject("delivery").getAsJsonArray("recipientNames").toString().contains("SW_pack_1"))throw new AssertionError("Full courier did not roll over: "+done);
        single.getServer().runOnServer(server->{var supply=(BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(2,70,2));if(supply.getItem(0).getCount()!=61)throw new AssertionError("Must take exactly one empty per parcel from 64 stack, remaining="+supply.getItem(0).getCount());});
        single.getServer().runCommand("player SW_pack_1 spawn at 0 70 -3");waitForRecipient("SW_pack_1");single.getServer().runOnServer(server->{
            var inv=server.getPlayerList().getPlayerByName("SW_pack_1").getInventory();var sizes=new ArrayList<Integer>();for(int i=0;i<36;i++){var box=inv.getItem(i);if(box.isEmpty())continue;if(box.getCount()!=1||!dev.superwy.game.Items.shulker(box))throw new AssertionError("Delivery contains loose or stacked cargo");var contents=box.get(DataComponents.CONTAINER).nonEmptyItemCopyStream().toList();if(contents.stream().anyMatch(stack->!stack.is(Items.STONE)))throw new AssertionError("Wrong parcel material");sizes.add(contents.stream().mapToInt(ItemStack::getCount).sum());}
            sizes.sort(Comparator.reverseOrder());if(!sizes.equals(List.of(1728,1408,704)))throw new AssertionError("Expected full 27 + partial 22 + 11 slot parcels after two-parcel flush: "+sizes);
        });single.getServer().runCommand("player SW_pack_1 kill");context.waitTicks(8);
        verifyLooseReceipt(single,"SW_pack",Map.of(Items.DIRT,2304));
        // Under 50 completed orders, the next delivery must preserve all 26 leftover main slots.
        single.getServer().runOnServer(server->{var player=server.getPlayerList().getPlayers().getFirst();for(int i=9;i<35;i++)player.getInventory().setItem(i,new ItemStack(Items.DIRT,1));player.getInventory().setItem(35,ItemStack.EMPTY);var source=(BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(2,70,0));source.setItem(0,new ItemStack(Items.STONE,1));source.setChanged();});context.waitTicks(8);awaitAction(context.computeOnClient(mc->scanner().refresh(new BlockPos(2,70,0))));
        var clean=awaitOrder(submit("SW_clean","[{\"itemId\":\"minecraft:stone\",\"count\":1}]"),"stored_offline",true);if(clean.get("cleanupCompleted").getAsBoolean())throw new AssertionError("Cleanup ran before 50 rounds");context.waitTicks(10);
        single.getServer().runOnServer(server->{var player=server.getPlayerList().getPlayers().getFirst();for(int i=9;i<35;i++)if(!player.getInventory().getItem(i).is(Items.DIRT)||player.getInventory().getItem(i).getCount()!=1)throw new AssertionError("Early cleanup moved leftovers: "+i);
            for(int i=0;i<9;i++)if(!player.getInventory().getItem(i).is(Items.DIAMOND)||player.getInventory().getItem(i).getCount()!=i+1)throw new AssertionError("Hotbar changed");
            if(!player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET)||!player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE)||!player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.LEGS).is(Items.DIAMOND_LEGGINGS)||!player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.FEET).is(Items.DIAMOND_BOOTS)||!player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND).is(Items.SHIELD))throw new AssertionError("Equipment or offhand changed");
            int dirt=0;for(var pos:List.of(new BlockPos(0,68,4),new BlockPos(0,69,4))){var container=(net.minecraft.world.Container)server.overworld().getBlockEntity(pos);for(int i=0;i<container.getContainerSize();i++)if(container.getItem(i).is(Items.DIRT))dirt+=container.getItem(i).getCount();}if(dirt!=0)throw new AssertionError("Early cleanup dropped dirt: "+dirt);
        });verifyLooseReceipt(single,"SW_clean",Map.of(Items.STONE,1));
        System.out.println("SUPERWY_PACKING_OK: real 3840-item batch spans 60 inventory slots; first 25 then <=24 rounds, QuickShulker packing fills 27 slots; configured two-parcel carry limit flushes before end; full base courier rolls to _1; exact 3 parcels 27/22/11 stacks; 64-stack empty supply consumes exactly 3; under 50 rounds keeps all 26 leftover main slots and preserves hotbar, four armor slots and offhand");
    }
    /**
     * Leftovers fill most of the main inventory. Scenario A (periodic cleanup off): rounds are tiny, yet each box is
     * filled completely and the courier is summoned once at the end. Scenario B (cleanup on): the leftovers are stored
     * in the cleanup chest first. An item with custom data in the hotbar must not disturb reconciliation.
     */
    private void verifyCrampedPacking(TestSingleplayerContext single)throws Exception{
        single.getServer().runCommand("carpet openFakePlayerInventory true");call("admin/scan/disable","POST","{}");call("admin/resume","POST","{}");context.waitTicks(10);
        call("admin/workflow","PUT","{\"returnEntry\":{\"x\":6,\"y\":70,\"z\":2},\"boxSupply\":{\"x\":2,\"y\":70,\"z\":2},\"packingRoundSlots\":25,\"maxCarriedParcels\":25}");
        single.getServer().runOnServer(server->{
            var level=server.overworld();
            for(int x=2;x<=5;x++){var p=new BlockPos(x,70,0);level.setBlockAndUpdate(p,Blocks.BARREL.defaultBlockState());((BarrelBlockEntity)level.getBlockEntity(p)).clearContent();}
            for(int i=0;i<89;i++){var barrel=(BarrelBlockEntity)level.getBlockEntity(new BlockPos(2+i/27,70,0));barrel.setItem(i%27,new ItemStack(Items.STONE,64));barrel.setChanged();}
            level.setBlockAndUpdate(new BlockPos(2,70,2),Blocks.BARREL.defaultBlockState());
            var supply=(BarrelBlockEntity)level.getBlockEntity(new BlockPos(2,70,2));supply.clearContent();ItemStack empties=new ItemStack(Items.SHULKER_BOX,64);empties.set(DataComponents.MAX_STACK_SIZE,64);supply.setItem(0,empties);supply.setChanged();
            level.setBlockAndUpdate(new BlockPos(6,70,2),Blocks.CHEST.defaultBlockState());
            var player=server.getPlayerList().getPlayers().getFirst();var inv=player.getInventory();
            for(int i=0;i<36;i++)inv.setItem(i,ItemStack.EMPTY);
            ItemStack tool=new ItemStack(Items.DIAMOND_SWORD);tool.set(DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("自用工具"));inv.setItem(0,tool);
            for(int i=9;i<32;i++)inv.setItem(i,new ItemStack(Items.DIRT,i));
        });context.waitTicks(10);
        call("admin/regions/cramped","PUT","{\"min\":{\"x\":2,\"y\":70,\"z\":0},\"max\":{\"x\":5,\"y\":70,\"z\":0}}");
        for(int x=2;x<=5;x++){int source=x;awaitAction(context.computeOnClient(mc->scanner().refresh(new BlockPos(source,70,0))));}
        boolean ready=false;for(int attempt=0;attempt<100;attempt++){if(call("capabilities","GET",null).get("canAcceptOrders").getAsBoolean()){ready=true;break;}context.waitTicks(2);}
        if(!ready)throw new AssertionError("Cramped packing fixture never became ready");
        var spawns=new java.util.concurrent.atomic.AtomicInteger();
        net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.ALLOW_COMMAND.register(command->{if(command.startsWith("player SW_cramp spawn at "))spawns.incrementAndGet();return true;});
        context.runOnClient(mc->config().cleanupMainInventory=false);long startA=System.currentTimeMillis();
        var first=awaitOrder(crampedBatch(3840),"stored_offline",false);long secondsA=(System.currentTimeMillis()-startA)/1000;
        checkFullBoxes(first,3840,3);
        // Only 4 free slots: full boxes may be handed over to make room (each one checked full above), never one per round.
        if(spawns.get()>2)throw new AssertionError("Courier summoned "+spawns.get()+" times for one order; boxes were delivered before they were full");
        System.out.println("SUPERWY_CRAMPED_PACKING_A_OK: 4 free slots, 3840 stone -> 3 boxes (1728/1728/384), courier summoned "+spawns.get()+" time(s), only full boxes before the last; "+secondsA+" s");
        context.runOnClient(mc->config().cleanupMainInventory=true);spawns.set(0);
        // Automatic scanning stays on during scenario B; cache refreshes must never stop the packing.
        call("admin/scan/enable","POST","{}");long startB=System.currentTimeMillis();
        var second=awaitOrder(crampedBatch(1856),"stored_offline",false);long secondsB=(System.currentTimeMillis()-startB)/1000;
        checkFullBoxes(second,1856,2);
        if(spawns.get()!=1)throw new AssertionError("Courier summoned "+spawns.get()+" times in scenario B");
        single.getServer().runOnServer(server->{
            var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)server.overworld().getBlockEntity(new BlockPos(6,70,2));int dirt=0;
            for(int i=0;i<chest.getContainerSize();i++)if(chest.getItem(i).is(Items.DIRT))dirt+=chest.getItem(i).getCount();
            if(dirt!=460)throw new AssertionError("Leftovers were not stored before packing: chest dirt="+dirt);
            var inv=server.getPlayerList().getPlayers().getFirst().getInventory();
            if(!inv.getItem(0).has(DataComponents.CUSTOM_NAME))throw new AssertionError("Hotbar tool was touched");
        });
        System.out.println("SUPERWY_CRAMPED_PACKING_B_OK: 23 leftover slots stored in the cleanup chest first, 1856 stone -> 2 boxes, courier summoned once, scanning on; "+secondsB+" s");
    }
    private JsonObject crampedBatch(int count){
        return call("orders","POST","{\"warehouseId\":\"main\",\"clientRequestId\":\"cramped-"+count+"\",\"items\":[{\"itemId\":\"minecraft:stone\",\"count\":"+count+"}],\"delivery\":{\"method\":\"carpet_fake_player\",\"fakePlayerName\":\"SW_cramp\"},\"options\":{\"batch\":true}}","cramped-"+UUID.randomUUID());
    }
    private static void checkFullBoxes(JsonObject done,int units,int boxes){
        var packages=done.getAsJsonArray("packages");var line=done.getAsJsonArray("lines").get(0).getAsJsonObject();
        if(line.get("delivered").getAsInt()!=units||packages.size()!=boxes)throw new AssertionError("Expected "+boxes+" boxes / "+units+" delivered: "+done);
        for(int i=0;i<packages.size();i++){
            int sum=0;for(var l:packages.get(i).getAsJsonObject().getAsJsonArray("lines"))sum+=l.getAsJsonObject().get("count").getAsInt();
            if(i<packages.size()-1&&sum!=1728)throw new AssertionError("Box "+(i+1)+" delivered with "+sum+" items instead of a full 1728: "+done);
        }
    }
    private void verifyParcelCap(TestSingleplayerContext single)throws Exception{
        single.getServer().runCommand("carpet openFakePlayerInventory true");call("admin/scan/disable","POST","{}");call("admin/resume","POST","{}");context.waitTicks(10);
        call("admin/workflow","PUT","{\"returnEntry\":{\"x\":0,\"y\":69,\"z\":4},\"boxSupply\":{\"x\":2,\"y\":70,\"z\":2},\"packingRoundSlots\":25,\"maxCarriedParcels\":25}");
        single.getServer().runOnServer(server->{var level=server.overworld();var source=(BarrelBlockEntity)level.getBlockEntity(new BlockPos(2,70,0));source.clearContent();source.setItem(0,new ItemStack(Items.STONE,64));source.setItem(1,new ItemStack(Items.STONE,64));source.setChanged();var supply=(BarrelBlockEntity)level.getBlockEntity(new BlockPos(2,70,2));supply.clearContent();ItemStack empty=new ItemStack(Items.SHULKER_BOX,64);empty.set(DataComponents.MAX_STACK_SIZE,64);supply.setItem(0,empty);supply.setChanged();});
        call("admin/regions/cap","PUT","{\"min\":{\"x\":2,\"y\":70,\"z\":0},\"max\":{\"x\":2,\"y\":70,\"z\":0}}");awaitAction(context.computeOnClient(mc->scanner().refresh(new BlockPos(2,70,0))));
        boolean ready=false;for(int attempt=0;attempt<100;attempt++){if(call("capabilities","GET",null).get("canAcceptOrders").getAsBoolean()){ready=true;break;}context.waitTicks(2);}if(!ready)throw new AssertionError("Parcel-cap fixture never became ready to accept orders");
        context.runOnClient(mc->{TaskQueueGate.armed=true;TaskQueueGate.started=false;TaskQueueGate.release=new CompletableFuture<>();});
        var submitted=submit("SW_cap","[{\"itemId\":\"minecraft:stone\",\"count\":1664}]");context.waitFor(mc->TaskQueueGate.started,400);
        // Preload a reconciled 24-box state, then exercise the real 25th-box boundary and continuation.
        single.getServer().runOnServer(server->{var inv=server.getPlayerList().getPlayers().getFirst().getInventory();for(int i=9;i<33;i++){ItemStack box=new ItemStack(Items.SHULKER_BOX);box.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(new ItemStack(Items.STONE,64))));inv.setItem(i,box);}});context.waitTicks(8);
        context.runOnClient(mc->{var client=(SuperwyClient)FabricLoader.getInstance().getEntrypoints("client",net.fabricmc.api.ClientModInitializer.class).stream().filter(e->e instanceof SuperwyClient).findFirst().orElseThrow();var engineField=SuperwyClient.class.getDeclaredField("engine");engineField.setAccessible(true);Object engine=engineField.get(client);var orderField=engine.getClass().getDeclaredField("order");orderField.setAccessible(true);var live=(Model.Order)orderField.get(engine);live.lines.getFirst().fetched=1536;live.lines.getFirst().packed=1536;
            for(int i=9;i<33;i++){var parcel=new Model.Parcel();parcel.batchId=live.orderId;parcel.inventorySlot=i;parcel.manifestHash=dev.superwy.game.Items.hash(mc.player.getInventory().getItem(i));parcel.status="packed";parcel.lines.add(new Model.PackageLine(0,"minecraft:stone",dev.superwy.game.Items.hash(new ItemStack(Items.STONE)),64));live.packages.add(parcel);}
            var budget=engine.getClass().getDeclaredField("roundBudget");budget.setAccessible(true);budget.setInt(engine,1);
        });
        var observations=new CopyOnWriteArrayList<Integer>();net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.ALLOW_COMMAND.register(command->{if(command.startsWith("player SW_cap spawn at ")){var live=storeUnchecked(submitted.get("orderId").getAsString());observations.add(live.lines.getFirst().fetched);if(live.packages.stream().filter(p->p.custody.equals("robot")).count()>25)throw new AssertionError("Exceeded 25 carried boxes");}return true;});
        context.runOnClient(mc->TaskQueueGate.release.complete(null));var done=awaitOrder(submitted,"stored_offline",false);
        if(done.getAsJsonArray("packages").size()!=26||done.getAsJsonArray("lines").get(0).getAsJsonObject().get("delivered").getAsInt()!=1664||observations.isEmpty()||observations.getFirst()!=1600)throw new AssertionError("25-box flush did not precede remaining pickup: "+observations+" "+done);
        single.getServer().runCommand("player SW_cap spawn at 0 70 -3");waitForRecipient("SW_cap");single.getServer().runOnServer(server->{var inv=server.getPlayerList().getPlayerByName("SW_cap").getInventory();int boxes=0,stone=0;for(int i=0;i<36;i++){var box=inv.getItem(i);if(box.isEmpty())continue;boxes++;stone+=box.get(DataComponents.CONTAINER).nonEmptyItemCopyStream().mapToInt(ItemStack::getCount).sum();}if(boxes!=26||stone!=1664)throw new AssertionError("Cap receipt incorrect: "+boxes+" "+stone);var supply=(BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(2,70,2));if(supply.getItem(0).getCount()!=62)throw new AssertionError("Boundary must take precisely two empty boxes");});single.getServer().runCommand("player SW_cap kill");
        System.out.println("SUPERWY_PARCEL_CAP_OK: fixture preloads 24 reconciled boxes; real pickup packs box 25, ships before final 64 items, then resumes and delivers box 26; exact offline receipt 1664 stone, empty stack consumes two");
    }
    private Model.Order storeUnchecked(String id){try{return store().order(id);}catch(Exception e){throw new RuntimeException(e);}}
    private Object requester()throws Exception{return Class.forName("dev.superwy.requester.RequesterClient").getField("INSTANCE").get(null);}
    private Object currentDraft()throws Exception{Object client=requester();var f=client.getClass().getDeclaredField("batch");f.setAccessible(true);return f.get(client);}
    private Map<?,?> requesterTasks()throws Exception{Object client=requester();var f=client.getClass().getDeclaredField("tasks");f.setAccessible(true);return (Map<?,?>)f.get(client);}
    private record QueuedBatch(Object draft,String id,JsonObject order){}
    private QueuedBatch queueClientBatch(String...items)throws Exception{
        context.runOnClient(mc->{Object client=requester();client.getClass().getMethod("key",int.class,int.class,int.class).invoke(client,GLFW.GLFW_PRESS,GLFW.GLFW_KEY_BACKSLASH,0);});
        context.waitFor(mc->{try{Object d=currentDraft();return d!=null&&(boolean)d.getClass().getMethod("editing").invoke(d);}catch(Exception e){throw new RuntimeException(e);}},600);
        Object draft=context.computeOnClient(mc->currentDraft());String id=(String)draft.getClass().getField("id").get(draft);
        context.runOnClient(mc->{for(String item:items)draft.getClass().getMethod("add",String.class,int.class,int.class).invoke(draft,item,1,64);Object client=requester();var submit=client.getClass().getDeclaredMethod("commitBatch");submit.setAccessible(true);submit.invoke(client);});
        var db=context.computeOnClient(mc->store());context.waitFor(mc->db.orders().stream().anyMatch(o->id.equals(o.request.batchId)),300);
        var order=db.orders().stream().filter(o->id.equals(o.request.batchId)).findFirst().orElseThrow();return new QueuedBatch(draft,id,Jsons.GSON.toJsonTree(order).getAsJsonObject());
    }
    private void waitTaskAhead(String id,int ahead){context.waitFor(mc->{try{Object task=requesterTasks().get(id);return task!=null&&task.getClass().getField("ahead").getInt(task)==ahead&&task.getClass().getField("state").get(task).equals("queued");}catch(Exception e){throw new RuntimeException(e);}},400);}
    private void verifyTaskQueue(TestSingleplayerContext single)throws Exception{
        single.getServer().runCommand("carpet openFakePlayerInventory true");call("admin/scan/disable","POST","{}");call("admin/resume","POST","{}");context.waitTicks(10);
        call("admin/regions/tasks","PUT","{\"min\":{\"x\":2,\"y\":70,\"z\":0},\"max\":{\"x\":2,\"y\":70,\"z\":0}}");
        single.getServer().runOnServer(server->{var source=(BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(2,70,0));source.setItem(3,new ItemStack(Items.GOLD_INGOT,64));source.setChanged();});
        awaitAction(context.computeOnClient(mc->scanner().refresh(new BlockPos(2,70,0))));
        String bot=context.computeOnClient(mc->mc.player.getName().getString());clientCommand("swrequest bot "+bot);clientCommand("swrequest recipient SW_batches");clientCommand("swrequest count 1");clientCommand("swrequest mode chat");
        context.runOnClient(mc->{mc.options.pauseOnLostFocus=false;var f=mc.getWindow().getClass().getDeclaredField("focused");f.setAccessible(true);f.setBoolean(mc.getWindow(),true);mc.mouseHandler.grabMouse();TaskQueueGate.armed=true;});
        var first=queueClientBatch("minecraft:stone");context.waitFor(mc->TaskQueueGate.started,400);
        var outbound=new CopyOnWriteArrayList<String>();net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.ALLOW_COMMAND.register(c->{outbound.add(c);return true;});
        clientCommand("swrequest request minecraft:stone 1");var db=context.computeOnClient(mc->store());context.waitFor(mc->db.pendingOrders().size()==2,200);
        var chat=db.pendingOrders().stream().filter(o->o.request.batchId==null).findFirst().orElseThrow();waitTaskAhead(chat.clientRequestId,1);
        // HTTP is independently authenticated but joins the same FIFO and updates its HUD through status reads.
        context.runOnClient(mc->{Object client=requester();var f=client.getClass().getDeclaredField("config");f.setAccessible(true);Object config=f.get(client);var c=config.getClass();c.getField("baseUrl").set(config,"http://127.0.0.1:"+URI.create(base).getPort());c.getField("token").set(config,token);c.getField("transport").set(config,"http");var save=client.getClass().getDeclaredMethod("saveSettings",c);save.setAccessible(true);save.invoke(client,config);});
        clientCommand("swrequest request minecraft:gold_ingot 1");context.waitFor(mc->db.pendingOrders().size()==3,200);
        var httpOrder=db.pendingOrders().stream().filter(o->!o.principalId.startsWith("msg_")).findFirst().orElseThrow();
        String httpTask=context.computeOnClient(mc->{for(var entry:requesterTasks().entrySet())if(httpOrder.orderId.equals(entry.getValue().getClass().getField("orderId").get(entry.getValue())))return entry.getKey().toString();return null;});if(httpTask==null)throw new AssertionError("HTTP task was not tracked");waitTaskAhead(httpTask,2);
        var second=queueClientBatch("minecraft:gold_ingot","minecraft:iron_ingot");waitTaskAhead(second.id,3);
        if(!db.order(second.order.get("orderId").getAsString()).request.batchCapacityPending)throw new AssertionError("Queued batch did not defer its capacity query");
        if(outbound.stream().anyMatch(c->c.startsWith("player SW_batches ")))throw new AssertionError("New draft interfered with the executing courier: "+outbound);
        if(context.computeOnClient(mc->requesterTasks().size())!=4)throw new AssertionError("New submissions overwrote old client tasks");
        System.out.println("SUPERWY_TASK_QUEUE_SCREENSHOT "+context.takeScreenshot("task-queue-four"));
        call("orders/"+chat.orderId+"/cancel","POST",null);waitTaskAhead(httpTask,1);waitTaskAhead(second.id,2);
        int count=db.orders().size();context.runOnClient(mc->mc.getConnection().sendCommand(WhisperProtocol.command(bot,dev.superwy.protocol.BatchProtocol.message(false,second.id,"commit",2))));context.waitTicks(10);if(db.orders().size()!=count)throw new AssertionError("Repeated batch commit duplicated a task");
        context.runOnClient(mc->TaskQueueGate.release.complete(null));
        var firstDone=awaitOrder(first.order,"stored_offline",true);var httpDone=awaitOrder(Jsons.GSON.toJsonTree(httpOrder).getAsJsonObject(),"stored_offline",true);var secondDone=awaitOrder(second.order,"stored_offline",false);
        if(java.time.Instant.parse(firstDone.get("executionStoppedAt").getAsString()).isAfter(java.time.Instant.parse(httpDone.get("executionStartedAt").getAsString()))||java.time.Instant.parse(httpDone.get("executionStoppedAt").getAsString()).isAfter(java.time.Instant.parse(secondDone.get("executionStartedAt").getAsString())))throw new AssertionError("Mixed tasks did not execute FIFO");
        context.waitFor(mc->{try{return second.draft.getClass().getField("completed").getInt(second.draft)==2&&second.draft.getClass().getField("status").get(second.draft).toString().contains("批量完成");}catch(Exception e){throw new RuntimeException(e);}},150);
        if(first.draft.getClass().getField("completed").getInt(first.draft)!=1||!first.draft.getClass().getField("status").get(first.draft).toString().contains("批量完成"))throw new AssertionError("Old batch progress was overwritten by new draft");
        verifyLooseReceipt(single,"SW_batches",Map.of(Items.STONE,1,Items.GOLD_INGOT,2,Items.IRON_INGOT,1));
        System.out.println("SUPERWY_TASK_QUEUE_OK: live batch admits same-player chat single, HTTP single and second batch; HUD ahead counts 1/2/3; cancelling middle task updates 1/2; repeated commit deduplicated; queued drafts issue no player commands; packing manifest admitted without courier probe; FIFO delivery exact; both batches retain independent progress");
    }
    private JsonObject submitBatchFixture(String name,String lines)throws Exception{
        String world=baseWorld(),bot=context.computeOnClient(mc->mc.player.getName().getString()),id=WhisperProtocol.newId();
        return context.computeOnClient(mc->{
            var body=JsonParser.parseString("{\"warehouseId\":\"main\",\"items\":"+lines+",\"delivery\":{\"method\":\"carpet_fake_player\",\"fakePlayerName\":\""+name+"\"}}").getAsJsonObject();
            var request=Requests.parse(body,new Config.Principal("batch-fixture",true,List.of(),List.of()));request.batchId=id;request.notifyPlayer=bot;
            Class<?> requester=Class.forName("dev.superwy.requester.RequesterClient"),type=Class.forName("dev.superwy.requester.BatchDraft");Object draft=type.getConstructor().newInstance();
            type.getField("id").set(draft,id);type.getField("bot").set(draft,bot);type.getField("recipient").set(draft,name);type.getMethod("capacity",int.class).invoke(draft,22);
            for(var line:request.items)type.getMethod("add",String.class,int.class,int.class).invoke(draft,line.itemId,line.count,64);
            type.getField("submitted").setBoolean(draft,true);type.getField("deadline").setLong(draft,System.currentTimeMillis()+35000);type.getField("nextSend").setLong(draft,Long.MAX_VALUE);
            var f=requester.getDeclaredField("batch");f.setAccessible(true);f.set(requester.getField("INSTANCE").get(null),draft);
            return Jsons.GSON.toJsonTree(store().accept("batch-fixture",id,request,true,world,"minecraft:overworld").order()).getAsJsonObject();
        });
    }
    private void assertBatchHud(String contains,int processed,int skipped){
        context.waitFor(mc->{try{var type=Class.forName("dev.superwy.requester.RequesterClient");var f=type.getDeclaredField("batch");f.setAccessible(true);Object b=f.get(type.getField("INSTANCE").get(null));return b!=null&&b.getClass().getField("status").get(b).toString().contains(contains)&&b.getClass().getField("completed").getInt(b)==processed&&((Map<?,?>)b.getClass().getField("skipped").get(b)).size()==skipped;}catch(Exception e){throw new RuntimeException(e);}},100);
    }
    private void verifyBatchSkip(TestSingleplayerContext single)throws Exception{
        String name="SW_batches";single.getServer().runCommand("carpet openFakePlayerInventory true");
        call("admin/scan/disable","POST","{}");call("admin/resume","POST","{}");context.waitTicks(10);
        call("admin/regions/batch_skip","PUT","{\"min\":{\"x\":2,\"y\":70,\"z\":0},\"max\":{\"x\":2,\"y\":70,\"z\":0}}");
        single.getServer().runOnServer(server->{var source=(BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(2,70,0));source.setItem(0,new ItemStack(Items.STONE,5));source.setItem(3,new ItemStack(Items.GOLD_INGOT,2));source.setChanged();});
        awaitAction(context.computeOnClient(mc->scanner().refresh(new BlockPos(2,70,0))));
        var outbound=new CopyOnWriteArrayList<String>();net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.ALLOW_COMMAND.register(c->{outbound.add(c);return true;});
        context.runOnClient(mc->BatchMenuFaults.reset("minecraft:gold_ingot",1));
        var result=awaitOrder(submitBatchFixture(name,"[{\"itemId\":\"minecraft:diamond_block\",\"count\":1},{\"itemId\":\"minecraft:stone\",\"count\":10},{\"itemId\":\"minecraft:gold_ingot\",\"count\":2},{\"itemId\":\"minecraft:iron_ingot\",\"count\":3}]"),"partial_stored_offline",false);
        var lines=result.getAsJsonArray("lines");
        if(!lines.get(0).getAsJsonObject().get("errorCode").getAsString().equals("OUT_OF_STOCK")||lines.get(1).getAsJsonObject().get("fetched").getAsInt()!=5||lines.get(1).getAsJsonObject().get("shortfall").getAsInt()!=5||lines.get(2).getAsJsonObject().get("delivered").getAsInt()!=2||lines.get(3).getAsJsonObject().get("delivered").getAsInt()!=3||BatchMenuFaults.closures!=1)throw new AssertionError("Batch skip lost progress or wrong reason: "+result);
        assertBatchHud("批量完成",4,2);context.waitFor(mc->normalChatAnnouncements.stream().filter(t->t.contains("已跳过，继续下一项")).count()==2,100);
        if(outbound.stream().filter(c->c.startsWith("player "+name+" spawn at ")).count()!=1)throw new AssertionError("Partial batch spawned more than once");
        verifyLooseReceipt(single,name,Map.of(Items.STONE,5,Items.IRON_INGOT,3,Items.GOLD_INGOT,2));
        // Close after the destination click: authoritative NBT must retain the transferred gold exactly once.
        single.getServer().runOnServer(server->{var source=(BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(2,70,0));source.setItem(3,new ItemStack(Items.GOLD_INGOT,2));source.setChanged();});awaitAction(context.computeOnClient(mc->scanner().refresh(new BlockPos(2,70,0))));
        outbound.clear();context.runOnClient(mc->BatchMenuFaults.reset("minecraft:gold_ingot",2));
        result=awaitOrder(submitBatchFixture(name,"[{\"itemId\":\"minecraft:gold_ingot\",\"count\":2},{\"itemId\":\"minecraft:diamond_block\",\"count\":1},{\"itemId\":\"minecraft:iron_ingot\",\"count\":1}]"),"partial_stored_offline",false);
        var moved=result.getAsJsonArray("lines").get(0).getAsJsonObject();
        if(moved.get("fetched").getAsInt()!=2||moved.get("delivered").getAsInt()!=2||(moved.has("errorCode")&&!moved.get("errorCode").isJsonNull())||BatchMenuFaults.closures!=1)throw new AssertionError("Interrupted completed click was not reconciled: "+result);
        assertBatchHud("批量完成",3,1);verifyLooseReceipt(single,name,Map.of(Items.STONE,5,Items.IRON_INGOT,4,Items.GOLD_INGOT,4));
        // The same menu closure inside a borrowed QuickShulker must return the original box before continuing.
        single.getServer().runOnServer(server->{var source=(BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(2,70,0));source.setItem(3,new ItemStack(Items.GOLD_INGOT,3));source.setChanged();});
        awaitAction(context.computeOnClient(mc->scanner().refresh(new BlockPos(2,70,0))));context.runOnClient(mc->BatchMenuFaults.reset("minecraft:iron_ingot",1));
        result=awaitOrder(submitBatchFixture(name,"[{\"itemId\":\"minecraft:iron_ingot\",\"count\":1},{\"itemId\":\"minecraft:gold_ingot\",\"count\":1}]"),"stored_offline",false);
        if(BatchMenuFaults.closures!=1||result.getAsJsonArray("lines").get(1).getAsJsonObject().get("delivered").getAsInt()!=1)throw new AssertionError("Nested menu failure stopped later line: "+result);
        single.getServer().runOnServer(server->{var source=(BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(2,70,0));if(source.getItem(2).get(DataComponents.CONTAINER).nonEmptyItemCopyStream().mapToInt(ItemStack::getCount).sum()!=16)throw new AssertionError("Source box was lost or altered during skip");});
        assertBatchHud("批量完成",2,0);System.out.println("SUPERWY_BATCH_SKIP_SCREENSHOT "+context.takeScreenshot("batch-skipped"));
        verifyLooseReceipt(single,name,Map.of(Items.STONE,5,Items.IRON_INGOT,5,Items.GOLD_INGOT,5));
        outbound.clear();result=awaitOrder(submitBatchFixture(name,"[{\"itemId\":\"minecraft:diamond_block\",\"count\":1},{\"itemId\":\"minecraft:emerald_block\",\"count\":1}]"),"failed",false);
        if(!result.get("errorCode").getAsString().equals("NOTHING_DELIVERED")||outbound.stream().anyMatch(c->c.startsWith("player "+name+" spawn at ")))throw new AssertionError("All-missing batch spawned empty delivery: "+result);
        assertBatchHud("均未取得材料",2,2);
        // A close with a full source stack on the cursor cannot be guessed as a one-item withdrawal.
        single.getServer().runOnServer(server->{var source=(BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(2,70,0));source.setItem(3,new ItemStack(Items.GOLD_INGOT,64));source.setChanged();});
        awaitAction(context.computeOnClient(mc->scanner().refresh(new BlockPos(2,70,0))));context.runOnClient(mc->BatchMenuFaults.reset("minecraft:gold_ingot",3));
        result=awaitOrder(submitBatchFixture(name,"[{\"itemId\":\"minecraft:gold_ingot\",\"count\":1},{\"itemId\":\"minecraft:iron_ingot\",\"count\":1}]"),"failed",false);
        if(!result.get("needsItemReview").getAsBoolean()||result.getAsJsonArray("lines").get(1).getAsJsonObject().get("fetched").getAsInt()!=0)throw new AssertionError("Unreconciled cursor was ignored: "+result);
        context.runOnClient(mc->BatchMenuFaults.reset(null,0));
        System.out.println("SUPERWY_BATCH_SKIP_OK: missing and partial lines skip; MENU_DESYNC before click and CURSOR_OR_MENU_DESYNC after transfer retry after reconciliation; exact quantities delivered once; borrowed nested box returned before retry; all-missing list completes without spawning; client skipped counts and partial/empty endings; genuinely uncertain cursor still stops for review");
    }
    private void verifyBatchPickup(TestSingleplayerContext single)throws Exception{
        String name="SW_batches";
        single.getServer().runCommand("carpet openFakePlayerInventory true");
        call("admin/scan/disable","POST","{}");call("admin/resume","POST","{}");context.waitTicks(10);
        call("admin/regions/batch","PUT","{\"min\":{\"x\":2,\"y\":70,\"z\":0},\"max\":{\"x\":2,\"y\":70,\"z\":0}}");
        single.getServer().runOnServer(server->{var source=(BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(2,70,0));source.setItem(0,new ItemStack(Items.STONE,2));source.setItem(1,new ItemStack(Items.STONE,3));source.setItem(3,new ItemStack(Items.GOLD_INGOT,12));source.setChanged();});
        awaitAction(context.computeOnClient(mc->scanner().refresh(new BlockPos(2,70,0))));
        single.getServer().runCommand("player "+name+" spawn at 8 70 8");waitForRecipient(name);
        single.getServer().runOnServer(server->{var inv=server.getPlayerList().getPlayerByName(name).getInventory();for(int i=0;i<24;i++)inv.setItem(i,new ItemStack(Items.DIRT,1));});single.getServer().runCommand("player "+name+" kill");context.waitTicks(10);
        Class<?> requester=Class.forName("dev.superwy.requester.RequesterClient"),settings=Class.forName("dev.superwy.requester.RequesterConfig");Object client=requester.getField("INSTANCE").get(null);
        var configField=requester.getDeclaredField("config");configField.setAccessible(true);var batchField=requester.getDeclaredField("batch");batchField.setAccessible(true);
        var key=requester.getMethod("key",int.class,int.class,int.class);
        context.runOnClient(mc->{Object c=settings.getConstructor().newInstance();settings.getField("botName").set(c,mc.player.getName().getString());settings.getField("fakePlayerName").set(c,name);configField.set(client,c);mc.options.pauseOnLostFocus=false;var focus=mc.getWindow().getClass().getDeclaredField("focused");focus.setAccessible(true);focus.setBoolean(mc.getWindow(),true);mc.mouseHandler.grabMouse();key.invoke(client,GLFW.GLFW_PRESS,GLFW.GLFW_KEY_BACKSLASH,0);});
        context.waitFor(mc->{try{Object b=batchField.get(client);return b!=null&&(boolean)b.getClass().getMethod("editing").invoke(b);}catch(Exception e){throw new RuntimeException(e);}},600);
        Object draft=context.computeOnClient(mc->batchField.get(client));Class<?> draftType=draft.getClass();
        context.runOnClient(mc->{Object probe=draftType.getConstructor().newInstance();draftType.getField("id").set(probe,"0123456789abcdef0123456789abcdef");var add=draftType.getMethod("add",String.class,int.class,int.class);for(int i=0;i<128;i++)add.invoke(probe,"minecraft:stone",1,64);try{add.invoke(probe,"minecraft:stone",1,64);throw new AssertionError("Accepted a 129th manifest line");}catch(java.lang.reflect.InvocationTargetException e){if(!(e.getCause() instanceof IllegalArgumentException))throw e;}});
        single.getServer().runOnServer(server->{if(server.getPlayerList().getPlayerByName(name)!=null)throw new AssertionError("Editing draft summoned courier");});
        var outbound=new CopyOnWriteArrayList<String>();net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.ALLOW_COMMAND.register(command->{outbound.add(command);return true;});
        context.runOnClient(mc->{var add=draftType.getMethod("add",String.class,int.class,int.class);add.invoke(draft,"minecraft:stone",5,64);add.invoke(draft,"minecraft:iron_ingot",3,64);add.invoke(draft,"minecraft:gold_ingot",4,64);});
        System.out.println("SUPERWY_BATCH_HUD_SCREENSHOT "+context.takeScreenshot("batch-manifest"));
        // Confirmation is handled with no quantity prompt and no Screen.
        context.runOnClient(mc->{if(mc.screen!=null)throw new AssertionError("Batch unexpectedly opened a Screen");key.invoke(client,GLFW.GLFW_PRESS,GLFW.GLFW_KEY_ENTER,0);});
        var db=context.computeOnClient(mc->store());String id=(String)draftType.getField("id").get(draft);
        context.waitFor(mc->db.orders().stream().anyMatch(o->id.equals(o.clientRequestId)),400);
        var order=db.orders().stream().filter(o->id.equals(o.clientRequestId)).findFirst().orElseThrow();
        context.waitFor(mc->{var o=db.order(order.orderId);return o.lines.stream().anyMatch(l->l.fetched>0);},600);
        awaitOrder(Jsons.GSON.toJsonTree(order).getAsJsonObject(),"stored_offline",true);
        context.waitFor(mc->{try{return draftType.getField("completed").getInt(draft)==3&&draftType.getField("status").get(draft).toString().contains("批量完成");}catch(Exception e){throw new RuntimeException(e);}},100);
        context.waitFor(mc->normalChatAnnouncements.stream().anyMatch(t->t.contains("搞定了，我歇逼了")),100);
        long spawns=outbound.stream().filter(c->c.startsWith("player "+name+" spawn at ")).count();if(spawns!=1)throw new AssertionError("Batch delivered in more than one spawn: "+outbound);
        if(outbound.stream().filter(c->c.contains(" progress ")).count()<5)throw new AssertionError("Missing live private progress: "+outbound);
        System.out.println("SUPERWY_BATCH_DONE_SCREENSHOT "+context.takeScreenshot("batch-complete"));
        verifyLooseReceipt(single,name,Map.of(Items.DIRT,24,Items.STONE,5,Items.IRON_INGOT,3,Items.GOLD_INGOT,4));
        awaitOrder(submit("SW_delivery","[{\"itemId\":\"minecraft:gold_ingot\",\"count\":1}]"),"stored_offline",true);
        verifyLooseReceipt(single,"SW_delivery",Map.of(Items.GOLD_INGOT,1));
        // A new draft can be explicitly discarded, releasing all admission locks.
        context.runOnClient(mc->{key.invoke(client,GLFW.GLFW_PRESS,GLFW.GLFW_KEY_BACKSLASH,0);key.invoke(client,GLFW.GLFW_PRESS,GLFW.GLFW_KEY_ESCAPE,0);});context.waitTicks(15);
        awaitOrder(submit("SW_after_cancel","[{\"itemId\":\"minecraft:gold_ingot\",\"count\":1}]"),"stored_offline",true);
        System.out.println("SUPERWY_BATCH_PICKUP_OK: both real mods; default queue/confirm hotkeys; 128-line manifest without courier probe; three-line multi-packet private manifest; partial source stacks merged; nested source returned; live tell progress 0..3/3; only one post-fetch summon; GCA receipt with preexisting items; exact public completion; admission restored and draft abort unlocks");
    }
    private void verifySpawnFailureCleanup(TestSingleplayerContext single)throws Exception{
        String name="SW_spawn_fail",prefix="player "+name;
        single.getServer().runCommand("carpet openFakePlayerInventory true");
        call("admin/scan/disable","POST","{}");call("admin/resume","POST","{}");context.waitTicks(10);
        call("admin/regions/spawn_failure","PUT","{\"min\":{\"x\":2,\"y\":70,\"z\":0},\"max\":{\"x\":2,\"y\":70,\"z\":0}}");
        awaitAction(context.computeOnClient(mc->scanner().refresh(new BlockPos(2,70,0))));context.waitTicks(25);
        var commands=new CopyOnWriteArrayList<String>();var blockSpawns=new java.util.concurrent.atomic.AtomicBoolean();
        net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents.ALLOW_COMMAND.register(command->{
            if(command.startsWith(prefix+" "))commands.add(command);
            return !blockSpawns.get()||!command.startsWith(prefix+" spawn at ");
        });
        single.getServer().runCommand(prefix+" spawn at 8 70 8");waitForRecipient(name);hideCourierFromClient(single,name);
        awaitOrder(submit(name,"[{\"itemId\":\"minecraft:stone\",\"count\":1}]"),"stored_offline",true);context.waitTicks(10);
        if(commands.size()!=4||!commands.get(0).startsWith(prefix+" spawn at ")||!commands.get(1).equals(prefix+" kill")||!commands.get(2).startsWith(prefix+" spawn at ")||!commands.get(3).equals(prefix+" kill"))throw new AssertionError("Expected failed spawn, cleanup, retry and final delivery despawn: "+commands);
        verifyLooseReceipt(single,name,Map.of(Items.STONE,1));
        // Block both attempts to confirm the retry is bounded and cargo stays held.
        commands.clear();blockSpawns.set(true);
        var failed=awaitOrder(submit(name,"[{\"itemId\":\"minecraft:stone\",\"count\":1}]"),"failed",true);
        blockSpawns.set(false);
        if(!failed.get("errorCode").getAsString().equals("FAKE_PLAYER_UNAVAILABLE"))throw new AssertionError("Wrong second-spawn failure: "+failed);
        var line=failed.getAsJsonArray("lines").get(0).getAsJsonObject();
        if(line.get("fetched").getAsInt()!=1||line.get("delivered").getAsInt()!=0||!failed.get("needsItemReview").getAsBoolean())throw new AssertionError("Failed spawn mishandled cargo: "+failed);
        context.waitTicks(15);
        if(commands.size()!=3||!commands.get(0).startsWith(prefix+" spawn at ")||!commands.get(1).equals(prefix+" kill")||!commands.get(2).startsWith(prefix+" spawn at "))throw new AssertionError("Spawn retry repeated or skipped cleanup: "+commands);
        String failureText="minecraft:stone 取货失败："+ErrorMessages.message("FAKE_PLAYER_UNAVAILABLE");
        context.waitFor(mc->normalChatAnnouncements.stream().anyMatch(t->t.contains(failureText)),100);
        if(normalChatAnnouncements.stream().filter(t->t.contains(failureText)).count()!=1)throw new AssertionError("Spawn failure announced more than once");
        awaitOrder(submit("SW_after_cancel","[{\"itemId\":\"minecraft:stone\",\"count\":1}]"),"stored_offline",true);
        verifyLooseReceipt(single,"SW_after_cancel",Map.of(Items.STONE,1));
        // Cancelling the first spawn wait must stop cleanup and retry as well.
        commands.clear();single.getServer().runCommand(prefix+" spawn at 8 70 8");waitForRecipient(name);hideCourierFromClient(single,name);
        var cancelled=submit(name,"[{\"itemId\":\"minecraft:stone\",\"count\":1}]");
        context.waitFor(mc->commands.stream().anyMatch(c->c.startsWith(prefix+" spawn at ")),200);
        call("orders/"+cancelled.get("orderId").getAsString()+"/cancel","POST",null);context.waitTicks(20);
        if(commands.size()!=1||!commands.get(0).startsWith(prefix+" spawn at "))throw new AssertionError("Cancelled spawn continued cleanup/retry: "+commands);
        single.getServer().runOnServer(server->{if(server.getPlayerList().getPlayerByName(name)==null)throw new AssertionError("Cancelled spawn removed courier");});
        if(!call("orders/"+cancelled.get("orderId").getAsString(),"GET",null).get("state").getAsString().equals("cancelled"))throw new AssertionError("Late cleanup changed cancellation");
        single.getServer().runCommand(prefix+" kill");
        System.out.println("SUPERWY_SPAWN_RETRY_OK: real already-logged-on rejection with hidden client state; one kill then one successful retry and GCA receipt; second failure stops after two spawn attempts and retains cargo; following FIFO succeeds; cancellation sends no cleanup or retry");
    }
    private void verifyDeliveryQueue(TestSingleplayerContext single)throws Exception{
        String name="SW_deliveryQ";
        single.getServer().runCommand("carpet openFakePlayerInventory true");
        call("admin/scan/disable","POST","{}");call("admin/resume","POST","{}");context.waitTicks(10);
        call("admin/regions/delivery_queue","PUT","{\"min\":{\"x\":2,\"y\":70,\"z\":0},\"max\":{\"x\":2,\"y\":70,\"z\":0}}");
        awaitAction(context.computeOnClient(mc->scanner().refresh(new BlockPos(2,70,0))));
        single.getServer().runCommand("player "+name+" spawn at 8 70 8");waitForRecipient(name);
        var original=new java.util.concurrent.atomic.AtomicReference<net.minecraft.server.level.ServerPlayer>();
        single.getServer().runOnServer(server->{var player=server.getPlayerList().getPlayerByName(name);original.set(player);player.getInventory().setItem(0,new ItemStack(Items.DIRT,5));});
        var joins=new java.util.concurrent.atomic.AtomicInteger();var clearedBeforeJoin=new java.util.concurrent.atomic.AtomicBoolean();
        // Carpet's fake connection bypasses Fabric's ordinary DISCONNECT event.
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler,sender,server)->{
            if(handler.getPlayer().getName().getString().equals(name)&&joins.getAndIncrement()==0)clearedBeforeJoin.set(original.get().isRemoved()&&handler.getPlayer()!=original.get());
        });
        String world=baseWorld();
        var orders=context.computeOnClient(mc->{
            var submitted=new ArrayList<JsonObject>();
            for(int i=0;i<4;i++){
                var request=new Model.Request();request.delivery=new Model.DeliveryRequest();request.delivery.fakePlayerName=name;
                var line=new Model.RequestLine();line.itemId=i==2?"minecraft:diamond_block":"minecraft:stone";line.count=1;request.items.add(line);
                var accepted=store().accept("delivery-queue","delivery-queue-"+i,request,true,world,"minecraft:overworld").order();
                submitted.add(Jsons.GSON.toJsonTree(accepted).getAsJsonObject());
            }
            return submitted;
        });
        for(int i=0;i<orders.size();i++)awaitOrder(orders.get(i),i==2?"failed":"stored_offline",true);
        context.waitFor(mc->normalChatAnnouncements.stream().anyMatch(t->t.contains("都取完了，我歇逼了")),100);context.waitTicks(10);
        var queueMessages=normalChatAnnouncements.stream().filter(t->t.contains("个订单在队列")||t.contains("都取完了，我歇逼了")).toList();
        if(queueMessages.size()!=4)throw new AssertionError("Queue reports missing or duplicated: "+queueMessages);
        var expectedReports=List.of("还有3个订单在队列","还有2个订单在队列","还有1个订单在队列","都取完了，我歇逼了");
        for(int i=0;i<4;i++)if(!queueMessages.get(i).contains(expectedReports.get(i)))throw new AssertionError("Wrong queue count/order: "+queueMessages);
        if(publicAnnouncements.stream().filter(t->t.contains("个订单在队列")||t.contains("都取完了，我歇逼了")).count()!=4)throw new AssertionError("Queue reports used /say or duplicated");
        if(joins.get()!=3||!clearedBeforeJoin.get())throw new AssertionError("Existing courier was not cleared before three deliveries: joins="+joins+", cleared="+clearedBeforeJoin);
        single.getServer().runOnServer(server->{if(server.getPlayerList().getPlayerByName(name)!=null)throw new AssertionError("Courier remained online after delivery");});
        verifyLooseReceipt(single,name,Map.of(Items.DIRT,5,Items.STONE,3));
        awaitOrder(submit(name,"[{\"itemId\":\"minecraft:stone\",\"count\":1}]"),"stored_offline",true);context.waitTicks(10);
        if(normalChatAnnouncements.stream().filter(t->t.contains("个订单在队列")||t.contains("都取完了，我歇逼了")).count()!=4)throw new AssertionError("Small next batch inherited queue reporting");
        verifyLooseReceipt(single,name,Map.of(Items.DIRT,5,Items.STONE,4));
        System.out.println("SUPERWY_DELIVERY_QUEUE_OK: online courier cleared and respawned in place; inventory preserved; FIFO success/failure report remaining 3,2,1,0 exactly once as ChatType.CHAT; next small batch silent");
    }
    private void verifyRestockRetry(TestSingleplayerContext single)throws Exception{
        single.getServer().runCommand("carpet openFakePlayerInventory true");
        call("admin/scan/disable","POST","{}");call("admin/resume","POST","{}");context.waitTicks(10);
        call("admin/regions/restock_fixture","PUT","{\"min\":{\"x\":2,\"y\":70,\"z\":0},\"max\":{\"x\":2,\"y\":70,\"z\":0}}");
        var pos=new BlockPos(2,70,0);
        single.getServer().runOnServer(server->{
            server.getPlayerList().getPlayers().getFirst().getInventory().clearContent();
            server.overworld().setBlockAndUpdate(pos,Blocks.BLUE_SHULKER_BOX.defaultBlockState());
            var box=(net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity)server.overworld().getBlockEntity(pos);box.setItem(2,new ItemStack(Items.ANDESITE_WALL,64));box.setChanged();
        });context.waitTicks(5);
        awaitAction(context.computeOnClient(mc->scanner().refresh(pos)));context.waitTicks(20);context.runOnClient(mc->BatchMenuFaults.reset("minecraft:andesite_wall",2));
        var accepted=submit("SW_fb_next","[{\"itemId\":\"minecraft:andesite_wall\",\"count\":128}]");
        context.waitFor(mc->BatchMenuFaults.closures==1,200);context.waitTicks(3);
        single.getServer().runOnServer(server->{
            var level=server.overworld();var old=(net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity)level.getBlockEntity(pos);
            if(!old.getItem(2).isEmpty())throw new AssertionError("First 64 items were not removed before replenishment");
            level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());level.setBlockAndUpdate(pos,Blocks.BLUE_SHULKER_BOX.defaultBlockState());
            var next=(net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity)level.getBlockEntity(pos);next.setItem(2,new ItemStack(Items.DIRT,7));next.setItem(17,new ItemStack(Items.ANDESITE_WALL,64));next.setChanged();
        });
        var result=awaitOrder(accepted,"stored_offline",true);
        if(result.getAsJsonArray("lines").get(0).getAsJsonObject().get("delivered").getAsInt()!=128||BatchMenuFaults.reopenedAt-BatchMenuFaults.closedAt<1_000_000_000L)throw new AssertionError("Restock was retried too early or counted twice: "+result);
        verifyLooseReceipt(single,"SW_fb_next",Map.of(Items.ANDESITE_WALL,128));
        single.getServer().runOnServer(server->{var box=(net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity)server.overworld().getBlockEntity(pos);if(!box.getItem(17).isEmpty()||box.getItem(2).getCount()!=7)throw new AssertionError("Retried pickup used the old layout");box.clearContent();box.setItem(17,new ItemStack(Items.GOLD_INGOT,9));box.setChanged();});
        awaitAction(context.computeOnClient(mc->scanner().refresh(pos)));context.runOnClient(mc->BatchMenuFaults.reset("minecraft:gold_ingot",1));
        accepted=submit("SW_fb_variant","[{\"itemId\":\"minecraft:gold_ingot\",\"count\":4}]");context.waitFor(mc->BatchMenuFaults.closures==1,200);context.waitTicks(3);
        single.getServer().runOnServer(server->{var box=(net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity)server.overworld().getBlockEntity(pos);box.clearContent();box.setItem(17,new ItemStack(Items.DIRT,5));box.setItem(5,new ItemStack(Items.GOLD_INGOT,9));box.setChanged();});
        awaitOrder(accepted,"stored_offline",true);verifyLooseReceipt(single,"SW_fb_variant",Map.of(Items.GOLD_INGOT,4));
        if(BatchMenuFaults.reopenedAt-BatchMenuFaults.closedAt<1_000_000_000L)throw new AssertionError("MENU_DESYNC did not wait one second");
        context.runOnClient(mc->BatchMenuFaults.reset(null,0));
        single.getServer().runOnServer(server->{
            var level=server.overworld();level.setBlockAndUpdate(pos,Blocks.BARREL.defaultBlockState());var barrel=(BarrelBlockEntity)level.getBlockEntity(pos);barrel.clearContent();
            var box=new ItemStack(Items.SHULKER_BOX);box.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(new ItemStack(Items.GOLD_INGOT,4))));barrel.setItem(2,box);barrel.setChanged();
        });context.waitTicks(5);awaitAction(context.computeOnClient(mc->scanner().refresh(pos)));
        single.getServer().runOnServer(server->{
            var barrel=(BarrelBlockEntity)server.overworld().getBlockEntity(pos);barrel.clearContent();barrel.setItem(2,new ItemStack(Items.STONE,8));
            var box=new ItemStack(Items.SHULKER_BOX);box.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(List.of(new ItemStack(Items.IRON_INGOT,1),new ItemStack(Items.GOLD_INGOT,9))));barrel.setItem(20,box);barrel.setChanged();
        });
        accepted=submit("SW_nested","[{\"itemId\":\"minecraft:gold_ingot\",\"count\":3}]");
        context.waitFor(mc->mc.player.containerMenu instanceof net.minecraft.world.inventory.ShulkerBoxMenu,200);
        single.getServer().runOnServer(server->{var barrel=(BarrelBlockEntity)server.overworld().getBlockEntity(pos);if(!barrel.getItem(20).isEmpty())throw new AssertionError("Box was not borrowed from its current slot");barrel.setItem(20,new ItemStack(Items.DIRT,6));barrel.setChanged();});
        awaitOrder(accepted,"stored_offline",false);verifyLooseReceipt(single,"SW_nested",Map.of(Items.GOLD_INGOT,3));
        single.getServer().runOnServer(server->{
            var barrel=(BarrelBlockEntity)server.overworld().getBlockEntity(pos);if(!barrel.getItem(2).is(Items.STONE)||barrel.getItem(2).getCount()!=8||!barrel.getItem(20).is(Items.DIRT)||barrel.getItem(20).getCount()!=6)throw new AssertionError("Current occupied slots were overwritten");
            var boxes=java.util.stream.IntStream.range(0,27).mapToObj(barrel::getItem).filter(s->s.is(Items.SHULKER_BOX)).toList();if(boxes.size()!=1)throw new AssertionError("Source box lost or duplicated");
            var contents=boxes.getFirst().get(DataComponents.CONTAINER).nonEmptyItemCopyStream().toList();int gold=contents.stream().filter(s->s.is(Items.GOLD_INGOT)).mapToInt(ItemStack::getCount).sum(),iron=contents.stream().filter(s->s.is(Items.IRON_INGOT)).mapToInt(ItemStack::getCount).sum();if(gold!=6||iron!=1)throw new AssertionError("Nested pickup used cached contents");
        });
        System.out.println("SUPERWY_RESTOCK_RETRY_OK: after-transfer replacement reconciles 64+64 exactly once; before-click MENU_DESYNC waits at least 1s; reopened menus use new slots; changed nested box contents and inner slots accepted; occupied former return slot preserved and source box returned to a current free slot");
    }
    private void verifySourceFallback(TestSingleplayerContext single)throws Exception{
        single.getServer().runCommand("carpet openFakePlayerInventory true");
        call("admin/scan/disable","POST","{}");call("admin/resume","POST","{}");context.waitTicks(10);
        call("admin/regions/fallback_a","PUT","{\"min\":{\"x\":3,\"y\":70,\"z\":0},\"max\":{\"x\":3,\"y\":70,\"z\":0}}");
        call("admin/regions/fallback_b","PUT","{\"min\":{\"x\":6,\"y\":70,\"z\":0},\"max\":{\"x\":8,\"y\":70,\"z\":0}}");

        prepareFallback(single,Items.LEVER,new int[]{64,1,0},new int[]{-1,9,0},false);
        awaitOrder(submit("SW_fb_missing","[{\"itemId\":\"minecraft:lever\",\"count\":7}]"),"stored_offline",true);
        verifyLooseReceipt(single,"SW_fb_missing",Map.of(Items.LEVER,7));
        context.runOnClient(mc->{if(store().snapshots().stream().anyMatch(s->s.pos.equals(new Model.Pos(3,70,0))))throw new AssertionError("Missing A container remained indexed");});

        prepareFallback(single,Items.BLACK_STAINED_GLASS_PANE,new int[]{64,2,0},new int[]{0,6,0},false);
        awaitOrder(submit("SW_fb_empty","[{\"itemId\":\"minecraft:black_stained_glass_pane\",\"count\":5}]"),"stored_offline",true);
        verifyLooseReceipt(single,"SW_fb_empty",Map.of(Items.BLACK_STAINED_GLASS_PANE,5));
        context.runOnClient(mc->{var empty=store().snapshots().stream().filter(s->s.pos.equals(new Model.Pos(3,70,0))).findFirst().orElseThrow();if(!empty.complete||!empty.items.isEmpty())throw new AssertionError("Empty A cache was not refreshed");});

        prepareFallback(single,Items.LEVER,new int[]{64,64,64},new int[]{2,3,4},false);
        awaitOrder(submit("SW_fb_partial","[{\"itemId\":\"minecraft:lever\",\"count\":8}]"),"stored_offline",true);
        verifyLooseReceipt(single,"SW_fb_partial",Map.of(Items.LEVER,8));
        single.getServer().runOnServer(server->{for(int x:new int[]{3,6})if(!((BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(x,70,0))).isEmpty())throw new AssertionError("Did not exhaust partial source "+x);if(((BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(8,70,0))).getItem(0).getCount()!=1)throw new AssertionError("Partial source withdrawal count wrong");});

        prepareFallback(single,Items.LEVER,new int[]{1,1,0},new int[]{4,4,0},false);
        awaitOrder(submit("SW_fb_low","[{\"itemId\":\"minecraft:lever\",\"count\":7}]"),"stored_offline",true);
        verifyLooseReceipt(single,"SW_fb_low",Map.of(Items.LEVER,7));

        prepareFallback(single,Items.LEVER,new int[]{64,64,0},new int[]{-1,0,0},false);
        long failures=publicAnnouncements.stream().filter(t->t.contains("minecraft:lever 缺货")).count();
        var failed=awaitOrder(submit("SW_fb_none","[{\"itemId\":\"minecraft:lever\",\"count\":4}]"),"failed",true);
        if(!failed.get("errorCode").getAsString().equals("NOTHING_DELIVERED")||!failed.getAsJsonArray("lines").get(0).getAsJsonObject().get("errorCode").getAsString().equals("OUT_OF_STOCK")||failed.getAsJsonArray("lines").get(0).getAsJsonObject().get("fetched").getAsInt()!=0||failed.getAsJsonObject("delivery").has("spawnPosition"))throw new AssertionError("Exhausted sources ended incorrectly: "+failed);
        context.waitFor(mc->publicAnnouncements.stream().filter(t->t.contains("minecraft:lever 缺货")).count()==failures+1,100);
        if(normalChatAnnouncements.stream().noneMatch(t->t.contains("minecraft:lever 缺货")))throw new AssertionError("Shortage did not use normal chat");

        prepareFallback(single,Items.LEVER,new int[]{64,64,0},new int[]{6,6,0},true);
        awaitOrder(submit("SW_fb_variant","[{\"itemId\":\"minecraft:lever\",\"count\":5}]"),"stored_offline",true);
        verifyLooseReceipt(single,"SW_fb_variant",Map.of(Items.LEVER,5));
        single.getServer().runOnServer(server->{var item=((BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(3,70,0))).getItem(0);if(item.getCount()!=6||!item.has(DataComponents.CUSTOM_NAME))throw new AssertionError("Wrong component variant was withdrawn");});

        // Exclusions belong to one order; the restored A container must work in the next order.
        prepareFallback(single,Items.LEVER,new int[]{5,0,0},new int[]{5,0,0},false);
        awaitOrder(submit("SW_fb_next","[{\"itemId\":\"minecraft:lever\",\"count\":5}]"),"stored_offline",true);
        verifyLooseReceipt(single,"SW_fb_next",Map.of(Items.LEVER,5));
        System.out.println("SUPERWY_SOURCE_FALLBACK_OK: two enabled regions; removed/empty A falls through to B; partial quantities across three containers; low cache counts verified; all sources exhausted announces shortage once; components preserved; next order resets exclusions");
    }
    private void prepareFallback(TestSingleplayerContext single,net.minecraft.world.item.Item item,int[] cached,int[] actual,boolean namedFirst)throws Exception{
        single.getServer().runCommand("tp @a 0.5 70 0.5");
        int[] xs={3,6,8};
        single.getServer().runOnServer(server->{
            var level=server.overworld();server.getPlayerList().getPlayers().getFirst().getInventory().clearContent();
            for(int i=0;i<xs.length;i++){var p=new BlockPos(xs[i],70,0);level.setBlockAndUpdate(p,Blocks.BARREL.defaultBlockState());var barrel=(BarrelBlockEntity)level.getBlockEntity(p);barrel.clearContent();if(cached[i]>0)barrel.setItem(0,new ItemStack(item,cached[i]));barrel.setChanged();}
        });context.waitTicks(8);
        for(int x:xs)awaitAction(context.computeOnClient(mc->scanner().refresh(new BlockPos(x,70,0))));
        single.getServer().runOnServer(server->{
            var level=server.overworld();
            for(int i=0;i<xs.length;i++){var p=new BlockPos(xs[i],70,0);if(actual[i]<0){((BarrelBlockEntity)level.getBlockEntity(p)).clearContent();level.setBlockAndUpdate(p,Blocks.AIR.defaultBlockState());continue;}var barrel=(BarrelBlockEntity)level.getBlockEntity(p);barrel.clearContent();if(actual[i]>0){var stack=new ItemStack(item,actual[i]);if(namedFirst&&i==0)stack.set(DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Other variant"));barrel.setItem(0,stack);}barrel.setChanged();}
        });context.waitTicks(8);
    }
    private dev.superwy.game.Scanner scanner()throws ReflectiveOperationException{
        var client=FabricLoader.getInstance().getEntrypoints("client",net.fabricmc.api.ClientModInitializer.class).stream().filter(e->e instanceof SuperwyClient).findFirst().orElseThrow();
        var field=SuperwyClient.class.getDeclaredField("scanner");field.setAccessible(true);return (dev.superwy.game.Scanner)field.get(client);
    }
    private void verifyScanProtocols(TestSingleplayerContext single)throws Exception{
        if(!context.computeOnClient(mc->config().scanProtocol.equals("f3")))throw new AssertionError("New config did not default to F3");
        single.getServer().runOnServer(server->{
            var blocks=List.of(Blocks.CHEST,Blocks.TRAPPED_CHEST,Blocks.PURPLE_SHULKER_BOX,Blocks.BARREL,Blocks.HOPPER,Blocks.FURNACE,Blocks.DECORATED_POT);
            for(int i=0;i<blocks.size();i++){
                var p=new BlockPos(4+i,70,8);server.overworld().setBlockAndUpdate(p,blocks.get(i).defaultBlockState());
                if(server.overworld().getBlockEntity(p) instanceof net.minecraft.world.Container container){
                    container.setItem(0,new ItemStack(i<4?Items.STONE:Items.DIAMOND,11+i));
                    if(i==3){var inner=new ArrayList<ItemStack>();for(int slot=0;slot<27;slot++)inner.add(ItemStack.EMPTY);inner.set(19,new ItemStack(Items.GOLD_INGOT,7));var box=new ItemStack(Items.BLUE_SHULKER_BOX);box.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(inner));container.setItem(2,box);}
                    server.overworld().getBlockEntity(p).setChanged();
                }
            }
        });context.waitTicks(10);
        call("admin/regions/protocol_fixture","PUT","{\"min\":{\"x\":4,\"y\":70,\"z\":8},\"max\":{\"x\":10,\"y\":70,\"z\":8}}");
        call("admin/scan/start","POST","{\"regionId\":\"protocol_fixture\"}");var scan=waitForScan("complete");assertStorageScan(scan);
        if(dev.superwy.testutil.ServuxFixture.requests!=0)throw new AssertionError("Default F3 sent a Servux query");
        clientCommand("superwy scan protocol servux");
        if(!context.computeOnClient(mc->Config.load(FabricLoader.getInstance().getConfigDir().resolve("superwy/config.json")).scanProtocol.equals("servux")))throw new AssertionError("Protocol command did not persist selection");
        call("admin/scan/reset-failures","POST","{\"regionId\":\"protocol_fixture\"}");scan=waitForScan("complete");assertStorageScan(scan);
        if(dev.superwy.testutil.ServuxFixture.requests!=1||dev.superwy.testutil.ServuxFixture.slices<2)throw new AssertionError("Servux did not use one split bulk request for four containers");
        var stats=call("admin/status","GET",null);
        if(stats.getAsJsonArray("containers").size()!=4||!stats.get("scanProtocol").getAsString().equals("servux"))throw new AssertionError("Servux included excluded containers: "+stats);
        var protocolRegion=stats.getAsJsonArray("regions").asList().stream().map(JsonElement::getAsJsonObject).filter(r->r.get("id").getAsString().equals("protocol_fixture")).findFirst().orElseThrow();
        long completedAt=protocolRegion.get("lastScanAt").getAsLong();
        if(scan.get("intervalSeconds").getAsInt()!=600||scan.get("nextDueAt").getAsLong()!=completedAt+600000||store().scan("protocol_fixture").nextAttemptAt!=completedAt+600000)throw new AssertionError("Servux interval/deadline not ten minutes: "+scan);
        context.runOnClient(mc->{var barrel=store().snapshots().stream().filter(s->s.pos.equals(new Model.Pos(7,70,8))).findFirst().orElseThrow();var child=barrel.items.stream().filter(n->n.slot==2).findFirst().orElseThrow().children.getFirst();if(child.slot!=19||child.count!=7||!child.itemId.equals("minecraft:gold_ingot"))throw new AssertionError("Servux lost nested shulker slot/count");});
        var before=context.computeOnClient(mc->store().snapshots().stream().filter(s->s.pos.z()==8).map(s->s.observedAt).toList());
        dev.superwy.testutil.ServuxFixture.invalid=true;
        call("admin/scan/reset-failures","POST","{\"regionId\":\"protocol_fixture\"}");scan=waitForScan("protocol_error");
        if(scan.get("skippedChunks").getAsInt()!=0||scan.get("remainingChunks").getAsInt()!=1)throw new AssertionError("Bad protocol reply permanently skipped pending chunk");
        var after=context.computeOnClient(mc->store().snapshots().stream().filter(s->s.pos.z()==8).map(s->s.observedAt).toList());
        if(!before.equals(after))throw new AssertionError("Invalid reply overwrote historical stock");
        dev.superwy.testutil.ServuxFixture.invalid=false;clientCommand("superwy scan protocol f3");scan=waitForScan("complete");assertStorageScan(scan);
        if(scan.get("intervalSeconds").getAsInt()!=3600)throw new AssertionError("Switch back to F3 did not restore original region interval");

        // Reset the experimental transport, as a new connection would do after a malformed stream.
        context.runOnClient(mc->servuxTransport().reset());dev.superwy.testutil.ServuxFixture.hold=true;
        clientCommand("superwy scan protocol servux");int target=dev.superwy.testutil.ServuxFixture.requests+1;
        call("admin/scan/reset-failures","POST","{\"regionId\":\"protocol_fixture\"}");
        context.waitFor(mc->dev.superwy.testutil.ServuxFixture.requests>=target,100);
        clientCommand("superwy scan protocol f3");scan=waitForScan("complete");assertStorageScan(scan);
        var saved=context.computeOnClient(mc->store().snapshots().stream().filter(s->s.pos.z()==8).map(s->s.observedAt).toList());
        single.getServer().runOnServer(server->dev.superwy.testutil.ServuxFixture.release());context.waitTicks(8);
        if(!saved.equals(context.computeOnClient(mc->store().snapshots().stream().filter(s->s.pos.z()==8).map(s->s.observedAt).toList())))throw new AssertionError("Late cancelled Servux response replaced F3 inventory");
        dev.superwy.testutil.ServuxFixture.hold=false;clientCommand("superwy scan protocol servux");
        call("admin/scan/reset-failures","POST","{\"regionId\":\"protocol_fixture\"}");assertStorageScan(waitForScan("complete"));
        dev.superwy.testutil.ServuxFixture.hold=true;final int timeoutTarget=dev.superwy.testutil.ServuxFixture.requests+1;
        call("admin/scan/reset-failures","POST","{\"regionId\":\"protocol_fixture\"}");context.waitFor(mc->dev.superwy.testutil.ServuxFixture.requests>=timeoutTarget,100);
        context.runOnClient(mc->{var transport=servuxTransport();var field=transport.getClass().getDeclaredField("request");field.setAccessible(true);Object current=field.get(transport);var type=current.getClass();var chunk=type.getDeclaredMethod("chunk");var future=type.getDeclaredMethod("future");chunk.setAccessible(true);future.setAccessible(true);var constructor=type.getDeclaredConstructors()[0];constructor.setAccessible(true);field.set(transport,constructor.newInstance(chunk.invoke(current),0L,future.invoke(current)));transport.tick();});
        scan=waitForScan("protocol_error");if(!scan.get("protocolError").getAsString().equals("SERVUX_QUERY_TIMEOUT")||scan.get("remainingChunks").getAsInt()!=1)throw new AssertionError("Timeout lost pending work");
        clientCommand("superwy scan protocol f3");assertStorageScan(waitForScan("complete"));single.getServer().runOnServer(server->dev.superwy.testutil.ServuxFixture.release());dev.superwy.testutil.ServuxFixture.hold=false;
        dev.superwy.testutil.ServuxFixture.metadataVersion=99;context.runOnClient(mc->servuxTransport().reset());
        clientCommand("superwy scan protocol servux");
        call("admin/scan/reset-failures","POST","{\"regionId\":\"protocol_fixture\"}");scan=waitForScan("protocol_error");
        if(!scan.get("protocolError").getAsString().equals("SERVUX_PROTOCOL_UNSUPPORTED")||scan.get("remainingChunks").getAsInt()!=1)throw new AssertionError("Unsupported version was accepted: "+scan);
        clientCommand("superwy scan protocol f3");assertStorageScan(waitForScan("complete"));
        call("admin/regions/protocol_fixture","DELETE",null);
        int previous=dev.superwy.testutil.ServuxFixture.requests;clientCommand("superwy scan protocol servux");
        call("admin/regions/excluded_fixture","PUT","{\"min\":{\"x\":8,\"y\":70,\"z\":8},\"max\":{\"x\":10,\"y\":70,\"z\":8}}");
        call("admin/scan/start","POST","{\"regionId\":\"excluded_fixture\"}");scan=waitForScan("complete");
        if(scan.get("discovered").getAsInt()!=0||scan.get("processed").getAsInt()!=0||dev.superwy.testutil.ServuxFixture.requests!=previous)throw new AssertionError("Excluded-only chunk sent a query");
        call("admin/regions/excluded_fixture","DELETE",null);clientCommand("superwy scan protocol f3");
        verifyServuxDecodeBudget();
        System.out.println("SUPERWY_SCAN_PROTOCOLS_OK: F3 default; storage whitelist; persisted HUD commands; actual Servux 0.10.4 codecs and splitter; one bulk query for four containers; multi-slice NBT; native Litematica coexistence="+FabricLoader.getInstance().isModLoaded("litematica")+"; malformed/unsupported responses preserve history and work; switch mid-request; late reply discarded; F3 resumes; bounded decode backlog released on saturation");
    }
    private void verifyServuxDecodeBudget()throws Exception{
        context.runOnClient(mc->{
            var transport=servuxTransport();transport.reset();
            var field=transport.getClass().getDeclaredField("decoding");field.setAccessible(true);var executor=(java.util.concurrent.ThreadPoolExecutor)field.get(transport);
            var started=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
            executor.execute(()->{started.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            try{
                if(!started.await(2,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Decode worker did not start");
                var future=transport.chunk(new net.minecraft.world.level.ChunkPos(0,0),0,100);
                var receive=transport.getClass().getDeclaredMethod("receive",net.minecraft.client.multiplayer.ClientPacketListener.class,byte[].class);receive.setAccessible(true);
                for(int i=0;i<33;i++)receive.invoke(transport,mc.getConnection(),new byte[]{11,2,0});
                if(!future.isCompletedExceptionally()||!executor.getQueue().isEmpty())throw new AssertionError("Decode saturation retained queued packet arrays");
                try{future.join();throw new AssertionError("Saturated request succeeded");}catch(java.util.concurrent.CompletionException error){if(!dev.superwy.game.Scanner.rootMessage(error).equals("SERVUX_RESPONSE_TOO_LARGE"))throw error;}
            }finally{release.countDown();transport.reset();}
        });
    }
    private dev.superwy.game.ServuxScanner servuxTransport()throws ReflectiveOperationException{
        var client=FabricLoader.getInstance().getEntrypoints("client",net.fabricmc.api.ClientModInitializer.class).stream().filter(e->e instanceof SuperwyClient).findFirst().orElseThrow();
        var field=SuperwyClient.class.getDeclaredField("servux");field.setAccessible(true);return (dev.superwy.game.ServuxScanner)field.get(client);
    }
    private void assertStorageScan(JsonObject scan){if(scan.get("discovered").getAsInt()!=4||scan.get("processed").getAsInt()!=4||scan.get("skippedContainers").getAsInt()!=0)throw new AssertionError("Storage whitelist/protocol mismatch: "+scan);}
    private void verifyPersistentScan(TestSingleplayerContext single)throws Exception{
        single.getServer().runOnServer(server->{for(int x=4;x<8;x++)for(int z=4;z<8;z++)server.overworld().setBlockAndUpdate(new BlockPos(x,70,z),Blocks.BARREL.defaultBlockState());});
        context.waitTicks(10);
        call("admin/regions/resume_fixture","PUT","{\"min\":{\"x\":4,\"y\":70,\"z\":4},\"max\":{\"x\":7,\"y\":70,\"z\":7},\"intervalSeconds\":600}");
        call("admin/scan","POST","{\"regionId\":\"resume_fixture\"}");
        boolean reached=false;
        for(int i=0;i<100;i++){context.waitTicks(1);var scan=call("admin/status","GET",null).getAsJsonObject("scan");if(scan.has("processed")&&scan.get("processed").getAsInt()>=2){reached=true;break;}}
        if(!reached)throw new AssertionError("Resume fixture did not start");
        call("admin/scan/stop","POST","{}");context.waitTicks(6);
        JsonObject stopped=call("admin/status","GET",null);var scan=stopped.getAsJsonObject("scan");
        if(!scan.get("state").getAsString().equals("stopped")||scan.getAsJsonArray("remainingContainers").isEmpty())throw new AssertionError("No durable unfinished work: "+scan);
        context.getInput().lookAt(new BlockPos(5,70,5));context.waitTicks(2);System.out.println("SCAN_OVERLAY_SCREENSHOT "+context.takeScreenshot("scan-yellow-markers"));
        Map<String,Long> acknowledged=new HashMap<>();for(var c:stopped.getAsJsonArray("containers")){var j=c.getAsJsonObject();acknowledged.put(j.get("key").getAsString(),j.get("observedAt").getAsLong());}
        call("admin/scan/enable","POST","{}");
        context.runOnClient(mc->{try{
            var client=FabricLoader.getInstance().getEntrypoints("client",net.fabricmc.api.ClientModInitializer.class).stream().filter(e->e instanceof SuperwyClient).findFirst().orElseThrow();
            var field=SuperwyClient.class.getDeclaredField("scanner");field.setAccessible(true);var scanner=(dev.superwy.game.Scanner)field.get(client);scanner.reset();scanner.request("resume_fixture");
        }catch(Exception e){throw new RuntimeException(e);}});
        JsonObject resumed=null;for(int i=0;i<150;i++){context.waitTicks(2);resumed=call("admin/status","GET",null);if(resumed.getAsJsonObject("scan").get("state").getAsString().equals("complete"))break;}
        if(resumed==null||!resumed.getAsJsonObject("scan").get("state").getAsString().equals("complete"))throw new AssertionError("Resume did not finish: "+resumed);
        if(resumed.getAsJsonObject("scan").get("processed").getAsInt()!=16)throw new AssertionError("Resume skipped or repeated positions");
        for(var c:resumed.getAsJsonArray("containers")){var j=c.getAsJsonObject();Long old=acknowledged.get(j.get("key").getAsString());if(old!=null&&old.longValue()!=j.get("observedAt").getAsLong())throw new AssertionError("Already acknowledged container was rescanned");}
        call("admin/regions/resume_fixture","DELETE",null);System.out.println("SUPERWY_SCAN_RESUME_OK: unfinished positions continued; acknowledged snapshots unchanged");
    }
    private void verifySkipFailures(TestSingleplayerContext single)throws Exception{
        single.getServer().runOnServer(server->{
            server.overworld().setBlockAndUpdate(new BlockPos(8,70,4),Blocks.DECORATED_POT.defaultBlockState());
            server.overworld().setBlockAndUpdate(new BlockPos(9,70,4),Blocks.BARREL.defaultBlockState());
        });context.waitTicks(10);
        call("admin/regions/skip_fixture","PUT","{\"min\":{\"x\":8,\"y\":70,\"z\":4},\"max\":{\"x\":9,\"y\":70,\"z\":4}}");
        call("admin/scan","POST","{\"regionId\":\"skip_fixture\"}");
        JsonObject scan=waitForScan("complete");
        if(scan.get("skippedContainers").getAsInt()!=0||!scan.getAsJsonArray("remainingContainers").isEmpty()||scan.get("processed").getAsInt()!=1||scan.get("discovered").getAsInt()!=1)throw new AssertionError("Non-storage container was scanned: "+scan);
        single.getServer().runOnServer(server->server.overworld().setBlockAndUpdate(new BlockPos(8,70,4),Blocks.BARREL.defaultBlockState()));context.waitTicks(5);
        context.waitTicks(5);scan=waitForScan("complete");
        if(scan.get("processed").getAsInt()!=1)throw new AssertionError("Completed checkpoint was unexpectedly rescanned");
        call("admin/scan/reset-failures","POST","{\"regionId\":\"skip_fixture\"}");context.waitTicks(5);scan=waitForScan("complete");
        if(scan.get("processed").getAsInt()!=2||scan.get("skippedContainers").getAsInt()!=0)throw new AssertionError("Explicit reset failed");
        call("admin/regions/skip_fixture","DELETE",null);
        System.out.println("SUPERWY_STORAGE_FILTER_OK: decorated pot excluded; changing it to a barrel appears after explicit rescan");
    }
    private JsonObject waitForScan(String expected){
        JsonObject scan=null;
        for(int i=0;i<100;i++){context.waitTicks(2);scan=call("admin/status","GET",null).getAsJsonObject("scan");if(scan.get("state").getAsString().equals(expected))return scan;}
        throw new AssertionError("Scan did not reach "+expected+": "+scan);
    }
    private void startBackgroundScan(TestSingleplayerContext single){
        single.getServer().runOnServer(server->{for(int x=4;x<12;x++)for(int z=4;z<12;z++)server.overworld().setBlockAndUpdate(new BlockPos(x,70,z),Blocks.BARREL.defaultBlockState());});context.waitTicks(10);
        call("admin/regions/background","PUT","{\"min\":{\"x\":4,\"y\":70,\"z\":4},\"max\":{\"x\":11,\"y\":70,\"z\":11}}");
        call("admin/scan","POST","{\"regionId\":\"background\"}");
        for(int i=0;i<50;i++){context.waitTicks(1);var scan=call("admin/status","GET",null).getAsJsonObject("scan");if(scan.has("remainingContainers")&&scan.getAsJsonArray("remainingContainers").size()>=32)return;}
        throw new AssertionError("Background scan was not active before ordering");
    }
    private void verifyMissingStockStopsAndSays(TestSingleplayerContext single)throws Exception{
        long diamondBefore=publicAnnouncements.stream().filter(t->t.contains("minecraft:diamond_block 缺货")).count();
        long emeraldBefore=publicAnnouncements.stream().filter(t->t.contains("minecraft:emerald_block 缺货")).count();
        var request=call("orders","POST","{\"warehouseId\":\"main\",\"items\":[{\"itemId\":\"minecraft:stone\",\"count\":1},{\"itemId\":\"minecraft:diamond_block\",\"count\":1},{\"itemId\":\"minecraft:diamond_block\",\"count\":2},{\"itemId\":\"minecraft:emerald_block\",\"count\":1}],\"delivery\":{\"method\":\"carpet_fake_player\",\"fakePlayerName\":\"SW_missing\"},\"options\":{\"allowPartial\":true}}","missing-"+UUID.randomUUID());
        var failed=awaitOrder(request,"failed",false);
        if(!failed.get("errorCode").getAsString().equals("OUT_OF_STOCK")||failed.getAsJsonArray("missingItems").size()!=2)throw new AssertionError("Missing IDs not preserved: "+failed);
        for(var line:failed.getAsJsonArray("lines"))if(line.getAsJsonObject().get("fetched").getAsInt()!=0)throw new AssertionError("Incomplete list started fetching");
        context.waitFor(mc->publicAnnouncements.stream().filter(t->t.contains("minecraft:diamond_block 缺货")).count()>diamondBefore&&publicAnnouncements.stream().filter(t->t.contains("minecraft:emerald_block 缺货")).count()>emeraldBefore,100);
        context.waitTicks(10);
        if(publicAnnouncements.stream().filter(t->t.contains("minecraft:diamond_block 缺货")).count()!=diamondBefore+1||publicAnnouncements.stream().filter(t->t.contains("minecraft:emerald_block 缺货")).count()!=emeraldBefore+1)throw new AssertionError("Stock announcements repeated per duplicate line");
        awaitOrder(submit("SW_missing","[{\"itemId\":\"minecraft:stone\",\"count\":1}]"),"stored_offline",true);verifyLooseReceipt(single,"SW_missing",Map.of(Items.STONE,1));
        System.out.println("SUPERWY_STOCK_FAIL_SAY_OK: missing list immediately fails even with allowPartial; no items fetched; real say names each missing ID once; following order completes");
    }
    private void verifyMultiPlayerQueue(TestSingleplayerContext single)throws Exception{
        String alice="queue-alice-fixture-token-12345678901234567890",bob="queue-bob-fixture-token-12345678901234567890";
        context.runOnClient(mc->{
            var client=FabricLoader.getInstance().getEntrypoints("client",net.fabricmc.api.ClientModInitializer.class).stream().filter(e->e instanceof SuperwyClient).findFirst().orElseThrow();
            var field=SuperwyClient.class.getDeclaredField("config");field.setAccessible(true);var config=(Config)field.get(client);
            var a=new Config.Credential();a.principalId="queue-alice";a.token=alice;a.fakePlayerNames.add("SW_queueA");config.credentials.add(a);
            var b=new Config.Credential();b.principalId="queue-bob";b.token=bob;b.fakePlayerNames.add("SW_queueB");config.credentials.add(b);
        });
        single.getServer().runOnServer(server->{var boxes=(BarrelBlockEntity)server.overworld().getBlockEntity(new BlockPos(2,70,2));for(int i=0;i<5;i++)boxes.setItem(i,new ItemStack(Items.SHULKER_BOX));boxes.setChanged();});
        String a="{\"warehouseId\":\"main\",\"items\":[{\"itemId\":\"minecraft:stone\",\"count\":3}],\"delivery\":{\"method\":\"carpet_fake_player\",\"fakePlayerName\":\"SW_queueA\"}}";
        String b="{\"warehouseId\":\"main\",\"items\":[{\"itemId\":\"minecraft:iron_ingot\",\"count\":4}],\"delivery\":{\"method\":\"carpet_fake_player\",\"fakePlayerName\":\"SW_queueB\"}}";
        var reqA=HttpRequest.newBuilder(URI.create(base+"orders")).header("Authorization","Bearer "+alice).header("Content-Type","application/json").header("Idempotency-Key","queue-a-"+UUID.randomUUID()).POST(HttpRequest.BodyPublishers.ofString(a)).build();
        var reqB=HttpRequest.newBuilder(URI.create(base+"orders")).header("Authorization","Bearer "+bob).header("Content-Type","application/json").header("Idempotency-Key","queue-b-"+UUID.randomUUID()).POST(HttpRequest.BodyPublishers.ofString(b)).build();
        var futureA=http.sendAsync(reqA,HttpResponse.BodyHandlers.ofString());var futureB=http.sendAsync(reqB,HttpResponse.BodyHandlers.ofString());context.waitFor(mc->futureA.isDone()&&futureB.isDone(),400);
        if(futureA.join().statusCode()!=202||futureB.join().statusCode()!=202)throw new AssertionError("Concurrent requests rejected");
        var orderA=JsonParser.parseString(futureA.join().body()).getAsJsonObject();var orderB=JsonParser.parseString(futureB.join().body()).getAsJsonObject();
        String idA=orderA.get("orderId").getAsString(),idB=orderB.get("orderId").getAsString();
        boolean aFirst=orderA.get("queueSequence").getAsLong()<orderB.get("queueSequence").getAsLong();String firstId=aFirst?idA:idB,secondId=aFirst?idB:idA,firstToken=aFirst?alice:bob,secondToken=aFirst?bob:alice;
        var q=callAs(secondToken,"queue?warehouseId=main","GET",null,null);var own=q.getAsJsonArray("orders");if(own.size()!=1||q.toString().contains(firstId))throw new AssertionError("Queue leaked another player's order");
        if(own.get(0).getAsJsonObject().getAsJsonObject("queue").get("position").getAsInt()!=2)throw new AssertionError("Second caller not queued: "+q);
        boolean secondAdvanced=false;JsonObject first=null,second=null;
        for(int i=0;i<400;i++){
            context.waitTicks(5);second=callAs(secondToken,"orders/"+secondId,"GET",null,null);first=callAs(firstToken,"orders/"+firstId,"GET",null,null);
            if(!second.get("state").getAsString().equals("queued")){
                if(!first.get("state").getAsString().equals("stored_offline"))throw new AssertionError("Second order started before first completed: "+first+" / "+second);
                secondAdvanced=true;
            }else if(second.getAsJsonArray("lines").get(0).getAsJsonObject().get("fetched").getAsInt()!=0)throw new AssertionError("Queued order already fetched stock");
            if(first.get("state").getAsString().equals("stored_offline")&&second.get("state").getAsString().equals("stored_offline"))break;
            if(Set.of("failed","recovering","delivery_uncertain").contains(first.get("state").getAsString())||Set.of("failed","recovering","delivery_uncertain").contains(second.get("state").getAsString()))throw new AssertionError("Queue fulfilment failed: "+first+" / "+second);
        }
        if(!secondAdvanced||first==null||second==null||!first.get("state").getAsString().equals("stored_offline")||!second.get("state").getAsString().equals("stored_offline"))throw new AssertionError("Queue did not drain: "+first+" / "+second);
        single.getServer().runCommand("player SW_queueA spawn at 0 70 -3");single.getServer().runCommand("player SW_queueB spawn at 1 70 -3");context.waitTicks(20);
        single.getServer().runOnServer(server->{
            for(String name:List.of("SW_queueA","SW_queueB")){
                var recipient=server.getPlayerList().getPlayerByName(name);if(recipient==null)throw new AssertionError("Cannot inspect recipient");int quantity=0;
                for(int slot=0;slot<36;slot++){var stack=recipient.getInventory().getItem(slot);if(stack.isEmpty())continue;if(!stack.is(name.equals("SW_queueA")?Items.STONE:Items.IRON_INGOT))throw new AssertionError("Orders mixed between players");quantity+=stack.getCount();}
                if(quantity!=(name.equals("SW_queueA")?3:4))throw new AssertionError("Recipient quantity mismatch: "+name+" "+quantity);
            }
        });single.getServer().runCommand("player SW_queueA kill");single.getServer().runCommand("player SW_queueB kill");
        System.out.println("SUPERWY_MULTI_PLAYER_FIFO_OK: two concurrent principals; FIFO fulfilment; isolated queue views; separate correct loose items");
    }
    private void verifyHistoricalMode(TestSingleplayerContext single)throws Exception{
        call("admin/scan","POST","{\"regionId\":\"background\"}");context.waitFor(mc->dev.superwy.game.ControlGate.active(),100);
        context.getInput().pressKey(GLFW.GLFW_KEY_SLASH);context.getInput().typeChars("superwy scan off");context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
        context.waitTicks(10);
        var capabilities=call("capabilities","GET",null);if(capabilities.get("scanEnabled").getAsBoolean()||!capabilities.get("canAcceptOrders").getAsBoolean())throw new AssertionError("Closing scans also disabled orders: "+capabilities);
        var saved=Jsons.GSON.fromJson(Files.readString(FabricLoader.getInstance().getConfigDir().resolve("superwy/config.json")),Config.class);if(saved.scanEnabled)throw new AssertionError("Scan-off setting was not persisted");
        Store store=context.computeOnClient(mc->{var client=FabricLoader.getInstance().getEntrypoints("client",net.fabricmc.api.ClientModInitializer.class).stream().filter(e->e instanceof SuperwyClient).findFirst().orElseThrow();var field=SuperwyClient.class.getDeclaredField("store");field.setAccessible(true);return (Store)field.get(client);});
        long historical=System.currentTimeMillis()-30L*86400000;
        for(var snapshot:store.snapshots()){snapshot.observedAt=historical;store.saveSnapshot(snapshot).join();}
        context.waitTicks(22);boolean available=false;
        for(var value:call("inventory?warehouseId=main","GET",null).getAsJsonArray("items")){var row=value.getAsJsonObject();if(row.get("itemId").getAsString().equals("minecraft:stone")){available=row.get("availableCount").getAsLong()>0;if(!row.get("observedAt").getAsString().equals(java.time.Instant.ofEpochMilli(historical).toString()))throw new AssertionError("Historical timestamp was rewritten");}}
        if(!available)throw new AssertionError("Historical stock expired");
        var order=call("orders","POST","{\"warehouseId\":\"main\",\"items\":[{\"itemId\":\"minecraft:stone\",\"count\":1}],\"delivery\":{\"method\":\"carpet_fake_player\",\"fakePlayerName\":\"SW_history\"}}","history-"+UUID.randomUUID());String id=order.get("orderId").getAsString();
        for(int i=0;i<200;i++){context.waitTicks(5);order=call("orders/"+id,"GET",null);if(Set.of("stored_offline","failed","recovering","delivery_uncertain").contains(order.get("state").getAsString()))break;}
        if(!order.get("state").getAsString().equals("stored_offline"))throw new AssertionError("Historical cache order failed: "+order);
        var missing=call("orders","POST","{\"warehouseId\":\"main\",\"items\":[{\"itemId\":\"minecraft:diamond_block\",\"count\":1}],\"delivery\":{\"method\":\"carpet_fake_player\",\"fakePlayerName\":\"SW_hist_missing\"}}","hist-missing-"+UUID.randomUUID());String missingId=missing.get("orderId").getAsString();context.waitTicks(130);
        if(call("admin/status","GET",null).get("scanEnabled").getAsBoolean())throw new AssertionError("Orders re-enabled scanning");
        context.runOnClient(mc->{var client=FabricLoader.getInstance().getEntrypoints("client",net.fabricmc.api.ClientModInitializer.class).stream().filter(e->e instanceof SuperwyClient).findFirst().orElseThrow();var field=SuperwyClient.class.getDeclaredField("scanner");field.setAccessible(true);if(((dev.superwy.game.Scanner)field.get(client)).busy())throw new AssertionError("Missing stock restarted disabled scanner");});
        var failed=awaitOrder(missing,"failed",false);if(!failed.get("errorCode").getAsString().equals("OUT_OF_STOCK"))throw new AssertionError("Historical shortage did not fail immediately");
        System.out.println("SUPERWY_HISTORY_MODE_OK: scan-off HUD command persisted; 30-day cache remains available and fulfils order; missing stock does not restart scans");
    }
    private JsonObject call(String path,String method,String body){return call(path,method,body,null);}
    private JsonObject call(String path,String method,String body,String key){return callAs(token,path,method,body,key);}
    private JsonObject callAs(String credential,String path,String method,String body,String key){
        var b=HttpRequest.newBuilder(URI.create(base+path)).header("Authorization","Bearer "+credential);
        if(body!=null)b.header("Content-Type","application/json");if(key!=null)b.header("Idempotency-Key",key);
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        var future=http.sendAsync(b.build(),HttpResponse.BodyHandlers.ofString());context.waitFor(mc->future.isDone(),400);
        var response=future.join();if(response.statusCode()>=400)throw new AssertionError("HTTP "+response.statusCode()+" "+path+": "+response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}

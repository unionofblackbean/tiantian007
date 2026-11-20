package dev.superwy;

import com.google.gson.*;
import dev.superwy.core.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CoreTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path configDir;
    private Config.Principal user=new Config.Principal("alice",false,List.of("sf"),List.of("orders:write"));
    private JsonObject request(){return JsonParser.parseString("{\"warehouseId\":\"main\",\"items\":[{\"itemId\":\"minecraft:stone\",\"count\":13}],\"delivery\":{\"method\":\"carpet_fake_player\",\"fakePlayerName\":\"sf\"}}").getAsJsonObject();}
    @Test void requestPreservesDefaults(){var r=Requests.parse(request(),user);assertEquals(13,r.items.getFirst().count);assertEquals("item_id",r.items.getFirst().match.mode);assertEquals("main",r.delivery.stationId);assertEquals(86400,r.options.expiresInSeconds);}
    @Test void unknownFieldsRejected(){var r=request();r.addProperty("command","player somebody kill");assertThrows(IllegalArgumentException.class,()->Requests.parse(r,user));}
    @Test void commandInjectionNameRejected(){var r=request();r.getAsJsonObject("delivery").addProperty("fakePlayerName","sf spawn");assertThrows(IllegalArgumentException.class,()->Requests.parse(r,user));}
    @Test void unauthorizedNameRejected(){var r=request();r.getAsJsonObject("delivery").addProperty("fakePlayerName","bob");assertEquals("FORBIDDEN",assertThrows(IllegalArgumentException.class,()->Requests.parse(r,user)).getMessage());}
    @Test void stringCountsAndFractionsRejected(){for(JsonElement v:List.of(new JsonPrimitive("13"),new JsonPrimitive(1.5),new JsonPrimitive(-1),new JsonPrimitive(0))){var r=request();r.getAsJsonArray("items").get(0).getAsJsonObject().add("count",v);assertThrows(IllegalArgumentException.class,()->Requests.parse(r,user));}}
    @Test void exactMatchRequiresFullHash(){var r=request();r.getAsJsonArray("items").get(0).getAsJsonObject().add("match",JsonParser.parseString("{\"mode\":\"exact_components\",\"componentHash\":\"short\"}"));assertThrows(IllegalArgumentException.class,()->Requests.parse(r,user));}
    @Test void directDeliveryCanSupplyShulkerItem(){var r=request();r.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("itemId","minecraft:red_shulker_box");assertEquals("minecraft:red_shulker_box",Requests.parse(r,user).items.getFirst().itemId);}
    @Test void canonicalHashKeepsArrayOrder(){assertEquals(Jsons.hash(JsonParser.parseString("{\"a\":1,\"b\":2}")),Jsons.hash(JsonParser.parseString("{\"b\":2,\"a\":1}")));assertNotEquals(Jsons.hash(JsonParser.parseString("[1,2]")),Jsons.hash(JsonParser.parseString("[2,1]")));}
    @Test void caseInsensitiveFakeAuthorization(){assertTrue(user.permits("SF"));assertFalse(user.permits("sf2"));}
    @Test void volumeAndNegativeChunkBoundaries(){var r=new Model.Region("r","w","d",new Model.Pos(0,2,0),new Model.Pos(-1,1,-1));assertEquals(8,r.volume());assertEquals(4,r.chunks());assertTrue(r.contains(new Model.Pos(-1,1,0)));assertFalse(r.contains(new Model.Pos(1,1,0)));}
    @Test void largeRegionRejected(){assertThrows(IllegalArgumentException.class,()->new Model.Region("r","w","d",new Model.Pos(0,0,0),new Model.Pos(10000,10000,10000)));}
    @Test void capacityRespectsComponentsStackLimitsAndRecoverySlots(){var slots=List.of(new InventoryIndex.CapacitySlot("a",15,16,true),new InventoryIndex.CapacitySlot("b",1,1,true),new InventoryIndex.CapacitySlot(null,0,64,true),new InventoryIndex.CapacitySlot(null,0,64,true),new InventoryIndex.CapacitySlot(null,0,64,false));assertEquals(17,InventoryIndex.capacity("a",16,slots,1));assertEquals(1,InventoryIndex.capacity("c",1,slots,1));}
    private Model.Snapshot snapshot(){var s=new Model.Snapshot();s.world="w";s.dimension="d";s.pos=new Model.Pos(1,2,3);s.key="k";s.type="minecraft:chest";s.complete=true;s.observedAt=System.currentTimeMillis();return s;}
    private Model.ItemNode node(String id,String hash,int count){var n=new Model.ItemNode();n.itemId=id;n.componentHash=hash;n.count=count;n.maxStackSize=64;n.variantSummary=id;return n;}
    @Test void nestedSourcesKeepOuterAndInnerSlots(){var s=snapshot();var box=node("minecraft:shulker_box","box",1);box.slot=8;var stone=node("minecraft:stone","stone",13);stone.slot=2;box.children.add(stone);s.items.add(box);var source=InventoryIndex.sources(List.of(s),"w","d","minecraft:stone",null).getFirst();assertTrue(source.nested());assertEquals(8,source.outer().slot);assertEquals(2,source.item().slot);assertEquals(13,source.item().count);}
    @Test void worldAndDimensionAreIsolated(){var s=snapshot();s.items.add(node("minecraft:stone","h",64));assertTrue(InventoryIndex.sources(List.of(s),"other","d","minecraft:stone",null).isEmpty());assertTrue(InventoryIndex.aggregate(List.of(s),"w","other").isEmpty());}
    @Test void historicalSnapshotNeverExpiresByAge(){var s=snapshot();s.observedAt-=365L*86400000;s.items.add(node("minecraft:stone","h",64));var r=InventoryIndex.aggregate(List.of(s),"w","d").getFirst();assertEquals(64L,r.get("observedCount"));assertEquals(64L,r.get("availableCount"));assertEquals(false,r.get("stale"));}
    @Test void invalidatedHistoricalSnapshotCannotBeReserved(){var s=snapshot();s.complete=false;s.observedAt-=365L*86400000;s.items.add(node("minecraft:stone","h",64));var r=InventoryIndex.aggregate(List.of(s),"w","d").getFirst();assertEquals(64L,r.get("observedCount"));assertEquals(0L,r.get("availableCount"));assertTrue(InventoryIndex.sources(List.of(s),"w","d","minecraft:stone",null).isEmpty());}
    @Test void processingContainerIsNotAStockSource(){var s=snapshot();s.type="minecraft:furnace";s.items.add(node("minecraft:iron_ingot","h",64));assertTrue(InventoryIndex.sources(List.of(s),"w","d","minecraft:iron_ingot",null).isEmpty());assertTrue(InventoryIndex.aggregate(List.of(s),"w","d").isEmpty());}
    @Test void itemVariantsAreNeverMerged(){var s=snapshot();s.items.add(node("minecraft:diamond_pickaxe","enchanted",1));s.items.add(node("minecraft:diamond_pickaxe","plain",1));assertEquals(2,InventoryIndex.aggregate(List.of(s),"w","d").size());assertThrows(IllegalArgumentException.class,()->InventoryIndex.unambiguousHash(InventoryIndex.sources(List.of(s),"w","d","minecraft:diamond_pickaxe",null)));}
    @Test void defaultConfigKeepsStrongCredentialAcrossRestarts()throws Exception{var file=configDir.resolve("config.json");var first=Config.load(file);assertTrue(first.adminToken.length()>=32);assertFalse(first.automationEnabled);assertEquals(first.adminToken,Config.load(file).adminToken);}
    @Test void duplicateCredentialIsRejectedBeforeStartup()throws Exception{var c=new Config();var alice=new Config.Credential();alice.principalId="alice";alice.token=c.adminToken;c.credentials.add(alice);var file=configDir.resolve("config.json");java.nio.file.Files.writeString(file,Jsons.GSON.toJson(c));assertThrows(IllegalArgumentException.class,()->Config.load(file));}
    @Test void oneDynamicCargoSlotWorksEvenWhenLegacyWorkSlotsAreProtected()throws Exception{var c=new Config();for(int i=10;i<36;i++)c.protectedSlots.add(i);var file=configDir.resolve("config.json");java.nio.file.Files.writeString(file,Jsons.GSON.toJson(c));assertDoesNotThrow(()->Config.load(file));c.protectedSlots.add(9);java.nio.file.Files.writeString(file,Jsons.GSON.toJson(c));assertThrows(IllegalArgumentException.class,()->Config.load(file));}
    @Test void placedShulkerThenNestedBoxOutrankNearbyBarrel(){
        var barrel=snapshot();barrel.items.add(node("minecraft:stone","stone",64));
        var nested=snapshot();nested.pos=new Model.Pos(20,2,3);var box=node("minecraft:shulker_box","box",1);box.children.add(node("minecraft:stone","stone",32));nested.items.add(box);
        var placed=snapshot();placed.type="minecraft:red_shulker_box";placed.pos=new Model.Pos(40,2,3);placed.items.add(node("minecraft:stone","stone",16));
        var ordered=InventoryIndex.sources(List.of(barrel,nested,placed),"w","d","minecraft:stone",null).stream().sorted(InventoryIndex.sourcePriority(new Model.Pos(0,2,3))).toList();
        assertSame(placed,ordered.get(0).snapshot());assertSame(nested,ordered.get(1).snapshot());assertSame(barrel,ordered.get(2).snapshot());
    }
}

package dev.superwy;

import com.google.gson.*;
import dev.superwy.core.*;
import dev.superwy.web.WebServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpTest {
    boolean available=true;List<String> blockers=List.of();
    @TempDir Path dir;Store store;WebServer web;Config config;HttpClient client=HttpClient.newHttpClient();String base;
    static String request="{\"warehouseId\":\"main\",\"items\":[{\"itemId\":\"minecraft:stone\",\"count\":13}],\"delivery\":{\"method\":\"carpet_fake_player\",\"fakePlayerName\":\"sf\"}}";
    @BeforeEach void setup()throws Exception{
        config=new Config();config.port=0;var c=new Config.Credential();c.principalId="alice";c.token="alice-test-token";c.fakePlayerNames.add("sf");config.credentials.add(c);
        var b=new Config.Credential();b.principalId="bob";b.token="bob-test-token";b.fakePlayerNames.add("sf");config.credentials.add(b);store=new Store(dir.resolve("test.db"));
        web=new WebServer(config,store,new WebServer.Backend(){public Map<String,Object> status(Config.Principal p){return Map.of("canAcceptOrders",available,"blockingReasons",blockers);}public List<Map<String,Object>> inventory(){return List.of();}public Map<String,Long> stock(Collection<String> ids){var m=new HashMap<String,Long>();for(String id:ids)m.put(id,id.equals("minecraft:stone")?100L:0L);return m;}public CompletableFuture<Object> admin(String m,String p,JsonObject body){return CompletableFuture.completedFuture(Map.of());}public CompletableFuture<Model.Order> cancel(String id){var order=store.order(id);order.state("cancelled","USER_CANCELLED");return store.saveOrder(order).thenApply(v->store.order(id));}});web.start();base="http://127.0.0.1:"+web.port();
    }
    @AfterEach void cleanup()throws Exception{web.close();store.close();}
    HttpResponse<String> call(String path,String method,String token,String body,String key)throws Exception{var b=HttpRequest.newBuilder(URI.create(base+path));if(token!=null)b.header("Authorization","Bearer "+token);if(key!=null)b.header("Idempotency-Key",key);if(body!=null)b.header("Content-Type","application/json");b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));return client.send(b.build(),HttpResponse.BodyHandlers.ofString());}
    @Test void stockPreCheckMergesLinesAndReportsWhatIsMissing()throws Exception{
        var response=call("/api/v1/stock","POST","alice-test-token","{\"warehouseId\":\"main\",\"items\":[{\"itemId\":\"minecraft:stone\",\"count\":64},{\"itemId\":\"minecraft:stone\",\"count\":64},{\"itemId\":\"minecraft:dirt\",\"count\":5}]}",null);
        assertEquals(200,response.statusCode());var items=JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("items");
        assertEquals(2,items.size());var stone=items.get(0).getAsJsonObject();assertEquals(128,stone.get("requested").getAsLong());assertEquals(100,stone.get("available").getAsLong());assertEquals(28,stone.get("missing").getAsLong());
        assertEquals(5,items.get(1).getAsJsonObject().get("missing").getAsLong());
        assertEquals(400,call("/api/v1/stock","POST","alice-test-token","{\"items\":[]}",null).statusCode());
        assertEquals(401,call("/api/v1/stock","POST",null,"{\"items\":[]}",null).statusCode());
    }
    @Test void authenticationIsMandatory()throws Exception{assertEquals(401,call("/api/v1/inventory?warehouseId=main","GET",null,null,null).statusCode());}
    @Test void occupiedConfiguredPortFallsBackToAtomicEphemeralBinding()throws Exception{
        var second=new Config();second.autoPort=false;second.port=web.port();
        try(var other=new WebServer(second,store,new WebServer.Backend(){
            public Map<String,Object> status(Config.Principal p){return Map.of();}public List<Map<String,Object>> inventory(){return List.of();}public CompletableFuture<Object> admin(String m,String p,JsonObject body){return CompletableFuture.completedFuture(Map.of());}public CompletableFuture<Model.Order> cancel(String id){return CompletableFuture.failedFuture(new IllegalStateException());}
        })){other.start();assertNotEquals(web.port(),other.port());assertEquals(other.port(),second.port);}
    }
    @Test void cancellationReturnsPersistedTerminalStateAndAdvancesQueueImmediately()throws Exception{
        var a=JsonParser.parseString(call("/api/v1/orders","POST","alice-test-token",request,"cancel-head").body()).getAsJsonObject();
        var b=JsonParser.parseString(call("/api/v1/orders","POST","bob-test-token",request,"cancel-next").body()).getAsJsonObject();
        String id=a.get("orderId").getAsString();var response=call("/api/v1/orders/"+id+"/cancel","POST","alice-test-token",null,null);
        assertEquals(200,response.statusCode());assertEquals("cancelled",JsonParser.parseString(response.body()).getAsJsonObject().get("state").getAsString());assertEquals("cancelled",store.order(id).state);
        assertEquals(1,JsonParser.parseString(call("/api/v1/orders/"+b.get("orderId").getAsString(),"GET","bob-test-token",null,null).body()).getAsJsonObject().getAsJsonObject("queue").get("position").getAsInt());
        assertEquals(200,call("/api/v1/orders/"+id+"/cancel","POST","alice-test-token",null,null).statusCode());
    }
    @Test void refusalExplainsReadinessAndDoesNotCreateOrder()throws Exception{available=false;blockers=List.of("AUTOMATION_DISABLED","BARITONE_MISSING");var response=call("/api/v1/orders","POST","alice-test-token",request,"disabled-001");assertEquals(503,response.statusCode());var error=JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("error");assertEquals("AUTOMATION_DISABLED",error.get("code").getAsString());assertTrue(error.get("message").getAsString().contains("/superwy resume"));assertEquals(2,error.getAsJsonObject("details").getAsJsonArray("blockingReasons").size());assertTrue(store.orders().isEmpty());}
    @Test void acceptedRequestCanReplayEvenWhenReadinessChanges()throws Exception{assertEquals(202,call("/api/v1/orders","POST","alice-test-token",request,"replay-001").statusCode());available=false;blockers=List.of("OFFLINE");assertEquals(200,call("/api/v1/orders","POST","alice-test-token",request,"replay-001").statusCode());}
    @Test void createReplayAndConflict()throws Exception{var first=call("/api/v1/orders","POST","alice-test-token",request,"order-0001");assertEquals(202,first.statusCode(),first.body());var body=JsonParser.parseString(first.body()).getAsJsonObject();assertFalse(body.has("principalId"));assertFalse(body.has("request"));assertFalse(body.has("world"));assertEquals("sf",body.getAsJsonObject("delivery").get("fakePlayerName").getAsString());assertEquals(200,call("/api/v1/orders","POST","alice-test-token",request,"order-0001").statusCode());assertEquals(409,call("/api/v1/orders","POST","alice-test-token",request.replace(":13",":14"),"order-0001").statusCode());}
    @Test void otherPrincipalsCannotReadOrCancelOrder()throws Exception{var first=call("/api/v1/orders","POST","alice-test-token",request,"order-0001");String id=JsonParser.parseString(first.body()).getAsJsonObject().get("orderId").getAsString();assertEquals(404,call("/api/v1/orders/"+id,"GET","bob-test-token",null,null).statusCode());assertEquals(404,call("/api/v1/orders/"+id+"/cancel","POST","bob-test-token",null,null).statusCode());}
    @Test void ordinaryCredentialCannotOperateAdmin()throws Exception{var response=call("/api/v1/admin/pause","POST","alice-test-token","{}",null);assertEquals(403,response.statusCode());var error=JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("error");assertEquals("FORBIDDEN",error.get("code").getAsString());assertTrue(error.get("message").getAsString().contains("无权访问"));assertFalse(error.get("message").getAsString().contains("FORBIDDEN"));}
    @Test void frontendCanRequestTenHistorySummariesWithoutLosingLiveOrdersOrLeakingOtherPlayers()throws Exception{
        var req=Requests.parse(JsonParser.parseString(request).getAsJsonObject(),config.authenticate("alice-test-token"));
        for(int i=0;i<12;i++){var o=store.accept("alice","history-"+i,req).order();o.state("failed","OUT_OF_STOCK");store.saveOrder(o).join();}
        var other=store.accept("bob","hidden-history",req).order();other.state("cancelled","USER_CANCELLED");store.saveOrder(other).join();
        var live=store.accept("alice","live-history",req).order();
        var response=call("/api/v1/queue?warehouseId=main&historyLimit=10","GET","alice-test-token",null,null);
        assertEquals(200,response.statusCode());var rows=JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("orders");assertEquals(11,rows.size());assertTrue(response.body().contains(live.orderId));assertFalse(response.body().contains(other.orderId));
        assertEquals(6,JsonParser.parseString(call("/api/v1/queue","GET","alice-test-token",null,null).body()).getAsJsonObject().getAsJsonArray("orders").size());
        assertEquals(400,call("/api/v1/queue?historyLimit=11","GET","alice-test-token",null,null).statusCode());
    }
    @Test void crossOriginControlIsRejected()throws Exception{var r=HttpRequest.newBuilder(URI.create(base+"/api/v1/capabilities")).header("Authorization","Bearer "+config.adminToken).header("Origin","https://attacker.example").build();assertEquals(403,client.send(r,HttpResponse.BodyHandlers.ofString()).statusCode());}
    @Test void uiAssetsAreServedWithoutInlineScriptOrCdn()throws Exception{var r=call("/","GET",null,null,null);assertEquals(200,r.statusCode());assertTrue(r.body().contains("/app.js"));assertFalse(r.body().contains("unpkg.com"));assertTrue(r.headers().firstValue("Content-Security-Policy").orElseThrow().contains("frame-ancestors 'none'"));assertEquals(404,call("/../../config.json","GET",null,null,null).statusCode());}
    @Test void oversizedAndMalformedBodiesAreRejected()throws Exception{assertEquals(413,call("/api/v1/orders","POST","alice-test-token"," ".repeat(262145),"order-0001").statusCode());assertEquals(400,call("/api/v1/orders","POST","alice-test-token","{bad json}","order-0001").statusCode());}
    @Test void allowedNameAndScopeAreEnforced()throws Exception{assertEquals(403,call("/api/v1/orders","POST","alice-test-token",request.replace("\"sf\"","\"other\""),"order-0001").statusCode());config.credentials.getFirst().scopes=List.of("inventory:read");assertEquals(403,call("/api/v1/orders","POST","alice-test-token",request,"order-0001").statusCode());}
    @Test void queueShowsOwnPositionsWithoutOtherPlayersDetails()throws Exception{
        var a=JsonParser.parseString(call("/api/v1/orders","POST","alice-test-token",request,"alice-001").body()).getAsJsonObject();
        var b=JsonParser.parseString(call("/api/v1/orders","POST","bob-test-token",request,"bob-0001").body()).getAsJsonObject();
        assertEquals(1,a.getAsJsonObject("queue").get("position").getAsInt());assertEquals(2,b.getAsJsonObject("queue").get("position").getAsInt());
        var response=call("/api/v1/queue?warehouseId=main","GET","bob-test-token",null,null);var queue=JsonParser.parseString(response.body()).getAsJsonObject();
        assertEquals("fifo",queue.get("policy").getAsString());assertEquals(2,queue.get("totalPending").getAsInt());assertEquals(1,queue.getAsJsonArray("orders").size());assertFalse(response.body().contains(a.get("orderId").getAsString()));assertFalse(response.body().contains("principalId"));
        assertEquals(1,queue.getAsJsonArray("orders").get(0).getAsJsonObject().getAsJsonObject("queue").get("ahead").getAsInt());
    }
    @Test void queueRequiresReadScopeAndAuthentication()throws Exception{assertEquals(401,call("/api/v1/queue","GET",null,null,null).statusCode());config.credentials.getFirst().scopes=List.of("orders:write");assertEquals(403,call("/api/v1/queue","GET","alice-test-token",null,null).statusCode());}
    @Test void queueRetainsOwnOrdersWhenWorkerChangesWorld()throws Exception{var requestObject=Requests.parse(JsonParser.parseString(request).getAsJsonObject(),config.authenticate("alice-test-token"));var order=store.accept("alice","offline-order",requestObject,true,"other-world","other-dimension").order();var response=call("/api/v1/queue","GET","alice-test-token",null,null);assertEquals(200,response.statusCode());assertTrue(response.body().contains(order.orderId));assertEquals(1,JsonParser.parseString(response.body()).getAsJsonObject().get("totalPending").getAsInt());}
    @Test void queuePositionsAdvanceAfterHeadFinishes()throws Exception{var a=store.accept("alice","alice-first",Requests.parse(JsonParser.parseString(request).getAsJsonObject(),config.authenticate("alice-test-token"))).order();var b=store.accept("bob","bob-second",Requests.parse(JsonParser.parseString(request).getAsJsonObject(),config.authenticate("bob-test-token"))).order();a.state("stored_offline",null);store.saveOrder(a).join();var j=JsonParser.parseString(call("/api/v1/orders/"+b.orderId,"GET","bob-test-token",null,null).body()).getAsJsonObject();assertEquals(1,j.getAsJsonObject("queue").get("position").getAsInt());}
}

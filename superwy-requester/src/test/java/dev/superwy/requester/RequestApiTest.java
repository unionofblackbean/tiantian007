package dev.superwy.requester;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class RequestApiTest {
    HttpServer server;RequesterConfig config;
    final List<String> keys=new CopyOnWriteArrayList<>(),bodies=new CopyOnWriteArrayList<>(),paths=new CopyOnWriteArrayList<>(),meshHeaders=new CopyOnWriteArrayList<>();
    final CountDownLatch firstReceived=new CountDownLatch(1),releaseFirst=new CountDownLatch(1);
    final AtomicInteger requests=new AtomicInteger();
    ExecutorService executor;
    volatile int response=202;volatile String payload;volatile boolean holdFirst,dropFirst,holdBody;
    final CountDownLatch bodyStarted=new CountDownLatch(1);
    @BeforeEach void setup()throws Exception{
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        server.createContext("/api/v1/orders",x->{
            int number=requests.incrementAndGet(),status=response;String custom=payload;
            paths.add(x.getRequestURI().getPath());keys.add(x.getRequestHeaders().getFirst("Idempotency-Key"));meshHeaders.add(String.valueOf(x.getRequestHeaders().getFirst("X-Superwy-Mesh")));bodies.add(new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            if(number==1){firstReceived.countDown();if(holdFirst)try{releaseFirst.await();}catch(InterruptedException e){Thread.currentThread().interrupt();x.close();return;}}
            try{
                if(number==1&&dropFirst)return;
                String body=custom!=null?custom:status==202?"{\"orderId\":\"ord_fixture\",\"state\":\"queued\",\"queue\":{\"position\":2}}":"{\"error\":{\"code\":\"CAPABILITY_UNAVAILABLE\"}}";
                byte[] data=body.getBytes(StandardCharsets.UTF_8);x.sendResponseHeaders(status,data.length);
                if(number==1&&holdBody){x.getResponseBody().write(data,0,1);x.getResponseBody().flush();bodyStarted.countDown();try{releaseFirst.await();}catch(InterruptedException e){Thread.currentThread().interrupt();return;}x.getResponseBody().write(data,1,data.length-1);}
                else x.getResponseBody().write(data);
            }finally{x.close();}
        });server.start();config=new RequesterConfig();config.baseUrl="http://127.0.0.1:"+server.getAddress().getPort();config.token="test-fixture-credential-12345678901234567890";config.fakePlayerName="AliceCargo";
    }
    @AfterEach void close(){releaseFirst.countDown();server.stop(0);executor.shutdownNow();}
    @Test void batchIsOneHttpOrderAndMeshModeAddsHeader()throws Exception{
        try(var api=new RequestApi()){
            config.mesh=true;
            payload="{\"orderId\":\"ord_b\",\"state\":\"fetching\",\"meshRobots\":3,\"queue\":{\"ahead\":0},\"lines\":[{\"requested\":100,\"fetched\":64,\"delivered\":0,\"skipped\":false},{\"requested\":5,\"fetched\":0,\"delivered\":0,\"skipped\":true}]}";
            var reply=api.submitBatch(config,List.of(new RequestApi.Line("minecraft:stone",100),new RequestApi.Line("minecraft:dirt",5)),true).get(5,TimeUnit.SECONDS);
            assertTrue(reply.accepted());assertEquals(3,reply.progress().robots());assertEquals(64,reply.progress().fetched());assertEquals(105,reply.progress().requested());assertEquals(1,reply.progress().processed());assertEquals(1,reply.progress().skipped());
            var body=JsonParser.parseString(bodies.getLast()).getAsJsonObject();
            assertEquals(2,body.getAsJsonArray("items").size());assertTrue(body.getAsJsonObject("options").get("batch").getAsBoolean());assertTrue(body.getAsJsonObject("options").get("projectionParcels").getAsBoolean());
            assertEquals(keys.getLast(),body.get("clientRequestId").getAsString());assertEquals("1",meshHeaders.getLast());
            config.mesh=false;payload=null;api.submit(config,"minecraft:stone",1).get(5,TimeUnit.SECONDS);assertEquals("null",meshHeaders.getLast());
        }
        assertThrows(IllegalArgumentException.class,()->RequestApi.batchBody(config,List.of(new RequestApi.Line("minecraft:stone",1_000_001)),false,"k"));
        assertThrows(IllegalArgumentException.class,()->RequestApi.batchBody(config,List.of(),false,"k"));
    }
    @Test void stockPreCheckParsesMissingLinesAndSendsMeshHeader()throws Exception{
        final List<String> seen=new CopyOnWriteArrayList<>();
        server.createContext("/api/v1/stock",x->{seen.add(String.valueOf(x.getRequestHeaders().getFirst("X-Superwy-Mesh"))+"|"+new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] data="{\"items\":[{\"itemId\":\"minecraft:stone\",\"requested\":128,\"available\":100},{\"itemId\":\"minecraft:dirt\",\"requested\":5,\"available\":9}]}".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200,data.length);x.getResponseBody().write(data);x.close();});
        try(var api=new RequestApi()){
            config.mesh=true;var reply=api.stock(config,List.of(new RequestApi.Line("minecraft:stone",128),new RequestApi.Line("minecraft:dirt",5))).get(5,TimeUnit.SECONDS);
            assertTrue(reply.ok());assertEquals(1,reply.missing().size());assertEquals(28,reply.missing().getFirst().missing());assertEquals(9L,reply.available().get("minecraft:dirt"));
            assertTrue(seen.getFirst().startsWith("1|"));assertTrue(seen.getFirst().replace(" ","").contains("\"count\":128"));
        }
    }
    @Test void parsesAheadAndFallsBackToOlderPositionField()throws Exception{
        try(var api=new RequestApi()){
            assertEquals(1,api.submit(config,"minecraft:stone",1).get(5,TimeUnit.SECONDS).ahead());
            payload="{\"orderId\":\"ord_fixture\",\"state\":\"queued\",\"queue\":{\"position\":4,\"ahead\":3}}";
            assertEquals(3,api.submit(config,"minecraft:stone",1).get(5,TimeUnit.SECONDS).ahead());
        }
    }
    @Test void stalledResponseBodyTimesOutAndReleasesRequest()throws Exception{
        holdBody=true;
        try(var api=new RequestApi()){
            var first=api.submit(config,"minecraft:stone",1);
            try{
                assertTrue(bodyStarted.await(5,TimeUnit.SECONDS));
                var error=assertThrows(ExecutionException.class,()->first.get(15,TimeUnit.SECONDS));
                assertInstanceOf(java.net.http.HttpTimeoutException.class,error.getCause());
                assertTrue(api.submit(config,"minecraft:dirt",1).get(5,TimeUnit.SECONDS).accepted());
            }finally{releaseFirst.countDown();}
        }
    }
    @Test void disconnectCancelsPendingNetworkWorkAndAllowsANewSession()throws Exception{
        holdBody=true;
        try(var api=new RequestApi()){
            var first=api.submit(config,"minecraft:stone",1);assertTrue(bodyStarted.await(5,TimeUnit.SECONDS));
            api.cancelPending();assertTrue(first.isCompletedExceptionally());
            assertTrue(api.submit(config,"minecraft:dirt",1).get(5,TimeUnit.SECONDS).accepted());
        }finally{releaseFirst.countDown();}
    }
    @Test void closeDoesNotWaitForAStalledResponseBody()throws Exception{
        holdBody=true;var api=new RequestApi();
        try{
            var first=api.submit(config,"minecraft:stone",1);assertTrue(bodyStarted.await(5,TimeUnit.SECONDS));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),api::close);assertTrue(first.isCompletedExceptionally());
            assertTrue(api.submit(config,"minecraft:dirt",1).isCompletedExceptionally());
        }finally{releaseFirst.countDown();api.close();}
    }
    @Test void materialQuantityAndSpecifiedRecipient()throws Exception{
        try(var api=new RequestApi()){
            assertTrue(api.submit(config,"minecraft:stone",64).get(5,TimeUnit.SECONDS).accepted());
            var j=JsonParser.parseString(bodies.getFirst()).getAsJsonObject();
            assertEquals(64,j.getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt());
            assertEquals("AliceCargo",j.getAsJsonObject("delivery").get("fakePlayerName").getAsString());
            assertEquals(keys.getFirst(),j.get("clientRequestId").getAsString());assertEquals("ord_fixture",api.lastOrderId());
        }
    }
    @Test void everyHttpFailureAllowsNewSubmission()throws Exception{
        try(var api=new RequestApi()){
            for(int status:new int[]{302,400,401,403,404,409,413,422,429,500,503}){
                response=status;assertFalse(api.submit(config,"minecraft:stone",12).get(5,TimeUnit.SECONDS).accepted());
                response=202;assertTrue(api.submit(config,"minecraft:dirt",1).get(5,TimeUnit.SECONDS).accepted());
            }
            assertEquals(22,keys.size());assertEquals(22,new HashSet<>(keys).size());
        }
    }
    @Test void inFlightRequestDoesNotBlockNewSubmission()throws Exception{
        holdFirst=true;response=503;
        try(var api=new RequestApi()){
            var first=api.submit(config,"minecraft:stone",12);
            try{
                assertTrue(firstReceived.await(5,TimeUnit.SECONDS));response=202;
                assertTrue(api.submit(config,"minecraft:dirt",1).get(5,TimeUnit.SECONDS).accepted());
                assertFalse(first.isDone());assertNotEquals(keys.get(0),keys.get(1));
                assertEquals("minecraft:dirt",JsonParser.parseString(bodies.get(1)).getAsJsonObject().getAsJsonArray("items").get(0).getAsJsonObject().get("itemId").getAsString());
            }finally{releaseFirst.countDown();}
            assertFalse(first.get(5,TimeUnit.SECONDS).accepted());assertEquals("ord_fixture",api.lastOrderId());
        }
    }
    @Test void timeoutDoesNotBlockFollowingRequest()throws Exception{
        holdFirst=true;
        try(var api=new RequestApi()){
            var first=api.submit(config,"minecraft:stone",1);
            try{
                var error=assertThrows(ExecutionException.class,()->first.get(15,TimeUnit.SECONDS));
                assertInstanceOf(java.net.http.HttpTimeoutException.class,error.getCause());
                assertTrue(api.submit(config,"minecraft:dirt",1).get(5,TimeUnit.SECONDS).accepted());
            }finally{releaseFirst.countDown();}
        }
    }
    @Test void disconnectDoesNotBlockFollowingRequest()throws Exception{
        dropFirst=true;
        try(var api=new RequestApi()){
            assertThrows(ExecutionException.class,()->api.submit(config,"minecraft:stone",1).get(5,TimeUnit.SECONDS));
            assertTrue(api.submit(config,"minecraft:dirt",1).get(5,TimeUnit.SECONDS).accepted());
        }
    }
    @Test void invalidResponseDoesNotBlockFollowingRequest()throws Exception{
        try(var api=new RequestApi()){
            for(String invalid:List.of("not json","[]","x".repeat(262145))){
                payload=invalid;assertThrows(ExecutionException.class,()->api.submit(config,"minecraft:stone",1).get(5,TimeUnit.SECONDS));
                payload=null;assertTrue(api.submit(config,"minecraft:dirt",1).get(5,TimeUnit.SECONDS).accepted());
            }
        }
    }
    @Test void newSessionSubmitsIndependentlyAfterFailure()throws Exception{
        response=503;try(var api=new RequestApi()){assertFalse(api.submit(config,"minecraft:stone",1).get(5,TimeUnit.SECONDS).accepted());}
        response=202;try(var api=new RequestApi()){assertTrue(api.submit(config,"minecraft:dirt",2).get(5,TimeUnit.SECONDS).accepted());}
        assertNotEquals(keys.get(0),keys.get(1));
    }
    @Test void configurationCanChangeAfterFailure()throws Exception{
        response=503;try(var api=new RequestApi()){
            api.submit(config,"minecraft:stone",1).get(5,TimeUnit.SECONDS);config.token="another-fixture-token-12345678901234567890";
            response=202;assertTrue(api.submit(config,"minecraft:dirt",1).get(5,TimeUnit.SECONDS).accepted());
        }
    }
    @Test void validatesBeforeSending()throws Exception{
        try(var api=new RequestApi()){
            for(int count:new int[]{0,65,-1})assertThrows(IllegalArgumentException.class,()->api.submit(config,"minecraft:stone",count));
            assertThrows(IllegalArgumentException.class,()->api.submit(config,"invalid",1));assertTrue(keys.isEmpty());
        }
    }
    @Test void redirectIsNotFollowed()throws Exception{
        response=302;try(var api=new RequestApi()){assertFalse(api.submit(config,"minecraft:stone",1).get(5,TimeUnit.SECONDS).accepted());assertEquals(1,keys.size());}
    }
    @Test void successRequiresAnOrderId()throws Exception{
        payload="{}";try(var api=new RequestApi()){assertFalse(api.submit(config,"minecraft:stone",1).get(5,TimeUnit.SECONDS).accepted());assertNull(api.lastOrderId());}
    }
    @Test void cancellationUsesOwnOrderEndpoint()throws Exception{
        try(var api=new RequestApi()){assertTrue(api.cancel(config,"ord_fixture").get(5,TimeUnit.SECONDS).accepted());assertEquals("/api/v1/orders/ord_fixture/cancel",paths.getFirst());assertEquals("{}",bodies.getFirst());}
    }
    @Test void statusQueriesServerDirectly()throws Exception{
        try(var api=new RequestApi()){
            assertThrows(IllegalStateException.class,()->api.status(config));
            api.submit(config,"minecraft:stone",1).get(5,TimeUnit.SECONDS);
            assertEquals("queued",api.status(config).get(5,TimeUnit.SECONDS).state());assertEquals("/api/v1/orders/ord_fixture",paths.getLast());
        }
    }
    @Test void invalidOrderIdsCannotChangeRequestPath()throws Exception{
        try(var api=new RequestApi()){assertThrows(IllegalArgumentException.class,()->api.cancel(config,"../admin/pause"));assertThrows(IllegalArgumentException.class,()->api.status(config,"ord_a?x=y"));assertTrue(paths.isEmpty());}
    }
}

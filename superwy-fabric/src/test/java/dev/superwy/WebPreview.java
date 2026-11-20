package dev.superwy;

import com.google.gson.JsonObject;
import dev.superwy.core.*;
import dev.superwy.web.WebServer;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Local UI fixture; explicitly offline and incapable of executing Minecraft operations. */
public final class WebPreview {
    public static void main(String[] args)throws Exception{
        Config config=new Config();config.autoPort=false;config.port=18765;config.adminToken="superwy-ui-preview-local-only-credential";
        Path root=Path.of("build/ui-preview");Store store=new Store(root.resolve("preview.db"));
        Map<String,Object> view=new LinkedHashMap<>();view.put("protocolVersion","1");view.put("minecraftVersion",null);view.put("connectionState","offline");view.put("canAcceptOrders",false);view.put("activeWarehouseId",null);view.put("warehouses",List.of());view.put("features",Map.of("nbtBlockQuery","unknown","quickShulker","unavailable","carpetPlayerCommand","unavailable","recipientInventoryRead","unknown","recipientReceipt","unknown","navigation","unavailable"));view.put("limits",Map.of("maxBodyBytes",262144,"maxLines",128,"maxLineCount",1000000000,"maxOpenOrders",5,"writeRequestsPerMinute",30,"idempotencyRetentionDays",30));view.put("blockingReasons",List.of("UI_PREVIEW_ONLY","OFFLINE"));
        WebServer web=new WebServer(config,store,new WebServer.Backend(){
            public Map<String,Object> status(Config.Principal principal){var data=new LinkedHashMap<>(view);data.put("allowedFakePlayerNames",List.of());return data;}
            public List<Map<String,Object>> inventory(){return List.of();}
            public CompletableFuture<Model.Order> cancel(String id){return CompletableFuture.failedFuture(new IllegalArgumentException("CAPABILITY_UNAVAILABLE"));}
            public CompletableFuture<Object> admin(String method,String path,JsonObject body){
                // Sample super-dispatch data so the dispatch page can be checked without a game.
                if(method.equals("GET")&&path.equals("mesh"))return CompletableFuture.completedFuture(Map.of("enabled",true,"keyAvailable",true,"authorityName","tiantian996","role","authority","connected",true,"authorityUrl","http://frp.example.com:43210","blocks",1,"settings",new dev.superwy.mesh.MeshSettings(),
                    "ownBlocks",List.of(Map.of("chunkId","c0ffee00c0ffee00c0ffee00aa11bb22","orderId","ord_preview-batch-1","localOrders",List.of("ord_local-77aa1c"),"heldOnly",false,"finished",false))));
                if(method.equals("GET")&&path.equals("mesh/overview"))return CompletableFuture.completedFuture(Map.of("authorityName","tiantian996","robots",List.of(
                        new dev.superwy.mesh.MeshScheduler.RobotView("tiantian996",true,true,false,18,"任务块 fetching","overworld 120,64,-30","0.6.0","c0ffee00c0ffee00c0ffee00aa11bb22","ord_preview-batch-1",640,3456,false,800),
                        new dev.superwy.mesh.MeshScheduler.RobotView("WorkerBot2",true,true,false,27,"空闲","overworld 98,64,-12","0.6.0",null,null,0,0,false,400),
                        new dev.superwy.mesh.MeshScheduler.RobotView("WorkerBot3",false,false,false,9,"任务块 transferring","the_end 5,70,9","0.6.0","deadbeefdeadbeefdeadbeef11223344","ord_preview-batch-1",128,3456,false,64_000)),
                    "jobs",List.of(new dev.superwy.mesh.MeshScheduler.JobView("ord_preview-batch-1","projection","active",0,2,12800,768,0,5888,3,0),
                        new dev.superwy.mesh.MeshScheduler.JobView("ord_preview-single-2","simple","queued",0,0,64,0,0,64,0,0),
                        new dev.superwy.mesh.MeshScheduler.JobView("ord_preview-batch-3","batch","queued",1,0,900,0,0,900,0,0)),
                    "locks",List.of(new dev.superwy.mesh.MeshScheduler.LockView("sf","tiantian996"))));
                if(method.equals("GET")&&path.equals("mesh/debug"))return CompletableFuture.completedFuture(Map.of("events",List.of("2026-10-29T12:00:00Z 派给 WorkerBot3：批量 batch-1 的任务块 aa11bb22（3 种）","2026-10-29T12:01:00Z WorkerBot3 上线")));
                // Shortage page fixture: fake stock so the result table can be checked offline.
                if(method.equals("POST")&&path.equals("scan"))return CompletableFuture.completedFuture(Map.of("started",true));
                if(method.equals("POST")&&path.equals("shortage"))return CompletableFuture.completedFuture(ShortageCheck.compute(body,ids->{var m=new HashMap<String,Long>();for(String id:ids)m.put(id,(long)Math.floorMod(id.hashCode(),3000));return m;}));
                if(!method.equals("GET")||!Set.of("status","dashboard").contains(path))return CompletableFuture.failedFuture(new IllegalArgumentException("CAPABILITY_UNAVAILABLE"));var result=new LinkedHashMap<>(view);result.put("world","UI preview: no game connected");result.put("dimension","offline");result.put("scan",Map.of("state","idle"));result.put("scanRegions",List.of(Map.of("regionId","warehouse","state","complete","processed",120,"discovered",120)));result.put("regions",List.of());result.put("stations",List.of());result.put("orders",List.of());result.put("containers",List.of());result.put("selection",Map.of("pos1","—","pos2","—"));return CompletableFuture.completedFuture(result);}
        });web.start();Runtime.getRuntime().addShutdownHook(new Thread(()->{try{web.close();store.close();}catch(Exception ignored){}}));
        System.out.println("UI_PREVIEW_ONLY http://127.0.0.1:"+web.port()+" ; no Minecraft operations are enabled");Thread.currentThread().join();
    }
}

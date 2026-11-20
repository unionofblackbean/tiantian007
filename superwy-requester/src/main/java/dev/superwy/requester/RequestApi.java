package dev.superwy.requester;

import com.google.gson.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Independent asynchronous submissions; all order scheduling belongs to the server. */
public final class RequestApi implements AutoCloseable {
    /** Order progress from the robot: units over all lines, lines processed (fetched or skipped), robots working on it. */
    public record Progress(int requested,int fetched,int delivered,int lines,int processed,int skipped,int robots,List<ShortageReport.Line> items){static final Progress NONE=new Progress(0,0,0,0,0,0,0,List.of());}
    public record Reply(int status,String orderId,String state,String error,int ahead,Progress progress){public boolean accepted(){return status>=200&&status<300&&orderId!=null&&!orderId.isBlank();}}
    public record Line(String itemId,int count){}
    public record StockLine(String itemId,long requested,long available){public long missing(){return Math.max(0,requested-available);}}
    /** Cached warehouse stock from the robot (the dispatcher in mesh mode); lines with the same item are merged. */
    public record StockReply(int status,String error,List<StockLine> items){
        public boolean ok(){return status==200;}
        public List<StockLine> missing(){return items.stream().filter(l->l.missing()>0).toList();}
        public Map<String,Long> available(){var m=new HashMap<String,Long>();for(var l:items)m.put(l.itemId(),l.available());return m;}
    }
    public CompletableFuture<StockReply> stock(RequesterConfig c,List<Line> lines){
        c.validateOrder();
        var items=new ArrayList<Map<String,Object>>();for(var l:lines)items.add(Map.of("itemId",l.itemId(),"count",Math.min(1_000_000,Math.max(1,l.count()))));
        String body=RequesterConfig.GSON.toJson(Map.of("warehouseId",c.warehouseId,"items",items));
        var request=authorized(c,c.endpoint().resolve("stock")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return send(request).thenApply(response->{
            var j=read(response);var out=new ArrayList<StockLine>();
            if(j.has("items")&&j.get("items").isJsonArray())for(var e:j.getAsJsonArray("items")){var o=e.getAsJsonObject();out.add(new StockLine(string(o,"itemId"),o.get("requested").getAsLong(),o.get("available").getAsLong()));}
            return new StockReply(response.statusCode(),error(j),List.copyOf(out));
        });
    }
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final Set<CompletableFuture<?>> pending=new HashSet<>();
    private final ScheduledThreadPoolExecutor deadlines=deadlineExecutor();
    private boolean closed;
    private volatile String lastOrderId;
    public String lastOrderId(){return lastOrderId;}
    public static String body(RequesterConfig c,String item,int count,String key){
        if(!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||count<1||count>64)throw new IllegalArgumentException("物品或数量无效（1–64）");
        return RequesterConfig.GSON.toJson(Map.of("warehouseId",c.warehouseId,"clientRequestId",key,"items",List.of(Map.of("itemId",item,"count",count)),"delivery",Map.of("method","carpet_fake_player","fakePlayerName",c.fakePlayerName,"stationId",c.stationId),"options",Map.of("allowPartial",false,"expiresInSeconds",86400)));
    }
    /** A whole manifest as one order; the robot applies the batch rules (skip missing lines, pack when large). */
    public static String batchBody(RequesterConfig c,List<Line> lines,boolean parcels,String key){
        if(lines.isEmpty()||lines.size()>128)throw new IllegalArgumentException("清单需要 1–128 种材料");
        long total=0;var items=new ArrayList<Map<String,Object>>();
        for(var line:lines){
            if(line.itemId()==null||!line.itemId().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||line.count()<1||line.count()>1_000_000)throw new IllegalArgumentException("清单物品或数量无效");
            total+=line.count();items.add(Map.of("itemId",line.itemId(),"count",line.count()));
        }
        if(total>1_000_000)throw new IllegalArgumentException("清单合计最多 100 万件");
        return RequesterConfig.GSON.toJson(Map.of("warehouseId",c.warehouseId,"clientRequestId",key,"items",items,"delivery",Map.of("method","carpet_fake_player","fakePlayerName",c.fakePlayerName,"stationId",c.stationId),"options",Map.of("allowPartial",true,"batch",true,"projectionParcels",parcels,"expiresInSeconds",86400)));
    }
    public CompletableFuture<Reply> submit(RequesterConfig c,String item,int count){
        c.validateOrder();String key="projection-"+UUID.randomUUID();
        return post(c,key,body(c,item,count,key));
    }
    public CompletableFuture<Reply> submitBatch(RequesterConfig c,List<Line> lines,boolean parcels){
        c.validateOrder();String key="batch-"+UUID.randomUUID();
        return post(c,key,batchBody(c,lines,parcels,key));
    }
    private HttpRequest.Builder authorized(RequesterConfig c,java.net.URI uri){
        var b=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+c.token);
        // Super-dispatch robots refuse orders from mods that have not switched mesh mode on.
        if(c.mesh)b.header("X-Superwy-Mesh","1");
        return b;
    }
    private CompletableFuture<Reply> post(RequesterConfig c,String key,String body){
        HttpRequest request=authorized(c,c.endpoint()).header("Content-Type","application/json").header("Idempotency-Key",key).POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return send(request).thenApply(response->{
            Reply result=reply(response.statusCode(),read(response));
            if(result.accepted())lastOrderId=result.orderId();
            return result;
        });
    }
    private static String error(JsonObject json){var e=json.get("error");return e!=null&&e.isJsonObject()?string(e.getAsJsonObject(),"code"):string(json,"error");}
    private static String string(JsonObject json,String key){var v=json.get(key);return v!=null&&v.isJsonPrimitive()?v.getAsString():null;}
    private static Reply reply(int status,JsonObject j){
        int ahead=0;if(j.has("queue")&&j.get("queue").isJsonObject()){var q=j.getAsJsonObject("queue");if(q.has("ahead"))ahead=q.get("ahead").getAsInt();else if(q.has("position"))ahead=Math.max(0,q.get("position").getAsInt()-1);}
        return new Reply(status,string(j,"orderId"),string(j,"state"),error(j),ahead,progress(j));
    }
    static Progress progress(JsonObject j){
        if(!j.has("lines")||!j.get("lines").isJsonArray())return Progress.NONE;
        int requested=0,fetched=0,delivered=0,lines=0,processed=0,skipped=0;var items=new ArrayList<ShortageReport.Line>();
        for(var e:j.getAsJsonArray("lines")){
            if(!e.isJsonObject())continue;var l=e.getAsJsonObject();lines++;
            int r=integer(l,"requested"),f=integer(l,"fetched"),d=integer(l,"delivered");boolean skip=l.has("skipped")&&l.get("skipped").isJsonPrimitive()&&l.get("skipped").getAsBoolean();
            requested+=r;fetched+=Math.min(r,f);delivered+=d;if(skip)skipped++;if(skip||f>=r)processed++;
            items.add(new ShortageReport.Line(string(l,"itemId"),r,d,string(l,"errorCode")));
        }
        return new Progress(requested,fetched,delivered,lines,processed,skipped,integer(j,"meshRobots"),List.copyOf(items));
    }
    private static int integer(JsonObject j,String key){var v=j.get(key);try{return v!=null&&v.isJsonPrimitive()?v.getAsInt():0;}catch(RuntimeException e){return 0;}
    }
    public CompletableFuture<Reply> status(RequesterConfig c){
        String id=lastOrderId;if(id==null)throw new IllegalStateException("本次会话尚无提交成功的请求；可用 /swrequest status <订单ID> 查询");return status(c,id);
    }
    public CompletableFuture<Reply> status(RequesterConfig c,String id){return orderRequest(c,id,false);}
    public CompletableFuture<Reply> cancel(RequesterConfig c,String id){return orderRequest(c,id,true);}
    private CompletableFuture<Reply> orderRequest(RequesterConfig c,String id,boolean cancel){
        c.validateOrder();if(id==null||!id.matches("ord_[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("订单ID无效");
        var b=authorized(c,c.endpoint().resolve("orders/"+id+(cancel?"/cancel":"")));
        if(cancel)b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));else b.GET();
        return send(b.build()).thenApply(r->reply(r.statusCode(),read(r)));
    }
    private synchronized CompletableFuture<HttpResponse<String>> send(HttpRequest request){
        if(closed)return CompletableFuture.failedFuture(new IllegalStateException("请求客户端已关闭"));
        if(pending.size()>=32)return CompletableFuture.failedFuture(new IllegalStateException("HTTP 请求过多，请稍后再试"));
        var network=http.sendAsync(request,HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8),262144));
        var future=new CompletableFuture<HttpResponse<String>>();pending.add(future);
        // HttpRequest.timeout alone does not cover a server that stalls after sending headers.
        var deadline=deadlines.schedule(()->future.completeExceptionally(new HttpTimeoutException("机器人响应超时")),10,TimeUnit.SECONDS);
        future.whenComplete((v,e)->{deadline.cancel(false);if(!network.isDone())network.cancel(true);synchronized(this){pending.remove(future);}});
        network.whenComplete((v,e)->{if(e!=null)future.completeExceptionally(e);else future.complete(v);});return future;
    }
    private static ScheduledThreadPoolExecutor deadlineExecutor(){var executor=new ScheduledThreadPoolExecutor(1,r->{var thread=new Thread(r,"superwy-request-deadline");thread.setDaemon(true);return thread;});executor.setRemoveOnCancelPolicy(true);return executor;}
    private static JsonObject read(HttpResponse<String> response){return JsonParser.parseString(response.body()).getAsJsonObject();}
    public synchronized void cancelPending(){for(var future:List.copyOf(pending))future.cancel(true);pending.clear();lastOrderId=null;}
    public synchronized void close(){if(closed)return;closed=true;cancelPending();deadlines.shutdownNow();http.shutdownNow();}
}

package dev.superwy.web;

import com.google.gson.*;
import com.google.gson.stream.*;
import com.sun.net.httpserver.*;
import dev.superwy.core.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class WebServer implements AutoCloseable {
    public interface Backend {
        Map<String,Object> status(Config.Principal principal);
        List<Map<String,Object>> inventory();
        CompletableFuture<Object> admin(String method,String path,JsonObject body);
        CompletableFuture<Model.Order> cancel(String id);
        default String worldKey(){return "test-world";}
        default String dimensionKey(){return "minecraft:overworld";}
        /** Super-dispatch node, or null when this backend has none (tests, previews). */
        default dev.superwy.mesh.MeshNode mesh(){return null;}
        /** Cached warehouse stock for the given item IDs (sum over all variants), read on demand. */
        default Map<String,Long> stock(Collection<String> itemIds){throw new IllegalArgumentException("NOT_FOUND");}
    }
    private final Config config;private final Store store;private final Backend backend;private final HttpServer server;
    private final ExecutorService pool=Executors.newFixedThreadPool(4,r->{Thread t=new Thread(r,"superwy-http");t.setDaemon(true);return t;});
    private final Map<String,ArrayDeque<Long>> limits=new HashMap<>();
    public WebServer(Config config,Store store,Backend backend)throws IOException{
        this.config=config;this.store=store;this.backend=backend;
        HttpServer bound;
        try{bound=HttpServer.create(new InetSocketAddress(config.bindAddress,config.autoPort?0:config.port),32);}
        catch(BindException conflict){bound=HttpServer.create(new InetSocketAddress(config.bindAddress,0),32);}
        server=bound;config.port=server.getAddress().getPort();server.setExecutor(pool);server.createContext("/",this::handle);
    }
    public void start(){server.start();}
    public int port(){return server.getAddress().getPort();}
    private void handle(HttpExchange x)throws IOException{
        try{
            String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
            checkOrigin(x);
            x.getResponseHeaders().set("X-Content-Type-Options","nosniff");x.getResponseHeaders().set("Cache-Control","no-store");
            x.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'");
            if(!path.startsWith("/api/")){asset(x,path,method);return;}
            String rawQuery=x.getRequestURI().getRawQuery(),pathAndQuery=x.getRequestURI().getRawPath()+(rawQuery==null?"":"?"+rawQuery);
            byte[] raw=method.equals("GET")||method.equals("DELETE")?new byte[0]:readBody(x);
            Map<String,String> headers=new HashMap<>();for(var h:x.getRequestHeaders().entrySet())if(!h.getValue().isEmpty())headers.put(h.getKey().toLowerCase(Locale.ROOT),h.getValue().getFirst());
            var mesh=backend.mesh();
            if(path.equals("/api/v1/mesh/heartbeat")&&method.equals("POST")){
                if(mesh==null)throw new IllegalArgumentException("NOT_FOUND");
                String robot=mesh.verify(method,pathAndQuery,headers,raw);send(x,200,mesh.heartbeat(robot,raw));return;
            }
            if(path.startsWith("/api/v1/mesh/admin/")){
                if(mesh==null)throw new IllegalArgumentException("NOT_FOUND");
                mesh.verify(method,pathAndQuery,headers,raw);send(x,200,mesh.adminSigned(method,path.substring("/api/v1/mesh/admin/".length()),raw));return;
            }
            boolean forwarded=headers.containsKey("x-mesh-robot");Config.Principal principal;
            if(forwarded){if(mesh==null)throw new IllegalArgumentException("MESH_UNAUTHORIZED");principal=mesh.forwardedPrincipal(method,pathAndQuery,headers,raw);}
            else{String auth=x.getRequestHeaders().getFirst("Authorization");principal=config.authenticate(auth!=null&&auth.startsWith("Bearer ")?auth.substring(7):null);}
            if(principal==null){error(x,401,"UNAUTHENTICATED");return;}
            if(!method.equals("GET")&&!rate(principal.id())){x.getResponseHeaders().set("Retry-After","60");error(x,429,"RATE_LIMITED");return;}
            // Super-dispatch: players must switch their mod to mesh mode; workers relay player requests to the authority.
            if(mesh!=null&&!forwarded&&(path.equals("/api/v1/orders")||path.startsWith("/api/v1/orders/")||path.equals("/api/v1/queue")||path.equals("/api/v1/stock"))){
                if(path.equals("/api/v1/orders")&&method.equals("POST")&&!principal.admin()){String gate=mesh.gate("1".equals(headers.get("x-superwy-mesh")));if(gate!=null)throw new IllegalArgumentException(gate);}
                var relay=mesh.forward(method,pathAndQuery,principal,headers,raw);
                if(relay!=null){sendRaw(x,relay.status(),relay.body());return;}
            }
            if(path.equals("/api/v1/capabilities")&&method.equals("GET")){send(x,200,backend.status(principal));return;}
            if(path.equals("/api/v1/inventory")&&method.equals("GET")){
                if(!principal.scope("inventory:read"))throw new IllegalArgumentException("FORBIDDEN");
                Map<String,String> q=query(x.getRequestURI().getRawQuery());if(!"main".equals(q.get("warehouseId")))throw new IllegalArgumentException("FORBIDDEN");
                var rows=backend.inventory().stream().filter(row->!q.containsKey("itemId")||Objects.equals(row.get("itemId"),q.get("itemId"))).toList();
                String revision=Jsons.hash(Jsons.GSON.toJsonTree(rows));int offset=0;
                if(q.containsKey("cursor")){
                    try{String[] cursor=new String(Base64.getUrlDecoder().decode(q.get("cursor")),StandardCharsets.UTF_8).split(":");if(cursor.length!=2||!cursor[0].equals(revision))throw new IllegalArgumentException();offset=Integer.parseInt(cursor[1]);}catch(Exception e){throw new IllegalArgumentException("CURSOR_EXPIRED");}
                }
                int limit=Integer.parseInt(q.getOrDefault("limit","50"));if(limit<1||limit>200||offset<0||offset>rows.size())throw new IllegalArgumentException("INVALID_REQUEST");
                int end=Math.min(rows.size(),offset+limit);Map<String,Object> page=new LinkedHashMap<>();page.put("warehouseId","main");page.put("indexRevision",Long.parseUnsignedLong(revision.substring(0,12),16));page.put("items",rows.subList(offset,end));page.put("nextCursor",end<rows.size()?Base64.getUrlEncoder().withoutPadding().encodeToString((revision+":"+end).getBytes(StandardCharsets.UTF_8)):null);send(x,200,page);return;
            }
            if(path.equals("/api/v1/stock")&&method.equals("POST")){
                // Pre-check before a batch starts: cached stock only, no game interaction.
                if(!principal.scope("inventory:read"))throw new IllegalArgumentException("FORBIDDEN");
                JsonObject request=body(x,raw);Requests.keys(request,"warehouseId","items");
                if(request.has("warehouseId")&&!"main".equals(Requests.text(request,"warehouseId")))throw new IllegalArgumentException("FORBIDDEN");
                if(!request.has("items")||!request.get("items").isJsonArray())throw new IllegalArgumentException("INVALID_REQUEST");
                var lines=request.getAsJsonArray("items");if(lines.isEmpty()||lines.size()>128)throw new IllegalArgumentException("INVALID_REQUEST");
                Map<String,Long> wanted=new LinkedHashMap<>();
                for(JsonElement value:lines){
                    if(!value.isJsonObject())throw new IllegalArgumentException("INVALID_REQUEST");var line=value.getAsJsonObject();Requests.keys(line,"itemId","count");
                    String id=Requests.text(line,"itemId");if(!id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))throw new IllegalArgumentException("INVALID_REQUEST");
                    wanted.merge(id,(long)Requests.integer(line,"count",1,1_000_000),Long::sum);
                }
                var available=backend.stock(wanted.keySet());var items=new ArrayList<Map<String,Object>>();
                for(var e:wanted.entrySet()){long have=available.getOrDefault(e.getKey(),0L);items.add(Map.of("itemId",e.getKey(),"requested",e.getValue(),"available",have,"missing",Math.max(0,e.getValue()-have)));}
                send(x,200,Map.of("warehouseId","main","cached",true,"items",items));return;
            }
            if(path.equals("/api/v1/queue")&&method.equals("GET")){
                if(!principal.scope("orders:read"))throw new IllegalArgumentException("FORBIDDEN");
                String warehouse=query(x.getRequestURI().getRawQuery()).getOrDefault("warehouseId","main");
                if(!"main".equals(warehouse))throw new IllegalArgumentException("FORBIDDEN");
                int historyLimit=Integer.parseInt(query(x.getRequestURI().getRawQuery()).getOrDefault("historyLimit","5"));if(historyLimit<0||historyLimit>10)throw new IllegalArgumentException("INVALID_REQUEST");
                var all=store.recentOrders(historyLimit,principal.admin()?null:principal.id());
                var inQueue=all.stream().filter(o->!o.terminal()&&o.warehouseId.equals(warehouse)).toList();
                var mine=all.stream().filter(o->o.warehouseId.equals(warehouse)&&(principal.admin()||o.principalId.equals(principal.id()))).toList();
                var visible=new ArrayList<Model.Order>(mine.stream().filter(o->!o.terminal()).toList());
                visible.addAll(mine.stream().filter(Model.Order::terminal).sorted(Comparator.comparing((Model.Order o)->java.time.Instant.parse(o.updatedAt)).thenComparing(OrderQueue.ORDER).reversed()).limit(historyLimit).toList());
                Map<String,Object> reply=new LinkedHashMap<>();reply.put("policy","fifo");reply.put("warehouseId",warehouse);reply.put("totalPending",inQueue.size());reply.put("waitingCount",inQueue.stream().filter(o->o.state.equals("queued")).count());
                reply.put("workerState",backend.status(principal).getOrDefault("connectionState","unknown"));
                reply.put("headState",inQueue.stream().filter(o->Objects.equals(o.world,backend.worldKey())&&Objects.equals(o.dimension,backend.dimensionKey())).findFirst().map(o->o.state).orElse(null));
                reply.put("orders",visible.stream().map(o->OrderQueue.summary(o,all)).toList());send(x,200,reply);return;
            }
            if(path.equals("/api/v1/orders")&&method.equals("POST")){
                if(!principal.scope("orders:write"))throw new IllegalArgumentException("FORBIDDEN");
                String key=x.getRequestHeaders().getFirst("Idempotency-Key");if(key==null||!key.matches("[A-Za-z0-9_.-]{8,128}"))throw new IllegalArgumentException("INVALID_REQUEST");
                Model.Request req=Requests.parse(body(x,raw),principal);
                boolean authority=mesh!=null&&mesh.isAuthority();if(authority)req.meshParent=true;
                Store.Accepted accepted;
                try{accepted=store.accept(principal.id(),key,req,authority||Boolean.TRUE.equals(backend.status(principal).get("canAcceptOrders")),backend.worldKey(),backend.dimensionKey());}
                catch(IllegalArgumentException e){if(!"CAPABILITY_UNAVAILABLE".equals(e.getMessage()))throw e;readinessError(x,backend.status(principal));return;}
                x.getResponseHeaders().set("Location","/api/v1/orders/"+accepted.order().orderId);send(x,accepted.replay()?200:202,publicOrder(accepted.order()));return;
            }
            if(path.startsWith("/api/v1/orders/")&&!path.substring(15).contains("..")){
                String rest=path.substring("/api/v1/orders/".length());boolean cancel=rest.endsWith("/cancel");String id=cancel?rest.substring(0,rest.length()-7):rest;
                Model.Order order=store.order(id);if(order==null||(!principal.admin()&&!order.principalId.equals(principal.id()))){error(x,404,"NOT_FOUND");return;}
                if(method.equals("GET")&&!cancel){if(!principal.scope("orders:read"))throw new IllegalArgumentException("FORBIDDEN");send(x,200,publicOrder(order));return;}
                if(method.equals("POST")&&cancel){
                    if(!principal.scope("orders:write"))throw new IllegalArgumentException("FORBIDDEN");
                    if(order.terminal()&&!order.state.equals("cancelled"))throw new IllegalArgumentException("ORDER_NOT_CANCELLABLE");
                    Model.Order cancelled=backend.cancel(id).get(10,TimeUnit.SECONDS);send(x,200,publicOrder(cancelled));return;
                }
            }
            if(path.startsWith("/api/v1/admin/")){
                if(!principal.admin())throw new IllegalArgumentException("FORBIDDEN");
                JsonObject body=method.equals("GET")||method.equals("DELETE")?new JsonObject():body(x,raw);
                Object result=backend.admin(method,path.substring("/api/v1/admin/".length()),body).get(10,TimeUnit.SECONDS);send(x,200,result);return;
            }
            error(x,404,"NOT_FOUND");
        }catch(Exception exception){
            Throwable e=exception;while(e.getCause()!=null)e=e.getCause();String code=e.getMessage();
            if(code==null)code="INTERNAL_ERROR";
            int status=switch(code){case "FORBIDDEN"->403;case "MESH_UNAUTHORIZED"->401;case "MESH_MODE_REQUIRED","MESH_BUSY","MESH_KEY_MISSING"->409;case "MESH_AUTHORITY_OFFLINE"->503;case "NOT_FOUND"->404;case "IDEMPOTENCY_CONFLICT","CURSOR_EXPIRED","ORDER_NOT_CANCELLABLE","EXECUTOR_BUSY","BATCH_BUSY","SCAN_DISABLED"->409;case "ITEM_NOT_PACKABLE","AMBIGUOUS_ITEM","REQUEST_LIMIT_EXCEEDED"->422;case "QUEUE_FULL","RATE_LIMITED"->429;case "PAYLOAD_TOO_LARGE"->413;case "CAPABILITY_UNAVAILABLE","STORAGE_UNAVAILABLE"->503;default->e instanceof IllegalArgumentException||e instanceof JsonParseException?400:500;};
            if(status==429)x.getResponseHeaders().set("Retry-After","60");
            error(x,status,status==500?"INTERNAL_ERROR":code);
        }finally{x.close();}
    }
    private JsonObject publicOrder(Model.Order order){
        JsonObject result=Jsons.GSON.toJsonTree(order).getAsJsonObject();for(String key:List.of("principalId","world","dimension","request","cancelRequested","cargo"))result.remove(key);
        result.addProperty("errorMessage",ErrorMessages.order(order));
        result.add("queue",Jsons.GSON.toJsonTree(OrderQueue.position(order,store.pendingOrders())));
        // Super-dispatch parent: simple pickups count simple jobs ahead, batches count batches ahead.
        if(order.request!=null&&order.request.meshParent&&order.meshAhead!=null){var q=result.getAsJsonObject("queue");q.addProperty("ahead",order.meshAhead);q.addProperty("position",order.meshAhead+1);}
        if(result.get("clientRequestId").isJsonNull())result.remove("clientRequestId");
        for(String key:List.of("executionStartedAt","executionStoppedAt","terminationReason"))if(result.get(key).isJsonNull())result.remove(key);
        for(JsonElement line:result.getAsJsonArray("lines"))if(line.getAsJsonObject().get("componentHash").isJsonNull())line.getAsJsonObject().remove("componentHash");
        JsonObject delivery=result.getAsJsonObject("delivery");for(String key:List.of("fakePlayerUuid","receiptSource","receivedAt","offlineObservedAt","spawnMode","spawnDimension","spawnPosition"))if(delivery.get(key).isJsonNull())delivery.remove(key);
        for(JsonElement p:result.getAsJsonArray("packages"))p.getAsJsonObject().remove("inventorySlot");
        return result;
    }
    private synchronized boolean rate(String principal){long cutoff=System.currentTimeMillis()-60000;var history=limits.computeIfAbsent(principal,k->new ArrayDeque<>());while(!history.isEmpty()&&history.peek()<cutoff)history.poll();if(history.size()>=30)return false;history.add(System.currentTimeMillis());return true;}
    private void checkOrigin(HttpExchange x){
        String host=x.getRequestHeaders().getFirst("Host");if(host==null)throw new IllegalArgumentException("FORBIDDEN");
        String hostname=URI.create("http://"+host).getHost();
        if(!(hostname.equals("127.0.0.1")||hostname.equals("localhost")||hostname.equals("[::1]")||config.allowedHosts.contains(hostname)))throw new IllegalArgumentException("FORBIDDEN");
        String origin=x.getRequestHeaders().getFirst("Origin");if(origin!=null&&!origin.equals("http://"+host)&&!origin.equals("https://"+host))throw new IllegalArgumentException("FORBIDDEN");
    }
    private static byte[] readBody(HttpExchange x)throws IOException{
        byte[] bytes=x.getRequestBody().readNBytes(262145);if(bytes.length>262144)throw new IllegalArgumentException("PAYLOAD_TOO_LARGE");return bytes;
    }
    private static void sendRaw(HttpExchange x,int status,byte[] bytes)throws IOException{x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.sendResponseHeaders(status,bytes.length==0?-1:bytes.length);if(bytes.length>0)x.getResponseBody().write(bytes);}
    private static JsonObject body(HttpExchange x,byte[] bytes)throws IOException{
        String type=x.getRequestHeaders().getFirst("Content-Type");if(type==null||!type.toLowerCase(Locale.ROOT).startsWith("application/json"))throw new IllegalArgumentException("INVALID_REQUEST");
        JsonReader reader=new JsonReader(new StringReader(new String(bytes,StandardCharsets.UTF_8)));reader.setStrictness(Strictness.STRICT);
        try{JsonElement value=Jsons.GSON.fromJson(reader,JsonElement.class);if(value==null||!value.isJsonObject()||reader.peek()!=JsonToken.END_DOCUMENT)throw new IllegalArgumentException("INVALID_REQUEST");return value.getAsJsonObject();}
        catch(JsonParseException|MalformedJsonException|EOFException e){throw new IllegalArgumentException("INVALID_REQUEST");}
    }
    private static Map<String,String> query(String raw){Map<String,String> result=new HashMap<>();if(raw!=null)for(String pair:raw.split("&")){String[] p=pair.split("=",2);result.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),p.length==2?URLDecoder.decode(p[1],StandardCharsets.UTF_8):"");}return result;}
    private static void send(HttpExchange x,int status,Object value)throws IOException{byte[] bytes=Jsons.GSON.toJson(value).getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");x.sendResponseHeaders(status,bytes.length);x.getResponseBody().write(bytes);}
    private static void error(HttpExchange x,int status,String code)throws IOException{send(x,status,Map.of("error",Map.of("code",code,"message",ErrorMessages.message(code),"requestId","req_"+UUID.randomUUID(),"retryable",status==429||status==503,"details",Map.of())));}
    private static void readinessError(HttpExchange x,Map<String,Object> capabilities)throws IOException{
        var reasons=capabilities.get("blockingReasons") instanceof List<?> values?values.stream().filter(String.class::isInstance).map(String.class::cast).toList():List.<String>of();
        String code=reasons.isEmpty()?"CAPABILITY_UNAVAILABLE":reasons.getFirst();
        String message=switch(code){
            case "AUTOMATION_DISABLED"->"机器人执行器已暂停，请在机器人端执行 /superwy resume 或在 Web 启用执行；关闭扫描不需要暂停执行器";
            case "OFFLINE"->"机器人尚未连接游戏服务器";
            case "QUICKSHULKER_UNAVAILABLE"->"潜影盒打开通道不可用，请检查游戏服务端是否安装并启用对应模组";
            case "BARITONE_MISSING"->"机器人客户端未检测到可用的 Baritone API";
            case "PLAYER_INVENTORY_QUERY_UNVERIFIED"->"实体库存查询尚未通过，请检查 NBT 查询权限，再执行 /superwy resume 重新探测";
            case "NO_REGION"->"当前世界尚未保存仓库区域";
            case "RECONCILIATION_REQUIRED"->"存在需要管理员核对的订单，请先核对恢复状态";
            case "STORAGE_UNAVAILABLE"->"库存数据库不可用，请检查机器人日志";
            default->"机器人暂时不能接单，请查看执行能力";
        };
        send(x,503,Map.of("error",Map.of("code",code,"message",message,"requestId","req_"+UUID.randomUUID(),"retryable",true,"details",Map.of("category","CAPABILITY_UNAVAILABLE","blockingReasons",reasons))));
    }
    private static void asset(HttpExchange x,String path,String method)throws IOException{
        if(!method.equals("GET")||!Set.of("/","/index.html","/app.js","/style.css","/openapi.json","/names.csv").contains(path)){error(x,404,"NOT_FOUND");return;}
        String file=path.equals("/")?"index.html":path.substring(1);
        try(InputStream input=WebServer.class.getResourceAsStream("/web/"+file)){
            if(input==null){error(x,404,"NOT_FOUND");return;}byte[] bytes=input.readAllBytes();
            String content=file.endsWith(".js")?"text/javascript":file.endsWith(".css")?"text/css":file.endsWith(".json")?"application/json":file.endsWith(".csv")?"text/csv":"text/html";
            x.getResponseHeaders().set("Content-Type",content+"; charset=utf-8");x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);
        }
    }
    public void close(){server.stop(0);pool.shutdownNow();}
}

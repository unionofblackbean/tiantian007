package dev.superwy.mesh;

import com.google.gson.*;
import dev.superwy.core.*;
import dev.superwy.game.OrderEngine;
import dev.superwy.network.FrpTunnel;
import dev.superwy.protocol.MeshProtocol;
import dev.superwy.protocol.PrivateMessages;
import dev.superwy.protocol.WhisperProtocol;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/**
 * Super-dispatch on one robot. Every robot is a worker; the robot named {@code mesh.authorityName} is also the
 * authority. Workers find the authority through the server player list and a signed private-message handshake,
 * then report to it over signed HTTP once per second. Game-thread methods are marked; HTTP methods run on web
 * server threads and hop to the game thread through {@code commands}.
 */
public final class MeshNode implements MeshHooks {
    private static final Logger LOG=LoggerFactory.getLogger("SuperWY Mesh");
    public record Forward(int status,byte[] body){}
    private static final class Owned {
        MeshScheduler.Chunk spec;List<String> orders=new ArrayList<>();boolean heldOnly;
        /** Delivered per line by earlier attempts, so finished orders are never read again. */
        int[] previousDelivered;
        /** Final report once the latest attempt has ended; avoids a database read every second. */
        transient MeshScheduler.ChunkReport finished;
    }
    private final Minecraft mc=Minecraft.getInstance();
    private final Config config;private final Store store;private final MeshKey key;private final OrderEngine engine;
    private final Supplier<Boolean> workerReady,paused;private final Supplier<String> world,dimension,activity;private final Supplier<FrpTunnel> frp;
    private final Queue<Runnable> commands;private final Consumer<String> chat;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final SecureRandom random=new SecureRandom();
    private final Map<String,Owned> owned=new LinkedHashMap<>();
    private final Map<String,Long> helloRate=new HashMap<>();
    private MeshAuthority authority;
    private volatile String selfName,authorityUrl;
    private volatile boolean online;
    private volatile Set<String> avoid=Set.of();
    private volatile String using;
    private String lockWanted,pendingNonce,lastError;
    private CompletableFuture<Void> lockFuture;
    private long nextHello,lastReplyAt;
    private int failures;
    private boolean inFlight;
    public MeshNode(Config config,Store store,MeshKey key,OrderEngine engine,Supplier<Boolean> workerReady,Supplier<Boolean> paused,Supplier<String> activity,Supplier<String> world,Supplier<String> dimension,Supplier<FrpTunnel> frp,Queue<Runnable> commands,Consumer<String> chat){
        this.config=config;this.store=store;this.key=key;this.engine=engine;this.workerReady=workerReady;this.paused=paused;this.activity=activity;this.world=world;this.dimension=dimension;this.frp=frp;this.commands=commands;this.chat=chat;
        String saved=store.mesh("worker");
        if(saved!=null)try{
            var list=Jsons.GSON.fromJson(saved,Owned[].class);
            if(list!=null)for(var o:list)if(o!=null&&o.spec!=null&&o.spec.chunkId!=null){if(o.orders==null)o.orders=new ArrayList<>();owned.put(o.spec.chunkId,o);}
        }catch(RuntimeException e){LOG.warn("本机调度任务记录读取失败",e);}
    }
    public void register(){PrivateMessages.register(this::receive);}

    // ---------- role ----------
    public boolean keyAvailable(){return key.available();}
    public boolean enabled(){return config.mesh.enabled&&key.available();}
    public boolean active(){return enabled()&&online&&selfName!=null;}
    public boolean isAuthority(){return enabled()&&selfName!=null&&selfName.equalsIgnoreCase(config.mesh.authorityName);}
    public String authorityName(){return config.mesh.authorityName;}
    /** Game thread. */
    public void join(String name){selfName=name;online=true;pendingNonce=null;nextHello=0;failures=0;inFlight=false;}
    /** Game thread. */
    public void disconnect(){
        online=false;pendingNonce=null;inFlight=false;authorityUrl=null;avoid=Set.of();using=null;
        failLock(new IllegalStateException("EXECUTOR_INTERRUPTED"));
    }
    /** Game thread: switching on needs the private jar; switching off waits for unfinished blocks. */
    public void setEnabled(boolean value){
        if(value&&!key.available())throw new IllegalArgumentException("MESH_KEY_MISSING");
        if(!value&&(!owned.isEmpty()||authority!=null&&authority.busy()))throw new IllegalArgumentException("MESH_BUSY");
        config.mesh.enabled=value;
        if(!value){authorityUrl=null;avoid=Set.of();failLock(null);}
    }

    // ---------- game-thread tick, once per second ----------
    public void tick(){
        if(!active()){if(lockFuture!=null)failLock(null);return;}
        long cutoff=System.currentTimeMillis()-60000;helloRate.values().removeIf(t->t<cutoff);
        retryMissingOrders();
        if(isAuthority()){
            if(authority==null)authority=new MeshAuthority(config,store,chat);
            authority.tick();
            apply(authority.heartbeat(report()));lastReplyAt=System.currentTimeMillis();
            return;
        }
        long now=System.currentTimeMillis();
        if(authorityUrl==null){
            if(now>=nextHello&&authorityOnline()&&mc.getConnection()!=null){
                pendingNonce=HexFormat.of().formatHex(bytes(16));
                String body=MeshProtocol.hello(pendingNonce,now,key.sign("hello",selfName,pendingNonce,Long.toString(now)));
                try{mc.getConnection().sendCommand(WhisperProtocol.command(config.mesh.authorityName,body));}catch(Exception e){lastError="握手私聊发送失败";}
                nextHello=now+10000;
            }
            return;
        }
        if(inFlight)return;
        inFlight=true;String url=authorityUrl;byte[] body=Jsons.GSON.toJson(report()).getBytes(StandardCharsets.UTF_8);
        sendSigned(url,"POST","/api/v1/mesh/heartbeat",null,null,body).whenComplete((response,error)->commands.add(()->{
            inFlight=false;
            if(error==null&&response.statusCode()==200){
                failures=0;lastError=null;lastReplyAt=System.currentTimeMillis();
                try{apply(Jsons.GSON.fromJson(new String(response.body(),StandardCharsets.UTF_8),MeshScheduler.Reply.class));}catch(RuntimeException e){LOG.warn("调度回复无法解析",e);}
            }else{
                failures++;lastError=error!=null?"无法连接调度机器人":"调度机器人拒绝请求 HTTP "+response.statusCode();
                if(failures>=3&&Objects.equals(authorityUrl,url)){authorityUrl=null;nextHello=0;}
            }
        }));
    }
    private byte[] bytes(int n){byte[] b=new byte[n];random.nextBytes(b);return b;}
    private boolean authorityOnline(){var c=mc.getConnection();return c!=null&&c.getOnlinePlayers().stream().anyMatch(p->p.getProfile().name().equalsIgnoreCase(config.mesh.authorityName));}

    // ---------- worker ----------
    private MeshScheduler.Report report(){
        var r=new MeshScheduler.Report();r.robot=selfName;r.ready=Boolean.TRUE.equals(workerReady.get());r.freeSlots=freeSlots();r.using=using;r.lockWanted=lockWanted;
        r.activity=activity.get();r.paused=Boolean.TRUE.equals(paused.get());r.dimension=dimension.get();r.version=VERSION;
        if(mc.player!=null){var p=mc.player.blockPosition();r.x=p.getX();r.y=p.getY();r.z=p.getZ();}
        for(var o:owned.values())r.chunks.add(chunkReport(o));
        return r;
    }
    private int freeSlots(){
        if(mc.player==null)return 0;int n=0;
        for(int i=9;i<36;i++)if(!config.protectedSlots.contains(i)&&mc.player.getInventory().getItem(i).isEmpty())n++;
        return n;
    }
    private MeshScheduler.ChunkReport chunkReport(Owned o){
        if(o.finished!=null)return o.finished;
        var cr=new MeshScheduler.ChunkReport();cr.chunkId=o.spec.chunkId;
        var latest=o.orders.isEmpty()?null:store.order(o.orders.getLast());
        if(latest==null){cr.state="assigned";return cr;}
        for(int i=0;i<o.spec.lines.size();i++){
            var lr=new MeshScheduler.LineReport();int previous=o.previousDelivered!=null&&i<o.previousDelivered.length?o.previousDelivered[i]:0;
            if(i<latest.lines.size()){var line=latest.lines.get(i);lr.delivered=previous+line.delivered;lr.fetched=previous+line.fetched;lr.shortfall=line.shortfall;lr.skipped=line.skipped;lr.errorCode=line.errorCode;}
            else lr.delivered=lr.fetched=previous;
            cr.lines.add(lr);
        }
        cr.state=latest.state;cr.errorCode=latest.errorCode;cr.needsReview=latest.needsItemReview;cr.terminal=latest.terminal();cr.running=!latest.state.equals("queued");
        if(cr.terminal&&latest.state.equals("failed")){
            boolean continuation=o.orders.size()>1;
            boolean cargoChanged=Set.of("ORDER_CARGO_CHANGED","PARCEL_CONTENT_CHANGED").contains(latest.errorCode);
            cr.interrupted=latest.restartInterrupted||"EXECUTOR_INTERRUPTED".equals(latest.errorCode)||continuation&&cargoChanged;
            cr.resumable=cr.interrupted&&!cargoChanged&&!latest.cancelRequested&&!Set.of("transferring","verifying").contains(String.valueOf(latest.interruptedState))&&o.orders.size()<6;
        }
        if(cr.terminal)o.finished=cr;
        return cr;
    }
    private void apply(MeshScheduler.Reply reply){
        if(reply==null)return;boolean changed=false;
        for(String id:reply.forget)changed|=owned.remove(id)!=null;
        for(String id:reply.cancel){var o=owned.get(id);if(o!=null&&!o.orders.isEmpty()){String local=o.orders.getLast();try{engine.cancel(local);}catch(Exception e){LOG.warn("取消调度任务块失败",e);}}}
        for(var resume:reply.resume){
            var o=owned.get(resume.chunkId);if(o==null||o.orders.isEmpty())continue;
            var latest=store.order(o.orders.getLast());if(latest==null||!latest.terminal())continue;
            o.heldOnly=resume.heldOnly;create(o,latest);changed=true;
            LOG.info("继续掉线前的调度任务块：chunk={}, heldOnly={}",resume.chunkId,resume.heldOnly);
        }
        if(reply.assign!=null&&reply.assign.chunkId!=null&&!owned.containsKey(reply.assign.chunkId)){
            var o=new Owned();o.spec=reply.assign;owned.put(o.spec.chunkId,o);create(o,null);changed=true;
        }
        avoid=Set.copyOf(reply.avoid);
        if(reply.lockGranted&&lockFuture!=null&&lockWanted!=null){var f=lockFuture;lockFuture=null;f.complete(null);}
        if(changed)saveOwned();
    }
    private void retryMissingOrders(){for(var o:owned.values())if(o.orders.isEmpty())create(o,null);}
    /** One local order per attempt. A continuation adopts the cargo and parcels still carried from the previous one. */
    private void create(Owned o,Model.Order previous){
        var spec=o.spec;var request=new Model.Request();
        if(previous!=null){
            if(o.previousDelivered==null)o.previousDelivered=new int[spec.lines.size()];
            for(int i=0;i<Math.min(o.previousDelivered.length,previous.lines.size());i++)o.previousDelivered[i]+=previous.lines.get(i).delivered;
            o.finished=null;
        }
        request.warehouseId="main";request.clientRequestId=spec.chunkId;request.meshChunkId=spec.chunkId;
        request.batchId=spec.batch?spec.chunkId:null;request.projectionParcels=spec.parcels;request.forcePacking=spec.packing;
        request.delivery=new Model.DeliveryRequest();request.delivery.fakePlayerName=spec.recipient;request.delivery.stationId=spec.stationId==null?"main":spec.stationId;
        request.options.expiresInSeconds=86400;request.options.batch=spec.batch;request.options.projectionParcels=spec.parcels;
        for(int i=0;i<spec.lines.size();i++){
            var part=spec.lines.get(i);var line=new Model.RequestLine();line.itemId=part.itemId;
            if(part.componentHash!=null){line.match.mode="exact_components";line.match.componentHash=part.componentHash;}
            line.count=previous==null?part.count:o.heldOnly?held(previous,i):Math.max(0,part.count-o.previousDelivered[i]);
            request.items.add(line);
        }
        try{
            var accepted=store.accept("mesh","mesh-"+spec.chunkId+"-"+o.orders.size(),request,true,world.get(),dimension.get(),order->{if(previous!=null)adopt(previous,order);});
            o.orders.add(accepted.order().orderId);
            if(previous!=null){previous.meshResumed=true;previous.needsItemReview=false;store.saveOrder(previous);}
            saveOwned();
        }catch(Exception e){LOG.warn("无法创建调度任务块的本地订单：chunk={}",spec.chunkId,e);}
    }
    private static int held(Model.Order order,int index){
        int n=0;for(var c:order.cargo)if(c.count>0&&c.lineIndex==index)n+=c.count;
        for(var p:order.packages)if("robot".equals(p.custody))for(var l:p.lines)if(l.lineIndex==index)n+=l.count;
        return n;
    }
    static void adopt(Model.Order previous,Model.Order next){
        for(var c:previous.cargo){
            if(c.count<=0||c.lineIndex<0||c.lineIndex>=next.lines.size())continue;
            var copy=new Model.Cargo();copy.inventorySlot=c.inventorySlot;copy.lineIndex=c.lineIndex;copy.count=c.count;copy.itemId=c.itemId;copy.componentHash=c.componentHash;copy.status="held";
            next.cargo.add(copy);next.lines.get(c.lineIndex).fetched+=c.count;
        }
        for(var p:previous.packages){
            if(!"robot".equals(p.custody)||p.lines.isEmpty())continue;
            var copy=new Model.Parcel();copy.inventorySlot=p.inventorySlot;copy.batchId=next.orderId;copy.manifestHash=p.manifestHash;copy.status=p.status;
            for(var l:p.lines){
                if(l.lineIndex<0||l.lineIndex>=next.lines.size())continue;
                copy.lines.add(new Model.PackageLine(l.lineIndex,l.itemId,l.componentHash,l.count));
                next.lines.get(l.lineIndex).fetched+=l.count;next.lines.get(l.lineIndex).packed+=l.count;
            }
            next.packages.add(copy);
        }
        for(var line:next.lines)if(line.fetched>line.requested)line.requested=line.fetched;
    }
    private void saveOwned(){store.saveMesh("worker",Jsons.GSON.toJson(owned.values()));}

    // ---------- engine hooks (game thread) ----------
    public CompletableFuture<Void> acquireRecipient(String baseName){
        if(!active())return CompletableFuture.completedFuture(null);
        failLock(null);lockWanted=baseName.toLowerCase(Locale.ROOT);lockFuture=new CompletableFuture<>();return lockFuture;
    }
    public void releaseRecipient(String baseName){if(lockWanted!=null&&lockWanted.equalsIgnoreCase(baseName)){lockWanted=null;if(lockFuture!=null){var f=lockFuture;lockFuture=null;f.cancel(false);}}}
    public boolean busyElsewhere(String containerKey){return containerKey!=null&&active()&&avoid.contains(containerKey);}
    public void using(String containerKey){using=containerKey;}
    /** cause==null: mesh switched off, let the delivery continue on its own. */
    private void failLock(Throwable cause){
        lockWanted=null;var f=lockFuture;lockFuture=null;
        if(f!=null){if(cause==null)f.complete(null);else f.completeExceptionally(cause);}
    }

    // ---------- private-message handshake (game thread) ----------
    public boolean receive(PrivateMessages.Message message){
        if(!MeshProtocol.is(message.body()))return false;
        commands.add(()->handshake(message));return true;
    }
    private void handshake(PrivateMessages.Message message){
        if(!active()||mc.getConnection()==null)return;
        MeshProtocol.Packet packet;try{packet=MeshProtocol.parse(message.body());}catch(IllegalArgumentException e){return;}
        long now=System.currentTimeMillis();
        if(packet.action().equals("hello")){
            if(!isAuthority())return;
            long time=Long.parseLong(packet.arg());
            if(Math.abs(now-time)>120000||!key.verify(packet.signature(),"hello",message.senderName(),packet.nonce(),packet.arg()))return;
            Long last=helloRate.get(message.senderName().toLowerCase(Locale.ROOT));if(last!=null&&now-last<3000)return;
            helloRate.put(message.senderName().toLowerCase(Locale.ROOT),now);
            String body;
            try{String url=frp.get().readyUrl();body=MeshProtocol.welcome(packet.nonce(),url,key.sign("welcome",selfName,packet.nonce(),url));}
            catch(IllegalArgumentException notReady){body=MeshProtocol.waiting(packet.nonce(),key.sign("wait",selfName,packet.nonce()));}
            try{mc.getConnection().sendCommand(WhisperProtocol.command(message.senderName(),body));}catch(Exception ignored){}
            return;
        }
        if(isAuthority()||!message.senderName().equalsIgnoreCase(config.mesh.authorityName)||!packet.nonce().equals(pendingNonce))return;
        if(packet.action().equals("wait")){
            if(key.verify(packet.signature(),"wait",message.senderName(),packet.nonce())){pendingNonce=null;nextHello=now+10000;lastError="调度机器人的 FRP 尚未就绪";}
            return;
        }
        String url;try{url=MeshProtocol.decodeUrl(packet.arg());}catch(RuntimeException e){return;}
        if(!key.verify(packet.signature(),"welcome",message.senderName(),packet.nonce(),url))return;
        pendingNonce=null;authorityUrl=url;failures=0;lastError=null;
        LOG.info("已连接调度机器人 {}：{}",message.senderName(),url);
    }

    // ---------- signed HTTP ----------
    private static final Set<String> SIGNED_HEADERS=Set.of("x-mesh-robot","x-mesh-time","x-mesh-sig","x-mesh-principal");
    private String signature(String method,String pathAndQuery,String robot,String time,String principal,String idempotency,byte[] body){
        return key.sign("http",method,pathAndQuery,robot,time,principal==null?"-":principal,idempotency==null?"-":idempotency,MeshKey.bodyHash(body));
    }
    private CompletableFuture<HttpResponse<byte[]>> sendSigned(String base,String method,String pathAndQuery,String principal,Map<String,String> extra,byte[] body){
        try{
            String time=Long.toString(System.currentTimeMillis()),idempotency=extra==null?null:extra.get("Idempotency-Key");
            var builder=HttpRequest.newBuilder(URI.create(base+pathAndQuery)).timeout(Duration.ofSeconds(8))
                .header("X-Mesh-Robot",selfName).header("X-Mesh-Time",time).header("X-Mesh-Sig",signature(method,pathAndQuery,selfName,time,principal,idempotency,body));
            if(principal!=null)builder.header("X-Mesh-Principal",principal);
            if(extra!=null)for(var e:extra.entrySet())if(e.getValue()!=null)builder.header(e.getKey(),e.getValue());
            if(method.equals("GET"))builder.GET();else builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofByteArray(body==null?new byte[0]:body));
            // Bounded: a misbehaving peer cannot make this robot buffer an unlimited body.
            return http.sendAsync(builder.build(),HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(),1_048_576));
        }catch(RuntimeException e){return CompletableFuture.failedFuture(e);}
    }
    /** Web thread. Verifies a robot-signed request; returns the robot name. */
    public String verify(String method,String pathAndQuery,Map<String,String> headers,byte[] body){
        if(!enabled())throw new IllegalArgumentException("MESH_UNAUTHORIZED");
        String robot=headers.get("x-mesh-robot"),time=headers.get("x-mesh-time"),sig=headers.get("x-mesh-sig");
        if(!WhisperProtocol.name(robot)||time==null||!time.matches("[0-9]{1,15}")||Math.abs(System.currentTimeMillis()-Long.parseLong(time))>120000)throw new IllegalArgumentException("MESH_UNAUTHORIZED");
        if(!key.verify(sig,"http",method,pathAndQuery,robot,time,headers.getOrDefault("x-mesh-principal","-"),headers.getOrDefault("idempotency-key","-"),MeshKey.bodyHash(body)))throw new IllegalArgumentException("MESH_UNAUTHORIZED");
        return robot;
    }
    /** Web thread, authority only: a player request forwarded by a worker, with the player's scoped identity. */
    public Config.Principal forwardedPrincipal(String method,String pathAndQuery,Map<String,String> headers,byte[] body){
        if(!isAuthority())throw new IllegalArgumentException("MESH_UNAUTHORIZED");
        String robot=verify(method,pathAndQuery,headers,body);String encoded=headers.get("x-mesh-principal");
        if(encoded==null)throw new IllegalArgumentException("MESH_UNAUTHORIZED");
        try{
            JsonObject p=JsonParser.parseString(new String(Base64.getUrlDecoder().decode(encoded),StandardCharsets.UTF_8)).getAsJsonObject();
            String id=p.get("id").getAsString();if(!id.matches("[A-Za-z0-9_.:-]{1,64}"))throw new IllegalArgumentException();
            var names=new ArrayList<String>();for(var n:p.getAsJsonArray("names"))names.add(n.getAsString());
            var scopes=new ArrayList<String>();for(var s:p.getAsJsonArray("scopes"))if(Set.of("inventory:read","orders:read","orders:write").contains(s.getAsString()))scopes.add(s.getAsString());
            return new Config.Principal("m."+robot.toLowerCase(Locale.ROOT)+"."+id,false,List.copyOf(names),List.copyOf(scopes),p.get("recipientSelectable").getAsBoolean());
        }catch(RuntimeException e){throw new IllegalArgumentException("MESH_UNAUTHORIZED");}
    }
    /** Web thread, worker only: relay a player request to the authority. Null when this robot handles it itself. */
    public Forward forward(String method,String pathAndQuery,Config.Principal principal,Map<String,String> headers,byte[] body){
        if(!active()||isAuthority())return null;
        String url=authorityUrl;if(url==null)throw new IllegalArgumentException("MESH_AUTHORITY_OFFLINE");
        var p=new JsonObject();p.addProperty("id",principal.admin()?"admin":principal.id());
        var names=new JsonArray();principal.names().forEach(names::add);p.add("names",names);
        var scopes=new JsonArray();(principal.admin()?List.of("inventory:read","orders:read","orders:write"):principal.scopes()).forEach(scopes::add);p.add("scopes",scopes);
        p.addProperty("recipientSelectable",principal.admin()||principal.recipientSelectable());
        String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(p.toString().getBytes(StandardCharsets.UTF_8));
        var extra=new LinkedHashMap<String,String>();extra.put("Idempotency-Key",headers.get("idempotency-key"));extra.put("X-Superwy-Mesh","1");
        try{
            var response=sendSigned(url,method,pathAndQuery,encoded,extra,body).get(9,TimeUnit.SECONDS);
            return new Forward(response.statusCode(),response.body());
        }catch(Exception e){throw new IllegalArgumentException("MESH_AUTHORITY_OFFLINE");}
    }
    /** Web thread: in mesh mode only an updated player mod (mesh mode on) may place orders. */
    public String gate(boolean meshHeader){return enabled()&&!meshHeader?"MESH_MODE_REQUIRED":null;}
    /** Web thread, authority: one worker heartbeat. */
    public Object heartbeat(String robot,byte[] body){
        if(!isAuthority())throw new IllegalArgumentException("MESH_UNAUTHORIZED");
        var report=Jsons.GSON.fromJson(new String(body,StandardCharsets.UTF_8),MeshScheduler.Report.class);
        if(report==null||report.robot==null||!report.robot.equalsIgnoreCase(robot))throw new IllegalArgumentException("MESH_UNAUTHORIZED");
        if(report.chunks==null)report.chunks=new ArrayList<>();
        var future=new CompletableFuture<MeshScheduler.Reply>();
        commands.add(()->{try{if(authority==null)authority=new MeshAuthority(config,store,chat);future.complete(authority.heartbeat(report));}catch(Exception e){future.completeExceptionally(e);}});
        try{return future.get(8,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalArgumentException("MESH_AUTHORITY_OFFLINE");}
    }
    /** Game thread: a player cancelled an order; the authority stops its blocks. */
    public void cancelled(String orderId){if(authority!=null)authority.cancelled(orderId);}

    // ---------- dispatch page (game thread unless noted) ----------
    public static final String VERSION=net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("superwy").map(m->m.getMetadata().getVersion().getFriendlyString()).orElse("?");
    /** Dispatch management. On a worker everything except local settings is forwarded to the authority. */
    public CompletableFuture<Object> admin(String method,String path,JsonObject body){
        if(!enabled())return CompletableFuture.failedFuture(new IllegalArgumentException("MESH_AUTHORITY_OFFLINE"));
        if(isAuthority()){try{return CompletableFuture.completedFuture(adminLocal(method,path,body));}catch(Exception e){return CompletableFuture.failedFuture(e);}}
        String url=authorityUrl;if(url==null||!active())return CompletableFuture.failedFuture(new IllegalArgumentException("MESH_AUTHORITY_OFFLINE"));
        byte[] raw=body==null||method.equals("GET")?new byte[0]:body.toString().getBytes(StandardCharsets.UTF_8);
        return sendSigned(url,method,"/api/v1/mesh/admin/"+path,null,null,raw).handle((response,error)->{
            if(error!=null)throw new IllegalArgumentException("MESH_AUTHORITY_OFFLINE");
            JsonElement json;try{json=JsonParser.parseString(new String(response.body(),StandardCharsets.UTF_8));}catch(RuntimeException e){throw new IllegalArgumentException("MESH_AUTHORITY_OFFLINE");}
            if(response.statusCode()!=200){
                String code=json.isJsonObject()&&json.getAsJsonObject().has("error")?json.getAsJsonObject().getAsJsonObject("error").get("code").getAsString():"MESH_AUTHORITY_OFFLINE";
                throw new IllegalArgumentException(code);
            }
            return json;
        });
    }
    /** Web thread, authority: a signed management call forwarded by a worker's page. */
    public Object adminSigned(String method,String path,byte[] body){
        if(!isAuthority())throw new IllegalArgumentException("MESH_UNAUTHORIZED");
        JsonObject json=body.length==0?new JsonObject():JsonParser.parseString(new String(body,StandardCharsets.UTF_8)).getAsJsonObject();
        var future=new CompletableFuture<Object>();
        commands.add(()->{try{future.complete(adminLocal(method,path,json));}catch(Exception e){future.completeExceptionally(e);}});
        try{return future.get(8,TimeUnit.SECONDS);}
        catch(ExecutionException e){throw e.getCause() instanceof RuntimeException r?r:new IllegalArgumentException("MESH_AUTHORITY_OFFLINE");}
        catch(Exception e){throw new IllegalArgumentException("MESH_AUTHORITY_OFFLINE");}
    }
    private Object adminLocal(String method,String path,JsonObject body){
        if(authority==null)authority=new MeshAuthority(config,store,chat);
        var s=authority.scheduler();
        if(method.equals("GET")&&path.equals("overview")){
            var view=new LinkedHashMap<String,Object>();
            view.put("authorityName",config.mesh.authorityName);view.put("authorityUrl",safeUrl());view.put("settings",config.mesh);
            view.put("robots",s.robots());view.put("jobs",s.jobViews());view.put("locks",s.locks());view.put("eventCount",s.events().size());
            return view;
        }
        if(method.equals("GET")&&path.equals("debug"))return Map.of("events",s.events(),"locks",s.locks(),"state",s.state());
        if(method.equals("POST")&&path.equals("locks/clear")){s.clearLocks();return Map.of("cleared",true);}
        String[] parts=path.split("/");
        if(method.equals("POST")&&parts.length==3){
            String id=parts[1];if(!id.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("INVALID_REQUEST");
            switch(parts[0]+"/"+parts[2]){
                case "jobs/cancel"->{if(!s.has(id))throw new IllegalArgumentException("NOT_FOUND");authority.cancelled(id);engine.cancel(id);return Map.of("cancelled",id);}
                case "jobs/top"->{if(!s.prioritize(id))throw new IllegalArgumentException("NOT_FOUND");return Map.of("prioritized",id);}
                case "robots/forget"->{if(!s.forgetRobot(id))throw new IllegalArgumentException("MESH_BUSY");return Map.of("forgotten",id);}
                case "chunks/reassign"->{if(!s.reassignNow(id))throw new IllegalArgumentException("MESH_BUSY");return Map.of("reassigned",id);}
                default->{}
            }
        }
        throw new IllegalArgumentException("NOT_FOUND");
    }
    private String safeUrl(){try{return frp.get().readyUrl();}catch(RuntimeException e){return null;}}
    /** Game thread: this robot's own dispatch settings. */
    public void updateSettings(JsonObject body){
        var next=new MeshSettings();next.enabled=config.mesh.enabled;
        next.authorityName=Requests.text(body,"authorityName");
        next.offlineAfterSeconds=Requests.integer(body,"offlineAfterSeconds",5,120);next.reassignAfterSeconds=Requests.integer(body,"reassignAfterSeconds",30,3600);
        next.chunkSlots=Requests.integer(body,"chunkSlots",1,25);next.parcelChunkBoxes=Requests.integer(body,"parcelChunkBoxes",1,25);next.validate();
        boolean renamed=!next.authorityName.equalsIgnoreCase(config.mesh.authorityName);
        if(renamed&&(!owned.isEmpty()||authority!=null&&authority.busy()))throw new IllegalArgumentException("MESH_BUSY");
        config.mesh.authorityName=next.authorityName;config.mesh.offlineAfterSeconds=next.offlineAfterSeconds;config.mesh.reassignAfterSeconds=next.reassignAfterSeconds;
        config.mesh.chunkSlots=next.chunkSlots;config.mesh.parcelChunkBoxes=next.parcelChunkBoxes;
        if(renamed){authorityUrl=null;pendingNonce=null;nextHello=0;if(!isAuthority())authority=null;}
    }

    // ---------- status (game thread) ----------
    public Map<String,Object> status(){
        var view=new LinkedHashMap<String,Object>();
        view.put("enabled",config.mesh.enabled);view.put("keyAvailable",key.available());view.put("authorityName",config.mesh.authorityName);
        view.put("role",!enabled()?"off":isAuthority()?"authority":"worker");
        view.put("connected",isAuthority()||authorityUrl!=null&&System.currentTimeMillis()-lastReplyAt<10000);
        view.put("authorityUrl",isAuthority()?safeUrl():authorityUrl);view.put("error",lastError);view.put("blocks",owned.size());view.put("settings",config.mesh);
        view.put("ownBlocks",owned.values().stream().map(o->Map.of("chunkId",o.spec.chunkId,"orderId",o.spec.orderId,"localOrders",List.copyOf(o.orders),"heldOnly",o.heldOnly,"finished",o.finished!=null)).toList());
        if(authority!=null&&isAuthority()){
            var s=authority.scheduler();
            view.put("robots",s.robots());
            view.put("jobs",s.jobs().stream().map(j->Map.of("orderId",j.orderId,"kind",j.batch?"batch":"simple","state",j.state,"ahead",s.ahead(j),"robots",s.robotsOn(j),"remaining",s.remaining(j))).toList());
        }
        return view;
    }
}

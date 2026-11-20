package dev.superwy;

import com.google.gson.*;
import com.mojang.brigadier.arguments.*;
import dev.superwy.core.*;
import dev.superwy.game.*;
import dev.superwy.web.WebServer;
import dev.superwy.protocol.WhisperProtocol;
import dev.superwy.protocol.ConnectionCode;
import dev.superwy.network.FrpTunnel;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import org.lwjgl.glfw.GLFW;
import org.slf4j.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.*;

public final class SuperwyClient implements ClientModInitializer,WebServer.Backend {
    private static final Logger LOG=LoggerFactory.getLogger("SuperWY");
    private final Minecraft mc=Minecraft.getInstance();
    private Config config;private Store store;private Queries queries;private Actions actions;private dev.superwy.game.Scanner scanner;private OrderEngine engine;private WebServer web;private BatchInbox batch;private QueueNotifications queueNotifications;
    private FrpTunnel frp;
    private ServuxScanner servux;
    private dev.superwy.mesh.MeshNode mesh;
    private volatile boolean workerReady;
    private Path configPath;private volatile boolean online,paused;private volatile String world="offline",dimension="offline";
    private volatile Map<String,Object> view=Map.of();
    private volatile List<Model.Region> inventoryRegions=List.of();
    private volatile Model.Order inventoryOrder;
    private final Queue<Runnable> commands=new ConcurrentLinkedQueue<>();
    private BlockPos pos1,pos2;private int ticks;private boolean playerNbtAvailable;
    private KeyMapping selectKey;
    public void onInitializeClient(){
        try{
            QuickShulkerBridge.register();
            ServuxScanner.register();servux=new ServuxScanner();
            Path dir=FabricLoader.getInstance().getConfigDir().resolve("superwy");configPath=dir.resolve("config.json");config=Config.load(configPath);
            paused=config.manualControl;
            store=new Store(dir.resolve("warehouse.db"));ScanSchedule.upgrade(config,store);config.save(configPath);queries=new Queries();actions=new Actions(queries,()->online&&!paused);
            boolean smoke=FabricLoader.getInstance().isDevelopmentEnvironment()&&Boolean.getBoolean("superwy.smokeTest");
            boolean gameTest=FabricLoader.getInstance().isDevelopmentEnvironment()&&Boolean.getBoolean("superwy.gameTest");
            if(smoke||gameTest)config.port=smoke?Integer.getInteger("superwy.smokePort",0):0;
            scanner=new dev.superwy.game.Scanner(store,queries,actions,()->world,()->dimension,()->config.scanEnabled,()->config.scanProtocol,servux);engine=new OrderEngine(store,config,queries,actions,scanner,()->world,()->dimension);
            ScanOverlay.register(scanner);
            ControlGate.register(()->online&&mc.level!=null&&(engine.busy()||scanner.busy()||!scanner.safe()||actions.navigation.active()),this::stopForManual,()->setScanEnabled(false),this::tell);
            web=new WebServer(config,store,this);web.start();
            if(!smoke&&!gameTest)config.save(configPath);
            frp=new FrpTunnel(config.frp,web.port(),dir,()->config.save(configPath));
            if(!smoke&&!gameTest&&config.frp.enabled)frp.start();
            new WhisperInbox(config,store,()->{updateView();return Boolean.TRUE.equals(view.get("canAcceptOrders"));},()->world,()->dimension,commands,(recipient,body)->{
                if(mc.getConnection()!=null)mc.getConnection().sendCommand(WhisperProtocol.command(recipient,body));
            }).register();
            mesh=new dev.superwy.mesh.MeshNode(config,store,dev.superwy.mesh.MeshKey.fromPrivatePreset(),engine,()->workerReady,()->paused,this::activity,()->world,()->dimension,()->frp,commands,this::publicChat);
            mesh.register();engine.hooks(mesh);
            batch=new BatchInbox(config,store,queries,actions,()->{updateView();return Boolean.TRUE.equals(view.get("canAcceptOrders"));},()->!engine.busy()&&scanner.safe(),()->world,()->dimension,commands);batch.register();
            queueNotifications=new QueueNotifications(store,(recipient,body)->{if(mc.getConnection()!=null)mc.getConnection().sendCommand(WhisperProtocol.command(recipient,body));});
            var category=KeyMapping.Category.register(Identifier.fromNamespaceAndPath("superwy","warehouse"));
            selectKey=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.superwy.select",GLFW.GLFW_KEY_UNKNOWN,category));
            ClientTickEvents.END_CLIENT_TICK.register(client->tick());
            ClientTickEvents.START_CLIENT_TICK.register(client->ControlGate.update());
            ClientPlayConnectionEvents.JOIN.register((handler,sender,client)->join());
            ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->disconnect());
            ClientLifecycleEvents.CLIENT_STOPPING.register(client->{try{scanner.close();servux.close();frp.close();web.close();store.close();}catch(Exception e){LOG.error("Shutdown failed",e);}});
            ClientCommandRegistrationCallback.EVENT.register((dispatcher,registry)->{
                dispatcher.register(literal("superwy")
                    .executes(c->{tell("Web: http://127.0.0.1:"+config.port+" | pos1 / pos2 / region <名称> / scan start|stop <区域> / scan protocol f3|servux / token / stop / resume");return 1;})
                    .then(literal("pos1").executes(c->{pos1=target();pos2=null;tell("第一点: "+pos1.toShortString());return 1;}))
                    .then(literal("pos2").executes(c->{pos2=target();tell("第二点: "+pos2.toShortString());return 1;}))
                    .then(literal("region").then(argument("name",StringArgumentType.word()).executes(c->{saveSelection(StringArgumentType.getString(c,"name"));return 1;})))
                    .then(literal("station").then(argument("kind",StringArgumentType.word()).suggests((c,b)->{for(String s:List.of("box","return","delivery","recovery"))b.suggest(s);return b.buildFuture();}).executes(c->{stationHere(StringArgumentType.getString(c,"kind"));return 1;})))
                    .then(literal("scan").executes(c->{if(!config.scanEnabled){tell("扫描已关闭，正在使用历史库存；/superwy scan on 开启扫描");return 0;}startScans(null);tell("已启动当前世界未停止的区域扫描，各区域轮流推进");return 1;})
                        .then(literal("protocol").executes(c->{tell("扫描协议："+(config.scanProtocol.equals("servux")?"Servux（实验性）":"F3（默认）")+"；/superwy scan protocol f3 或 servux 切换");return 1;})
                            .then(literal("f3").executes(c->{setScanProtocol("f3");return 1;}))
                            .then(literal("servux").executes(c->{setScanProtocol("servux");return 1;})))
                        .then(literal("start").then(argument("region",StringArgumentType.word()).suggests((c,b)->{for(var r:store.regions())if(r.world.equals(world)&&r.dimension.equals(dimension))b.suggest(r.id);return b.buildFuture();}).executes(c->{
                            String id=StringArgumentType.getString(c,"region");scanner.startRegion(id).whenComplete((v,e)->commands.add(()->tell(e==null?"已启动区域扫描："+id:ErrorMessages.message(dev.superwy.game.Scanner.rootMessage(e)))));return 1;
                        })))
                        .then(literal("stop").then(argument("region",StringArgumentType.word()).suggests((c,b)->{for(var r:store.regions())if(r.world.equals(world)&&r.dimension.equals(dimension))b.suggest(r.id);return b.buildFuture();}).executes(c->{
                            String id=StringArgumentType.getString(c,"region");scanner.stopRegion(id).whenComplete((v,e)->commands.add(()->tell(e==null?"已停止区域扫描并保存断点："+id:ErrorMessages.message(dev.superwy.game.Scanner.rootMessage(e)))));return 1;
                        })))
                        .then(literal("off").executes(c->{setScanEnabled(false);tell("已关闭扫描并保存设置，继续使用历史库存取货");return 1;}))
                        .then(literal("on").executes(c->{setScanEnabled(true);tell("已开启扫描，按持久时间戳安排更新，默认每小时一次");return 1;})))
                    .then(literal("unscanned").executes(c->{showUnscanned(1);return 1;}).then(argument("page",IntegerArgumentType.integer(1)).executes(c->{showUnscanned(IntegerArgumentType.getInteger(c,"page"));return 1;})))
                    .then(literal("failed").executes(c->{showFailures(1);return 1;}).then(argument("page",IntegerArgumentType.integer(1)).executes(c->{showFailures(IntegerArgumentType.getInteger(c,"page"));return 1;})))
                    .then(literal("retry-failed").then(argument("region",StringArgumentType.word()).executes(c->{
                        if(engine.busy())throw new IllegalArgumentException("EXECUTOR_BUSY");
                        String id=StringArgumentType.getString(c,"region");scanner.retryFailed(id).whenComplete((v,e)->commands.add(()->tell(e==null?"已清除失败记忆并重新扫描区域 "+id:ErrorMessages.message(dev.superwy.game.Scanner.rootMessage(e)))));return 1;
                    })))
                    .then(literal("token").executes(c->{mc.keyboardHandler.setClipboard(config.adminToken);tell("管理令牌已复制到剪贴板，请粘贴到 Web 登录框。");return 1;}))
                    .then(literal("web").executes(c->{showWebLink();return 1;}))
                    .then(literal("frp").executes(c->{var status=frp.status();String label=switch((String)status.get("state")){case "ready"->"已连接";case "starting"->"正在启动";case "reconnecting"->"正在重连";case "failed"->"连接失败";default->"已停止";};tell("FRP："+label+"；本机 Web http://127.0.0.1:"+web.port()+"；公网："+Objects.toString(status.get("publicUrl"),"尚未就绪")+(status.get("errorMessage")==null?"":"；"+status.get("errorMessage")));return 1;})
                        .then(literal("start").executes(c->{try{startFrp();tell("FRP 正在连接，/superwy frp 查看状态");return 1;}catch(Exception e){tell(Objects.toString(e.getMessage(),"FRP 启动失败"));return 0;}}))
                        .then(literal("stop").executes(c->{frp.stop();config.frp.enabled=false;saveConfig();tell("FRP 已停止，本机 Web 与取货任务继续运行");return 1;})))
                    .then(literal("connect").executes(c->{
                        try{var result=connectionCommand();mc.keyboardHandler.setClipboard((String)result.get("command"));tell("玩家连接指令已复制；玩家执行后在弹窗填写收货假人名，默认 sf");return 1;}
                        catch(Exception e){tell(e instanceof IllegalArgumentException&&"FRP_NOT_READY".equals(e.getMessage())?ErrorMessages.message("FRP_NOT_READY"):Objects.toString(e.getMessage(),"生成连接指令失败"));return 0;}
                    }))
                    .then(literal("whisper").executes(c->{tell("私聊接单："+(config.whisperEnabled?"开启":"关闭")+"；短 key 验证："+(config.whisperKey.isEmpty()?"关闭（默认无需验证）":"已开启")+"。/superwy whisper on|off；/superwy whisper key <5位字母数字|off>");return 1;})
                        .then(literal("on").executes(c->{config.whisperEnabled=true;saveConfig();tell("已开启私聊接单并保存");return 1;}))
                        .then(literal("off").executes(c->{config.whisperEnabled=false;saveConfig();tell("已关闭私聊接单并保存，HTTP 不受影响");return 1;}))
                        .then(literal("key").then(argument("key",StringArgumentType.word()).executes(c->{
                            String key=StringArgumentType.getString(c,"key");if(key.equalsIgnoreCase("off"))key="";
                            if(!WhisperProtocol.key(key)){tell("短 key 必须为 5 位字母数字，off 关闭验证");return 0;}
                            config.whisperKey=key;saveConfig();tell(key.isEmpty()?"已关闭私聊短 key 验证并保存":"已开启 5 位短 key 验证并保存");return 1;
                        }))))
                    .then(literal("mesh").executes(c->{showMesh();return 1;})
                        .then(literal("on").executes(c->{setMesh(true);return 1;}))
                        .then(literal("off").executes(c->{setMesh(false);return 1;})))
                    .then(literal("pause").executes(c->{pause();tell("已暂停；中断交易将保留核对状态");return 1;}))
                    .then(literal("stop").executes(c->{stopForManual();return 1;}))
                    .then(literal("resume").executes(c->{resume();tell("执行器已启用；需要核对的订单仍不会自动重发");return 1;})));
            });
            LOG.info("SuperWY Web listening at {}:{}, credentials in {}",config.bindAddress,config.port,configPath);
            updateView();
            if(smoke)ClientLifecycleEvents.CLIENT_STARTED.register(client->{
                GLFW.glfwHideWindow(client.getWindow().handle());
                Thread check=new Thread(()->{try{Thread.sleep(8000);mc.execute(()->{LOG.info("SUPERWY_SMOKE_OK: client entrypoint, mixins, SQLite and HTTP initialized; port={}",web.port());mc.stop();});}catch(InterruptedException e){Thread.currentThread().interrupt();}},"superwy-development-smoke");check.setDaemon(true);check.start();
            });
        }catch(Exception e){throw new IllegalStateException("SuperWY 初始化失败，检查配置、端口与数据库",e);}
    }
    private void join(){
        online=true;paused=config.manualControl;playerNbtAvailable=false;pos1=pos2=null;
        world=(mc.getCurrentServer()!=null?mc.getCurrentServer().ip:"singleplayer:"+mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath())+"#"+config.worldKey;
        dimension=mc.level.dimension().identifier().toString();
        int added=WarehousePresets.apply(config,store,world);saveConfig();if(added>0)tell("已导入 "+added+" 个仓库预设，初始停止扫描；在仓库区域页分别开始。已有区域和断点保留。");
        queries.entity(mc.player.getId()).thenApply(Items::playerInventory).whenComplete((v,e)->playerNbtAvailable=e==null);
        mesh.join(mc.player.getName().getString());
        if(mesh.enabled())tell("超级调度模式已开启："+(mesh.isAuthority()?"本机是调度机器人":"本机是工人，调度机器人 "+mesh.authorityName())+"；/superwy mesh 查看状态");
        tell("Web 控制台 http://127.0.0.1:"+config.port+"，/superwy token 复制令牌；网页查看仓库区域。/superwy frp 查看公网连接；/superwy connect 复制玩家连接指令。");
    }
    private void disconnect(){mesh.disconnect();online=false;playerNbtAvailable=false;batch.reset();queueNotifications.clear();scanner.reset();servux.reset();queries.reset();actions.abort();ControlGate.update();updateView();}
    private void tick(){
        for(int i=0;i<32;i++){Runnable command=commands.poll();if(command==null)break;command.run();}
        engine.watchdog();queries.tick();servux.tick();actions.tick();
        if(online&&mc.level!=null&&mc.player!=null){
            String dim=mc.level.dimension().identifier().toString();
            if(!dim.equals(dimension)){actions.abort();scanner.reset();servux.dimensionChanged();queries.abort();dimension=dim;pos1=pos2=null;}
            while(selectKey.consumeClick()){if(pos1==null||pos2!=null){pos1=target();pos2=null;tell("第一点: "+pos1.toShortString());}else{pos2=target();tell("第二点: "+pos2.toShortString()+"；执行 /superwy region <名称> 保存");}}
            batch.tick();
            if(!paused&&mc.player.isAlive()){if(!batch.drafting())engine.tick();scanner.tick(batch.drafting()||engine.busy()&&!engine.safeWaiting());}
            else scanner.tick(true);
            ControlGate.update();
            if(++ticks%20==0){outline();updateView();queueNotifications.tick(world,dimension);mesh.tick();}
        }else if(++ticks%20==0)updateView();
    }
    private BlockPos target(){if(mc.hitResult instanceof BlockHitResult hit)return hit.getBlockPos().immutable();return mc.player.blockPosition();}
    private void saveSelection(String id){
        if(engine.busy()||scanner.busy())throw new IllegalArgumentException("EXECUTOR_BUSY");
        if(pos1==null||pos2==null){tell("请先设置 pos1、pos2");return;}
        if(!id.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("区域名称使用字母数字下划线或横线");
        Model.Region region=new Model.Region(id,world,dimension,Items.pos(pos1),Items.pos(pos2));store.saveRegion(region).thenRun(()->commands.add(()->scanner.request(id)));tell("已保存区域 "+id+"，"+region.volume()+" 方块");
    }
    private Model.Station mainStation(){Model.Station s=config.stations.stream().filter(x->x.id.equals("main")).findFirst().orElse(null);if(s==null){s=new Model.Station();config.stations.add(s);}s.world=world;s.dimension=dimension;return s;}
    private Map<String,Object> workflowView(){
        Model.Pos supply=config.stations.stream().filter(s->"main".equals(s.id)&&Objects.equals(world,s.world)&&Objects.equals(dimension,s.dimension)&&s.boxSupply!=null).map(s->s.boxSupply).findFirst().orElse(config.boxSupply);
        return Map.of("returnEntry",config.returnEntry,"boxSupply",supply,"packingRoundSlots",config.packingRoundSlots,"maxCarriedParcels",config.maxCarriedParcels,"cleanupMainInventory",config.cleanupMainInventory,"cleanupEveryOrders",CleanupSchedule.INTERVAL);
    }
    private void stationHere(String kind){
        if(!Set.of("box","recovery","delivery").contains(kind))throw new IllegalArgumentException("box / delivery / recovery");
        if(!online||mc.player==null)throw new IllegalArgumentException("CAPABILITY_UNAVAILABLE");
        if(!kind.equals("delivery")&&(!(mc.hitResult instanceof BlockHitResult hit)||hit.getType()!=net.minecraft.world.phys.HitResult.Type.BLOCK||!Model.storageType(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(hit.getBlockPos()).getBlock()).toString()))){tell("请对准箱子、木桶或潜影盒后设置站点");return;}
        Model.Station station=mainStation();switch(kind){case "box"->{station.boxSupply=Items.pos(target());config.boxSupply=station.boxSupply;}case "return"->config.returnEntry=Items.pos(target());case "recovery"->station.recovery=Items.pos(target());case "delivery"->station.delivery=Items.pos(mc.player.blockPosition());default->throw new IllegalArgumentException("box / return / delivery / recovery");}
        saveConfig();updateView();tell("站点 "+kind+" 已立即保存到磁盘，重启后保留。");
    }
    /** One line for the dispatch page: what this robot is doing now. */
    private String activity(){
        if(!online)return "离线";if(paused)return "已暂停";
        String active=engine.activeId();
        if(active!=null&&engine.busy()){var o=store.order(active);return o==null?"执行中":(o.request.meshChunkId!=null?"任务块 ":"订单 ")+o.state;}
        return scanner.busy()?"扫描中":"空闲";
    }
    public Map<String,Long> stock(Collection<String> itemIds){
        String w=world,d=dimension;var regions=inventoryRegions;var result=new HashMap<String,Long>();
        for(String id:itemIds){
            long total=0;
            for(var source:store.sources(w,d,id,null)){
                var snapshot=source.snapshot();
                if(snapshot.complete&&Model.storageType(snapshot.type)&&regions.stream().anyMatch(r->r.enabled&&r.world.equals(w)&&r.dimension.equals(d)&&r.contains(snapshot.pos)))total+=source.item().count;
            }
            result.put(id,total);
        }
        return result;
    }
    /** Web shortage check against the cached stock of the current world. */
    private Map<String,Object> shortage(JsonObject body){
        if(!online)throw new IllegalArgumentException("CAPABILITY_UNAVAILABLE");
        var result=ShortageCheck.compute(body,this::stock);result.put("world",world);result.put("dimension",dimension);return result;
    }
    private void publicChat(String message){if(mc.getConnection()!=null)mc.getConnection().sendChat(message);}
    private void setMeshEnabled(boolean value){mesh.setEnabled(value);saveConfig();updateView();}
    private void setMesh(boolean value){
        try{setMeshEnabled(value);tell(value?"已开启超级调度模式："+(mesh.isAuthority()?"本机负责调度并参与取货":"本机作为工人，自动寻找调度机器人 "+mesh.authorityName())+"。玩家需在取货端执行 /swrequest mesh on":"已关闭超级调度模式");}
        catch(IllegalArgumentException e){tell(ErrorMessages.message(e.getMessage()));}
    }
    private void showMesh(){
        var s=mesh.status();
        String role=switch((String)s.get("role")){case "authority"->"调度机器人";case "worker"->"工人";default->"未开启";};
        tell("超级调度："+role+"；调度机器人 "+s.get("authorityName")+(Boolean.TRUE.equals(s.get("keyAvailable"))?"":"；缺少 private jar 凭证，无法开启")+("worker".equals(s.get("role"))?"；"+(Boolean.TRUE.equals(s.get("connected"))?"已连接 "+s.get("authorityUrl"):"未连接"+(s.get("error")==null?"":"："+s.get("error"))):"")+"；本机任务块 "+s.get("blocks"));
        if(s.get("robots") instanceof List<?> robots)for(var r:robots)if(r instanceof dev.superwy.mesh.MeshScheduler.RobotView v)tell(v.name()+(v.online()?" 在线":" 离线")+(v.ready()?" 可接单":" 未就绪")+" 空槽 "+v.freeSlots()+(v.chunkId()==null?" 空闲":" 执行中"));
        if(s.get("jobs") instanceof List<?> jobs)tell("调度中的任务 "+jobs.size()+" 个");
        tell("/superwy mesh on|off 切换");
    }
    private void saveConfig(){try{config.save(configPath);}catch(Exception e){throw new IllegalStateException(e);}}
    private CompletableFuture<Void> startScans(String id){
        if(id!=null)return scanner.startRegion(id);
        var saves=new ArrayList<CompletableFuture<Void>>();
        for(var r:store.regions())if(r.enabled&&r.world.equals(world)&&r.dimension.equals(dimension)&&!scanner.regionPaused(r.id))saves.add(scanner.startRegion(r.id));
        return CompletableFuture.allOf(saves.toArray(CompletableFuture[]::new));
    }
    private void setScanEnabled(boolean enabled){config.scanEnabled=enabled;if(enabled)scanner.reschedule();else scanner.stop();saveConfig();updateView();}
    private void setScanProtocol(String value){
        if(!Set.of("f3","servux").contains(value))throw new IllegalArgumentException("INVALID_SCAN_PROTOCOL");
        if(!config.scanProtocol.equals(value)){scanner.protocolChanged();config.scanProtocol=value;saveConfig();}
        updateView();tell("扫描协议已保存："+(value.equals("servux")?"Servux（实验性，按区块批量读取；不可用时可切回 f3）":"F3（逐容器 NBT 查询）"));
    }
    /** Local admin link with the token in the URL fragment: browsers never send the fragment to the server, and the page removes it after login. */
    private void showWebLink(){
        String url=webLoginUrl(web.port(),config.adminToken);mc.keyboardHandler.setClipboard(url);
        if(mc.player==null)return;
        var link=Component.literal("[点击打开管理网页]").withStyle(s->s.withColor(net.minecraft.ChatFormatting.GREEN).withUnderlined(true)
            .withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(java.net.URI.create(url)))
            .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(Component.literal("本机 http://127.0.0.1:"+web.port()+"，自动登录"))));
        mc.player.sendSystemMessage(Component.literal("[SuperWY] 管理地址已复制到剪贴板，浏览器打开即自动登录（仅本机可用，含管理令牌，勿发给他人）：").append(link));
    }
    public static String webLoginUrl(int port,String token){return "http://127.0.0.1:"+port+"/#token="+java.net.URLEncoder.encode(token,java.nio.charset.StandardCharsets.UTF_8);}
    private void tell(String message){if(mc.player!=null)mc.player.sendSystemMessage(Component.literal("[SuperWY] "+message));}
    private void pause(){paused=true;config.automationEnabled=false;config.manualControl=true;queries.abort();actions.abort();scanner.stop();saveConfig();}
    private void stopForManual(){pause();ControlGate.update();tell("已停止机器人；/superwy resume 恢复自动执行。物品核对状态会保留。");}
    private void resume(){scanner.reschedule();paused=false;config.automationEnabled=true;config.manualControl=false;if(mc.player!=null)queries.entity(mc.player.getId()).thenApply(Items::playerInventory).whenComplete((v,e)->playerNbtAvailable=e==null);saveConfig();}
    private void outline(){
        if(pos1==null||pos2==null)return;
        double[] min={Math.min(pos1.getX(),pos2.getX()),Math.min(pos1.getY(),pos2.getY()),Math.min(pos1.getZ(),pos2.getZ())};
        double[] max={Math.max(pos1.getX(),pos2.getX())+1,Math.max(pos1.getY(),pos2.getY())+1,Math.max(pos1.getZ(),pos2.getZ())+1};
        for(int axis=0;axis<3;axis++)for(int corner=0;corner<4;corner++)for(int t=0;t<=8;t++){
            double[] p=min.clone();p[axis]=min[axis]+(max[axis]-min[axis])*t/8.0;p[(axis+1)%3]=(corner&1)==0?min[(axis+1)%3]:max[(axis+1)%3];p[(axis+2)%3]=(corner&2)==0?min[(axis+2)%3]:max[(axis+2)%3];
            mc.level.addParticle(ParticleTypes.END_ROD,p[0],p[1],p[2],0,0,0);
        }
    }
    private void showUnscanned(int page){
        var positions=scanner.remaining();long from=(page-1L)*10;
        tell("未扫描容器 "+positions.size()+" 个；第 "+page+" / "+Math.max(1,(positions.size()+9)/10)+" 页");
        for(var p:positions.stream().skip(from).limit(10).toList())tell(p.x()+", "+p.y()+", "+p.z());
        tell("未探索区块 "+scanner.status.getOrDefault("remainingChunks",0)+"；其容器坐标将在区块加载后发现");
    }
    private void showFailures(int page){
        var failures=(List<?>)scanner.status.getOrDefault("failures",List.of());long from=(page-1L)*10;
        tell("已跳过失败位置 "+failures.size()+" 个，不自动重试；第 "+page+" 页");
        for(var failure:failures.stream().skip(from).limit(10).toList()){
            if(failure instanceof Map<?,?> f)tell(String.valueOf(f.containsKey("position")?f.get("position"):f.get("chunk"))+" · "+String.valueOf(f.containsKey("message")?f.get("message"):"扫描失败，已跳过"));
        }
    }
    private List<Model.Snapshot> eligibleSnapshots(){
        var regions=store.regions().stream().filter(r->r.enabled&&r.world.equals(world)&&r.dimension.equals(dimension)).toList();
        return store.snapshots().stream().filter(s->Model.storageType(s.type)&&regions.stream().anyMatch(r->r.contains(s.pos))).toList();
    }
    private void updateView(){
        Map<String,Object> result=new LinkedHashMap<>();result.put("protocolVersion","1");result.put("modVersion",FabricLoader.getInstance().getModContainer("superwy").orElseThrow().getMetadata().getVersion().getFriendlyString());result.put("minecraftVersion",online?"26.1.2":null);result.put("connectionState",online?"online":"offline");
        boolean quick=online&&actions.quickAvailable(),navigation=actions.navigation.available();
        boolean carpet=online&&mc.getConnection()!=null&&mc.getConnection().getCommands().getRoot().getChild("player")!=null;
        boolean region=store.regions().stream().anyMatch(r->r.enabled&&r.world.equals(world)&&r.dimension.equals(dimension));
        boolean accept=online&&!paused&&config.automationEnabled&&carpet&&navigation&&playerNbtAvailable&&region&&store.failure==null;
        workerReady=accept;
        // The authority queues player orders even while it cannot pick up itself; workers do the picking.
        if(mesh!=null&&mesh.isAuthority())accept=online&&store.failure==null;
        result.put("canAcceptOrders",accept);result.put("mesh",Map.of("enabled",mesh!=null&&mesh.enabled(),"authority",config.mesh.authorityName));result.put("scanEnabled",config.scanEnabled);result.put("inventoryMode",config.scanEnabled?"cached_live_updates":"historical_cache");result.put("activeWarehouseId",online?"main":null);result.put("warehouses",List.of(Map.of("warehouseId","main","displayName","主仓库","dimension",dimension)));
        result.put("scanProtocol",config.scanProtocol);result.put("servuxScan",servux.state());
        result.put("features",Map.of("nbtBlockQuery",queries.blockQuerySucceeded?"available":"unknown","quickShulker",quick?"available":"unavailable","carpetPlayerCommand",carpet?"available":"unavailable","recipientInventoryRead",playerNbtAvailable?"available":"unknown","recipientReceipt",playerNbtAvailable?"available":"unknown","navigation",navigation?"available":"unavailable"));
        result.put("limits",Map.of("maxBodyBytes",262144,"maxLines",128,"maxLineCount",1_000_000_000,"maxOpenOrders",5,"writeRequestsPerMinute",30,"idempotencyRetentionDays",30));
        List<String> reasons=new ArrayList<>();if(!online)reasons.add("OFFLINE");if(paused||!config.automationEnabled)reasons.add("AUTOMATION_DISABLED");if(!navigation)reasons.add("BARITONE_MISSING");if(!playerNbtAvailable)reasons.add("PLAYER_INVENTORY_QUERY_UNVERIFIED");if(!region)reasons.add("NO_REGION");if(store.failure!=null)reasons.add("STORAGE_UNAVAILABLE");result.put("blockingReasons",List.copyOf(reasons));result.put("blockingMessages",reasons.stream().map(ErrorMessages::message).toList());view=Collections.unmodifiableMap(result);
        String active=engine.activeId();Model.Order running=active==null?null:store.order(active);
        inventoryOrder=running;inventoryRegions=store.regions();
    }
    public Map<String,Object> status(Config.Principal principal){Map<String,Object> result=new LinkedHashMap<>(view);result.put("allowedFakePlayerNames",principal.names());result.put("recipientSelectable",principal.recipientSelectable());return result;}
    /** Called by HTTP workers only; idle clients never aggregate inventory on game ticks. */
    public List<Map<String,Object>> inventory(){
        String w=world,d=dimension;var regions=inventoryRegions;var running=inventoryOrder;
        var eligible=store.snapshots().stream().filter(s->Objects.equals(s.world,w)&&Objects.equals(s.dimension,d)&&Model.storageType(s.type)&&regions.stream().anyMatch(r->r.enabled&&r.world.equals(w)&&r.dimension.equals(d)&&r.contains(s.pos))).toList();
        var rows=InventoryIndex.aggregate(eligible,w,d);
        if(running!=null&&!running.terminal()&&Objects.equals(running.world,w)&&Objects.equals(running.dimension,d))for(Model.Line line:running.lines){long need=line.requested-line.fetched-line.shortfall;for(var row:rows)if(row.get("itemId").equals(line.itemId)&&(line.componentHash==null||row.get("componentHash").equals(line.componentHash))){long n=Math.min(need,(long)row.get("availableCount"));row.put("reservedCount",(long)row.get("reservedCount")+n);row.put("availableCount",(long)row.get("availableCount")-n);need-=n;}}
        return rows;
    }
    public dev.superwy.mesh.MeshNode mesh(){return mesh;}
    public String worldKey(){return world;}
    public String dimensionKey(){return dimension;}
    public CompletableFuture<Model.Order> cancel(String id){
        var result=new CompletableFuture<Model.Order>();
        commands.add(()->{try{mesh.cancelled(id);engine.cancel(id).whenComplete((order,error)->{if(error!=null)result.completeExceptionally(error);else result.complete(order);});}catch(Exception error){result.completeExceptionally(error);}});
        return result;
    }
    public CompletableFuture<Object> admin(String method,String path,JsonObject body){
        if(commands.size()>64)return CompletableFuture.failedFuture(new IllegalArgumentException("QUEUE_FULL"));
        var future=new CompletableFuture<Object>();commands.add(()->{try{Object result=adminNow(method,path,body);if(result instanceof CompletionStage<?> stage)stage.whenComplete((v,e)->{if(e!=null)future.completeExceptionally(e);else future.complete(v);});else future.complete(result);}catch(Exception e){future.completeExceptionally(e);}});return future;
    }
    private Object adminNow(String method,String path,JsonObject body){
        if(method.equals("GET")&&path.equals("network"))return networkView();
        if(method.equals("GET")&&path.equals("mesh"))return mesh.status();
        if(method.equals("POST")&&path.equals("mesh/enable")){setMeshEnabled(true);return mesh.status();}
        if(method.equals("POST")&&path.equals("mesh/disable")){setMeshEnabled(false);return mesh.status();}
        if(method.equals("PUT")&&path.equals("mesh/settings")){mesh.updateSettings(body);saveConfig();return mesh.status();}
        if(path.startsWith("mesh/"))return mesh.admin(method,path.substring(5),body);
        if(method.equals("POST")&&path.equals("frp/start")){startFrp();return networkView();}
        if(method.equals("POST")&&path.equals("frp/stop")){frp.stop();config.frp.enabled=false;saveConfig();return networkView();}
        if(method.equals("POST")&&path.equals("connect"))return connectionCommand();
        if(method.equals("GET")&&path.equals("dashboard")){
            Map<String,Object> result=new LinkedHashMap<>(view);result.put("world",world);result.put("dimension",dimension);result.put("paused",paused);result.put("navigation",actions.navigation.status());
            result.put("workflow",workflowView());
            result.put("scan",DashboardData.scan(scanner.status));result.put("scanRegions",scanner.regionStatuses().stream().map(DashboardData::scan).toList());result.put("regions",store.regions());
            result.put("orders",DashboardData.orders(store.recentOrders(DashboardData.HISTORY_LIMIT,null)));result.put("historyLimit",DashboardData.HISTORY_LIMIT);result.put("selection",Map.of("pos1",pos1==null?"未设置":pos1.toShortString(),"pos2",pos2==null?"未设置":pos2.toShortString()));return result;
        }
        if(method.equals("GET")&&path.equals("status")){Map<String,Object> result=new LinkedHashMap<>(view);result.put("world",world);result.put("dimension",dimension);result.put("paused",paused);result.put("navigation",actions.navigation.status());result.put("scan",scanner.status);result.put("scanRegions",scanner.regionStatuses());result.put("regions",store.regions());result.put("stations",config.stations);result.put("workflow",workflowView());var allOrders=store.recentOrders(DashboardData.HISTORY_LIMIT,null);result.put("orders",allOrders.stream().map(o->{var row=Jsons.GSON.toJsonTree(o).getAsJsonObject();row.add("queue",Jsons.GSON.toJsonTree(OrderQueue.position(o,allOrders)));row.addProperty("errorMessage",ErrorMessages.order(o));return row;}).toList());result.put("containers",eligibleSnapshots());result.put("selection",Map.of("pos1",pos1==null?"未设置":pos1.toShortString(),"pos2",pos2==null?"未设置":pos2.toShortString()));return result;}
        if(method.equals("POST")&&path.equals("pause")){pause();return Map.of("paused",true);}
        if(method.equals("POST")&&path.equals("resume")){resume();updateView();return Map.of("paused",false);}
        if(method.equals("POST")&&path.equals("probe")){if(!online)throw new IllegalArgumentException("CAPABILITY_UNAVAILABLE");queries.entity(mc.player.getId()).thenApply(Items::playerInventory).whenComplete((v,e)->playerNbtAvailable=e==null);return Map.of("queued",true);}
        if(path.startsWith("regions/")){
            String id=path.substring(8);if(!id.matches("[A-Za-z0-9_-]{1,64}"))throw new IllegalArgumentException("INVALID_REQUEST");
            if(method.equals("DELETE")){if(engine.busy()||scanner.busy())throw new IllegalArgumentException("EXECUTOR_BUSY");return store.deleteRegion(id).thenApply(v->{commands.add(()->scanner.forget(id));return Map.of("deleted",id);});}
            if(method.equals("PUT")){
                if(engine.busy()||scanner.busy())throw new IllegalArgumentException("EXECUTOR_BUSY");if(!online)throw new IllegalArgumentException("CAPABILITY_UNAVAILABLE");
                Model.Pos min=parsePos(Requests.object(body,"min")),max=parsePos(Requests.object(body,"max"));Model.Region r=new Model.Region(id,world,dimension,min,max);r.intervalSeconds=body.has("intervalSeconds")?Requests.integer(body,"intervalSeconds",30,86400):ScanSchedule.DEFAULT_INTERVAL_SECONDS;
                for(var previous:store.regions())if(previous.id.equals(id)&&ScanProgress.fingerprint(previous).equals(ScanProgress.fingerprint(r))){r.lastScanAt=previous.lastScanAt;r.lastSuccessAt=previous.lastSuccessAt;r.scanPaused=previous.scanPaused;}
                return store.saveRegion(r).thenApply(v->{commands.add(()->scanner.forget(id));return r;});
            }
        }
        if(method.equals("POST")&&(path.equals("scan")||path.equals("scan/start"))){
            if(!config.scanEnabled)throw new IllegalArgumentException("SCAN_DISABLED");if(paused||!online)throw new IllegalArgumentException("CAPABILITY_UNAVAILABLE");
            return startScans(body.has("regionId")?Requests.text(body,"regionId"):null).thenApplyAsync(v->Map.of("started",true,"scanRegions",scanner.regionStatuses()),mc::execute);
        }
        if(method.equals("POST")&&path.equals("scan/disable")){setScanEnabled(false);return Map.of("scanEnabled",false,"inventoryMode","historical_cache");}
        if(method.equals("POST")&&path.equals("scan/enable")){setScanEnabled(true);return Map.of("scanEnabled",true);}
        if(method.equals("POST")&&path.equals("scan/stop")){
            if(body.has("regionId")){String id=Requests.text(body,"regionId");return scanner.stopRegion(id).thenApplyAsync(v->Map.of("stopped",true,"regionId",id,"scanRegions",scanner.regionStatuses()),mc::execute);}
            setScanEnabled(false);return Map.of("stopped",true,"scanEnabled",false);
        }
        if(method.equals("POST")&&path.equals("shortage"))return shortage(body);
        if(method.equals("POST")&&path.equals("scan/reset-failures")){
            if(engine.busy()||paused)throw new IllegalArgumentException("EXECUTOR_BUSY");
            return scanner.retryFailed(Requests.text(body,"regionId")).thenApply(v->Map.of("queued",true));
        }
        if(method.equals("PUT")&&path.equals("workflow")){
            if(engine.busy())throw new IllegalArgumentException("EXECUTOR_BUSY");
            Model.Pos entry=parsePos(Requests.object(body,"returnEntry")),supply=parsePos(Requests.object(body,"boxSupply"));
            int round=Requests.integer(body,"packingRoundSlots",1,25),parcels=Requests.integer(body,"maxCarriedParcels",1,25);
            config.returnEntry=entry;config.boxSupply=supply;config.packingRoundSlots=round;config.maxCarriedParcels=parcels;
            if(online)mainStation().boxSupply=supply;saveConfig();return Map.of("saved",true);
        }
        if(method.equals("PUT")&&path.equals("station")){
            if(engine.busy())throw new IllegalArgumentException("EXECUTOR_BUSY");if(!online)throw new IllegalArgumentException("CAPABILITY_UNAVAILABLE");
            Model.Station s=mainStation();if(body.has("boxSupply"))s.boxSupply=parsePos(Requests.object(body,"boxSupply"));s.delivery=parsePos(Requests.object(body,"delivery"));if(body.has("recovery"))s.recovery=parsePos(Requests.object(body,"recovery"));saveConfig();return s;
        }
        if(method.equals("POST")&&path.startsWith("orders/")&&path.endsWith("/resolve")){
            if(engine.busy()||!Requests.text(body,"confirmation").equals("I_VERIFIED_ITEMS_AND_RECIPIENT"))throw new IllegalArgumentException("EXECUTOR_BUSY");
            if(!online||!mc.player.containerMenu.getCarried().isEmpty())throw new IllegalArgumentException("CLEAR_MANAGED_INVENTORY_FIRST");
            for(int i=9;i<36;i++)if(!config.protectedSlots.contains(i)&&!mc.player.getInventory().getItem(i).isEmpty())throw new IllegalArgumentException("CLEAR_MANAGED_INVENTORY_FIRST");
            String id=path.substring(7,path.length()-8);Model.Order o=store.order(id);if(o==null||!Set.of("recovering","delivery_uncertain").contains(o.state))throw new IllegalArgumentException("NOT_FOUND");o.state("failed","MANUALLY_RECONCILED");store.saveOrder(o);return o;
        }
        throw new IllegalArgumentException("NOT_FOUND");
    }
    private static Model.Pos parsePos(JsonObject o){return new Model.Pos(Requests.integer(o,"x",-30_000_000,30_000_000),Requests.integer(o,"y",-2048,2047),Requests.integer(o,"z",-30_000_000,30_000_000));}
    private Map<String,Object> networkView(){var result=new LinkedHashMap<String,Object>(frp.status());result.put("localUrl","http://127.0.0.1:"+web.port());result.put("enabled",config.frp.enabled);return result;}
    private void startFrp(){frp.start();config.frp.enabled=true;if(!config.allowedHosts.contains(config.frp.serverAddr))config.allowedHosts.add(config.frp.serverAddr);saveConfig();}
    private Map<String,Object> connectionCommand(){
        String url=frp.readyUrl();var credential=config.connectionCredential();
        String bot=mc.player==null?"":mc.player.getName().getString();
        String command="/swrequest connect "+ConnectionCode.encode(new ConnectionCode.Settings(url,credential.token,"",bot,"main"));
        if(!config.allowedHosts.contains(config.frp.serverAddr))config.allowedHosts.add(config.frp.serverAddr);
        saveConfig();return Map.of("command",command,"publicUrl",url,"recipientSelectable",true);
    }
}

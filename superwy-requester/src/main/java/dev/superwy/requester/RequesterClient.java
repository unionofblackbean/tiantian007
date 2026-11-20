package dev.superwy.requester;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.ClientTooltipComponentCallback;
import net.minecraft.client.gui.screens.Screen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.arguments.IdentifierArgument;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import dev.superwy.protocol.*;
import java.util.*;
import java.util.function.Consumer;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;

public final class RequesterClient implements ClientModInitializer {
    public static RequesterClient INSTANCE;
    private final Minecraft mc=Minecraft.getInstance();
    public final QuantityPrompt prompt=new QuantityPrompt();
    private ProjectionBridge bridge;private RequesterConfig config;private RequestApi api;private Path configPath;
    private ProjectionBridge.Target target;
    private String adapterError,lastChatOrder;
    private boolean settingsRequested;
    private RequesterConfig pendingConnection;
    private BatchDraft batch;private int batchPage;
    private final LinkedHashMap<String,RequestTask> tasks=new LinkedHashMap<>();private long nextBatchPacket;
    private RequesterBindings bindings;private boolean bindingsInitialized;private String bindingSaveError;
    public void onInitializeClient(){
        INSTANCE=this;
        try{
            Path dir=FabricLoader.getInstance().getConfigDir().resolve("superwy-requester");configPath=dir.resolve("config.json");
            config=RequesterConfig.load(configPath);api=new RequestApi();
            bindings=new RequesterBindings();
            try{bridge=new ProjectionBridge();}catch(ReflectiveOperationException e){adapterError="Litematica 接口版本不兼容，请使用 26.1.2 / 0.27.14";LoggerFactory.getLogger("SuperWY Requester").warn(adapterError,e);}
            ClientTooltipComponentCallback.EVENT.register(data->data instanceof ParcelTooltip parcel?new ParcelTooltip.Client(parcel):null);
            ClientTickEvents.END_CLIENT_TICK.register(client->{
                syncBindings();
                if(pendingConnection!=null){var draft=pendingConnection;pendingConnection=null;settingsRequested=false;prompt.close();mc.setScreen(new ConnectionSetupScreen(draft,chosen->{saveSettings(chosen);tell("连接配置已保存，已切换 HTTP；收货假人："+config.fakePlayerName);}));}
                if(settingsRequested){settingsRequested=false;prompt.close();mc.setScreen(new RequesterSettingsScreen(config,(draft,send)->{
                    if(send){validateMaterial(draft.defaultItem,draft.defaultCount);draft.validateOrder();}
                    saveSettings(draft);if(send)sendRequest(config.defaultItem,config.defaultCount);
                }));}
                tickBatch();
                if(mc.player==null||mc.screen!=null||!mc.isWindowActive()){prompt.close();return;}
                prompt.move(mc.player.getX(),mc.player.getY(),mc.player.getZ(),System.nanoTime()/1_000_000);
            });
            ScreenEvents.AFTER_INIT.register((client,screen,width,height)->{
                if(!(screen instanceof ChatScreen))return;
                ScreenEvents.afterExtract(screen).register((current,gui,mouseX,mouseY,delta)->{
                    if(mc.player!=null&&!mc.options.hideGui&&batch!=null&&batch.editing())renderBatch(gui);
                });
                ScreenMouseEvents.allowMouseClick(screen).register((current,event)->!clickBatch(event.x(),event.y(),event.button()));
            });
            ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->{prompt.close();pendingConnection=null;lastChatOrder=null;batch=null;tasks.clear();api.cancelPending();});
            ClientLifecycleEvents.CLIENT_STOPPING.register(client->api.close());
            ClientCommandRegistrationCallback.EVENT.register((dispatcher,registry)->dispatcher.register(commandTree()));
            HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("superwy_requester","quantity"),(gui,delta)->{
                if(mc.player==null||mc.options.hideGui)return;
                renderTasks(gui);
                if(batch!=null&&batch.editing()&&!(mc.screen instanceof ChatScreen))renderBatch(gui);
                if(!prompt.isOpen()||target==null)return;
                int w=Math.min(250,gui.guiWidth()-16),x=Math.max(8,gui.guiWidth()-w-12),y=Math.max(12,gui.guiHeight()/2-52);
                gui.fill(x,y,x+w,y+102,0xe6101721);gui.fill(x,y,x+3,y+102,0xffeac54f);
                gui.text(mc.font,"取货 · 投影材料",x+10,y+8,0xffeac54f);
                gui.item(target.item(),x+10,y+26);
                gui.text(mc.font,mc.font.plainSubstrByWidth(target.item().getHoverName().getString(),w-43),x+32,y+30,0xfff3f4f6);
                gui.fill(x+10,y+49,x+w-10,y+68,0xff293445);
                gui.text(mc.font,"数量  "+prompt.text()+"_  / "+prompt.maximum(),x+16,y+55,prompt.valid()?0xffffffff:0xffff7777);
                gui.text(mc.font,RequestHotkey.label(config.confirmKey)+" 确认  "+RequestHotkey.label(config.queueKey)+" 入队",x+10,y+77,0xffc7d0dc);
                gui.text(mc.font,"可自由移动 · 连续移动 1 秒关闭",x+10,y+90,0xffa6b5c7);
            });
        }catch(Exception e){throw new IllegalStateException("SuperWY Requester 初始化失败，请检查配置文件",e);}
    }
    public boolean middleClick(){
        if(mc.player==null||mc.level==null||mc.screen!=null||!mc.mouseHandler.isMouseGrabbed())return false;
        if(adapterError!=null){tell(adapterError);return false;}
        if(bridge==null||!bridge.available())return false;
        try{
            var picked=bridge.pick(mc,config.pickRange);if(picked==null)return false;
            target=picked;prompt.open(mc.player.getX(),mc.player.getY(),mc.player.getZ(),config.defaultCount,batch!=null&&batch.editing()?1_000_000:64);return true;
        }catch(ReflectiveOperationException e){prompt.close();tell("投影读取失败，请检查 Litematica 版本");return true;}
    }
    public boolean mouse(int action,int button){
        if(mc.screen instanceof RequesterSettingsScreen settings&&action==GLFW.GLFW_PRESS&&settings.captureHotkey(RequestHotkey.mouse(button)))return true;
        return input(action,RequestHotkey.mouse(button),0);
    }
    public boolean key(int action,int key,int modifiers){
        return key(action,key,0,modifiers);
    }
    public boolean key(int action,int key,int scancode,int modifiers){
        if(key==GLFW.GLFW_KEY_UNKNOWN)key=RequestHotkey.scanCode(scancode);
        if(mc.screen instanceof RequesterSettingsScreen settings&&action==GLFW.GLFW_PRESS&&settings.captureHotkey(key))return true;
        return input(action,key,modifiers);
    }
    private boolean input(int action,int key,int modifiers){
        if(mc.screen!=null||mc.player==null||!mc.isWindowActive())return false;
        if(key==config.queueKey){if(action==GLFW.GLFW_PRESS)run(this::queueMaterial);return true;}
        if(key==config.confirmKey||(config.confirmKey==GLFW.GLFW_KEY_ENTER&&key==GLFW.GLFW_KEY_KP_ENTER)){
            if(!prompt.isOpen()&&(batch==null||!batch.editing()))return false;
            if(action==GLFW.GLFW_PRESS)run(()->{if(batch!=null&&batch.editing()){if(prompt.isOpen())addPrompt();commitBatch();}else submit();});return true;
        }
        if(key==config.pickupKey){if(action==GLFW.GLFW_PRESS)return middleClick();return prompt.isOpen();}
        if(!prompt.isOpen()){
            if(key==GLFW.GLFW_KEY_ESCAPE&&batch!=null&&batch.editing()){if(action==GLFW.GLFW_PRESS)abortBatch();return true;}
            return false;
        }
        boolean digit=key>=GLFW.GLFW_KEY_0&&key<=GLFW.GLFW_KEY_9||key>=GLFW.GLFW_KEY_KP_0&&key<=GLFW.GLFW_KEY_KP_9;
        boolean control=key==GLFW.GLFW_KEY_BACKSPACE||key==GLFW.GLFW_KEY_ESCAPE||key==GLFW.GLFW_KEY_A&&(modifiers&GLFW.GLFW_MOD_CONTROL)!=0;
        if(!digit&&!control)return false;
        if(action==GLFW.GLFW_RELEASE)return true;
        if(digit){if((modifiers&(GLFW.GLFW_MOD_CONTROL|GLFW.GLFW_MOD_ALT|GLFW.GLFW_MOD_SUPER))==0)prompt.digit(key>=GLFW.GLFW_KEY_KP_0?key-GLFW.GLFW_KEY_KP_0:key-GLFW.GLFW_KEY_0);}
        else if(key==GLFW.GLFW_KEY_BACKSPACE)prompt.backspace();
        else if(key==GLFW.GLFW_KEY_ESCAPE)prompt.close();
        else prompt.selectAll();
        return true;
    }
    private void queueMaterial(){
        if(batch==null||!batch.editing()){
            config.validateOrder();
            batchPage=0;batch=new BatchDraft();batch.id=WhisperProtocol.newId();batch.recipient=config.fakePlayerName;batch.status="超过 25 格自动打包，可继续添加材料";batch.deadline=Long.MAX_VALUE;
        }
        if(!prompt.isOpen()&&!middleClick())return;
        addPrompt();
    }
    private void addPrompt(){
        if(!prompt.valid())throw new IllegalArgumentException("数量必须在 1–"+prompt.maximum()+" 之间");
        String item=BuiltInRegistries.ITEM.getKey(target.item().getItem()).toString();
        batch.add(item,prompt.quantity(),target.item().getMaxStackSize());batchPage=(batch.items.size()-1)/batchRows();prompt.close();
    }
    /** First confirm queries cached stock; the next confirm starts (pressing again while waiting forces the start). */
    private void commitBatch(){
        if(batch.items.isEmpty())throw new IllegalArgumentException("队列为空，请先选择材料");
        requireTaskCapacity();config.validateOrder();
        if(!batch.stockChecked&&!batch.stockChecking){checkStock(batch);return;}
        var draft=batch;draft.submitted=true;draft.status="正在提交批量清单…";draft.deadline=Long.MAX_VALUE;
        var task=new RequestTask(draft.id,"批量 · "+draft.items.size()+" 项材料","");task.batch=draft;task.http=config.copy();tasks.put(task.id,task);
        submitDraft(task,false);
    }
    private void checkStock(BatchDraft draft){
        draft.stockChecking=true;draft.status="正在查询机器人库存… 再按 "+RequestHotkey.label(config.confirmKey)+" 可强制开始";
        var lines=draft.items.stream().map(e->new RequestApi.Line(e.item(),e.count())).toList();
        api.stock(config.copy(),lines).whenComplete((reply,error)->mc.execute(()->{
            if(batch!=draft||!draft.stockChecking)return;
            draft.stockChecking=false;draft.stockChecked=true;String key=RequestHotkey.label(config.confirmKey);
            if(error!=null||!reply.ok()){draft.status="库存查询失败（"+(error!=null?"无法连接机器人":reply.error())+"），再按 "+key+" 仍然开始";return;}
            draft.available.putAll(reply.available());long kinds=draft.missingKinds();
            draft.status=kinds==0?"库存充足，按 "+key+" 开始取货":"库存不足 "+kinds+" 种（标红），按 "+key+" 开始，缺货项会跳过";
        }));
    }
    /** The whole manifest is one HTTP order; status comes from polling it. */
    private void submitDraft(RequestTask task,boolean parcels){
        var draft=task.batch;var lines=draft.items.stream().map(e->new RequestApi.Line(e.item(),e.count())).toList();
        api.submitBatch(task.http,lines,parcels).whenComplete((reply,error)->mc.execute(()->{
            if(tasks.get(task.id)!=task)return;long now=System.currentTimeMillis();
            if(error!=null||!reply.accepted()){
                draft.status="批量提交失败："+(error!=null?"无法连接机器人":errorText(reply));draft.hideAt=now+15000;task.update(0,"failed",now);tell(draft.status);return;
            }
            draft.accepted=true;draft.orderId=reply.orderId();task.orderId=reply.orderId();draft.status="批量订单已受理，等待取货";
            applyProgress(task,reply,now);tell("批量订单已受理："+reply.orderId());
        }));
    }
    private void abortBatch(){
        if(batch==null||!batch.editing())return;
        batch=null;batchPage=0;prompt.close();
    }
    private void tickBatch(){
        long now=System.currentTimeMillis();
        tasks.values().removeIf(t->t.hideAt>0&&now>=t.hideAt);
        if(batch!=null&&batch.hideAt>0&&now>=batch.hideAt)batch=null;
        for(var task:List.copyOf(tasks.values()))if(task.http!=null&&task.orderId!=null&&task.hideAt==0&&!task.polling&&now>=task.nextPoll){
            task.polling=true;task.nextPoll=now+3000;
            api.status(task.http,task.orderId).whenComplete((reply,error)->mc.execute(()->{task.polling=false;if(tasks.get(task.id)!=task)return;if(error==null&&reply.accepted())applyProgress(task,reply,System.currentTimeMillis());}));
        }
    }
    private void applyProgress(RequestTask task,RequestApi.Reply reply,long now){
        var p=reply.progress();String state=reply.state()==null?"queued":reply.state();
        task.robots=p.robots();task.fetched=p.fetched();task.requested=p.requested();
        var draft=task.batch;
        if(draft!=null&&p.lines()>0){
            draft.completed=Math.min(draft.items.size(),p.processed());draft.fetchedUnits=p.fetched();draft.requestedUnits=p.requested();
            boolean done=java.util.Set.of("stored_offline","partial_stored_offline","failed","cancelled").contains(state);
            draft.status=switch(state){case "stored_offline"->"批量完成，已放入收货假人";case "partial_stored_offline"->"批量完成，已交付可取材料（含跳过项）";case "failed"->p.delivered()==0?"清单已处理完，均未取得材料":"批量已停止，请查看聊天并核对物品";case "cancelled"->"已取消";case "packing"->"正在装入潜影盒";case "queued"->"等待取货";default->"正在批量取货";};
            if(done&&draft.hideAt==0){draft.hideAt=now+15000;tell(draft.status);}
        }
        task.update(reply.ahead(),state,now);
        // Shortages never stop a task; once it has finished, say what was missing and by how much.
        if(task.hideAt>0&&!task.reported&&!state.equals("cancelled")){
            task.reported=true;
            for(String line:ShortageReport.messages(p.items(),RequesterClient::itemName))tell(line);
        }
    }
    private static String itemName(String id){
        try{var key=Identifier.parse(id);return BuiltInRegistries.ITEM.containsKey(key)?new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.getValue(key)).getHoverName().getString():id;}
        catch(RuntimeException e){return id;}
    }
    private void renderTasks(net.minecraft.client.gui.GuiGraphicsExtractor gui){
        if(tasks.isEmpty())return;
        // One line: current work first, otherwise the nearest pending task.
        var task=tasks.values().stream().min(java.util.Comparator.<RequestTask>comparingInt(t->t.hideAt>0?3:t.state.equals("queued")?1:t.state.equals("submitting")?2:0).thenComparingInt(t->t.ahead)).orElseThrow();
        String text=mc.font.plainSubstrByWidth(task.hudStatus(),gui.guiWidth()-16);
        int y=gui.guiHeight()-(mc.screen instanceof ChatScreen?36:20);
        if(batch!=null&&batch.editing())y=batchPanel(gui.guiWidth(),gui.guiHeight()).y-14;
        gui.text(mc.font,text,gui.guiWidth()-mc.font.width(text)-8,Math.max(8,y),task.state.equals("queued")?0xffffd479:0xffeeeeee);
    }
    private int batchRows(){return tasks.isEmpty()?6:4;}
    private record BatchPanel(int x,int y,int width,int height,int start,int visible,boolean interactive){}
    private BatchPanel batchPanel(int width,int height){
        int rows=batchRows(),pages=Math.max(1,(batch.items.size()+rows-1)/rows);
        batchPage=Math.max(0,Math.min(batchPage,pages-1));
        boolean interactive=batch.editing()&&mc.screen instanceof ChatScreen;
        int start=batch.editing()?batchPage*rows:Math.max(0,batch.items.size()-rows);
        int visible=Math.min(rows,batch.items.size()-start),w=Math.min(280,width-16),h=(batch.editing()?80:58)+visible*18;
        return new BatchPanel(width-w-8,Math.max(8,height-h-(interactive?36:12)),w,h,start,visible,interactive);
    }
    private boolean hit(double mx,double my,int x,int y,int w,int h){return mx>=x&&mx<x+w&&my>=y&&my<y+h;}
    private boolean clickBatch(double mx,double my,int button){
        if(!(mc.screen instanceof ChatScreen)||mc.options.hideGui||batch==null||!batch.editing())return false;
        var p=batchPanel(mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight());
        if(!hit(mx,my,p.x,p.y,p.width,p.height))return false;
        if(button!=GLFW.GLFW_MOUSE_BUTTON_LEFT)return true;
        for(int row=0;row<p.visible;row++)if(hit(mx,my,p.x+p.width-42,p.y+22+row*18,34,16)){
            batch.remove(p.start+row);return true;
        }
        int controls=p.y+p.height-48;
        if(hit(mx,my,p.x+8,controls,46,16)){batchPage=Math.max(0,batchPage-1);return true;}
        if(hit(mx,my,p.x+60,controls,46,16)){batchPage=Math.min(Math.max(0,(batch.items.size()-1)/batchRows()),batchPage+1);return true;}
        if(hit(mx,my,p.x+p.width-90,controls,82,16)){run(this::abortBatch);return true;}
        return true;
    }
    private void batchButton(net.minecraft.client.gui.GuiGraphicsExtractor gui,String label,int x,int y,int width,boolean enabled,int color){
        gui.fill(x,y,x+width,y+16,enabled?0xff34465b:0xff202b38);
        gui.text(mc.font,label,x+4,y+4,enabled?color:0xff687586);
    }
    private void renderBatch(net.minecraft.client.gui.GuiGraphicsExtractor gui){
        if(batch==null)return;
        var p=batchPanel(gui.guiWidth(),gui.guiHeight());int x=p.x,y=p.y,w=p.width,h=p.height;
        gui.fill(x,y,x+w,y+h,0xf0101721);gui.fill(x,y,x+3,y+h,0xff59c9ad);
        gui.text(mc.font,batch.editing()?"待提交清单 · "+batch.items.size()+" 项 · "+batch.used()+" 格":"批量 · "+batch.completed+"/"+batch.items.size()+" 跳过 "+batch.skipped.size()+" 占 "+batch.used()+" 格",x+10,y+8,0xff59c9ad);
        for(int row=0;row<p.visible;row++){
            int i=p.start+row;var e=batch.items.get(i);String name=new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(e.item()))).getHoverName().getString();
            long missing=batch.missing(i);
            gui.text(mc.font,mc.font.plainSubstrByWidth((batch.skipped.containsKey(i)?"[跳过] ":"")+(i+1)+". "+name+" ×"+e.count()+(missing>0?" · 缺 "+missing:""),w-(batch.editing()?60:20)),x+10,y+24+row*18,missing>0?0xffff7777:0xffeeeeee);
            if(batch.editing())batchButton(gui,"删除",x+w-42,y+22+row*18,34,p.interactive,0xffffaaaa);
        }
        if(batch.editing()){
            int controls=y+h-48,pages=Math.max(1,(batch.items.size()+batchRows()-1)/batchRows());
            batchButton(gui,"上一页",x+8,controls,46,p.interactive&&batchPage>0,0xffeeeeee);
            batchButton(gui,"下一页",x+60,controls,46,p.interactive&&batchPage+1<pages,0xffeeeeee);
            gui.text(mc.font,(batchPage+1)+"/"+pages,x+112,controls+4,0xffa6b5c7);
            batchButton(gui,"取消整单",x+w-90,controls,82,p.interactive,0xffffaaaa);
        }
        gui.text(mc.font,mc.font.plainSubstrByWidth(batch.status,w-20),x+10,y+h-26,0xffd0d8e0);
        gui.text(mc.font,batch.editing()?"T 聊天栏点击编辑 · "+RequestHotkey.label(config.confirmKey)+" 提交":"已处理 / 清单总项数 · 含跳过项",x+10,y+h-12,0xffa6b5c7);
    }
    /** Called from the Litematica area editor button. */
    public void openProjectionOrder(Screen parent){
        if(mc.player==null||mc.level==null)return;
        prompt.close();mc.setScreen(new ProjectionOrderScreen(parent,config.mesh?"超级调度":"机器人",config.fakePlayerName,this::submitProjection,lines->api.stock(config.copy(),lines)));
    }
    /** One authenticated batch with the "parcels" flag; lines keep the preview order so the robot packs the same way. */
    private String submitProjection(ParcelPlan.Plan plan){
        config.validateOrder();
        requireTaskCapacity();
        var draft=new BatchDraft();draft.id=WhisperProtocol.newId();draft.recipient=config.fakePlayerName;
        draft.parcels=true;draft.plannedBoxes=plan.boxes().size();
        for(var line:plan.lines())draft.add(line.itemId(),line.count(),line.stackSize());
        draft.submitted=true;draft.status="正在提交投影清单…";draft.deadline=Long.MAX_VALUE;
        var task=new RequestTask(draft.id,"投影取货 · "+plan.lines().size()+" 种 · 约 "+plan.boxes().size()+" 盒","");task.batch=draft;task.http=config.copy();tasks.put(task.id,task);
        submitDraft(task,true);
        return "投影清单 "+plan.lines().size()+" 种 / "+plan.units()+" 件正在提交"+(config.mesh?"（超级调度）":"")+"，预计 "+plan.boxes().size()+" 个潜影盒"+(plan.loose().isEmpty()?"":"、"+plan.loose().size()+" 个散装潜影盒");
    }
    private void submit(){
        if(!prompt.valid()){tell("数量必须在 1–"+prompt.maximum()+" 之间");return;}
        try{
            String id=BuiltInRegistries.ITEM.getKey(target.item().getItem()).toString();
            sendRequest(id,prompt.quantity());prompt.close();
        }catch(Exception e){tell(e.getMessage());}
    }
    private LiteralArgumentBuilder<FabricClientCommandSource> commandTree(){
        return literal("swrequest").executes(c->{settingsRequested=true;return 1;})
            .then(literal("config").executes(c->{settingsRequested=true;return 1;}))
            .then(literal("connect").executes(c->{settingsRequested=true;return 1;}).then(argument("code",StringArgumentType.word()).executes(c->run(()->{
                pendingConnection=config.withConnection(StringArgumentType.getString(c,"code"));
            }))))
            .then(literal("reload").executes(c->run(()->{try{saveSettings(RequesterConfig.load(configPath));tell("已重新加载取货配置");}catch(Exception e){throw new IllegalArgumentException("配置读取失败，请检查 config.json",e);}})))
            .then(literal("mesh").executes(c->run(()->tell("超级调度模式："+(config.mesh?"开启":"关闭")+"；/swrequest mesh on|off 切换。机器人开启超级调度后，取货端必须开启才能下单，任务自动发给调度机器人")))
                .then(literal("on").executes(c->change(d->d.mesh=true)))
                .then(literal("off").executes(c->change(d->d.mesh=false))))
            .then(literal("recipient").then(argument("name",StringArgumentType.word()).executes(c->change(d->d.fakePlayerName=StringArgumentType.getString(c,"name")))))
            .then(literal("item").then(argument("item",IdentifierArgument.id()).executes(c->change(d->d.defaultItem=c.getArgument("item",Identifier.class).toString()))))
            .then(literal("count").then(argument("count",IntegerArgumentType.integer(1,64)).executes(c->change(d->d.defaultCount=IntegerArgumentType.getInteger(c,"count")))))
            .then(literal("send").executes(c->run(()->sendRequest(config.defaultItem,config.defaultCount))))
            .then(literal("request").then(argument("item",IdentifierArgument.id()).then(argument("count",IntegerArgumentType.integer(1,64)).executes(c->run(()->sendRequest(c.getArgument("item",Identifier.class).toString(),IntegerArgumentType.getInteger(c,"count")))))))
            .then(literal("status").executes(c->run(()->report(api.status(config),false)))
                .then(argument("id",StringArgumentType.word()).executes(c->run(()->report(api.status(config,StringArgumentType.getString(c,"id")),false)))))
            .then(literal("cancel").then(argument("id",StringArgumentType.word()).executes(c->run(()->{
                api.cancel(config,StringArgumentType.getString(c,"id")).whenComplete((reply,error)->mc.execute(()->{
                    if(error!=null)tell("取消失败，请查询订单状态");else if(reply.accepted())tell("订单已立即取消");else tell("取消失败："+failureLabel(reply));
                }));
            }))));
    }
    private int run(Runnable action){try{action.run();return 1;}catch(Exception e){tell(Objects.toString(e.getMessage(),"操作失败"));return 0;}}
    private int change(Consumer<RequesterConfig> edit){return run(()->{var draft=config.copy();edit.accept(draft);saveSettings(draft);tell("取货配置已保存；超级调度模式："+(config.mesh?"开启":"关闭"));});}
    private void saveSettings(RequesterConfig draft){try{draft.save(configPath);config=draft;if(bindings!=null&&mc.options!=null){bindings.apply(config);mc.options.save();}}catch(Exception e){throw new IllegalArgumentException(Objects.toString(e.getMessage(),"配置保存失败"),e);}}
    private void syncBindings(){
        if(bindings==null||mc.options==null)return;
        try{
            if(!bindingsInitialized){
                if(config.nativeHotkeysInitialized)saveSettings(bindings.read(config));
                else {var draft=config.copy();draft.nativeHotkeysInitialized=true;saveSettings(draft);}
                bindingsInitialized=true;
            }else if(bindings.differs(config))saveSettings(bindings.read(config));
            bindingSaveError=null;
        }catch(Exception e){String message=Objects.toString(e.getMessage(),"按键设置保存失败");if(!message.equals(bindingSaveError)){bindingSaveError=message;tell(message);LoggerFactory.getLogger("SuperWY Requester").warn("按键设置保存失败",e);}}
    }
    private void validateMaterial(String item,int count){
        if(item==null||!item.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||count<1||count>64||!BuiltInRegistries.ITEM.containsKey(Identifier.parse(item)))throw new IllegalArgumentException("物品 ID 无效，数量须为 1–64");
    }
    private void sendRequest(String item,int count){
        validateMaterial(item,count);
        requireTaskCapacity();
        var chosen=config.copy();chosen.validateOrder();String id=WhisperProtocol.newId();var task=new RequestTask(id,item+" ×"+count,"");task.http=chosen;tasks.put(id,task);
        api.submit(chosen,item,count).whenComplete((reply,error)->mc.execute(()->{if(tasks.get(id)!=task)return;if(error!=null||!reply.accepted()){task.update(0,"failed",System.currentTimeMillis());tell("请求提交失败："+(error!=null?"无法连接机器人":errorText(reply)));}else{task.orderId=reply.orderId();lastChatOrder=reply.orderId();applyProgress(task,reply,System.currentTimeMillis());}}));
        tell("正在发送取货请求…");
    }
    private void requireTaskCapacity(){if(tasks.size()>=128)throw new IllegalArgumentException("待跟踪任务过多，请等待现有任务结束后再提交");}
    private void report(CompletableFuture<RequestApi.Reply> request){report(request,true);}
    private void report(CompletableFuture<RequestApi.Reply> request,boolean submission){request.whenComplete((reply,error)->mc.execute(()->{
        if(error!=null)tell(submission?"请求提交失败，请稍后重新提交":"查询失败，请稍后重试");
        else if(reply.accepted())tell(submission?"请求提交成功："+reply.orderId():"订单 "+reply.orderId()+"："+stateLabel(reply.state()));
        else tell((submission?"请求提交失败：":"查询失败：")+failureLabel(reply));
    }));}
    private static String errorText(RequestApi.Reply reply){
        return switch(String.valueOf(reply.error())){
            case "MESH_MODE_REQUIRED"->"机器人处于超级调度模式，请执行 /swrequest mesh on 后重新提交";
            case "MESH_AUTHORITY_OFFLINE"->"调度机器人暂时不在线，请稍后再试";
            case "QUEUE_FULL"->"机器人任务队列已满";
            case "CAPABILITY_UNAVAILABLE","AUTOMATION_DISABLED","OFFLINE"->"机器人尚未启用或未就绪";
            case "UNAUTHENTICATED"->"连接凭证无效，请重新执行连接指令";
            default->failureLabel(reply);
        };
    }
    private static String failureLabel(RequestApi.Reply reply){return "HTTP "+reply.status()+(reply.error()==null?"":" · "+reply.error());}
    private static String stateLabel(String state){if(state==null)return "未知状态";return switch(state){
        case "queued"->"服务端已受理";case "waiting_stock"->"等待库存";case "waiting_boxes"->"等待空盒";case "waiting_recipient"->"等待领取/收货容量";
        case "fetching"->"取货中";case "packing"->"装箱中";case "stored_offline"->"已交付并下线保存";case "partial_stored_offline"->"部分交付并下线保存";
        case "cancelled"->"已取消";case "failed"->"已结束（异常）";case "recovering","delivery_uncertain"->"等待管理员核对";case "cancelling"->"安全取消中";default->"处理中";
    };}
    private void tell(String text){if(mc.player!=null)mc.player.sendSystemMessage(Component.literal("[取货] "+text));}
}

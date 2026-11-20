package dev.superwy.game;

import dev.superwy.core.*;
import dev.superwy.protocol.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Independent authenticated manifests. Editing never owns inventory or courier actions. */
public final class BatchInbox {
    private final Minecraft mc=Minecraft.getInstance();private final Config config;private final Store store;
    private final Supplier<Boolean> ready;private final Supplier<String> world,dimension;private final Queue<Runnable> commands;
    private final Map<String,Session> sessions=new LinkedHashMap<>();
    private static final class Session {
        String id,owner,name,recipient,world,dimension;long deadline;int limit=-1;boolean deferred,parcels;
        TaskScope scope=new TaskScope();List<Model.RequestLine> lines=new ArrayList<>();
    }
    public BatchInbox(Config config,Store store,Queries queries,Actions a,Supplier<Boolean> ready,Supplier<Boolean> safe,Supplier<String> world,Supplier<String> dimension,Queue<Runnable> commands){
        this.config=config;this.store=store;this.ready=ready;this.world=world;this.dimension=dimension;this.commands=commands;
    }
    private String key(String owner,String id){return owner+":"+id;}
    public void register(){PrivateMessages.register(message->{if(!BatchProtocol.request(message.body()))return false;if(commands.size()<64)commands.add(()->receive(message));return true;});}
    public boolean drafting(){return false;}
    private void reply(Session s,String action,Object...args){if(mc.getConnection()!=null)mc.getConnection().sendCommand(WhisperProtocol.command(s.name,BatchProtocol.message(true,s.id,action,args)));}
    private void check(Session s){s.scope.check();if(sessions.get(key(s.owner,s.id))!=s||mc.player==null||mc.level==null||!Objects.equals(s.world,world.get())||!Objects.equals(s.dimension,dimension.get())||!config.automationEnabled)throw new IllegalStateException("EXECUTOR_INTERRUPTED");}
    private void receive(PrivateMessages.Message message){
        BatchProtocol.Packet packet;try{packet=BatchProtocol.parse(message.body(),false);}catch(Exception e){return;}
        String owner="msg_"+message.senderId();Session s=sessions.get(key(owner,packet.id()));
        try{
            if(!config.whisperEnabled)throw new IllegalArgumentException("WHISPER_DISABLED");String[] args=packet.args();
            if(packet.action().equals("abort")&&s==null)return;
            if(packet.action().equals("begin")){
                // Player orders by private message are retired; tell old player mods once per manifest.
                if(mc.getConnection()!=null)mc.getConnection().sendCommand(WhisperProtocol.command(message.senderName(),WhisperInbox.retiredHint(config)));
                throw new IllegalArgumentException("WHISPER_ORDERS_DISABLED");
            }
            if(s==null){
                throw new IllegalArgumentException("BATCH_EXPIRED");
            }
            check(s);s.deadline=System.currentTimeMillis()+120000;
            if(packet.action().equals("abort")){remove(s);return;}
            if(s.limit<0)throw new IllegalArgumentException("EXECUTOR_BUSY");
            if(packet.action().equals("add")){
                if(args.length!=3)throw new IllegalArgumentException("INVALID_REQUEST");int index=Integer.parseInt(args[0]),count=Integer.parseInt(args[2]);
                if(index!=s.lines.size()||count<1||count>PackingPolicy.MAX_COUNT||!args[1].matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||!BuiltInRegistries.ITEM.containsKey(Identifier.parse(args[1])))throw new IllegalArgumentException("INVALID_REQUEST");
                var line=new Model.RequestLine();line.itemId=args[1];line.count=count;if(s.lines.size()>=PackingPolicy.MANIFEST_LINES||s.lines.stream().mapToLong(l->l.count).sum()+count>PackingPolicy.MAX_COUNT)throw new IllegalArgumentException("REQUEST_LIMIT_EXCEEDED");
                s.lines.add(line);reply(s,"added",index);return;
            }
            if(!packet.action().equals("commit")||args.length!=1||Integer.parseInt(args[0])!=s.lines.size()||s.lines.isEmpty())throw new IllegalArgumentException("INVALID_REQUEST");
            var request=new Model.Request();request.batchId=s.id;request.notifyPlayer=s.name;request.batchCapacityPending=s.deferred;request.projectionParcels=s.parcels;request.clientRequestId=s.id;request.items=List.copyOf(s.lines);request.delivery=new Model.DeliveryRequest();request.delivery.fakePlayerName=s.recipient;
            var order=store.accept(s.owner,"batch-"+s.id,request,ready.get(),s.world,s.dimension).order();reply(s,"accepted",order.orderId,OrderQueue.position(order,store.pendingOrders()).position());remove(s);
        }catch(Exception e){String code=e instanceof NumberFormatException?"INVALID_REQUEST":Objects.toString(e.getMessage(),"INVALID_REQUEST");if(!code.matches("[A-Z_]{1,64}"))code="INVALID_REQUEST";
            if(mc.getConnection()!=null)mc.getConnection().sendCommand(WhisperProtocol.command(message.senderName(),BatchProtocol.message(true,packet.id(),"error",code)));
        }
    }
    static int slots(List<Model.RequestLine> lines){return lines.stream().mapToInt(l->{int size=new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(l.itemId))).getMaxStackSize();return (l.count+size-1)/size;}).sum();}
    public void tick(){
        for(Session s:List.copyOf(sessions.values())){
            if(mc.player==null||!Objects.equals(s.world,world.get())||!Objects.equals(s.dimension,dimension.get())||!config.automationEnabled){remove(s);continue;}
            if(System.currentTimeMillis()>s.deadline){reply(s,"error","BATCH_EXPIRED");remove(s);continue;}
        }
    }
    private void remove(Session s){sessions.remove(key(s.owner,s.id),s);s.scope.stop("EXECUTOR_INTERRUPTED");}
    public void reset(){for(Session s:List.copyOf(sessions.values()))remove(s);}
}

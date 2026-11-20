package dev.superwy.core;

import dev.superwy.protocol.*;
import java.util.*;
import java.util.function.BiConsumer;

/** Changed positions only, addressed to the authenticated requester; no other manifest is disclosed. */
public final class QueueNotifications {
    private final Store store;private final BiConsumer<String,String> send;
    private record Sent(String player,String request,String orderId,int ahead,String state){}
    private final Map<String,Sent> sent=new HashMap<>();
    public QueueNotifications(Store store,BiConsumer<String,String> send){this.store=store;this.send=send;}
    public void clear(){sent.clear();}
    public void tick(String world,String dimension){
        for(String id:store.drainNoticeArrivals()){
            var order=store.order(id);if(order==null||!order.terminal()||sent.containsKey(id)||!Objects.equals(world,order.world)||!Objects.equals(dimension,order.dimension)||!WhisperProtocol.name(order.request.notifyPlayer))continue;
            String request=order.request.batchId!=null?order.request.batchId:order.clientRequestId;
            if(request!=null&&request.matches("[a-f0-9]{32}"))send(new Sent(order.request.notifyPlayer,request,id,0,order.state));
        }
        var pending=store.pendingOrders();var remaining=new HashSet<>(sent.keySet());
        for(var order:pending){
            if(!Objects.equals(world,order.world)||!Objects.equals(dimension,order.dimension)||!WhisperProtocol.name(order.request.notifyPlayer))continue;
            String id=order.request.batchId!=null?order.request.batchId:order.clientRequestId;
            if(id==null||!id.matches("[a-f0-9]{32}"))continue;
            var position=OrderQueue.position(order,pending);var next=new Sent(order.request.notifyPlayer,id,order.orderId,position.ahead(),order.state.equals("queued")?"queued":"active");
            if(!next.equals(sent.get(order.orderId))){send(next);sent.put(order.orderId,next);}remaining.remove(order.orderId);
        }
        for(String id:remaining){var order=store.order(id);var previous=sent.get(id);
            if(order!=null&&order.terminal())send(new Sent(previous.player,previous.request,id,0,order.state));
            sent.remove(id);
        }
    }
    private void send(Sent notice){send.accept(notice.player,BatchProtocol.message(true,notice.request,"queue",notice.ahead,notice.state,notice.orderId));}
}

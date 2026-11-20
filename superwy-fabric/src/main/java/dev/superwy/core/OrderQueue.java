package dev.superwy.core;

import java.util.*;

/** FIFO within a warehouse/world/dimension; never exposes another principal's identity. */
public final class OrderQueue {
    private OrderQueue(){}
    public static final Comparator<Model.Order> ORDER=Comparator.comparingLong((Model.Order o)->o.queueSequence).thenComparing(o->o.createdAt).thenComparing(o->o.orderId);
    public record Position(long sequence,int position,int ahead,int totalPending,boolean active){}
    /** Once a backlog exceeds three, report every finished order until it drains. */
    public static final class Progress {
        private String warehouse,world,dimension;
        private boolean reporting;
        public void idle(){reporting=false;}
        public void observe(Model.Order context,int pending){
            if(!Objects.equals(warehouse,context.warehouseId)||!Objects.equals(world,context.world)||!Objects.equals(dimension,context.dimension)){
                warehouse=context.warehouseId;world=context.world;dimension=context.dimension;reporting=false;
            }
            if(pending>3)reporting=true;
        }
        public String completed(Model.Order context,int remaining){
            observe(context,remaining+1);
            String message=reporting?(remaining==0?"都取完了，我歇逼了":"还有"+remaining+"个订单在队列"):null;
            if(remaining==0)reporting=false;
            return message;
        }
    }
    public static boolean sameQueue(Model.Order a,Model.Order b){return Objects.equals(a.warehouseId,b.warehouseId)&&Objects.equals(a.world,b.world)&&Objects.equals(a.dimension,b.dimension);}
    public static List<Model.Order> pending(List<Model.Order> orders,Model.Order context){return orders.stream().filter(o->!o.terminal()&&sameQueue(o,context)).sorted(ORDER).toList();}
    public static Position position(Model.Order order,List<Model.Order> orders){
        var pending=pending(orders,order);int position=0;
        if(!order.terminal())for(int i=0;i<pending.size();i++)if(pending.get(i).orderId.equals(order.orderId)){position=i+1;break;}
        return new Position(order.queueSequence,position,Math.max(0,position-1),pending.size(),!order.terminal()&&!order.state.equals("queued"));
    }
    public static Map<String,Object> summary(Model.Order order,List<Model.Order> all){
        var row=new LinkedHashMap<String,Object>();row.put("orderId",order.orderId);row.put("state",order.state);row.put("queue",position(order,all));
        row.put("fakePlayerName",order.delivery.fakePlayerName);row.put("blockingReason",order.blockingReason);row.put("errorCode",order.errorCode);
        row.put("errorMessage",ErrorMessages.order(order));row.put("clientRequestId",order.clientRequestId);row.put("updatedAt",order.updatedAt);row.put("missingItems",List.copyOf(order.missingItems));row.put("needsItemReview",order.needsItemReview);
        row.put("requested",order.lines.stream().mapToLong(l->l.requested).sum());row.put("fetched",order.lines.stream().mapToLong(l->l.fetched).sum());row.put("delivered",order.lines.stream().mapToLong(l->l.delivered).sum());
        return row;
    }
}

package dev.superwy.core;

import java.util.*;

/** Failure notifications and shortage totals. Repeated lines share the same stock pool. */
public final class OrderFailurePolicy {
    public record Demand(String itemId,String componentHash,long count,long available){}
    private record Variant(String itemId,String hash){}
    private OrderFailurePolicy(){}
    public static List<String> missing(List<Demand> lines){
        Map<Variant,Long> demand=new LinkedHashMap<>(),stock=new HashMap<>();
        for(var line:lines){var key=new Variant(line.itemId(),line.componentHash());demand.merge(key,line.count(),Long::sum);stock.merge(key,line.available(),Math::min);}
        Set<String> missing=new LinkedHashSet<>();
        for(var entry:demand.entrySet())if(entry.getValue()>stock.getOrDefault(entry.getKey(),0L))missing.add(entry.getKey().itemId());
        return List.copyOf(missing);
    }
    private static boolean item(String id){return id!=null&&id.length()<=240&&id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+");}
    public static void interrupted(Model.Order order,boolean hasOperations){
        order.needsItemReview=order.needsItemReview||hasOperations||!order.cargo.isEmpty()||!order.packages.isEmpty()||order.lines.stream().anyMatch(line->line.fetched>0||line.delivered>0)||!"not_started".equals(order.delivery.stage);
        String reason=order.errorCode!=null?order.errorCode:order.blockingReason!=null?order.blockingReason:"RESTART_INTERRUPTED";
        order.interruptedState=order.state;order.restartInterrupted=true;
        order.errorCode=reason;order.terminationReason="EXECUTION_ERROR";order.executionStoppedAt=java.time.Instant.now().toString();order.delivery.stage="interrupted";order.state("failed",reason);
    }
    public static List<String> announcements(Model.Order order,String reason){
        if("OUT_OF_STOCK".equals(reason))return shortageAnnouncements(order);
        String id=null;
        if(order.currentLineIndex!=null&&order.currentLineIndex>=0&&order.currentLineIndex<order.lines.size())id=order.lines.get(order.currentLineIndex).itemId;
        if(!item(id))id=order.lines.stream().map(line->line.itemId).filter(OrderFailurePolicy::item).findFirst().orElse("取货任务");
        return List.of(id+" 取货失败："+ErrorMessages.message(reason));
    }
    /** Minecraft rejects chat messages longer than 256 characters. */
    static final int CHAT_LIMIT=256;
    static String clip(String text){return text.length()<=CHAT_LIMIT?text:text.substring(0,CHAT_LIMIT-1)+"…";}
    public static boolean boxShortage(String reason){return "EMPTY_SHULKER_BOX_REQUIRED".equals(reason);}
    public static String boxSupplyWarning(int empty,int needed){
        return "取盒点只有"+empty+"个空潜影盒，本单预计需要"+needed+"个；空盒用完时会先交付已装好的盒子，剩余物品存入安全箱";
    }
    /** Public report after a batch order fails: cause, what was handed over or stashed, and what is still missing. */
    public static List<String> batchFailureReport(Model.Order order,String reason){
        var out=new ArrayList<String>();
        long requested=order.lines.stream().mapToLong(l->l.requested).sum(),delivered=order.lines.stream().mapToLong(l->l.delivered).sum();
        out.add(clip("批量取货失败："+ErrorMessages.message(reason)+"；已交付"+delivered+"/"+requested+"件"));
        long boxes=order.packages.stream().filter(p->"recipient".equals(p.custody)).count();
        var names=order.delivery.recipientNames.stream().filter(OrderFailurePolicy::playerName).distinct().toList();
        if(boxes>0)out.add(clip("已把"+boxes+"盒交给 "+(names.isEmpty()?"收货假人":String.join("、",names))));
        else if(delivered>0&&!names.isEmpty())out.add(clip("已把物品交给 "+String.join("、",names)));
        if(order.stashedItems>0){
            var p=order.stashedTo;
            out.add(clip("剩余"+order.stashedItems+"件已存回安全箱"+(p==null?"":" "+p.x()+","+p.y()+","+p.z())));
        }
        if(order.salvageErrorCode!=null)out.add(clip("收尾未完成："+ErrorMessages.message(order.salvageErrorCode)+"；剩余物品留在机器人身上，请核对"));
        Map<String,Long> missing=new LinkedHashMap<>();
        for(var line:order.lines)if(item(line.itemId)&&line.delivered<line.requested)missing.merge(line.itemId,(long)line.requested-line.delivered,Long::sum);
        if(!missing.isEmpty()){
            var text=new StringBuilder("未交付：");boolean first=true;
            for(var entry:missing.entrySet()){
                String part=(first?"":"、")+entry.getKey().replace("minecraft:","")+"×"+entry.getValue();
                if(text.length()+part.length()>CHAT_LIMIT-12){text.append(" 等").append(missing.size()).append("项");break;}
                text.append(part);first=false;
            }
            out.add(clip(text.toString()));
        }
        return out;
    }
    private static boolean playerName(String name){return name!=null&&name.matches("[A-Za-z0-9_]{1,16}");}
    /**
     * Final report once the whole task has run: items with nothing delivered, items that came up short (and by how
     * much), and items left out for other reasons. Repeated lines of one item are merged; messages stay under the
     * chat limit and never split an entry.
     */
    public static List<String> shortageAnnouncements(Model.Order order){
        Map<String,long[]> totals=new LinkedHashMap<>();Map<String,String> otherReason=new LinkedHashMap<>();
        for(var line:order.lines){
            if(!item(line.itemId))continue;
            var t=totals.computeIfAbsent(line.itemId,k->new long[2]);t[0]+=line.requested;t[1]+=line.delivered;
            if(line.delivered<line.requested&&line.errorCode!=null&&!"OUT_OF_STOCK".equals(line.errorCode))otherReason.putIfAbsent(line.itemId,line.errorCode);
        }
        var none=new ArrayList<String>();var partial=new ArrayList<String>();var other=new ArrayList<String>();
        for(var e:totals.entrySet()){
            long requested=e.getValue()[0],delivered=e.getValue()[1],missing=requested-delivered;if(missing<=0)continue;
            String name=e.getKey().replace("minecraft:","");
            boolean stock=order.missingItems.contains(e.getKey());
            if(!stock&&otherReason.containsKey(e.getKey()))other.add(name+"×"+missing+"（"+ErrorMessages.message(otherReason.get(e.getKey()))+"）");
            else if(!stock)continue;
            else if(delivered==0)none.add(name+"×"+missing);
            else partial.add(name+" 缺"+missing+"（已交付"+delivered+"）");
        }
        var out=new ArrayList<String>();
        group(out,"完全缺货：",none);group(out,"数量不足：",partial);group(out,"未取到：",other);
        return out;
    }
    private static void group(List<String> out,String title,List<String> entries){
        if(entries.isEmpty())return;
        var text=new StringBuilder(title);
        for(String entry:entries){
            String part=(text.length()==title.length()?"":"、")+entry;
            if(text.length()+part.length()>CHAT_LIMIT){out.add(clip(text.toString()));text=new StringBuilder(title.replace("：","（续）："));part=entry;}
            text.append(part);
        }
        out.add(clip(text.toString()));
    }
}

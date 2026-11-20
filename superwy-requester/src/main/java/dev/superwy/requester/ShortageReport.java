package dev.superwy.requester;

import java.util.*;
import java.util.function.Function;

/** Final per-task summary for the player: nothing delivered, delivered short (by how much), and other skips. */
public final class ShortageReport {
    public record Line(String itemId,int requested,int delivered,String errorCode){}
    private ShortageReport(){}
    public static List<String> messages(List<Line> lines,Function<String,String> name){
        Map<String,int[]> totals=new LinkedHashMap<>();Map<String,String> reasons=new HashMap<>();
        for(var l:lines){
            if(l.itemId()==null)continue;
            var t=totals.computeIfAbsent(l.itemId(),k->new int[2]);t[0]+=l.requested();t[1]+=l.delivered();
            if(l.delivered()<l.requested()&&l.errorCode()!=null&&!"OUT_OF_STOCK".equals(l.errorCode()))reasons.putIfAbsent(l.itemId(),l.errorCode());
        }
        var none=new ArrayList<String>();var partial=new ArrayList<String>();var other=new ArrayList<String>();
        for(var e:totals.entrySet()){
            int requested=e.getValue()[0],delivered=e.getValue()[1],missing=requested-delivered;if(missing<=0)continue;
            String label=name.apply(e.getKey());
            if(reasons.containsKey(e.getKey()))other.add(label+" ×"+missing);
            else if(delivered==0)none.add(label+" ×"+missing);
            else partial.add(label+" 缺 "+missing+"（已交付 "+delivered+"）");
        }
        var out=new ArrayList<String>();
        if(!none.isEmpty())out.add("完全缺货："+String.join("、",none));
        if(!partial.isEmpty())out.add("数量不足："+String.join("、",partial));
        if(!other.isEmpty())out.add("未取到（取货异常，已跳过）："+String.join("、",other));
        return out;
    }
}

package dev.superwy.core;

import java.time.Instant;
import java.util.*;

/** Bounded dashboard summaries; inventory trees and transfer evidence are loaded only on demand. */
public final class DashboardData {
    public static final int HISTORY_LIMIT=10;
    private DashboardData(){}
    public static List<Map<String,Object>> orders(List<Model.Order> all){
        var pending=all.stream().filter(o->!o.terminal()).sorted(OrderQueue.ORDER).toList();
        var visible=new ArrayList<>(pending);
        visible.addAll(all.stream().filter(Model.Order::terminal).sorted(Comparator.comparing((Model.Order o)->Instant.parse(o.updatedAt)).thenComparing(OrderQueue.ORDER).reversed()).limit(HISTORY_LIMIT).toList());
        return visible.stream().map(o->OrderQueue.summary(o,pending)).toList();
    }
    public static Map<String,Object> scan(Map<String,Object> full){
        var result=new LinkedHashMap<>(full);
        Object positions=result.remove("remainingContainers");
        if(positions instanceof Collection<?> list)result.put("remainingContainerCount",list.size());
        Object failures=result.get("failures");
        if(failures instanceof List<?> list)result.put("failures",List.copyOf(list.subList(Math.max(0,list.size()-8),list.size())));
        return Collections.unmodifiableMap(result);
    }
}

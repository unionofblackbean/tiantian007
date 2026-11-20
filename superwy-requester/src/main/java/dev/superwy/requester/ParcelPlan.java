package dev.superwy.requester;

import java.util.*;

/**
 * Local estimate of how the robot will pack a projection order. Pure logic, no Minecraft classes.
 * Mirrors the robot's packing: manifest lines are fetched in order, every item tops up a partial stack of the
 * same item in the current box before taking a new slot, a full box (27 slots) is closed and the next one opened.
 * Requested shulker boxes cannot go inside a box, so they travel loose after the parcels.
 */
public final class ParcelPlan {
    public static final int BOX_SLOTS=27,RECIPIENT_SLOTS=36,MAX_LINES=128,MAX_TOTAL=1_000_000;
    public record Line(String itemId,int count,int stackSize,boolean shulker){
        public Line{
            if(itemId==null||count<1||stackSize<1)throw new IllegalArgumentException("材料数据无效");
        }
        public int slots(){return (count+stackSize-1)/stackSize;}
    }
    public record Stack(String itemId,int count){}
    /** One parcel: up to 27 stacks, listed slot by slot. */
    public record Box(int index,List<Stack> slots){
        public int units(){return slots.stream().mapToInt(Stack::count).sum();}
        /** Item totals inside this box, in first-seen order. */
        public Map<String,Integer> totals(){var m=new LinkedHashMap<String,Integer>();for(var s:slots)m.merge(s.itemId(),s.count(),Integer::sum);return m;}
    }
    public record Recipient(String name,int boxes,int loose){}
    public record Plan(List<Line> lines,List<Box> boxes,List<Stack> loose,List<Recipient> recipients){
        public int units(){return lines.stream().mapToInt(Line::count).sum();}
        public int looseUnits(){return loose.stream().mapToInt(Stack::count).sum();}
    }
    private ParcelPlan(){}

    /** Groups related materials together (alphabetical by item path, then namespace); shulker items last. */
    public static List<Line> order(Collection<Line> lines){
        var merged=new LinkedHashMap<String,Line>();
        for(var l:lines)merged.merge(l.itemId(),l,(a,b)->new Line(a.itemId(),Math.addExact(a.count(),b.count()),a.stackSize(),a.shulker()));
        var list=new ArrayList<>(merged.values());
        list.sort(Comparator.comparing(Line::shulker).thenComparing(l->path(l.itemId())).thenComparing(Line::itemId));
        return List.copyOf(list);
    }
    private static String path(String id){int i=id.indexOf(':');return i<0?id:id.substring(i+1);}

    public static void validate(List<Line> lines){
        if(lines.isEmpty())throw new IllegalArgumentException("没有需要取货的材料");
        if(lines.size()>MAX_LINES)throw new IllegalArgumentException("材料种类 "+lines.size()+" 超过单单上限 "+MAX_LINES+" 种，请缩小选区或排除部分材料");
        long total=lines.stream().mapToLong(Line::count).sum();
        if(total>MAX_TOTAL)throw new IllegalArgumentException("材料总数 "+total+" 超过单单上限 "+MAX_TOTAL+" 件，请缩小选区");
    }

    /** @param lines already ordered with {@link #order(Collection)}; the same order is sent to the robot. */
    public static Plan plan(List<Line> lines,String recipient){
        validate(lines);
        List<Box> boxes=new ArrayList<>();List<Stack> loose=new ArrayList<>();
        List<int[]> open=null;List<String> openIds=null;
        for(var line:lines){
            if(line.shulker()){
                // Shulkers do not stack beyond their own max size and are handed over one slot at a time.
                for(int left=line.count();left>0;){int n=Math.min(left,line.stackSize());loose.add(new Stack(line.itemId(),n));left-=n;}
                continue;
            }
            int left=line.count();
            while(left>0){
                if(open==null){open=new ArrayList<>();openIds=new ArrayList<>();}
                int slot=-1;
                for(int i=0;i<open.size();i++)if(openIds.get(i).equals(line.itemId())&&open.get(i)[0]<line.stackSize()){slot=i;break;}
                if(slot<0&&open.size()<BOX_SLOTS){open.add(new int[]{0});openIds.add(line.itemId());slot=open.size()-1;}
                if(slot<0){boxes.add(box(boxes.size(),open,openIds));open=null;continue;}
                int n=Math.min(left,line.stackSize()-open.get(slot)[0]);open.get(slot)[0]+=n;left-=n;
            }
        }
        if(open!=null&&!open.isEmpty())boxes.add(box(boxes.size(),open,openIds));
        return new Plan(lines,List.copyOf(boxes),List.copyOf(loose),recipients(recipient,boxes.size(),loose.size()));
    }
    private static Box box(int index,List<int[]> counts,List<String> ids){
        var slots=new ArrayList<Stack>();for(int i=0;i<counts.size();i++)slots.add(new Stack(ids.get(i),counts.get(i)[0]));
        return new Box(index,List.copyOf(slots));
    }
    /** Assumes freshly spawned (empty) couriers; boxes are delivered before the loose shulkers. */
    static List<Recipient> recipients(String base,int boxes,int loose){
        var out=new ArrayList<Recipient>();int total=boxes+loose,given=0;
        for(int i=0;given<total;i++){
            int take=Math.min(RECIPIENT_SLOTS,total-given);int b=Math.max(0,Math.min(take,boxes-given));
            out.add(new Recipient(recipientName(base,i),b,take-b));given+=take;
        }
        return List.copyOf(out);
    }
    /** Same suffix rule as the robot: base, base_1, base_2 … shortened to 16 characters. */
    public static String recipientName(String base,int index){
        if(index==0)return base;
        String suffix="_"+index;return base.substring(0,Math.min(base.length(),16-suffix.length()))+suffix;
    }
}

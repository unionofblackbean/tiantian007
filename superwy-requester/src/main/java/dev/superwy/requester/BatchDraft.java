package dev.superwy.requester;

import java.util.*;

/** Local manifest and validated progress. No transport retry can create a second order. */
public final class BatchDraft {
    public record Entry(String item,int count,int slots){}
    public final List<Entry> items=new ArrayList<>();
    public final Map<Integer,String> skipped=new LinkedHashMap<>();
    public String id,bot,recipient,key,status="",orderId;
    public int sent,completed;public long deadline,nextSend,hideAt;
    public boolean submitted,accepted;
    /** Litematica area order: robot always delivers shulker parcels. */
    public boolean parcels;public int plannedBoxes;
    public int parcelCount,fetchedUnits,requestedUnits;
    /** Stock pre-check before the manifest is submitted; any edit invalidates it. */
    public boolean stockChecking,stockChecked;
    public final Map<String,Long> available=new HashMap<>();
    public void resetStock(){stockChecking=false;stockChecked=false;available.clear();}
    /** Units still missing for the item at this index, after earlier lines of the same item took their share. */
    public long missing(int index){
        if(!stockChecked||!available.containsKey(items.get(index).item()))return 0;
        String item=items.get(index).item();long left=available.get(item);
        for(int i=0;i<index;i++)if(items.get(i).item().equals(item))left-=items.get(i).count();
        return Math.max(0,items.get(index).count()-Math.max(0,left));
    }
    public long missingKinds(){return java.util.stream.IntStream.range(0,items.size()).filter(i->missing(i)>0).mapToObj(i->items.get(i).item()).distinct().count();}
    public boolean editing(){return id!=null&&!submitted&&hideAt==0;}
    public boolean active(){return id!=null&&hideAt==0;}
    public void add(String item,int count,int stackSize){
        if(!editing())throw new IllegalArgumentException("批量正在处理，请等待完成");
        int slots=(count+stackSize-1)/stackSize;
        if(count<1||count>1_000_000||stackSize<1||items.size()>=128||items.stream().mapToLong(e->e.count()).sum()+count>1_000_000)throw new IllegalArgumentException("批量清单已达到容量上限");
        items.add(new Entry(item,count,slots));resetStock();
    }
    public int used(){return items.stream().mapToInt(Entry::slots).sum();}
    // Legacy replies are validated but never limit or clear the local manifest.
    public void capacity(int value){if(value<0||value>128)throw new IllegalArgumentException("容量回复无效");}
    public void remove(int index){
        if(!editing())throw new IllegalArgumentException("已提交的清单不能修改");
        if(index<0||index>=items.size())throw new IllegalArgumentException("材料已不存在");
        items.remove(index);resetStock();
    }
    public boolean progress(int value,int total){if(total!=items.size()||value<completed||value>total||value<0)return false;completed=value;return true;}
    public boolean skip(int index,String reason){if(index<0||index>=items.size()||reason==null||!reason.matches("[A-Z_]{1,64}"))return false;skipped.putIfAbsent(index,reason);return true;}
}

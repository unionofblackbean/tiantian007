package dev.superwy.requester;

/** One submitted task, independent of whichever batch draft is being edited now. */
public final class RequestTask {
    public final String id,label,bot;public String orderId,state="submitting";public int ahead=-1;
    public BatchDraft batch;public RequesterConfig http;public boolean polling;public long nextPoll,hideAt;
    /** Super-dispatch: robots currently working on this order, and its unit progress. */
    public int robots,fetched,requested;
    /** The end-of-task shortage summary has been shown. */
    public boolean reported;
    public RequestTask(String id,String label,String bot){this.id=id;this.label=label;this.bot=bot;}
    public void update(int ahead,String state,long now){
        if(hideAt>0)return;
        this.ahead=Math.max(0,ahead);this.state=state;
        if(java.util.Set.of("stored_offline","partial_stored_offline","failed","cancelled").contains(state))hideAt=now+15000;
    }
    public String hudStatus(){
        if(state.equals("queued"))return "你在取货排队中，前方还有 "+Math.max(0,ahead)+" 个任务";
        if(hideAt==0&&robots>1)return "超级调度："+robots+" 台机器人执行中（已取 "+fetched+"/"+requested+" 件）";
        if(hideAt==0&&!state.equals("submitting")&&!state.equals("unconfirmed")&&batch!=null){
            int fetched=batch.requestedUnits>0?batch.fetchedUnits:Math.max(0,batch.completed-batch.skipped.size());
            int total=batch.requestedUnits>0?batch.requestedUnits:batch.items.size();
            if(batch.parcels)return batch.requestedUnits>0?"投影取货执行中（"+batch.fetchedUnits+"/"+batch.requestedUnits+" 件 · 携带 "+batch.parcelCount+" 盒）":"投影取货执行中（"+fetched+"/"+total+" 项）";
            return "批量任务执行中（"+fetched+"/"+total+"）";
        }
        return status();
    }
    public String status(){
        if(state.equals("queued"))return "队列中，前方还有 "+Math.max(0,ahead)+" 个任务";
        if(batch!=null&&batch.hideAt>0)return batch.status;
        return switch(state){case "submitting"->"正在提交…";case "unconfirmed"->"未收到回执，请检查机器人";case "stored_offline"->"已交付";case "partial_stored_offline"->"已交付可取材料（含跳过项）";case "failed"->"任务已结束，请查看聊天原因";case "cancelled"->"已取消";
            default->batch==null?"正在取货 / 交付":batch.status;
        };
    }
}

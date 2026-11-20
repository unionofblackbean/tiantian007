package dev.superwy.requester;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class RequestTaskTest {
    @Test void oneLineHudUsesQueuePositionAndFetchedUnits(){
        var task=new RequestTask("hud","批量","Bot");task.batch=new BatchDraft();task.batch.id="a".repeat(32);task.batch.add("minecraft:stone",128,64);
        task.update(3,"queued",1);assertEquals("你在取货排队中，前方还有 3 个任务",task.hudStatus());
        task.update(0,"active",2);assertEquals("批量任务执行中（0/1）",task.hudStatus());
        task.batch.completed=1;assertEquals("批量任务执行中（1/1）",task.hudStatus());
        task.batch.skipped.put(0,"OUT_OF_STOCK");assertEquals("批量任务执行中（0/1）",task.hudStatus());
        task.batch.fetchedUnits=64;task.batch.requestedUnits=128;assertEquals("批量任务执行中（64/128）",task.hudStatus());
        task.update(0,"stored_offline",3);assertEquals("已交付",task.hudStatus());
    }

    @Test void queuedCountAdvancesAndTerminalCannotBeResurrected(){
        var task=new RequestTask("id","材料", "Bot");task.update(3,"queued",100);assertEquals("队列中，前方还有 3 个任务",task.status());
        task.update(1,"queued",200);assertTrue(task.status().contains("1 个任务"));task.update(0,"active",300);assertFalse(task.status().contains("队列中"));
        task.update(0,"stored_offline",400);task.update(2,"queued",500);assertEquals("已交付",task.status());assertEquals(15400,task.hideAt);
    }
    @Test void submittedBatchStateDoesNotDependOnTheNewestDraft(){
        var first=new RequestTask("first","批量", "Bot");first.batch=new BatchDraft();first.batch.status="正在批量取货";first.update(0,"active",1);
        var second=new RequestTask("second","批量", "Bot");second.batch=new BatchDraft();second.update(2,"queued",1);
        assertEquals("正在批量取货",first.status());assertTrue(second.status().contains("2 个任务"));first.update(0,"stored_offline",2);assertTrue(second.status().contains("队列中"));
    }
    @Test void finalShortageReportGroupsItems(){
        var lines=java.util.List.of(new ShortageReport.Line("minecraft:stone",64,0,"OUT_OF_STOCK"),new ShortageReport.Line("minecraft:dirt",100,40,"OUT_OF_STOCK"),
            new ShortageReport.Line("minecraft:dirt",20,0,null),new ShortageReport.Line("minecraft:glass",10,0,"PATH_UNREACHABLE"),new ShortageReport.Line("minecraft:sand",5,5,null));
        var report=ShortageReport.messages(lines,id->id.replace("minecraft:",""));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("完全缺货：stone ×64","数量不足：dirt 缺 80（已交付 40）","未取到（取货异常，已跳过）：glass ×10"),report);
        org.junit.jupiter.api.Assertions.assertTrue(ShortageReport.messages(java.util.List.of(new ShortageReport.Line("minecraft:sand",5,5,null)),id->id).isEmpty());
    }
}

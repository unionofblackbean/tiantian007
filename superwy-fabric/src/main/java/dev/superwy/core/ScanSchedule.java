package dev.superwy.core;

/** Wall-clock schedule derived from durable scan timestamps, independent of process uptime. */
public final class ScanSchedule {
    public static final int DEFAULT_INTERVAL_SECONDS=3600;
    public static final int SERVUX_INTERVAL_SECONDS=600;
    private ScanSchedule(){}
    public static int intervalSeconds(Model.Region region,String protocol){return "servux".equals(protocol)?SERVUX_INTERVAL_SECONDS:region.intervalSeconds;}
    public static long nextDue(Model.Region region,ScanProgress progress){
        return nextDue(region,progress,"f3");
    }
    public static long nextDue(Model.Region region,ScanProgress progress,String protocol){
        if(progress!=null&&progress.matches(region)&&!progress.finished)return 0;
        long completed=Math.max(region.lastScanAt,region.lastSuccessAt);
        return completed==0?0:completed+intervalSeconds(region,protocol)*1000L;
    }
    public static void upgrade(Config config,Store store)throws Exception{
        if(config.scanPolicyVersion>=1)return;
        for(var region:store.regions()){
            region.intervalSeconds=DEFAULT_INTERVAL_SECONDS;store.saveRegion(region).join();
            var progress=store.scan(region.id);
            if(progress!=null&&progress.matches(region)){progress.nextAttemptAt=nextDue(region,progress);store.saveScan(progress).join();}
        }
        config.scanPolicyVersion=1;
    }
}

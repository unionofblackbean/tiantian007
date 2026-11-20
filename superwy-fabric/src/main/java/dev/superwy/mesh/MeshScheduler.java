package dev.superwy.mesh;

import java.util.*;
import java.util.function.LongSupplier;

/**
 * Authority-side dispatch state. Pure logic: no game, network or storage access, so it can be tested directly.
 *
 * Rules:
 *  - a robot gets new work only when it holds no unfinished block;
 *  - simple jobs (single pickups) go first, oldest first, one whole job per robot;
 *  - batch jobs run strictly in arrival order; the next batch starts only after every part of the previous one
 *    has been handed out, and is cut lazily into blocks, so a robot that finishes early takes the next block and a
 *    simple job waiting in line is served at the next block boundary;
 *  - a robot that stops reporting keeps its block for {@code reassignAfterSeconds}; after that the undelivered
 *    remainder goes back into the pool. When it returns it continues the block (only what it carries, if the rest
 *    was already handed to others).
 */
public final class MeshScheduler {
    // ---------- persisted state ----------
    public static final class JobLine {
        public int lineIndex,requested,maxStack=64,unassigned;
        /** Times undelivered parts of this line were handed out again after a robot-side failure. */
        public int retries;
        public String itemId,componentHash;
        public boolean shulker;
        public int fetched,delivered,shortfall;
        public boolean skipped;
        public String errorCode;
    }
    public static final class Job {
        public String orderId,recipient,stationId="main",state="queued";
        public boolean batch,parcels,packing;
        public long seq,createdAt;
        public List<JobLine> lines=new ArrayList<>();
        public List<String> chunks=new ArrayList<>();
        public boolean open(){return state.equals("queued")||state.equals("active");}
    }
    public static final class ChunkLine {
        public int lineIndex,count,fetched,delivered,shortfall,returned;
        public String itemId,componentHash,errorCode;
        public boolean skipped;
    }
    public static final class Chunk {
        public String chunkId,orderId,robot,recipient,stationId="main",state="assigned",errorCode;
        public boolean batch,parcels,packing,orphaned,heldOnly,cancelRequested,needsReview;
        public long assignedAt,lastReportAt,offlineSince;
        public List<ChunkLine> lines=new ArrayList<>();
        public boolean terminal(){return state.equals("done")||state.equals("failed")||state.equals("cancelled");}
    }
    public static final class State {
        public long nextSeq=1;
        public List<Job> jobs=new ArrayList<>();
        public Map<String,Chunk> chunks=new LinkedHashMap<>();
    }
    // ---------- wire ----------
    public static final class LineReport {public int fetched,delivered,shortfall;public boolean skipped;public String errorCode;}
    public static final class ChunkReport {
        public String chunkId,state,errorCode;
        /** terminal: the robot's local order(s) for this block have ended. */
        public boolean terminal,running,needsReview;
        /** Ended by a disconnect or restart. resumable: the robot can safely pick it up again. */
        public boolean interrupted,resumable;
        public List<LineReport> lines=new ArrayList<>();
    }
    public static final class Report {
        public String robot,using,lockWanted;
        public boolean ready;
        public int freeSlots;
        // Shown on the dispatch page only.
        public String activity,dimension,version;
        public boolean paused;
        public int x,y,z;
        public List<ChunkReport> chunks=new ArrayList<>();
    }
    public static final class Resume {public String chunkId;public boolean heldOnly;public Resume(){}public Resume(String id,boolean held){chunkId=id;heldOnly=held;}}
    public static final class Reply {
        public Chunk assign;
        public List<String> cancel=new ArrayList<>(),forget=new ArrayList<>(),avoid=new ArrayList<>();
        public List<Resume> resume=new ArrayList<>();
        public boolean lockGranted;
        public String lockHolder;
    }
    public record RobotView(String name,boolean online,boolean ready,boolean paused,int freeSlots,String activity,String position,String version,
                            String chunkId,String orderId,int blockDelivered,int blockCount,boolean orphaned,long lastSeenMillisAgo){}
    public record JobView(String orderId,String kind,String state,int ahead,int robots,long requested,long delivered,long shortfall,int remaining,int blocks,long createdAt){}
    public record LockView(String name,String robot){}

    private static final class Robot {String name,using,lockWanted,activity,dimension,version;long lastSeen;boolean ready,paused;int freeSlots,x,y,z;}
    /** Recent dispatch decisions for the debug page; bounded and never persisted. */
    private final ArrayDeque<String> events=new ArrayDeque<>();
    private void event(String text){if(events.size()>=200)events.removeFirst();events.addLast(java.time.Instant.ofEpochMilli(clock.getAsLong())+" "+text);}
    public List<String> events(){return List.copyOf(events);}
    private final MeshSettings settings;private final LongSupplier clock;
    private State state=new State();
    private final Map<String,Robot> robots=new LinkedHashMap<>();
    private final Map<String,String> locks=new HashMap<>();
    private boolean dirty;
    public MeshScheduler(MeshSettings settings,LongSupplier clock){this.settings=settings;this.clock=clock;}
    public MeshScheduler(MeshSettings settings){this(settings,System::currentTimeMillis);}

    public void load(State loaded){
        state=loaded==null?new State():loaded;
        if(state.jobs==null)state.jobs=new ArrayList<>();if(state.chunks==null)state.chunks=new LinkedHashMap<>();
        robots.clear();locks.clear();
    }
    public State state(){return state;}
    /** True once since the last call when something worth saving changed. */
    public boolean takeDirty(){boolean d=dirty;dirty=false;return d;}
    public boolean has(String orderId){return job(orderId)!=null;}
    public Job job(String orderId){for(var j:state.jobs)if(j.orderId.equals(orderId))return j;return null;}
    public List<Job> jobs(){return Collections.unmodifiableList(state.jobs);}
    public List<Chunk> chunksOf(Job job){var out=new ArrayList<Chunk>();for(String id:job.chunks){var c=state.chunks.get(id);if(c!=null)out.add(c);}return out;}

    public void submit(Job job){
        job.seq=state.nextSeq++;job.createdAt=clock.getAsLong();job.state="queued";
        for(var line:job.lines)line.unassigned=line.requested;
        state.jobs.add(job);dirty=true;
    }
    public void cancel(String orderId){
        var job=job(orderId);if(job==null||!job.open())return;
        job.state="cancelled";for(var line:job.lines)line.unassigned=0;event("任务 "+shortId(orderId)+" 已取消");
        for(var c:chunksOf(job))if(!c.terminal())c.cancelRequested=true;
        dirty=true;
    }
    /** Forget finished jobs once the authority has written their final result. */
    public void prune(String orderId){
        var job=job(orderId);if(job==null||job.open())return;
        for(var c:chunksOf(job))if(!c.terminal())return;
        for(String id:job.chunks)state.chunks.remove(id);
        state.jobs.remove(job);dirty=true;
    }

    public Reply heartbeat(Report report){
        long now=clock.getAsLong();var reply=new Reply();
        Robot robot=robots.computeIfAbsent(report.robot,k->{var r=new Robot();r.name=k;return r;});
        if(robot.lastSeen==0||now-robot.lastSeen>=settings.offlineAfterSeconds*1000L)event(report.robot+" 上线");
        robot.lastSeen=now;robot.ready=report.ready;robot.freeSlots=report.freeSlots;robot.using=report.using;robot.lockWanted=report.lockWanted==null?null:report.lockWanted.toLowerCase(Locale.ROOT);
        robot.activity=report.activity;robot.dimension=report.dimension;robot.version=report.version;robot.paused=report.paused;robot.x=report.x;robot.y=report.y;robot.z=report.z;
        Set<String> reported=new HashSet<>();
        for(var cr:report.chunks){
            if(cr==null||cr.chunkId==null)continue;reported.add(cr.chunkId);
            Chunk c=state.chunks.get(cr.chunkId);
            if(c==null||!report.robot.equals(c.robot)||c.terminal()){reply.forget.add(cr.chunkId);continue;}
            c.lastReportAt=now;c.offlineSince=0;dirty=true;
            progress(c,cr);
            if(!cr.terminal){
                if(cr.running&&c.state.equals("assigned"))c.state="running";
                if(c.cancelRequested)reply.cancel.add(c.chunkId);
                continue;
            }
            Job job=job(c.orderId);
            if(cr.interrupted&&cr.resumable&&!c.cancelRequested&&job!=null){
                // Continue the same block. If others already took the remainder, only deliver what is carried.
                c.heldOnly=c.orphaned;c.state="running";reply.resume.add(new Resume(c.chunkId,c.orphaned));
                event(report.robot+" 继续任务块 "+shortId(c.chunkId)+(c.orphaned?"（只交付身上的货）":""));continue;
            }
            finish(c,cr,job);reply.forget.add(c.chunkId);
        }
        for(var c:state.chunks.values()){
            if(c.terminal()||!report.robot.equals(c.robot)||reported.contains(c.chunkId))continue;
            if(c.state.equals("assigned")&&!c.orphaned&&reply.assign==null){reply.assign=c;continue;}
            // The robot no longer knows this block (e.g. its record was lost): treat it as interrupted.
            if(now-Math.max(c.lastReportAt,c.assignedAt)>settings.offlineAfterSeconds*1000L){
                var lost=new ChunkReport();lost.chunkId=c.chunkId;lost.terminal=true;lost.interrupted=true;lost.state="failed";lost.errorCode="MESH_BLOCK_LOST";lost.needsReview=true;
                finish(c,lost,job(c.orderId));
            }
        }
        if(robot.lockWanted!=null){
            String holder=locks.get(robot.lockWanted);
            if(holder==null||holder.equals(robot.name)||!online(holder,now)){if(!robot.name.equals(holder))event(robot.name+" 获得收货假人 "+robot.lockWanted);locks.put(robot.lockWanted,robot.name);reply.lockGranted=true;}
            else reply.lockHolder=holder;
        }
        locks.entrySet().removeIf(e->e.getValue().equals(robot.name)&&!e.getKey().equals(robot.lockWanted));
        for(var other:robots.values())if(other!=robot&&other.using!=null&&online(other.name,now))reply.avoid.add(other.using);
        if(reply.assign==null&&robot.ready&&idle(robot.name))reply.assign=next(robot,now);
        return reply;
    }

    public void tick(){
        long now=clock.getAsLong();
        for(var c:state.chunks.values()){
            if(c.terminal())continue;
            if(online(c.robot,now)){if(c.offlineSince!=0){c.offlineSince=0;dirty=true;}continue;}
            if(c.offlineSince==0){c.offlineSince=now;dirty=true;}
            if(!c.orphaned&&now-c.offlineSince>=settings.reassignAfterSeconds*1000L){
                Job job=job(c.orderId);c.orphaned=true;dirty=true;event(c.robot+" 掉线超时，任务块 "+shortId(c.chunkId)+" 的未交付部分改派");
                if(c.state.equals("assigned")){c.state="cancelled";c.errorCode="MESH_ROBOT_OFFLINE";}
                if(job!=null&&job.open()&&!c.cancelRequested)for(var line:c.lines)giveBack(job,line,Math.max(0,line.count-line.delivered));
            }
        }
        locks.entrySet().removeIf(e->!online(e.getValue(),now));
        for(var job:state.jobs){
            if(!job.open()||remaining(job)>0)continue;
            boolean busy=false;for(var c:chunksOf(job))if(!c.terminal()&&!c.orphaned)busy=true;
            if(!busy&&!job.chunks.isEmpty()){job.state="done";dirty=true;event("任务 "+shortId(job.orderId)+" 完成");}
        }
        for(var job:state.jobs)aggregate(job);
        // Release memory: robots gone for 10 minutes, and blocks whose robot never came back within a day.
        robots.values().removeIf(r->now-r.lastSeen>600_000L&&idle(r.name));
        for(var c:state.chunks.values())if(!c.terminal()&&c.orphaned&&c.offlineSince>0&&now-c.offlineSince>86_400_000L){c.state="cancelled";c.errorCode="MESH_ROBOT_OFFLINE";dirty=true;}
    }

    // ---------- management (dispatch page) ----------
    /** Move a job to the front of its kind (simple jobs or batches). */
    public boolean prioritize(String orderId){
        var job=job(orderId);if(job==null||!job.open())return false;
        long first=state.jobs.stream().filter(j->j.batch==job.batch).mapToLong(j->j.seq).min().orElse(job.seq);
        if(first<job.seq){job.seq=first-1;dirty=true;event("任务 "+shortId(orderId)+" 被置顶");}
        return true;
    }
    /** Skip the remaining wait for an offline robot's block. */
    public boolean reassignNow(String chunkId){
        var c=state.chunks.get(chunkId);long now=clock.getAsLong();
        if(c==null||c.terminal()||c.orphaned||online(c.robot,now))return false;
        c.offlineSince=now-settings.reassignAfterSeconds*1000L;tick();return true;
    }
    public boolean forgetRobot(String name){
        long now=clock.getAsLong();var r=robots.get(name);if(r==null||online(name,now))return false;
        robots.remove(name);locks.values().removeIf(name::equals);event("移除离线机器人记录 "+name);return true;
    }
    public void clearLocks(){locks.clear();event("已清除全部收货假人锁");}
    public List<LockView> locks(){return locks.entrySet().stream().map(e->new LockView(e.getKey(),e.getValue())).toList();}
    public List<JobView> jobViews(){
        var out=new ArrayList<JobView>();
        for(var j:state.jobs.stream().sorted(Comparator.comparing((Job j)->!j.open()).thenComparing(j->j.batch).thenComparingLong(j->j.seq)).toList()){
            long requested=0,delivered=0,shortfall=0;for(var l:j.lines){requested+=l.requested;delivered+=l.delivered;shortfall+=l.shortfall;}
            out.add(new JobView(j.orderId,j.parcels?"projection":j.batch?"batch":"simple",j.state,ahead(j),robotsOn(j),requested,delivered,shortfall,remaining(j),j.chunks.size(),j.createdAt));
        }
        return out;
    }
    private static String shortId(String id){return id==null?"?":id.length()>12?id.substring(id.length()-8):id;}

    public int remaining(Job job){int n=0;for(var l:job.lines)n+=l.unassigned;return n;}
    /** Player-facing queue position: simple jobs count simple jobs ahead, batches count unfinished batches ahead. */
    public int ahead(Job job){
        if(!job.state.equals("queued"))return 0;
        int n=0;
        for(var other:state.jobs){
            if(other==job||other.seq>job.seq||other.batch!=job.batch)continue;
            if(job.batch?other.open():other.state.equals("queued"))n++;
        }
        return n;
    }
    public int robotsOn(Job job){var names=new HashSet<String>();for(var c:chunksOf(job))if(!c.terminal()&&!c.orphaned)names.add(c.robot);return names.size();}
    public List<RobotView> robots(){
        long now=clock.getAsLong();var out=new ArrayList<RobotView>();
        for(var r:robots.values()){
            Chunk current=state.chunks.values().stream().filter(c->!c.terminal()&&r.name.equals(c.robot)).findFirst().orElse(null);
            int done=0,count=0;if(current!=null)for(var l:current.lines){done+=l.delivered;count+=l.count;}
            String position=r.dimension==null?null:r.dimension.replace("minecraft:","")+" "+r.x+","+r.y+","+r.z;
            out.add(new RobotView(r.name,online(r.name,now),r.ready,r.paused,r.freeSlots,r.activity,position,r.version,current==null?null:current.chunkId,current==null?null:current.orderId,done,count,current!=null&&current.orphaned,now-r.lastSeen));
        }
        return out;
    }
    public long onlineRobots(){long now=clock.getAsLong();return robots.values().stream().filter(r->online(r.name,now)).count();}

    // ---------- internals ----------
    private boolean online(String name,long now){var r=robots.get(name);return r!=null&&now-r.lastSeen<settings.offlineAfterSeconds*1000L;}
    private boolean idle(String name){for(var c:state.chunks.values())if(!c.terminal()&&name.equals(c.robot))return false;return true;}
    private Chunk next(Robot robot,long now){
        var ordered=new ArrayList<>(state.jobs);ordered.sort(Comparator.comparingLong(j->j.seq));
        for(var job:ordered)if(!job.batch&&job.open()&&remaining(job)>0)return cut(job,robot,now);
        for(var job:ordered)if(job.batch&&job.open()&&remaining(job)>0)return cut(job,robot,now);
        return null;
    }
    private Chunk cut(Job job,Robot robot,long now){
        int maxSlots=job.packing?27*settings.parcelChunkBoxes:Math.max(1,Math.min(settings.chunkSlots,robot.freeSlots-1));
        var chunk=new Chunk();chunk.chunkId=UUID.randomUUID().toString().replace("-","");chunk.orderId=job.orderId;chunk.robot=robot.name;
        chunk.recipient=job.recipient;chunk.stationId=job.stationId;chunk.batch=job.batch;chunk.parcels=job.parcels;chunk.packing=job.packing;chunk.assignedAt=now;chunk.lastReportAt=now;
        int used=0;
        for(var line:job.lines){
            if(line.unassigned<=0)continue;
            int stack=Math.max(1,line.maxStack),room=maxSlots-used;if(room<=0)break;
            int take=(int)Math.min(line.unassigned,(long)room*stack);
            var part=new ChunkLine();part.lineIndex=line.lineIndex;part.itemId=line.itemId;part.componentHash=line.componentHash;part.count=take;
            chunk.lines.add(part);line.unassigned-=take;used+=(take+stack-1)/stack;
        }
        state.chunks.put(chunk.chunkId,chunk);job.chunks.add(chunk.chunkId);job.state="active";dirty=true;
        event("派给 "+robot.name+"："+(job.batch?"批量":"单项")+" "+shortId(job.orderId)+" 的任务块 "+shortId(chunk.chunkId)+"（"+chunk.lines.size()+" 种）");
        return chunk;
    }
    private void progress(Chunk c,ChunkReport cr){
        for(int i=0;i<Math.min(c.lines.size(),cr.lines.size());i++){
            var line=c.lines.get(i);var r=cr.lines.get(i);if(r==null)continue;
            line.fetched=Math.max(0,r.fetched);line.delivered=Math.max(0,r.delivered);line.shortfall=Math.max(0,r.shortfall);line.skipped=r.skipped;line.errorCode=r.errorCode;
        }
    }
    private void finish(Chunk c,ChunkReport cr,Job job){
        progress(c,cr);c.errorCode=cr.errorCode;c.needsReview=cr.needsReview;dirty=true;
        event(c.robot+" 结束任务块 "+shortId(c.chunkId)+"："+cr.state+(cr.errorCode==null?"":" "+cr.errorCode));
        boolean success="stored_offline".equals(cr.state)||"partial_stored_offline".equals(cr.state)||"NOTHING_DELIVERED".equals(cr.errorCode);
        if(success){c.state="done";return;}
        c.state="cancelled".equals(cr.state)?"cancelled":"failed";
        // A disconnect that cannot be resumed safely, or a robot-side error: hand whatever was not delivered out
        // again instead of ending the task (up to twice per line for errors). Real shortages are not retried.
        if(c.state.equals("failed")&&!c.orphaned&&!c.cancelRequested&&job!=null&&job.open())
            for(var line:c.lines){
                if(line.skipped&&"OUT_OF_STOCK".equals(line.errorCode))continue;
                var target=job.lines.stream().filter(l->l.lineIndex==line.lineIndex).findFirst().orElse(null);
                if(target==null)continue;
                if(!cr.interrupted){if(target.retries>=2)continue;target.retries++;}
                giveBack(job,line,Math.max(0,line.count-line.delivered-line.returned));
            }
    }
    private void giveBack(Job job,ChunkLine part,int amount){
        if(amount<=0)return;
        for(var line:job.lines)if(line.lineIndex==part.lineIndex){line.unassigned+=amount;part.returned+=amount;dirty=true;return;}
    }
    private void aggregate(Job job){
        for(var line:job.lines){line.fetched=0;line.delivered=0;line.shortfall=0;line.skipped=false;}
        for(var c:chunksOf(job))for(var part:c.lines)for(var line:job.lines)if(line.lineIndex==part.lineIndex){
            line.fetched+=part.fetched;line.delivered+=part.delivered;
            if(c.terminal())line.shortfall+=Math.max(0,part.count-part.delivered-part.returned);
            if(part.skipped){line.skipped=true;line.errorCode=part.errorCode;}
            else if(c.terminal()&&c.state.equals("failed")&&part.count-part.delivered-part.returned>0&&line.errorCode==null)line.errorCode=c.errorCode;
        }
        for(var line:job.lines){line.delivered=Math.min(line.delivered,Integer.MAX_VALUE);line.shortfall=Math.min(line.shortfall,Math.max(0,line.requested-line.delivered));}
    }
}

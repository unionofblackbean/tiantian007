package dev.superwy.game;

import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.level.ClipContext;
import java.lang.reflect.*;
import java.util.*;
import org.slf4j.LoggerFactory;

/** Optional Baritone API bridge: no bundling or private pathfinder implementation. */
public final class Navigation {
    public static final double CONTAINER_REACH=10;
    private static Navigation owner;
    private Object baritone,goalProcess,pathing,goal;
    private Object rotationHandler,rotationType;
    private Constructor<?> rotationConstructor;
    private Method rotationDispatch,rotationYaw,rotationPitch;
    private BlockPos target;private Double interactionReach;
    private Vec3 start,lastProgress;
    private long startedAt,lastProgressAt,finishedAt;
    private int replans;
    private String lastError;
    private double moved;
    private long steeringUpdates;
    private final Map<Object,Object> previous=new IdentityHashMap<>();
    private final Map<Object,Object> applied=new IdentityHashMap<>();
    public boolean available(){try{Class.forName("baritone.api.BaritoneAPI",false,getClass().getClassLoader());return true;}catch(Exception e){return false;}}
    public boolean active(){return !previous.isEmpty();}
    private static Object call(Object target,String method) throws Exception{return target.getClass().getMethod(method).invoke(target);}
    public void go(BlockPos target)throws Exception{go(target,0);}
    public void go(BlockPos target,int radius)throws Exception{go(target,radius,null);}
    public void goContainer(BlockPos target,double reach)throws Exception{go(target,0,reach);}
    private void go(BlockPos target,int radius,Double containerReach)throws Exception{
        if(!available())throw new IllegalStateException("BARITONE_MISSING");
        Class<?> api=Class.forName("baritone.api.BaritoneAPI");
        Object provider=api.getMethod("getProvider").invoke(null);baritone=call(provider,"getPrimaryBaritone");
        goalProcess=call(baritone,"getCustomGoalProcess");pathing=call(baritone,"getPathingBehavior");
        // cancelEverything alone does not clear Baritone's persistent pause command.
        Object manager=call(baritone,"getCommandManager");manager.getClass().getMethod("execute",String.class).invoke(manager,"cancel");
        Object settings=api.getMethod("getSettings").invoke(null);
        set(settings,"allowBreak",false);set(settings,"allowPlace",false);set(settings,"allowParkour",true);set(settings,"allowParkourAscend",true);set(settings,"allowParkourPlace",false);set(settings,"assumeStep",false);set(settings,"allowInventory",false);set(settings,"allowBreakAnyway",new ArrayList<>());
        // Use actual client rotations, including with a released mouse in the background.
        set(settings,"freeLook",true);set(settings,"antiCheatCompatibility",false);set(settings,"blockFreeLook",true);set(settings,"smoothLook",false);set(settings,"remainWithExistingLookDirection",false);
        Minecraft mc=Minecraft.getInstance();
        if(mc.screen!=null&&mc.player.containerMenu==mc.player.inventoryMenu)mc.setScreen(null);
        BlockPos resolved=target;
        if(containerReach==null&&radius==0&&!mc.level.getBlockState(target).getCollisionShape(mc.level,target).isEmpty())resolved=target.above();
        interactionReach=containerReach;
        // Baritone evaluates block centres, while the player may still be at the cell edge.
        // Leave room for that offset and the container's actual outline before stopping.
        goal=containerReach!=null?containerGoal(target,Math.max(.5,containerReach-.9),mc.player.getEyePosition().y-mc.player.getY()):radius==0?Class.forName("baritone.api.pathing.goals.GoalBlock").getConstructor(BlockPos.class).newInstance(resolved):Class.forName("baritone.api.pathing.goals.GoalNear").getConstructor(BlockPos.class,int.class).newInstance(target,radius);
        this.target=resolved;start=lastProgress=mc.player.position();startedAt=lastProgressAt=System.currentTimeMillis();finishedAt=0;replans=0;moved=0;steeringUpdates=0;lastError=null;
        rotationHandler=call(baritone,"getGameEventHandler");Class<?> event=Class.forName("baritone.api.event.events.RotationMoveEvent"),type=Class.forName("baritone.api.event.events.RotationMoveEvent$Type");
        rotationType=type.getField("MOTION_UPDATE").get(null);rotationConstructor=event.getConstructor(type,float.class,float.class);rotationDispatch=rotationHandler.getClass().getMethod("onPlayerRotationMove",event);rotationYaw=event.getMethod("getYaw");rotationPitch=event.getMethod("getPitch");
        owner=this;requestPath();
    }
    private void requestPath()throws Exception{goalProcess.getClass().getMethod("setGoalAndPath",Class.forName("baritone.api.pathing.goals.Goal")).invoke(goalProcess,goal);}
    public boolean arrived(){
        try{Object context=call(baritone,"getPlayerContext");BlockPos feet=(BlockPos)Class.forName("baritone.api.utils.IPlayerContext").getMethod("playerFeet").invoke(context);return (boolean)Class.forName("baritone.api.pathing.goals.Goal").getMethod("isInGoal",BlockPos.class).invoke(goal,feet);}
        catch(Exception e){throw new IllegalStateException("BARITONE_GOAL_CHECK_FAILED",e);}
    }
    public boolean poll(){
        if(lastError!=null)throw new IllegalStateException(lastError);
        var player=Minecraft.getInstance().player;if(player==null)throw new IllegalStateException("DISCONNECTED");
        Vec3 position=player.position();moved=Math.max(moved,start.distanceTo(position));long now=System.currentTimeMillis();
        if(position.distanceToSqr(lastProgress)>.04||manualInputActive()){lastProgress=position;lastProgressAt=now;}
        if(arrived()&&player.onGround()&&(interactionReach==null||containerHit(target,interactionReach)!=null))return true;
        if(now-lastProgressAt>6000&&replans==0&&!calculating())try{
            call(baritone,"getCommandManager").getClass().getMethod("execute",String.class).invoke(call(baritone,"getCommandManager"),"cancel");requestPath();replans++;
        }catch(Exception e){throw new IllegalStateException("BARITONE_REPLAN_FAILED",e);}
        if(now-lastProgressAt>14000&&!calculating())throw new IllegalStateException("NAVIGATION_NOT_MOVING");
        return false;
    }
    private boolean calculating(){try{return ((Optional<?>)call(pathing,"getInProgress")).isPresent();}catch(Exception e){return false;}}
    private Object rotation(float yaw,float pitch)throws Exception{Object event=rotationConstructor.newInstance(rotationType,yaw,pitch);rotationDispatch.invoke(rotationHandler,event);return event;}
    /** Reuse Baritone's current movement target; never steer directly at the final goal. */
    public static float movementYaw(float original){
        Navigation current=owner;var player=Minecraft.getInstance().player;if(externalFix()||current==null||!current.active()||player==null)return original;
        try{Object event=current.rotation(original,player.getXRot());float yaw=((Number)current.rotationYaw.invoke(event)).floatValue();current.steeringUpdates++;return yaw;}
        catch(Exception e){current.lastError="BARITONE_STEERING_FAILED";return original;}
    }
    private static boolean externalFix(){return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("baritone_steering_fix");}
    /** Physical directions may hold against a wall; this is not an automatic-path stall. */
    public static boolean manualInputActive(){var mc=Minecraft.getInstance();if(mc.player==null||mc.screen!=null||!mc.isWindowActive())return false;var o=mc.options;return o.keyUp.isDown()||o.keyDown.isDown()||o.keyLeft.isDown()||o.keyRight.isDown();}
    public static void mergeManualInput(){if(!externalFix()&&owner!=null&&owner.active()){var p=Minecraft.getInstance().player;if(p!=null)SharedInput.merge(movementYaw(p.getYRot()));}}
    public Map<String,Object> status(){
        Map<String,Object> result=new LinkedHashMap<>();result.put("state",active()?(calculating()?"calculating":"moving"):lastError!=null?"failed":target==null?"idle":"stopped");
        result.put("target",target==null?null:List.of(target.getX(),target.getY(),target.getZ()));result.put("movedBlocks",Math.round(moved*100)/100.0);result.put("replans",replans);result.put("steeringUpdates",steeringUpdates);result.put("error",lastError);result.put("errorMessage",lastError==null?null:dev.superwy.core.ErrorMessages.message(lastError));
        var player=Minecraft.getInstance().player;result.put("position",player==null?null:List.of(player.getX(),player.getY(),player.getZ()));result.put("yaw",player==null?null:player.getYRot());result.put("elapsedSeconds",startedAt==0?0:((finishedAt==0?System.currentTimeMillis():finishedAt)-startedAt)/1000.0);
        try{result.put("pathing",pathing!=null&&Boolean.TRUE.equals(call(pathing,"isPathing")));}catch(Exception ignored){}
        if(interactionReach!=null)result.put("interactionReach",interactionReach);return result;
    }
    public void failed(String reason){lastError=reason;LoggerFactory.getLogger("SuperWY").warn("寻路失败：{}",status());}
    private void set(Object settings,String name,Object value)throws Exception{
        Object setting=settings.getClass().getField(name).get(settings);Field field=setting.getClass().getField("value");
        previous.putIfAbsent(setting,field.get(setting));applied.put(setting,value);field.set(setting,value);
    }
    public void stop(){
        if(active())finishedAt=System.currentTimeMillis();
        if(owner==this)owner=null;
        try{if(pathing!=null)call(pathing,"cancelEverything");}catch(Exception ignored){}
        previous.forEach((s,v)->{try{Field field=s.getClass().getField("value");if(Objects.equals(field.get(s),applied.get(s)))field.set(s,v);}catch(Exception ignored){}});previous.clear();applied.clear();
    }
    /** Let Baritone find any traversable point within interaction distance, including slabs.
     * No client-side list of air blocks, visible rays, or configured delivery stations is required.
     */
    private Object containerGoal(BlockPos target,double reach,double eyeHeight)throws Exception{
        Class<?> type=Class.forName("baritone.api.pathing.goals.Goal");
        var interfaces=new ArrayList<Class<?>>();interfaces.add(type);
        try{interfaces.add(Class.forName("baritone.api.utils.interfaces.IGoalRenderPos"));}catch(ClassNotFoundException ignored){}
        return java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),interfaces.toArray(Class<?>[]::new),(proxy,method,args)->{
            String name=method.getName();
            if(name.equals("toString"))return "SuperWYContainerGoal["+target.toShortString()+", "+reach+"]";
            if(name.equals("hashCode"))return System.identityHashCode(proxy);
            if(name.equals("equals"))return proxy==args[0];
            if(name.equals("getGoalPos"))return target;
            if(name.equals("heuristic")&&(args==null||args.length==0))return 0d;
            if(name.equals("isInGoal")||name.equals("heuristic")){
                int x,y,z;if(args.length==1){var p=(BlockPos)args[0];x=p.getX();y=p.getY();z=p.getZ();}else{x=(int)args[0];y=(int)args[1];z=(int)args[2];}
                double distance=dev.superwy.core.ContainerDistance.toBlock(x+.5,y+eyeHeight,z+.5,target.getX(),target.getY(),target.getZ());
                return name.equals("isInGoal")?distance<=reach:Math.max(0,distance-reach);
            }
            if(method.isDefault())return InvocationHandler.invokeDefault(proxy,method,args);
            throw new UnsupportedOperationException(name);
        });
    }
    public static boolean loaded(BlockPos p){var level=Minecraft.getInstance().level;return level!=null&&level.getChunkSource().hasChunk(p.getX()>>4,p.getZ()>>4);}
    public static BlockHitResult hit(BlockPos container){
        return hit(container,CONTAINER_REACH);
    }
    /** Hit the selected container's own outline, without rejecting it for intervening blocks. */
    public static BlockHitResult containerHit(BlockPos container,double reach){
        var player=Minecraft.getInstance().player;
        return player==null?null:containerHit(container,player.getEyePosition(),reach);
    }
    private static BlockHitResult containerHit(BlockPos container,Vec3 eye,double reach){
        var level=Minecraft.getInstance().level;
        if(level==null||!loaded(container)||!level.getWorldBorder().isWithinBounds(container)
            ||!(level.getBlockEntity(container) instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity))return null;
        var shape=level.getBlockState(container).getShape(level,container);
        if(shape.isEmpty())return null;
        BlockHitResult hit=shape.clip(eye,shape.bounds().getCenter().add(container.getX(),container.getY(),container.getZ()),container);
        if(hit==null&&shape.bounds().move(container).contains(eye))hit=new BlockHitResult(eye,Direction.UP,container,true);
        return hit!=null&&eye.distanceTo(hit.getLocation())<=reach?hit:null;
    }
    public static BlockHitResult hit(BlockPos container,double reach){
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null||mc.level==null)return null;
        Vec3 eye=mc.player.getEyePosition();if(eye.distanceTo(Vec3.atCenterOf(container))>reach+.9)return null;
        BlockHitResult hit=mc.level.clip(new ClipContext(eye,Vec3.atCenterOf(container),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,mc.player));
        return hit.getType()==HitResult.Type.BLOCK&&hit.getBlockPos().equals(container)&&eye.distanceTo(hit.getLocation())<=reach?hit:null;
    }
}

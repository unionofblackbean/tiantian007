package dev.superwy.baritonefix;

import baritone.api.*;
import baritone.api.event.events.*;
import baritone.api.event.events.type.EventState;
import baritone.api.event.listener.AbstractGameEventListener;
import baritone.api.pathing.path.IPathExecutor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import org.slf4j.LoggerFactory;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

/** Keeps the current movement's look target alive until the movement actually consumes it. */
public final class SteeringBridge implements ClientModInitializer {
    private static boolean enabled=true;
    private static Target target;
    private static long captures,movements,jumps;
    private static final java.util.Map<baritone.api.Settings.Setting<Boolean>,Boolean> previous=new java.util.IdentityHashMap<>(),applied=new java.util.IdentityHashMap<>();
    private static void lease(baritone.api.Settings.Setting<Boolean> setting,boolean value){previous.putIfAbsent(setting,setting.value);applied.put(setting,value);setting.value=value;}
    private static void settings(boolean active){
        if(active){var s=BaritoneAPI.getSettings();lease(s.freeLook,true);lease(s.antiCheatCompatibility,false);lease(s.blockFreeLook,true);lease(s.smoothLook,false);}
        else{previous.forEach((s,v)->{if(java.util.Objects.equals(s.value,applied.get(s)))s.value=v;});previous.clear();applied.clear();}
    }
    private record Target(IBaritone baritone,LocalPlayer player,ClientLevel level,IPathExecutor path,int position,float yaw,float pitch,long capturedAt){}
    public void onInitializeClient(){
        ClientLifecycleEvents.CLIENT_STARTED.register(mc->{
            IBaritone baritone=BaritoneAPI.getProvider().getPrimaryBaritone();
            baritone.getGameEventHandler().registerEventListener(new AbstractGameEventListener(){
                @Override public void onTick(TickEvent event){
                    if(event.getState()!=EventState.PRE)return;
                    target=null;boolean active=enabled&&event.getType()==TickEvent.Type.IN&&baritone.getPathingBehavior().isPathing();
                    settings(active);if(active)capture(baritone);
                }
                @Override public void onWorldEvent(WorldEvent event){target=null;settings(false);}
                @Override public void onPlayerRotationMove(RotationMoveEvent event){Target t=current();if(t!=null){event.setYaw(t.yaw);event.setPitch(t.pitch);if(event.getType()==RotationMoveEvent.Type.JUMP)jumps++;}}
            });
            LoggerFactory.getLogger("BaritoneSteeringFix").info("Steering bridge registered for Baritone {}",net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("baritone").orElseThrow().getMetadata().getVersion());
        });
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,registry)->dispatcher.register(literal("baritonefix")
            .executes(c->{c.getSource().sendFeedback(Component.literal(status()));return 1;})
            .then(literal("on").executes(c->{enabled=true;c.getSource().sendFeedback(Component.literal("Baritone 转向补丁已开启"));return 1;}))
            .then(literal("off").executes(c->{enabled=false;target=null;settings(false);c.getSource().sendFeedback(Component.literal("Baritone 转向补丁已关闭"));return 1;}))));
    }
    private static void capture(IBaritone baritone){
        var mc=Minecraft.getInstance();var player=mc.player;
        if(player==null||mc.level==null||!baritone.getPathingBehavior().isPathing())return;
        // A distinct finite probe detects an absent look target without reading obfuscated private fields.
        float probeYaw=player.getYRot()+137.375f,probePitch=79.375f;
        var event=new RotationMoveEvent(RotationMoveEvent.Type.MOTION_UPDATE,probeYaw,probePitch);
        baritone.getLookBehavior().onPlayerRotationMove(event);
        if(event.getYaw()==probeYaw&&event.getPitch()==probePitch)return;
        if(!Float.isFinite(event.getYaw())||!Float.isFinite(event.getPitch()))return;
        var path=baritone.getPathingBehavior().getCurrent();if(path==null)return;
        target=new Target(baritone,player,mc.level,path,path.getPosition(),event.getYaw(),event.getPitch(),System.nanoTime());captures++;
    }
    private static Target current(){
        if(!enabled||target==null)return null;
        Target t=target;var mc=Minecraft.getInstance();var behavior=t.baritone.getPathingBehavior();
        if(mc.player!=t.player||mc.level!=t.level||!behavior.isPathing()||behavior.getCurrent()!=t.path||t.path.getPosition()!=t.position||System.nanoTime()-t.capturedAt>250_000_000L){target=null;return null;}
        return t;
    }
    public static float movementYaw(float original){Target t=current();if(t==null)return original;movements++;return t.yaw;}
    public static void mergeManualInput(){Target t=current();if(t!=null)SharedInput.merge(t.yaw);}
    public static String status(){return "enabled="+enabled+", active="+(current()!=null)+", captures="+captures+", movementUpdates="+movements+", jumps="+jumps+(target==null?"":", plannedYaw="+target.yaw);}
}

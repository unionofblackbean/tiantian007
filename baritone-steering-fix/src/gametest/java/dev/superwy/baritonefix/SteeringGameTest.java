package dev.superwy.baritonefix;
import baritone.api.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.*;
import net.minecraft.core.BlockPos;
import org.lwjgl.glfw.GLFW;
import java.util.*;

/** No SuperWY mod is loaded. Baritone is driven exclusively through its own goto command. */
@SuppressWarnings("UnstableApiUsage")
public final class SteeringGameTest implements FabricClientGameTest {
    public static boolean earlyCleanup;
    public static long cleanups;
    private ClientGameTestContext context;
    public void runTest(ClientGameTestContext context){
        this.context=context;context.runOnClient(mc->GLFW.glfwHideWindow(mc.getWindow().handle()));
        try(TestSingleplayerContext single=context.worldBuilder().adjustSettings(s->s.setAllowCommands(true)).create()){
            single.getClientLevel().waitForChunksDownload();
            single.getServer().runCommand("fill -12 69 -12 12 69 12 minecraft:stone");single.getServer().runCommand("gamemode survival @a");
            for(boolean fault:new boolean[]{false,true})for(boolean background:new boolean[]{false,true})for(boolean freeLook:new boolean[]{false,true})corridor(single,freeLook,background,fault);
            manualMovement(single);
            earlyCleanup=true;single.getServer().runCommand("fill -6 70 -2 -4 70 2 minecraft:stone");single.getServer().runCommand("tp @a -9.5 70 0.5 180 0");context.waitTicks(8);
            context.runOnClient(mc->{BaritoneAPI.getSettings().allowParkour.value=true;BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("goto -5 71 0");});
            boolean airborne=false,arrived=false;
            for(int i=0;i<240;i++){context.waitTicks(1);airborne|=context.computeOnClient(mc->!mc.player.onGround()&&mc.player.getY()>70.2);arrived=context.computeOnClient(mc->mc.player.onGround()&&mc.player.getY()>70.95&&mc.player.position().distanceToSqr(new net.minecraft.world.phys.Vec3(-4.5,71,.5))<1.5);if(arrived)break;}
            if(!airborne||!arrived)throw new AssertionError("Jump did not complete with early target cleanup");
            context.runOnClient(mc->{BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("goto 11 70 11");});context.waitTicks(10);
            context.runOnClient(mc->{var b=BaritoneAPI.getProvider().getPrimaryBaritone();if(!b.getPathingBehavior().isPathing())throw new AssertionError("Pause fixture lacks active path");b.getCommandManager().execute("pause");});context.waitTicks(5);
            String before=context.computeOnClient(mc->SteeringBridge.status());
            context.runOnClient(mc->{mc.player.setYRot(37);mc.player.setXRot(12);});context.waitTicks(5);
            context.runOnClient(mc->{if(Math.abs(mc.player.getYRot()-37)>.01||Math.abs(mc.player.getXRot()-12)>.01)throw new AssertionError("Paused path retained steering ownership");BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("cancel");mc.player.setYRot(81);});context.waitTicks(5);
            context.runOnClient(mc->{if(Math.abs(mc.player.getYRot()-81)>.01)throw new AssertionError("Cancelled path retained steering ownership");});
            if(cleanups<20)throw new AssertionError("Early cleanup fixture was not exercised");
            System.out.println("BARITONE_FIX_JUMP_STOP_OK: airborne jump; active path paused and cancelled releases look; "+before+", test cleanups="+cleanups);
        }catch(Exception e){throw new RuntimeException(e);}finally{earlyCleanup=false;}
    }
    private void manualMovement(TestSingleplayerContext single)throws Exception{
        single.getServer().runCommand("tp @a -8.5 70 0.5 0 0");context.waitTicks(6);
        context.runOnClient(mc->{var focus=mc.getWindow().getClass().getDeclaredField("focused");focus.setAccessible(true);focus.setBoolean(mc.getWindow(),true);mc.mouseHandler.grabMouse();BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("goto 9 70 0");});
        context.waitTicks(6);
        context.runOnClient(mc->{mc.player.setYRot(0);mc.player.setXRot(11);});
        var before=context.computeOnClient(mc->mc.player.position());
        context.getInput().holdKey(GLFW.GLFW_KEY_W);context.waitTicks(5);context.getInput().releaseKey(GLFW.GLFW_KEY_W);
        var after=context.computeOnClient(mc->mc.player.position());
        if(after.z-before.z<.15)throw new AssertionError("Manual W did not move along camera: "+before+" -> "+after);
        context.runOnClient(mc->{var b=BaritoneAPI.getProvider().getPrimaryBaritone();if(!b.getCustomGoalProcess().isActive()&&!b.getPathingBehavior().isPathing())throw new AssertionError("Manual movement cancelled goal");if(Math.abs(mc.player.getYRot())>.1)throw new AssertionError("Manual camera not preserved");});
        boolean arrived=false;for(int i=0;i<240;i++){context.waitTicks(1);arrived=context.computeOnClient(mc->mc.player.position().distanceToSqr(new net.minecraft.world.phys.Vec3(9.5,70,.5))<1.4);if(arrived)break;}
        if(!arrived)throw new AssertionError("Path did not resume after manual direction release");
        context.runOnClient(mc->{BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("cancel");});
        System.out.println("BARITONE_FIX_SHARED_INPUT_OK: manual camera-relative movement, goal retained, release resumed original destination");
    }
    private void corridor(TestSingleplayerContext single,boolean freeLook,boolean background,boolean fault)throws Exception{
        single.getServer().runCommand("fill -2 70 -12 10 72 -1 minecraft:stone");single.getServer().runCommand("fill 0 70 -10 1 71 -5 minecraft:air");single.getServer().runCommand("fill 0 70 -6 8 71 -5 minecraft:air");single.getServer().runCommand("fill 7 70 -6 8 71 -2 minecraft:air");single.getServer().runCommand("tp @a 0.5 70 -9.5 180 0");context.waitTicks(8);
        var settings=BaritoneAPI.getSettings();settings.allowBreak.value=false;settings.allowPlace.value=false;settings.freeLook.value=freeLook;settings.smoothLook.value=false;settings.antiCheatCompatibility.value=false;earlyCleanup=fault;
        context.runOnClient(mc->{mc.options.pauseOnLostFocus=false;mc.setScreen(null);var focus=mc.getWindow().getClass().getDeclaredField("focused");focus.setAccessible(true);focus.setBoolean(mc.getWindow(),!background);if(background)mc.mouseHandler.releaseMouse();else mc.mouseHandler.grabMouse();var manager=BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager();manager.execute("cancel");manager.execute("goto 8 70 -2");});
        boolean east=false,south=false,arrived=false,turned=false;
        for(int i=0;i<300;i++){
            if(i>4)context.runOnClient(mc->{mc.player.setYRot(47);mc.player.setXRot(17);});
            context.waitTicks(1);
            if(i>5)context.runOnClient(mc->{if(Math.abs(net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot()-47))>.1||Math.abs(mc.player.getXRot()-17)>.1)throw new AssertionError("Camera was locked by path: "+mc.player.getYRot());});var pos=context.computeOnClient(mc->mc.player.position());east|=pos.x>6&&pos.z<-4;south|=pos.x>6&&pos.z>-4;turned|=context.computeOnClient(mc->Math.abs(net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot()-180))>45);arrived=pos.distanceToSqr(new net.minecraft.world.phys.Vec3(8.5,70,-1.5))<1.3;if(arrived)break;}
        if(!east||!south||!arrived)throw new AssertionError("Corridor failed: freeLook="+freeLook+", background="+background+", earlyCleanup="+fault+", "+context.computeOnClient(mc->SteeringBridge.status()));
        context.runOnClient(mc->{BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("cancel");});
        System.out.println("BARITONE_FIX_CORRIDOR_OK: freeLook="+freeLook+", background="+background+", earlyCleanup="+fault+", actual two corners with independent camera");
        single.getServer().runCommand("fill -2 70 -12 10 72 -1 minecraft:air");context.waitTicks(3);
    }
}

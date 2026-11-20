package dev.superwy.baritonefix;
import dev.superwy.baritonefix.mixin.ClientInputAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec2;
/** Manual directions temporarily override motion, without changing the path or goal. */
public final class SharedInput {
    private SharedInput(){}
    public static void merge(float plannedYaw){
        var mc=Minecraft.getInstance();if(mc.player==null||mc.screen!=null||!mc.isWindowActive())return;
        // Other input providers (e.g. freecam) keep control of their own vectors.
        var input=mc.player.input;if(!input.getClass().getName().startsWith("baritone."))return;
        var o=mc.options;boolean f=o.keyUp.isDown(),b=o.keyDown.isDown(),l=o.keyLeft.isDown(),r=o.keyRight.isDown();
        var auto=input.keyPresses;boolean manual=f||b||l||r;
        if(manual){
            float x=(l?1:0)-(r?1:0),z=(f?1:0)-(b?1:0);double delta=Math.toRadians(mc.player.getYRot()-plannedYaw),n=Math.max(1,Math.sqrt(x*x+z*z));
            ((ClientInputAccessor)input).superwy$moveVector(new Vec2((float)((x*Math.cos(delta)-z*Math.sin(delta))/n),(float)((x*Math.sin(delta)+z*Math.cos(delta))/n)));
        }
        input.keyPresses=new Input(manual?f:auto.forward(),manual?b:auto.backward(),manual?l:auto.left(),manual?r:auto.right(),auto.jump()||o.keyJump.isDown(),auto.shift()||o.keyShift.isDown(),auto.sprint()||o.keySprint.isDown());
    }
}

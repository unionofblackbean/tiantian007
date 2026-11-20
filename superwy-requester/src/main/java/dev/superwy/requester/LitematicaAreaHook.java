package dev.superwy.requester;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;

/** Only loaded from the Litematica mixin, so the requester still runs without Litematica. */
public final class LitematicaAreaHook {
    private LitematicaAreaHook(){}
    public static void addButton(GuiBase gui){
        if(RequesterClient.INSTANCE==null)return;
        int width=90;
        var button=new ButtonGeneric(gui.getScreenWidth()-width-10,2,width,20,"机器人取货","统计选区内的投影材料，检查背包缺失，","预估潜影盒装箱后交给机器人打包交付");
        gui.addButton(button,(b,mouseButton)->RequesterClient.INSTANCE.openProjectionOrder(gui));
    }
}

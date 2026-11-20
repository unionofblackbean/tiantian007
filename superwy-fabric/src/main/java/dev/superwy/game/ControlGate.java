package dev.superwy.game;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import java.util.function.*;
/** Tracks background work without taking ownership of physical controls. */
public final class ControlGate {
    private static BooleanSupplier tasks=()->false;
    private static boolean active;
    private ControlGate(){}
    public static boolean active(){return active;}
    public static boolean locked(){return false;}
    public static void register(BooleanSupplier running,Runnable stop,Runnable disableScan,Consumer<String> feedback){
        tasks=running;
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("superwy","control"),(gui,delta)->{
            if(!active)return;var mc=Minecraft.getInstance();int y=gui.guiHeight()-18;
            gui.text(mc.font,mc.font.plainSubstrByWidth("机器人运行中 · 可自由操作 · /superwy stop 停止任务",gui.guiWidth()-20),10,y,0xffffd83d);
        });
    }
    public static void update(){
        active=tasks.getAsBoolean();var mc=Minecraft.getInstance();
        if(active&&!mc.isWindowActive()&&mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen)mc.setScreen(null);
    }
}

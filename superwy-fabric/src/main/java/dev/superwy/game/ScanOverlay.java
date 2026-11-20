package dev.superwy.game;

import dev.superwy.core.Model;
import dev.superwy.core.OverlayPositions;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.shapes.Shapes;
import java.util.*;

/** Reads the scanner's immutable view; rendering never changes scan progress. */
public final class ScanOverlay {
    public static void register(Scanner scanner){
        var mc=Minecraft.getInstance();
        var box=Shapes.box(-.003,-.003,-.003,1.003,1.003,1.003);
        var cache=new Object(){long nextUpdate;OverlayPositions.View selection=new OverlayPositions.View(List.of(),List.of());void update(){long now=System.nanoTime();if(now<nextUpdate)return;nextUpdate=now+250_000_000L;selection=OverlayPositions.select(scanner.remaining(),new Model.Pos(mc.player.blockPosition().getX(),mc.player.blockPosition().getY(),mc.player.blockPosition().getZ()));}};
        LevelRenderEvents.END_MAIN.register(context->{
            if(mc.level==null||mc.player==null||mc.options.hideGui)return;
            cache.update();
            var camera=context.levelState().cameraRenderState.pos;
            var lines=context.bufferSource().getBuffer(RenderTypes.LINES);
            for(Model.Pos p:cache.selection.markers()){
                if(mc.player.distanceToSqr(p.x()+.5,p.y()+.5,p.z()+.5)>128*128)continue;
                ShapeRenderer.renderShape(context.poseStack(),lines,box,p.x()-camera.x,p.y()-camera.y,p.z()-camera.z,0xffffd83d,2f);
            }
            context.bufferSource().endBatch(RenderTypes.LINES);
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("superwy","scan"),(gui,delta)->{
            if(mc.level==null||mc.player==null||mc.options.hideGui)return;
            var positions=scanner.remaining();String state=String.valueOf(scanner.status.get("state"));
            if(positions.isEmpty()&&Set.of("idle","offline","complete").contains(state))return;
            cache.update();var sorted=cache.selection.hud();
            int height=70+sorted.size()*12,x=8,y=8;
            gui.fill(x,y,x+240,y+height,0xb8101721);gui.fill(x,y,x+3,y+height,0xffffd83d);
            String label=switch(state){case "complete_with_skips"->"完成（含跳过）";case "paused_for_order"->"取货优先，扫描暂停";case "complete"->"完成";case "reading"->"读取容器";case "discovering"->"发现容器";case "resuming"->"恢复扫描";case "stopped"->"已停止";default->state;};
            gui.text(mc.font,"仓库扫描 · "+label,x+9,y+7,0xffffd83d);
            gui.text(mc.font,"未扫描容器 "+positions.size()+" / 未探索区块 "+scanner.status.getOrDefault("remainingChunks",0),x+9,y+21,0xffeeeeee);
            gui.text(mc.font,"读取成功 "+scanner.status.getOrDefault("processed",0)+" · 跳过 "+scanner.status.getOrDefault("skippedContainers",0)+" 容器 / "+scanner.status.getOrDefault("skippedChunks",0)+" 区块",x+9,y+35,0xffe2b37f);
            int row=y+49;
            for(var p:sorted){gui.text(mc.font,p.x()+", "+p.y()+", "+p.z(),x+9,row,0xffffd83d);row+=12;}
            gui.text(mc.font,positions.isEmpty()?"失败详情 /superwy failed <页>":"更多坐标 /superwy unscanned <页>",x+9,row+2,0xffa9b5c8);
        });
    }
}

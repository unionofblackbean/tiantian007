package dev.superwy.requester;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.Objects;
import java.util.function.Consumer;

/** Import is only committed after the player chooses a recipient. Escape leaves the old config intact. */
public final class ConnectionSetupScreen extends Screen {
    private final RequesterConfig draft;
    private final Consumer<RequesterConfig> save;
    private EditBox recipient;
    private String name,error="";
    private int left,top,panelWidth;
    public ConnectionSetupScreen(RequesterConfig draft,Consumer<RequesterConfig> save){
        super(Component.literal("连接取货机器人"));this.draft=draft.copy();this.save=save;name=draft.fakePlayerName;
    }
    protected void init(){
        panelWidth=Math.min(340,width-24);left=(width-panelWidth)/2;top=Math.max(8,(height-160)/2);
        recipient=new EditBox(font,left,top+66,panelWidth,20,Component.literal("收货假人名"));
        recipient.setMaxLength(16);recipient.setValue(name);recipient.setResponder(value->name=value);
        addRenderableWidget(recipient);setInitialFocus(recipient);
        addRenderableWidget(Button.builder(Component.literal("保存连接"),b->commit()).bounds(left,top+104,(panelWidth-8)/2,20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"),b->onClose()).bounds(left+(panelWidth+8)/2,top+104,(panelWidth-8)/2,20).build());
    }
    private void commit(){
        try{var chosen=draft.copy();chosen.fakePlayerName=recipient.getValue().trim();chosen.validateSettings();chosen.validateOrder();save.accept(chosen);onClose();}
        catch(Exception failure){error=Objects.toString(failure.getMessage(),"连接保存失败");}
    }
    public void extractRenderState(GuiGraphicsExtractor gui,int mouseX,int mouseY,float delta){
        super.extractRenderState(gui,mouseX,mouseY,delta);
        gui.text(font,title,left,top+8,0xffffffff);
        gui.text(font,font.plainSubstrByWidth("机器人地址："+draft.baseUrl,panelWidth),left,top+30,0xffc9d1dc);
        gui.text(font,"收货假人名（默认 sf，可随时在取货设置修改）",left,top+51,0xffc9d1dc);
        gui.text(font,font.plainSubstrByWidth(error.isEmpty()?"保存后生效，不会自动下单；Esc 取消":error,panelWidth),left,top+138,error.isEmpty()?0xffa6b5c7:0xffff7777);
    }
    public boolean isPauseScreen(){return false;}
}

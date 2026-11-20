package dev.superwy.requester;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import java.util.function.LongConsumer;

/** Right-click on a material row: type the exact quantity to take. 0 effectively removes it from the order. */
public final class MaterialAmountScreen extends Screen {
    private final Screen parent;private final ItemStack icon;private final String name;
    private final long current,automatic;private final int stackSize;
    private final LongConsumer apply;private final Runnable reset;
    private EditBox field;private String text,error="";
    private int left,top,panelW;

    MaterialAmountScreen(Screen parent,ItemStack icon,String name,long current,long automatic,int stackSize,LongConsumer apply,Runnable reset){
        super(Component.literal("修改取货数量"));
        this.parent=parent;this.icon=icon;this.name=name;this.current=current;this.automatic=automatic;this.stackSize=stackSize;this.apply=apply;this.reset=reset;
        text=Long.toString(current);
    }
    protected void init(){
        panelW=Math.min(300,width-16);left=(width-panelW)/2;top=Math.max(4,(height-130)/2);
        field=new EditBox(font,left+10,top+52,panelW-20,20,Component.literal("数量"));
        field.setMaxLength(12);field.setValue(text);field.setResponder(v->{text=v;error="";});
        addRenderableWidget(field);setInitialFocus(field);
        int w=(panelW-32)/3,y=top+102;
        addRenderableWidget(Button.builder(Component.literal("确定"),b->commit()).bounds(left+8,y,w,20).build());
        addRenderableWidget(Button.builder(Component.literal("恢复自动"),b->{reset.run();onClose();}).bounds(left+16+w,y,w,20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"),b->onClose()).bounds(left+24+2*w,y,w,20).build());
    }
    private void commit(){
        try{apply.accept(QuantityPrompt.parseAmount(text,stackSize,ParcelPlan.MAX_TOTAL));onClose();}
        catch(IllegalArgumentException e){error=e.getMessage();}
    }
    public boolean keyPressed(KeyEvent event){
        if(event.isConfirmation()){commit();return true;}
        return super.keyPressed(event);
    }
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
    public void extractBackground(GuiGraphicsExtractor gui,int mouseX,int mouseY,float delta){
        super.extractBackground(gui,mouseX,mouseY,delta);
        gui.fill(left,top,left+panelW,top+130,0xf0101721);gui.fill(left,top,left+3,top+130,0xff59c9ad);
    }
    public void extractRenderState(GuiGraphicsExtractor gui,int mouseX,int mouseY,float delta){
        super.extractRenderState(gui,mouseX,mouseY,delta);
        gui.text(font,title,left+10,top+8,0xff59c9ad);
        gui.item(icon,left+10,top+22);
        gui.text(font,font.plainSubstrByWidth(name,panelW-40),left+30,top+22,0xffeeeeee);
        gui.text(font,font.plainSubstrByWidth("当前 "+current+" · 自动 "+automatic+" · 每组 "+stackSize,panelW-40),left+30,top+32,0xffa6b5c7);
        gui.text(font,font.plainSubstrByWidth(error.isEmpty()?"输入取货数量（可写 2组、2组+5），0 表示不取 · Enter 确定":error,panelW-20),left+10,top+80,error.isEmpty()?0xff8fa3b8:0xffff7777);
    }
}

package dev.superwy.requester;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.*;
import net.minecraft.util.FormattedCharSequence;
import java.util.*;
import java.util.function.BiConsumer;

/** Game-local settings and manual request. Projection quantity remains a non-modal HUD. */
public final class RequesterSettingsScreen extends Screen {
    private final RequesterConfig draft;private final BiConsumer<RequesterConfig,Boolean> save;
    private final Map<String,EditBox> fields=new LinkedHashMap<>();private final Map<String,String> labels=new LinkedHashMap<>();
    private String error="";private int left,top,panelWidth;private Button mode;
    private int layoutHeight,rowStep;private int binding=-1;private final List<Button> hotkeys=new ArrayList<>();
    public RequesterSettingsScreen(RequesterConfig config,BiConsumer<RequesterConfig,Boolean> save){super(Component.literal("SuperWY 取货设置"));this.draft=config.copy();this.save=save;}
    protected void init(){
        fields.clear();labels.clear();hotkeys.clear();panelWidth=Math.min(460,width-20);left=(width-panelWidth)/2;layoutHeight=Math.min(274,height);rowStep=Math.min(34,Math.max(22,(layoutHeight-124)/4));top=Math.max(0,(height-layoutHeight)/2);int col=(panelWidth-12)/2;
        mode=addRenderableWidget(Button.builder(Component.literal(modeLabel()),b->{draft.mesh=!draft.mesh;b.setMessage(Component.literal(modeLabel()));}).bounds(left,top+28,panelWidth,20).build());
        field("recipient","放货的收货假人名",draft.fakePlayerName,0,0,col,16,false);
        field("url","机器人 HTTP 地址",draft.baseUrl,1,0,col,256,false);
        field("item","取货物品 ID",draft.defaultItem,0,1,col,160,false);
        field("count","数量（1–64）",Integer.toString(draft.defaultCount),1,1,col,2,false);
        field("token","玩家 token（连接指令自动填写）",draft.token,0,2,panelWidth,256,true);
        int button=(panelWidth-12)/3;
        for(int i=0;i<3;i++){final int index=i;hotkeys.add(addRenderableWidget(Button.builder(Component.literal(hotkeyLabel(i)),b->{binding=index;refreshHotkeys();}).bounds(left+i*(button+6),top+layoutHeight-65,button,20).build()));}
        addRenderableWidget(Button.builder(Component.literal("保存"),b->commit(false)).bounds(left,top+layoutHeight-38,button,20).build());
        addRenderableWidget(Button.builder(Component.literal("保存并取货"),b->commit(true)).bounds(left+button+6,top+layoutHeight-38,button,20).build());
        addRenderableWidget(Button.builder(Component.literal("取消"),b->onClose()).bounds(left+(button+6)*2,top+layoutHeight-38,button,20).build());
    }
    private String hotkeyLabel(int i){return new String[]{"取货：","确认：","队列："}[i]+(binding==i?"请按键…":RequestHotkey.label(i==0?draft.pickupKey:i==1?draft.confirmKey:draft.queueKey));}
    private void refreshHotkeys(){for(int i=0;i<hotkeys.size();i++)hotkeys.get(i).setMessage(Component.literal(hotkeyLabel(i)));}
    public boolean captureHotkey(int code){if(binding<0)return false;if(code!=256){if(binding==0)draft.pickupKey=code;else if(binding==1)draft.confirmKey=code;else draft.queueKey=code;}binding=-1;refreshHotkeys();return true;}
    private String modeLabel(){return "超级调度模式："+(draft.mesh?"开启（任务自动发给调度机器人）":"关闭")+" · 点击切换";}
    private void field(String id,String label,String value,int column,int row,int fieldWidth,int max,boolean secret){
        int x=left+(column==0?0:(panelWidth+12)/2),y=top+60+row*rowStep;
        var field=new EditBox(font,x,y,fieldWidth,18,Component.literal(label));field.setMaxLength(max);field.setValue(value==null?"":value);
        if(secret)field.addFormatter((text,cursor)->FormattedCharSequence.forward("*".repeat(text.length()),Style.EMPTY));
        fields.put(id,addRenderableWidget(field));labels.put(id,label);
    }
    private void commit(boolean request){
        try{
            draft.fakePlayerName=fields.get("recipient").getValue().trim();draft.defaultItem=fields.get("item").getValue().trim();
            draft.defaultCount=Integer.parseInt(fields.get("count").getValue());draft.baseUrl=fields.get("url").getValue().trim();draft.token=fields.get("token").getValue().trim();
            draft.validateSettings();draft.validateDistinctHotkeys();save.accept(draft.copy(),request);onClose();
        }catch(Exception e){error=e instanceof NumberFormatException?"数量必须为 1–64 的整数":Objects.toString(e.getMessage(),"配置无效");}
    }
    public void extractRenderState(GuiGraphicsExtractor gui,int mouseX,int mouseY,float delta){
        super.extractRenderState(gui,mouseX,mouseY,delta);
        gui.text(font,title,left,top+10,0xffffffff);
        for(var entry:fields.entrySet()){var f=entry.getValue();gui.text(font,labels.get(entry.getKey()),f.getX(),f.getY()-10,0xffc9d1dc);}
        if(!error.isEmpty())gui.text(font,font.plainSubstrByWidth(error,panelWidth),left,top+layoutHeight-12,0xffff7777);
    }
    public boolean isPauseScreen(){return false;}
}

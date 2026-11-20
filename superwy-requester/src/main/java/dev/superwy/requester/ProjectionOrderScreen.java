package dev.superwy.requester;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import java.util.*;

/**
 * "机器人取货" for a Litematica area selection:
 * 1. counts the materials in the selection, from the projection or from an already built structure (toggle,
 *    auto-picked on open), and compares them with the player's inventory; right-click a row to set its quantity;
 * 2. after one confirmation shows the locally estimated parcels (hover a box to see its contents);
 * 3. hands the ordered manifest to the robot, which delivers it as shulker parcels.
 */
public final class ProjectionOrderScreen extends Screen {
    /** Sends the order; returns a short status for the chat, throws IllegalArgumentException with a readable reason. */
    public interface Submit { String submit(ParcelPlan.Plan plan); }
    /** Asks the robot for cached stock of the manifest before the task starts. */
    public interface StockCheck { java.util.concurrent.CompletableFuture<RequestApi.StockReply> check(List<RequestApi.Line> lines); }
    private enum Stage { SCAN, MATERIALS, PREVIEW }
    private static final int ROW=20,CELL=22;
    private static final class Row {
        final Item item;final String id,name;final ItemStack icon;final long need,have;final int stackSize;final boolean shulker;boolean included=true;
        /** Manually typed take quantity (right-click); -1 = automatic. */
        long custom=-1;
        Row(Item item,long need,long have){
            this.item=item;this.need=need;this.have=have;icon=new ItemStack(item);id=BuiltInRegistries.ITEM.getKey(item).toString();
            name=icon.getHoverName().getString();stackSize=Math.max(1,icon.getMaxStackSize());shulker=item instanceof BlockItem b&&b.getBlock() instanceof ShulkerBoxBlock;
        }
        long missing(){return Math.max(0,need-have);}
        long automatic(boolean missingOnly){return missingOnly?missing():need;}
        long order(boolean missingOnly){return included?(custom>=0?custom:automatic(missingOnly)):0;}
    }
    private final Screen parent;private final String recipient,bot;private final Submit submit;private final StockCheck stockCheck;
    private RequestApi.StockReply stock;private String stockError;private boolean stockPending;private int stockGeneration;
    private static final int SHORT_PANEL=176;
    private Stage stage=Stage.SCAN;private AreaMaterials scan;private AreaMaterials finished;
    private boolean skipPlaced=true,missingOnly=true,sending;
    private AreaMaterials.Source source;
    /** Manual quantities survive rescans (toggling the source or placed-block deduction). */
    private final Map<Item,Long> custom=new HashMap<>();
    private final List<Row> rows=new ArrayList<>();private ParcelPlan.Plan plan;
    private String error="";private int scroll;
    private int left,top,panelW,panelH,listTop,listBottom;

    public ProjectionOrderScreen(Screen parent,String bot,String recipient,Submit submit,StockCheck stockCheck){
        super(Component.literal("机器人取货 · 选区材料"));this.parent=parent;this.bot=bot;this.recipient=recipient;this.submit=submit;this.stockCheck=stockCheck;
        source=AreaMaterials.detect();startScan();
    }
    private void startScan(){
        rows.clear();plan=null;scroll=0;error="";finished=null;
        try{scan=AreaMaterials.start(source,skipPlaced);stage=Stage.SCAN;}
        catch(RuntimeException e){scan=null;stage=Stage.MATERIALS;error=Objects.toString(e.getMessage(),"选区读取失败");}
    }
    protected void init(){
        panelW=Math.min(440,width-16);panelH=Math.min(300,height-12);left=(width-panelW)/2;top=Math.max(4,(height-panelH)/2);
        listTop=top+(stage==Stage.PREVIEW?62:58);listBottom=top+panelH-70;
        int half=(panelW-24)/2,bottom=top+panelH-26,upper=top+panelH-50;
        switch(stage){
            case SCAN->addRenderableWidget(Button.builder(Component.literal("取消"),b->onClose()).bounds(left+8,bottom,panelW-16,20).build());
            case MATERIALS->{
                int third=(panelW-32)/3;
                addRenderableWidget(Button.builder(Component.literal("来源："+source.label),b->{source=source.next();startScan();rebuildWidgets();})
                    .tooltip(Tooltip.create(Component.literal("投影：统计 Litematica 投影里的方块\n已建建筑：统计选区内服务器里实际已有的方块\n打开时自动判断（选区和已启用的投影重叠时用投影）"))).bounds(left+8,upper,third,20).build());
                var placed=addRenderableWidget(Button.builder(Component.literal("已放置方块："+(source==AreaMaterials.Source.WORLD?"—":skipPlaced?"扣除":"不扣除")),b->{skipPlaced=!skipPlaced;startScan();rebuildWidgets();}).bounds(left+16+third,upper,third,20).build());
                placed.active=source==AreaMaterials.Source.PROJECTION;
                addRenderableWidget(Button.builder(Component.literal("取货数量："+(missingOnly?"只取缺少的":"全部数量")),b->{missingOnly=!missingOnly;rebuildWidgets();}).bounds(left+24+2*third,upper,third,20).build());
                addRenderableWidget(Button.builder(Component.literal("取消"),b->onClose()).bounds(left+8,bottom,half,20).build());
                var confirm=addRenderableWidget(Button.builder(Component.literal("确认清单 → 预估装箱"),b->confirmMaterials()).bounds(left+16+half,bottom,half,20).build());
                confirm.active=finished!=null&&orderLines().stream().anyMatch(l->l.count()>0);
            }
            case PREVIEW->{
                addRenderableWidget(Button.builder(Component.literal("← 返回清单"),b->{stage=Stage.MATERIALS;scroll=0;error="";rebuildWidgets();}).bounds(left+8,bottom,half,20).build());
                String label=stockPending?"强制开始（不等库存结果）":stockError!=null?"仍然开始":stock!=null&&!stock.missing().isEmpty()?"确认开始（缺货项跳过）":"确认开始 · "+(bot==null||bot.isBlank()?"机器人":bot);
                var send=addRenderableWidget(Button.builder(Component.literal(label),b->send()).bounds(left+16+half,bottom,half,20).build());
                send.active=plan!=null&&!sending;
            }
        }
    }
    public void tick(){
        if(stage!=Stage.SCAN||scan==null)return;
        try{scan.step(minecraft,8_000_000L);}
        catch(RuntimeException e){error=Objects.toString(e.getMessage(),"统计失败");scan=null;stage=Stage.MATERIALS;rebuildWidgets();return;}
        if(scan.done()){finished=scan;scan=null;buildRows();stage=Stage.MATERIALS;rebuildWidgets();}
    }
    private void buildRows(){
        rows.clear();var inventory=inventory(minecraft.player);
        for(var e:finished.counts.entrySet()){var row=new Row(e.getKey(),e.getValue(),inventory.getOrDefault(e.getKey(),0L));row.custom=custom.getOrDefault(e.getKey(),-1L);rows.add(row);}
        rows.sort(Comparator.comparingLong(Row::missing).reversed().thenComparing(Comparator.comparingLong((Row r)->r.need).reversed()).thenComparing(r->r.id));
        if(rows.isEmpty())error=finished.source==AreaMaterials.Source.PROJECTION?"选区内没有投影方块（投影未加载、未放置或已全部建好）；统计已建好的建筑请把来源切换为「已建建筑」"
            :finished.unloadedWorldColumns>0?"选区内的区块未加载，请靠近建筑后重试":"选区内没有方块；统计投影请把来源切换为「投影」";
    }
    /** Main inventory, hotbar, offhand and the contents of shulker boxes carried there. */
    static Map<Item,Long> inventory(Player player){
        var counts=new HashMap<Item,Long>();if(player==null)return counts;
        List<ItemStack> stacks=new ArrayList<>(player.getInventory().getNonEquipmentItems());stacks.add(player.getInventory().getItem(Inventory.SLOT_OFFHAND));
        for(ItemStack s:stacks){
            if(s.isEmpty())continue;counts.merge(s.getItem(),(long)s.getCount(),Long::sum);
            var contents=s.get(DataComponents.CONTAINER);
            if(contents!=null)contents.nonEmptyItemCopyStream().forEach(i->counts.merge(i.getItem(),(long)i.getCount(),Long::sum));
        }
        return counts;
    }
    private List<ParcelPlan.Line> orderLines(){
        var lines=new ArrayList<ParcelPlan.Line>();
        for(Row r:rows){long n=r.order(missingOnly);if(n>0)lines.add(new ParcelPlan.Line(r.id,(int)Math.min(n,ParcelPlan.MAX_TOTAL+1L),r.stackSize,r.shulker));}
        return lines;
    }
    private void confirmMaterials(){
        try{plan=ParcelPlan.plan(ParcelPlan.order(orderLines()),recipient);stage=Stage.PREVIEW;scroll=0;error="";startStock();}
        catch(IllegalArgumentException|ArithmeticException e){error=Objects.toString(e.getMessage(),"清单无效");}
        rebuildWidgets();
    }
    /** Step two waits for the robot's cached stock; the player confirms (or forces the start) afterwards. */
    private void startStock(){
        int generation=++stockGeneration;stock=null;stockError=null;stockPending=true;
        var lines=plan.lines().stream().map(l->new RequestApi.Line(l.itemId(),l.count())).toList();
        try{
            stockCheck.check(lines).whenComplete((reply,failure)->minecraft.execute(()->{
                if(generation!=stockGeneration||stage!=Stage.PREVIEW)return;
                stockPending=false;
                if(failure!=null)stockError="无法连接机器人";else if(!reply.ok())stockError=Objects.toString(reply.error(),"HTTP "+reply.status());else stock=reply;
                scroll=0;rebuildWidgets();
            }));
        }catch(RuntimeException e){stockPending=false;stockError=Objects.toString(e.getMessage(),"库存查询失败");}
    }
    private boolean shortPanel(){return stage==Stage.PREVIEW&&stock!=null&&!stock.missing().isEmpty();}
    private void send(){
        if(plan==null||sending)return;
        try{sending=true;String status=submit.submit(plan);minecraft.setScreen(null);if(minecraft.player!=null)minecraft.player.sendSystemMessage(Component.literal("[取货] "+status));}
        catch(RuntimeException e){sending=false;error=Objects.toString(e.getMessage(),"发送失败");rebuildWidgets();}
    }
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}

    // ---- input ----
    private int visibleRows(){return Math.max(1,(listBottom-listTop)/ROW);}
    private int columns(){return Math.max(1,(panelW-16-(shortPanel()?SHORT_PANEL:0))/CELL);}
    private int cellCount(){return plan==null?0:plan.boxes().size()+plan.loose().size();}
    private int maxScroll(){
        if(stage==Stage.MATERIALS)return Math.max(0,rows.size()-visibleRows());
        if(stage==Stage.PREVIEW){int lines=(cellCount()+columns()-1)/columns();return Math.max(0,lines-Math.max(1,(listBottom-listTop)/CELL));}
        return 0;
    }
    public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(vertical!=0){scroll=Math.max(0,Math.min(maxScroll(),scroll-(int)Math.signum(vertical)));return true;}
        return super.mouseScrolled(x,y,horizontal,vertical);
    }
    public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick){
        if(super.mouseClicked(event,doubleClick))return true;
        if(stage==Stage.MATERIALS&&(event.button()==0||event.button()==1)){
            Row row=rowAt(event.x(),event.y());
            if(row!=null&&event.button()==0){row.included=!row.included;rebuildWidgets();return true;}
            if(row!=null){editAmount(row);return true;}
        }
        return false;
    }
    private void editAmount(Row row){
        minecraft.setScreen(new MaterialAmountScreen(this,row.icon,row.name,row.order(missingOnly),row.automatic(missingOnly),row.stackSize,
            value->setCustom(row,value),()->setCustom(row,-1)));
    }
    void setCustom(Row row,long value){
        row.custom=value;if(value<0)custom.remove(row.item);else{custom.put(row.item,value);row.included=true;}
    }
    private Row rowAt(double x,double y){
        if(x<left+4||x>=left+panelW-4||y<listTop||y>=listTop+visibleRows()*ROW)return null;
        int i=scroll+(int)((y-listTop)/ROW);return i>=0&&i<rows.size()?rows.get(i):null;
    }
    private int cellAt(double x,double y){
        if(plan==null||x<left+8||y<listTop||y>=listBottom)return -1;
        int col=(int)((x-left-8)/CELL),line=(int)((y-listTop)/CELL);if(col>=columns())return -1;
        int i=(scroll+line)*columns()+col;return i<cellCount()?i:-1;
    }

    // ---- drawing ----
    public void extractBackground(GuiGraphicsExtractor gui,int mouseX,int mouseY,float delta){
        super.extractBackground(gui,mouseX,mouseY,delta);
        gui.fill(left,top,left+panelW,top+panelH,0xf0101721);gui.fill(left,top,left+3,top+panelH,0xff59c9ad);
    }
    public void extractRenderState(GuiGraphicsExtractor gui,int mouseX,int mouseY,float delta){
        super.extractRenderState(gui,mouseX,mouseY,delta);
        gui.text(font,title,left+10,top+8,0xff59c9ad);
        switch(stage){
            case SCAN->{
                float p=scan==null?1:scan.progress();
                gui.text(font,"正在统计选区「"+(scan==null?"":scan.selectionName)+"」内的投影方块… "+(int)(p*100)+"%",left+10,top+30,0xffeeeeee);
                int w=panelW-20;gui.fill(left+10,top+46,left+10+w,top+52,0xff293445);gui.fill(left+10,top+46,left+10+(int)(w*p),top+52,0xff59c9ad);
            }
            case MATERIALS->drawMaterials(gui,mouseX,mouseY);
            case PREVIEW->drawPreview(gui,mouseX,mouseY);
        }
        if(!error.isEmpty())gui.text(font,font.plainSubstrByWidth(error,panelW-20),left+10,top+panelH-60,0xffff7777);
    }
    private void drawMaterials(GuiGraphicsExtractor gui,int mouseX,int mouseY){
        if(finished==null)return;
        long need=0,have=0,missing=0,order=0;int kinds=0;
        for(Row r:rows){if(!r.included)continue;need+=r.need;have+=Math.min(r.have,r.need);missing+=r.missing();long n=r.order(missingOnly);order+=n;if(n>0)kinds++;}
        gui.text(font,font.plainSubstrByWidth("["+finished.source.label+"] 选区「"+finished.selectionName+"」 "+rows.size()+" 种材料 · 需要 "+need+" · 背包已有 "+have+" · 缺少 "+missing,panelW-20),left+10,top+22,0xffeeeeee);
        String note="本次取货 "+kinds+" 种 / "+order+" 件"+(finished.placedSkipped>0?" · 已扣除 "+finished.placedSkipped+" 个已放置方块":"")+(finished.unloadedWorldColumns>0?" · "+finished.unloadedWorldColumns+(finished.source==AreaMaterials.Source.WORLD?" 个区块未加载，未统计":" 个区块未加载，未能扣除已放置"):"");
        gui.text(font,font.plainSubstrByWidth(note,panelW-20),left+10,top+33,0xffa6b5c7);
        int cNeed=left+panelW-200,cHave=left+panelW-150,cMiss=left+panelW-100,cTake=left+panelW-50;
        gui.text(font,font.plainSubstrByWidth("材料（左键排除 · 右键改数量）",cNeed-left),left+10,top+46,0xff8fa3b8);right(gui,"需要",cNeed+40,top+46,0xff8fa3b8);right(gui,"背包",cHave+40,top+46,0xff8fa3b8);right(gui,"缺少",cMiss+40,top+46,0xff8fa3b8);right(gui,"取货",cTake+40,top+46,0xff8fa3b8);
        Row hovered=rowAt(mouseX,mouseY);
        for(int i=0;i<visibleRows()&&scroll+i<rows.size();i++){
            Row r=rows.get(scroll+i);int y=listTop+i*ROW;
            if(r==hovered)gui.fill(left+4,y,left+panelW-4,y+ROW,0x30ffffff);
            gui.item(r.icon,left+8,y+2);
            int color=r.included?0xffeeeeee:0xff687586;
            gui.text(font,font.plainSubstrByWidth(r.name+(r.included?"":"（已排除）"),cNeed-left-40),left+28,y+6,color);
            right(gui,Long.toString(r.need),cNeed+40,y+6,color);
            right(gui,Long.toString(r.have),cHave+40,y+6,r.included?0xffb8c4d0:color);
            right(gui,r.missing()>0?Long.toString(r.missing()):"✔",cMiss+40,y+6,!r.included?color:r.missing()>0?0xffff7777:0xff77dd88);
            right(gui,(r.custom>=0&&r.included?"*":"")+r.order(missingOnly),cTake+40,y+6,!r.included?color:r.custom>=0?0xffffd75e:0xffeeeeee);
        }
        if(maxScroll()>0)gui.text(font,"滚轮翻页 "+(scroll+1)+"–"+Math.min(rows.size(),scroll+visibleRows())+" / "+rows.size(),left+10,listBottom+2,0xff8fa3b8);
        if(hovered!=null)gui.setComponentTooltipForNextFrame(font,List.of(Component.literal(hovered.name),Component.literal(hovered.id).withStyle(ChatFormatting.DARK_GRAY),
            Component.literal("需要 "+hovered.need+"（"+stacks(hovered.need,hovered.stackSize)+"）"),Component.literal("背包已有 "+hovered.have+" · 缺少 "+hovered.missing()),
            Component.literal("本次取货 "+hovered.order(missingOnly)+(hovered.custom>=0?"（手动设置，自动为 "+hovered.automatic(missingOnly)+"）":"")).withStyle(hovered.custom>=0?ChatFormatting.YELLOW:ChatFormatting.WHITE),
            Component.literal((hovered.included?"左键排除此材料":"左键重新加入")+" · 右键修改数量").withStyle(ChatFormatting.GRAY)),mouseX,mouseY);
    }
    private void drawPreview(GuiGraphicsExtractor gui,int mouseX,int mouseY){
        if(plan==null)return;
        gui.text(font,font.plainSubstrByWidth("预估交付："+plan.boxes().size()+" 个潜影盒"+(plan.loose().isEmpty()?"":" + "+plan.loose().size()+" 个散装潜影盒")+" · 共 "+plan.lines().size()+" 种 "+plan.units()+" 件",panelW-20),left+10,top+22,0xffeeeeee);
        var who=new StringBuilder("收货假人：");
        for(int i=0;i<plan.recipients().size();i++){var r=plan.recipients().get(i);if(i>0)who.append(" → ");who.append(r.name()).append(" ").append(r.boxes()).append(" 盒");if(r.loose()>0)who.append("+").append(r.loose()).append(" 散装");}
        gui.text(font,font.plainSubstrByWidth(who.toString(),panelW-20),left+10,top+33,0xffa6b5c7);
        String stockLine;int stockColor;
        if(stockPending){stockLine="正在查询机器人库存（缓存）… 也可以直接强制开始";stockColor=0xffffd479;}
        else if(stockError!=null){stockLine="库存查询失败："+stockError+"；仍可开始，缺货项会跳过";stockColor=0xffff7777;}
        else if(stock!=null&&stock.missing().isEmpty()){stockLine="库存充足（按机器人缓存） · 鼠标移到盒子上查看内容";stockColor=0xff77dd88;}
        else if(stock!=null){stockLine="库存不足 "+stock.missing().size()+" 种（右侧列表），确认后缺货项跳过，其余照常取货";stockColor=0xffff9a7a;}
        else{stockLine="鼠标移到盒子上查看内容";stockColor=0xff8fa3b8;}
        gui.text(font,font.plainSubstrByWidth(stockLine,panelW-20),left+10,top+44,stockColor);
        if(shortPanel())drawShortages(gui);
        int hovered=cellAt(mouseX,mouseY),cols=columns(),rowsVisible=Math.max(1,(listBottom-listTop)/CELL);
        ItemStack box=new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace("shulker_box")));
        for(int line=0;line<rowsVisible;line++)for(int col=0;col<cols;col++){
            int i=(scroll+line)*cols+col;if(i>=cellCount())break;
            int x=left+8+col*CELL,y=listTop+line*CELL;boolean loose=i>=plan.boxes().size();
            gui.fill(x,y,x+CELL-2,y+CELL-2,i==hovered?0xff59c9ad:loose?0xff4a3b22:0xff293445);gui.fill(x+1,y+1,x+CELL-3,y+CELL-3,loose?0xff2c2418:0xff1a2330);
            ItemStack icon=loose?stack(plan.loose().get(i-plan.boxes().size())):box;
            gui.item(icon,x+2,y+2);gui.itemDecorations(font,icon,x+2,y+2,loose?null:Integer.toString(i+1));
        }
        if(maxScroll()>0)gui.text(font,"滚轮翻页",left+10,listBottom+2,0xff8fa3b8);
        if(hovered>=0)tooltip(gui,hovered,mouseX,mouseY);
    }
    /** A separate column listing what the robot's cache cannot cover. */
    private void drawShortages(GuiGraphicsExtractor gui){
        int x=left+panelW-SHORT_PANEL-4,w=SHORT_PANEL-4;
        gui.fill(x,listTop-2,x+w,listBottom,0xff2a1c1c);
        gui.text(font,"库存不足 · 需要/库存",x+4,listTop+2,0xffff9a7a);
        var missing=stock.missing();int rows=Math.max(1,(listBottom-listTop-14)/18);
        for(int i=0;i<Math.min(rows,missing.size());i++){
            var line=missing.get(i);int y=listTop+14+i*18;ItemStack icon=new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(line.itemId())));
            gui.item(icon,x+2,y);String counts=line.requested()+"/"+line.available();
            gui.text(font,font.plainSubstrByWidth(icon.getHoverName().getString(),w-24-font.width(counts)-6),x+20,y+4,0xffeeeeee);
            gui.text(font,counts,x+w-4-font.width(counts),y+4,0xffff7777);
        }
        if(missing.size()>rows)gui.text(font,"…另有 "+(missing.size()-rows)+" 种",x+4,listBottom-10,0xffa6b5c7);
    }
    private void tooltip(GuiGraphicsExtractor gui,int index,int mouseX,int mouseY){
        if(index>=plan.boxes().size()){
            var s=plan.loose().get(index-plan.boxes().size());
            gui.setComponentTooltipForNextFrame(font,List.of(Component.literal(stack(s).getHoverName().getString()+" ×"+s.count()),Component.literal("潜影盒不能装进潜影盒，散装交付").withStyle(ChatFormatting.GRAY)),mouseX,mouseY);
            return;
        }
        var b=plan.boxes().get(index);var lines=new ArrayList<Component>();
        lines.add(Component.literal("第 "+(index+1)+" 盒 · "+b.slots().size()+"/"+ParcelPlan.BOX_SLOTS+" 格 · "+b.units()+" 件").withStyle(ChatFormatting.AQUA));
        int shown=0;var totals=b.totals();
        for(var e:totals.entrySet()){if(shown++>=8){lines.add(Component.literal("…另有 "+(totals.size()-8)+" 种").withStyle(ChatFormatting.GRAY));break;}
            ItemStack s=stack(new ParcelPlan.Stack(e.getKey(),1));lines.add(Component.literal(s.getHoverName().getString()+" ×"+e.getValue()+"（"+stacks(e.getValue(),s.getMaxStackSize())+"）"));}
        var slots=new ArrayList<ItemStack>();for(var s:b.slots())slots.add(stack(s));
        gui.setTooltipForNextFrame(font,lines,Optional.of(new ParcelTooltip(slots)),mouseX,mouseY);
    }
    private static ItemStack stack(ParcelPlan.Stack s){return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(s.itemId())),s.count());}
    private static String stacks(long count,int size){long full=count/size,rest=count%size;return size==1?count+" 个":full==0?rest+" 个":full+" 组"+(rest>0?"+"+rest:"");}
    private void right(GuiGraphicsExtractor gui,String text,int x,int y,int color){gui.text(font,text,x-font.width(text),y,color);}
}

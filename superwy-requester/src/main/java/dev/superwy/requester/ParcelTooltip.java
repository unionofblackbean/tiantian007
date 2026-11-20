package dev.superwy.requester;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import java.util.List;

/** Tooltip image of one planned shulker parcel: the 27 slots laid out like the real box (9 × 3). */
public record ParcelTooltip(List<ItemStack> slots) implements TooltipComponent {
    public static final int COLUMNS=9;
    public static final class Client implements ClientTooltipComponent {
        private final List<ItemStack> slots;
        public Client(ParcelTooltip data){slots=data.slots();}
        private int rows(){return Math.max(1,(Math.max(slots.size(),ParcelPlan.BOX_SLOTS)+COLUMNS-1)/COLUMNS);}
        public int getHeight(Font font){return rows()*18+4;}
        public int getWidth(Font font){return COLUMNS*18+2;}
        public void extractImage(Font font,int x,int y,int width,int height,GuiGraphicsExtractor gui){
            for(int i=0;i<rows()*COLUMNS;i++){
                int sx=x+1+(i%COLUMNS)*18,sy=y+2+(i/COLUMNS)*18;
                gui.fill(sx,sy,sx+17,sy+17,0xff3a3a46);gui.fill(sx+1,sy+1,sx+16,sy+16,0xff8b8b99);
                if(i<slots.size()&&!slots.get(i).isEmpty()){gui.item(slots.get(i),sx,sy);gui.itemDecorations(font,slots.get(i),sx,sy);}
            }
        }
    }
}

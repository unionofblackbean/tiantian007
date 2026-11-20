package dev.superwy.requester;

/** Pure input/movement state; no Screen is opened and no movement key is captured. */
public final class QuantityPrompt {
    private boolean open,replace=true;
    private int maximum=64;
    private String digits="64";
    private long movingSince=-1;
    private double x,y,z;
    public void open(double x,double y,double z){open(x,y,z,64);}
    public void open(double x,double y,double z,int count){open(x,y,z,count,64);}
    public void open(double x,double y,double z,int count,int maximum){this.maximum=maximum;if(count<1||count>maximum)throw new IllegalArgumentException("数量为 1–"+maximum);this.x=x;this.y=y;this.z=z;open=true;replace=true;digits=Integer.toString(count);movingSince=-1;}
    public int maximum(){return maximum;}
    public boolean isOpen(){return open;}
    public void close(){open=false;movingSince=-1;}
    public String text(){return digits;}
    public int quantity(){return digits.isEmpty()?0:Integer.parseInt(digits);}
    public boolean valid(){return quantity()>=1&&quantity()<=maximum;}
    public void digit(int digit){
        if(digit<0||digit>9||!open)return;
        String next=replace?Integer.toString(digit):digits+digit;
        if(next.length()>7||Integer.parseInt(next)>maximum)return;
        digits=next;replace=false;
    }
    public void backspace(){if(!open)return;digits=replace?"":digits.substring(0,Math.max(0,digits.length()-1));replace=false;}
    public void selectAll(){replace=true;}
    public void move(double x,double y,double z,long now){
        if(!open)return;
        double delta=(this.x-x)*(this.x-x)+(this.y-y)*(this.y-y)+(this.z-z)*(this.z-z);
        this.x=x;this.y=y;this.z=z;
        if(delta>0.000001){if(movingSince<0)movingSince=now;else if(now-movingSince>=1000)close();}
        else movingSince=-1;
    }
    /** Accepts plain numbers, or "组" shorthand: "3组" / "3s" = 3 stacks, "3组+5" = 3 stacks and 5. */
    public static long parseAmount(String raw,int stackSize,long maximum){
        String s=raw.trim().replace(" ","").toLowerCase(java.util.Locale.ROOT);
        if(s.isEmpty())throw new IllegalArgumentException("请输入数量");
        long value;
        try{
            int sep=Math.max(s.indexOf('组'),s.indexOf('s'));
            if(sep<0)value=Long.parseLong(s);
            else{
                long stacks=sep==0?1:Long.parseLong(s.substring(0,sep));String rest=s.substring(sep+1);
                if(rest.startsWith("+"))rest=rest.substring(1);
                value=Math.addExact(Math.multiplyExact(stacks,stackSize),rest.isEmpty()?0:Long.parseLong(rest));
            }
        }catch(NumberFormatException|ArithmeticException e){throw new IllegalArgumentException("数量格式不对，可输入 128、2组、2组+5");}
        if(value<0||value>maximum)throw new IllegalArgumentException("数量为 0–"+maximum);
        return value;
    }
}

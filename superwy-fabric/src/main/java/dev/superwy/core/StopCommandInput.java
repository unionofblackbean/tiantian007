package dev.superwy.core;

/** Local command editor for stopping automation or disabling scans while controls are owned. */
public final class StopCommandInput {
    private boolean open,replace;private int openingCharacter;
    private String text="";
    public void open(boolean slash){open(slash,slash?'/':'t');}
    public void open(boolean slash,int opening){open=true;replace=false;text=slash?"/":"";openingCharacter=opening;}
    public boolean isOpen(){return open;}
    public String text(){return text;}
    public void close(){open=false;replace=false;openingCharacter=0;}
    public void character(int codepoint){
        if(!open||!Character.isValidCodePoint(codepoint)||Character.isISOControl(codepoint))return;
        if(openingCharacter!=0){int opening=openingCharacter;openingCharacter=0;if(codepoint==opening||opening=='t'&&codepoint=='T')return;}
        if(replace){text="";replace=false;}
        String next=text+new String(Character.toChars(codepoint));if(next.length()<=128)text=next;
    }
    public void backspace(){openingCharacter=0;if(replace){text="";replace=false;}else if(!text.isEmpty())text=text.substring(0,text.offsetByCodePoints(text.length(),-1));}
    public void selectAll(){replace=true;openingCharacter=0;}
    public void paste(String value){openingCharacter=0;if(replace){text="";replace=false;}String next=text+value.replaceAll("[\\r\\n]","");text=next.substring(0,Math.min(128,next.length()));}
    public boolean isStopCommand(){String command=text.strip();if(command.startsWith("/"))command=command.substring(1);return command.equals("superwy stop");}
    public boolean isScanOffCommand(){String command=text.strip();if(command.startsWith("/"))command=command.substring(1);return command.equals("superwy scan off");}
}

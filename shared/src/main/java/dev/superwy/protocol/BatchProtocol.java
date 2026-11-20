package dev.superwy.protocol;

/** Small ordered packets keep a full manifest below Minecraft's command length limit. */
public final class BatchProtocol {
    public static final String REQUEST="swbatch1",REPLY="swbatchreply1";
    public record Packet(String id,String action,String[] args){}
    private BatchProtocol(){}
    public static boolean request(String text){return text!=null&&text.startsWith(REQUEST+" ");}
    public static boolean reply(String text){return text!=null&&text.startsWith(REPLY+" ");}
    public static Packet parse(String text,boolean response){
        if(text==null||text.length()>230||text.indexOf('\n')>=0||text.indexOf('\r')>=0)throw new IllegalArgumentException("INVALID_REQUEST");
        String[] w=text.split(" ");
        if(w.length<3||!w[0].equals(response?REPLY:REQUEST)||!w[1].matches("[a-f0-9]{32}")||!w[2].matches("[a-z]+"))throw new IllegalArgumentException("INVALID_REQUEST");
        return new Packet(w[1],w[2],java.util.Arrays.copyOfRange(w,3,w.length));
    }
    public static String message(boolean response,String id,String action,Object...args){
        StringBuilder b=new StringBuilder(response?REPLY:REQUEST).append(' ').append(id).append(' ').append(action);
        for(Object arg:args)b.append(' ').append(arg);
        String text=b.toString();parse(text,response);return text;
    }
    public static int limit(int robot,int recipient){return Math.max(0,Math.min(robot,recipient)-5);}
    /** Optional third begin argument: deliver the whole manifest as shulker parcels (Litematica area order). */
    public static final String PARCELS="parcels";
    public static boolean validBegin(String[] args){
        if(args==null||args.length<2||args.length>3||!WhisperProtocol.name(args[0])||!WhisperProtocol.key(args[1].equals("-")?"":args[1]))return false;
        return args.length==2||PARCELS.equals(args[2]);
    }
    public static boolean parcelsRequested(String[] args){return args!=null&&args.length==3&&PARCELS.equals(args[2]);}
}

package dev.superwy.protocol;

import java.util.*;
import java.util.regex.Pattern;

/** Versioned, single-command PM protocol. No executable names, selectors or arbitrary commands. */
public final class WhisperProtocol {
    public static final String REQUEST="swget1",REPLY="swreply1";
    private static final Pattern NAME=Pattern.compile("[A-Za-z0-9_]{1,16}"),ITEM=Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+"),ID=Pattern.compile("[a-f0-9]{32}");
    public record Line(String itemId,int count){}
    public record Request(String recipient,List<Line> items,String key,String requestId){}
    public record Reply(String requestId,boolean accepted,String orderId,String code,int position){}
    private WhisperProtocol(){}
    public static boolean name(String value){return value!=null&&NAME.matcher(value).matches();}
    public static boolean key(String value){return value!=null&&(value.isEmpty()||value.matches("[A-Za-z0-9]{5}"));}
    public static String newId(){return UUID.randomUUID().toString().replace("-","");}
    public static boolean isRequest(String text){return text!=null&&(text.equals(REQUEST)||text.startsWith(REQUEST+" "));}
    public static boolean isReply(String text){return text!=null&&(text.equals(REPLY)||text.startsWith(REPLY+" "));}
    public static Request parse(String text){
        if(text==null||text.length()>230||text.indexOf('\n')>=0||text.indexOf('\r')>=0)throw invalid();
        String[] words=text.trim().split(" +");
        if(words.length<3||words.length>5||!words[0].equals(REQUEST)||!name(words[1]))throw invalid();
        List<Line> lines=new ArrayList<>();long total=0;
        for(String raw:words[2].split(",",-1)){
            String[] pair=raw.split("=",-1);if(pair.length!=2||!ITEM.matcher(pair[0]).matches()||!pair[1].matches("[0-9]{1,7}"))throw invalid();
            int count=Integer.parseInt(pair[1]);if(count<1||count>1_000_000)throw invalid();
            total+=count;lines.add(new Line(pair[0],count));
        }
        if(lines.size()>128||total>1_000_000)throw invalid();
        String key="",id=null;
        for(int i=3;i<words.length;i++){
            if(words[i].startsWith("id=")){if(id!=null||!ID.matcher(words[i].substring(3)).matches())throw invalid();id=words[i].substring(3);}
            else {if(i!=3||!key.isEmpty())throw invalid();key=words[i].equals("-")?"":words[i];if(!key(key))throw invalid();}
        }
        return new Request(words[1],List.copyOf(lines),key,id);
    }
    public static String encode(String recipient,List<Line> items,String key,String id){
        if(!key(key)||id==null||!ID.matcher(id).matches())throw invalid();
        var list=items.stream().map(i->i.itemId()+"="+i.count()).toList();
        String text=REQUEST+" "+recipient+" "+String.join(",",list)+" "+(key.isEmpty()?"-":key)+" id="+id;
        parse(text);return text;
    }
    public static String command(String bot,String body){
        if(!name(bot))throw new IllegalArgumentException("请配置接收私聊的机器人玩家名");
        String command="tell "+bot+" "+body;
        if(command.length()>256||body.indexOf('\n')>=0||body.indexOf('\r')>=0)throw new IllegalArgumentException("私聊清单超过单条消息长度，请拆分请求");
        return command;
    }
    public static String responseId(String text){
        if(text!=null)for(String word:text.split(" "))if(word.startsWith("id=")&&ID.matcher(word.substring(3)).matches())return word.substring(3);
        return null;
    }
    public static String accepted(String id,String order,int position){return REPLY+" "+id+" accepted "+order+" "+Math.max(0,position);}
    public static String rejected(String id,String code){return REPLY+" "+id+" rejected "+(code!=null&&code.matches("[A-Z_]{1,64}")?code:"INVALID_REQUEST");}
    public static Reply parseReply(String text){
        if(text==null||text.length()>230)throw invalid();String[] w=text.split(" ");
        if(w.length<4||!w[0].equals(REPLY)||!ID.matcher(w[1]).matches())throw invalid();
        if(w[2].equals("rejected")&&w.length==4&&w[3].matches("[A-Z_]{1,64}"))return new Reply(w[1],false,null,w[3],0);
        if(w[2].equals("accepted")&&w.length==5&&w[3].matches("ord_[A-Za-z0-9_-]{1,100}")&&w[4].matches("[0-9]{1,3}"))return new Reply(w[1],true,w[3],null,Integer.parseInt(w[4]));
        throw invalid();
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("INVALID_WHISPER_REQUEST");}
}

package dev.superwy.protocol;

import java.util.regex.Pattern;

/** Exact server-rendered private-message templates; no substring search in arbitrary chat. */
public final class SystemWhisperFormat {
    public record Parsed(String senderName,String body,boolean outgoing){}
    private static final String BODY="(sw(?:get|reply|batch|batchreply|mesh)1(?: +[^\\r\\n]*)?)";
    private static final Pattern CHINESE=Pattern.compile("^([A-Za-z0-9_]{1,16})\\s*悄悄地[对對]你[说說]\\s*[:：]\\s*"+BODY+"$");
    private static final Pattern ENGLISH=Pattern.compile("^([A-Za-z0-9_]{1,16}) whispers to you: *"+BODY+"$");
    private static final Pattern OUT_CHINESE=Pattern.compile("^你悄悄地[对對][^\\r\\n]+[说說]\\s*[:：]\\s*"+BODY+"$");
    private static final Pattern OUT_ENGLISH=Pattern.compile("^You whisper to [^\\r\\n]+: *"+BODY+"$");
    private SystemWhisperFormat(){}
    public static Parsed parse(String text){
        if(text==null||text.length()>1024)return null;
        text=text.replaceAll("§[0-9A-FK-ORXa-fk-orx]","").strip();
        for(var pattern:new Pattern[]{CHINESE,ENGLISH}){
            var match=pattern.matcher(text);if(match.matches())return new Parsed(match.group(1),match.group(2).strip(),false);
        }
        for(var pattern:new Pattern[]{OUT_CHINESE,OUT_ENGLISH}){
            var match=pattern.matcher(text);if(match.matches())return new Parsed(null,match.group(1).strip(),true);
        }
        return null;
    }
}

package dev.superwy.protocol;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** A compact local-client command; contains a scoped player credential, never an admin/FRP key. */
public final class ConnectionCode {
    private ConnectionCode(){}
    public record Settings(String url,String token,String recipient,String bot,String warehouse){
        @Override public String toString(){return "ConnectionSettings[credential omitted]";}
    }
    public static String encode(Settings settings){
        validate(settings);
        String code=(settings.recipient().isEmpty()?"swc2.":"swc1.")+Base64.getUrlEncoder().withoutPadding().encodeToString(String.join("\n",settings.url(),settings.token(),settings.recipient(),settings.bot(),settings.warehouse()).getBytes(StandardCharsets.UTF_8));
        if(("/swrequest connect "+code).length()>256)throw invalid();
        return code;
    }
    public static Settings decode(String code){
        try{
            if(code==null||code.length()>238||!(code.startsWith("swc1.")||code.startsWith("swc2.")))throw invalid();
            String[] fields=new String(Base64.getUrlDecoder().decode(code.substring(5)),StandardCharsets.UTF_8).split("\n",-1);
            if(fields.length!=5)throw invalid();
            if(code.startsWith("swc1.")==fields[2].isEmpty())throw invalid();
            var settings=new Settings(fields[0],fields[1],fields[2],fields[3],fields[4]);validate(settings);return settings;
        }catch(RuntimeException error){throw invalid();}
    }
    private static void validate(Settings settings){
        try{
            URI uri=URI.create(settings.url());
            if(!java.util.Set.of("http","https").contains(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!(uri.getPath().isEmpty()||uri.getPath().equals("/"))||uri.getPort()>65535||uri.getPort()==0)throw invalid();
            if(!settings.token().matches("[A-Za-z0-9._~+/=-]{32,128}")||!(settings.recipient().isEmpty()||WhisperProtocol.name(settings.recipient()))||!(settings.bot().isEmpty()||WhisperProtocol.name(settings.bot()))||!"main".equals(settings.warehouse()))throw invalid();
        }catch(RuntimeException error){throw invalid();}
    }
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("连接指令无效或过长，请重新生成完整指令");}
}

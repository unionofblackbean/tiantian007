package dev.superwy.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Robot-to-robot handshake over server private messages. Only used to find the authority's FRP address;
 * all dispatch traffic after the handshake goes over signed HTTP.
 *
 *   swmesh1 hello   <nonce> <timeMillis> <signature>
 *   swmesh1 welcome <nonce> <base64url(authorityUrl)> <signature>
 *   swmesh1 wait    <nonce> <signature>
 */
public final class MeshProtocol {
    public static final String PREFIX="swmesh1";
    public record Packet(String action,String nonce,String arg,String signature){}
    private MeshProtocol(){}
    public static boolean is(String text){return text!=null&&text.startsWith(PREFIX+" ");}
    public static Packet parse(String text){
        if(text==null||text.length()>230||text.indexOf('\n')>=0||text.indexOf('\r')>=0)throw invalid();
        String[] w=text.split(" ");
        if(w.length<4||!w[0].equals(PREFIX)||!w[2].matches("[a-f0-9]{32}")||!w[w.length-1].matches("[a-f0-9]{64}"))throw invalid();
        return switch(w[1]){
            case "hello"->{if(w.length!=5||!w[3].matches("[0-9]{1,15}"))throw invalid();yield new Packet("hello",w[2],w[3],w[4]);}
            case "welcome"->{if(w.length!=5||!w[3].matches("[A-Za-z0-9_-]{4,200}"))throw invalid();yield new Packet("welcome",w[2],w[3],w[4]);}
            case "wait"->{if(w.length!=4)throw invalid();yield new Packet("wait",w[2],null,w[3]);}
            default->throw invalid();
        };
    }
    public static String hello(String nonce,long time,String signature){return check(PREFIX+" hello "+nonce+" "+time+" "+signature);}
    public static String welcome(String nonce,String url,String signature){return check(PREFIX+" welcome "+nonce+" "+encodeUrl(url)+" "+signature);}
    public static String waiting(String nonce,String signature){return check(PREFIX+" wait "+nonce+" "+signature);}
    public static String encodeUrl(String url){return Base64.getUrlEncoder().withoutPadding().encodeToString(url.getBytes(StandardCharsets.UTF_8));}
    public static String decodeUrl(String value){
        String url=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8);
        if(!url.matches("https?://[A-Za-z0-9.-]{1,253}(:[0-9]{1,5})?/?"))throw invalid();
        return url.endsWith("/")?url.substring(0,url.length()-1):url;
    }
    private static String check(String text){parse(text);return text;}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("INVALID_MESH_MESSAGE");}
}

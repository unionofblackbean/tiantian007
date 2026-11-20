package dev.superwy.mesh;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Shared robot secret derived from the FRP credential in the owner's private jar. Stable across repackaging,
 * so robots on different private-jar builds still trust each other. Public jars have no key and cannot join.
 */
public final class MeshKey {
    private final byte[] key;
    private MeshKey(byte[] key){this.key=key;}
    public static MeshKey of(String token){
        if(token==null||token.isEmpty())return new MeshKey(null);
        try{return new MeshKey(MessageDigest.getInstance("SHA-256").digest(("superwy-mesh-v1\n"+token).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    public static MeshKey fromPrivatePreset(){
        try(var input=MeshKey.class.getResourceAsStream("/superwy-private.json")){
            if(input==null)return new MeshKey(null);
            JsonObject json=JsonParser.parseReader(new InputStreamReader(input,StandardCharsets.UTF_8)).getAsJsonObject();
            return of(json.has("authToken")&&json.get("authToken").isJsonPrimitive()?json.get("authToken").getAsString():null);
        }catch(Exception e){return new MeshKey(null);}
    }
    public boolean available(){return key!=null;}
    public String sign(String...parts){
        if(key==null)throw new IllegalStateException("MESH_KEY_MISSING");
        try{
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(String.join("\n",parts).getBytes(StandardCharsets.UTF_8)));
        }catch(Exception e){throw new IllegalStateException(e);}
    }
    public boolean verify(String signature,String...parts){
        if(key==null||signature==null||!signature.matches("[a-f0-9]{64}"))return false;
        return MessageDigest.isEqual(sign(parts).getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII));
    }
    public static String bodyHash(byte[] body){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body==null?new byte[0]:body));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
}

package dev.superwy;

import com.google.gson.JsonParser;
import dev.superwy.core.*;
import dev.superwy.mesh.MeshKey;
import dev.superwy.protocol.*;
import org.junit.jupiter.api.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MeshProtocolTest {
    private static final String NONCE="0123456789abcdef0123456789abcdef";
    @Test void handshakeRoundTripsAndStaysShort(){
        var key=MeshKey.of("frp-token");String sig=key.sign("welcome","tiantian996",NONCE,"http://frp.example.com:43210");
        String text=MeshProtocol.welcome(NONCE,"http://frp.example.com:43210",sig);
        assertTrue(("tell tiantian996 "+text).length()<=256);
        var p=MeshProtocol.parse(text);assertEquals("welcome",p.action());assertEquals("http://frp.example.com:43210",MeshProtocol.decodeUrl(p.arg()));
        assertTrue(key.verify(p.signature(),"welcome","tiantian996",NONCE,MeshProtocol.decodeUrl(p.arg())));
        assertEquals("hello",MeshProtocol.parse(MeshProtocol.hello(NONCE,123,sig)).action());
        assertEquals("wait",MeshProtocol.parse(MeshProtocol.waiting(NONCE,sig)).action());
    }
    @Test void malformedPacketsAreRejected(){
        assertThrows(IllegalArgumentException.class,()->MeshProtocol.parse("swmesh1 hello xyz 1 abc"));
        assertThrows(IllegalArgumentException.class,()->MeshProtocol.decodeUrl(MeshProtocol.encodeUrl("javascript:alert(1)")));
        assertTrue(SystemWhisperFormat.parse("tiantian996悄悄地对你说：swmesh1 wait "+NONCE+" "+"a".repeat(64))!=null);
    }
    @Test void keysFromDifferentCredentialsDoNotMatch(){
        var a=MeshKey.of("one");var b=MeshKey.of("two");String sig=a.sign("x");
        assertTrue(a.verify(sig,"x"));assertFalse(b.verify(sig,"x"));assertFalse(a.verify(sig,"y"));
        assertFalse(MeshKey.of("").available());assertThrows(IllegalStateException.class,()->MeshKey.of(null).sign("x"));
    }
    @Test void httpBatchOptionsBecomeABatchWithStableId(){
        var principal=new Config.Principal("link_1",false,List.of(),List.of("orders:write"),true);
        String json="{\"warehouseId\":\"main\",\"clientRequestId\":\"batch-1\",\"items\":[{\"itemId\":\"minecraft:stone\",\"count\":5000},{\"itemId\":\"minecraft:dirt\",\"count\":3}],\"delivery\":{\"method\":\"carpet_fake_player\",\"fakePlayerName\":\"sf\"},\"options\":{\"batch\":true,\"projectionParcels\":true,\"allowPartial\":true}}";
        var a=Requests.parse(JsonParser.parseString(json).getAsJsonObject(),principal);var b=Requests.parse(JsonParser.parseString(json).getAsJsonObject(),principal);
        assertNotNull(a.batchId);assertEquals(a.batchId,b.batchId);assertTrue(a.projectionParcels);assertFalse(a.meshParent);
        var single=Requests.parse(JsonParser.parseString("{\"warehouseId\":\"main\",\"items\":[{\"itemId\":\"minecraft:stone\",\"count\":5}],\"delivery\":{\"method\":\"carpet_fake_player\",\"fakePlayerName\":\"sf\"}}").getAsJsonObject(),principal);
        assertNull(single.batchId);
    }
}

package dev.superwy;

import dev.superwy.protocol.ConnectionCode;
import dev.superwy.core.Config;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConnectionCodeTest {
    @Test void commandFitsVanillaChatAndRoundTrips(){
        var s=new ConnectionCode.Settings("http://frp.example.com:59999","a".repeat(43),"BuilderCargo_123","tiantian996","main");
        var code=ConnectionCode.encode(s);assertTrue(("/swrequest connect "+code).length()<=256);assertEquals(s,ConnectionCode.decode(code));assertFalse(s.toString().contains(s.token()));
    }
    @Test void corruptedAndUntrustedEndpointsAreRejectedWithoutEchoingCredential(){
        for(String code:new String[]{"bad","swc1.abc","swc1."+"a".repeat(300)})assertThrows(IllegalArgumentException.class,()->ConnectionCode.decode(code));
        for(String url:new String[]{"file:///test","http://user:pass@host","http://host/path","http://host?x=1","http://host#secret","http://host:0"})assertThrows(IllegalArgumentException.class,()->ConnectionCode.encode(new ConnectionCode.Settings(url,"a".repeat(43),"Cargo","Bot","main")));
    }
    @Test void sharedCredentialIsScopedDurableAndNeverAdmin()throws Exception{
        var c=new Config();var credential=c.playerCredential("Cargo");assertSame(credential,c.playerCredential("Cargo"));assertNotEquals(c.adminToken,credential.token);
        var principal=c.authenticate(credential.token);assertFalse(principal.admin());assertTrue(principal.permits("Cargo"));assertFalse(principal.permits("Another"));assertTrue(principal.scope("orders:write"));
        assertThrows(IllegalArgumentException.class,()->c.playerCredential("@a"));assertEquals(1,c.credentials.size());
    }
    @Test void newConnectionLetsRequesterChooseNameAndKeepsCredentialsIndependent(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)throws Exception{
        var c=new Config();var first=c.connectionCredential();var second=c.connectionCredential();
        assertNotEquals(first.token,second.token);assertNotEquals(first.principalId,second.principalId);assertNotEquals(c.adminToken,first.token);
        var settings=new ConnectionCode.Settings("http://example.com:23456",first.token,"","Bot","main");String code=ConnectionCode.encode(settings);
        assertTrue(code.startsWith("swc2."));assertEquals(settings,ConnectionCode.decode(code));assertTrue(("/swrequest connect "+code).length()<=256);
        c.save(dir.resolve("config.json"));var principal=Config.load(dir.resolve("config.json")).authenticate(first.token);
        assertFalse(principal.admin());assertTrue(principal.permits("sf"));assertTrue(principal.permits("MyCargo"));assertFalse(principal.permits("@a"));assertFalse(principal.scope("admin"));
        assertThrows(IllegalArgumentException.class,()->ConnectionCode.decode("swc1."+code.substring(5)));
    }
}

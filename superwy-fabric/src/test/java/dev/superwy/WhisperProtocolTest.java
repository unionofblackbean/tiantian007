package dev.superwy;

import dev.superwy.protocol.WhisperProtocol;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WhisperProtocolTest {
    @Test void optionalKeyAndManualSingleOrListRequests(){
        var one=WhisperProtocol.parse("swget1 Cargo minecraft:stone=64");assertEquals("",one.key());assertNull(one.requestId());assertEquals(64,one.items().getFirst().count());
        var list=WhisperProtocol.parse("swget1 Cargo minecraft:stone=13,minecraft:iron_ingot=9 Ab123");assertEquals(2,list.items().size());assertEquals("Ab123",list.key());
    }
    @Test void independentRequestIdsAndCanonicalRoundTrip(){
        String id=WhisperProtocol.newId();assertNotEquals(id,WhisperProtocol.newId());
        var text=WhisperProtocol.encode("Cargo",List.of(new WhisperProtocol.Line("minecraft:stone",12)),"",id);
        var parsed=WhisperProtocol.parse(text);assertEquals(id,parsed.requestId());assertEquals(id,WhisperProtocol.responseId(text));
        assertTrue(WhisperProtocol.command("WarehouseBot",text).startsWith("tell WarehouseBot swget1 "));
    }
    @Test void selectorCommandInjectionMalformedCountsAndKeysAreRejected(){
        for(String text:List.of("swget1 @a minecraft:stone=1","swget1 Cargo minecraft:stone=-1","swget1 Cargo minecraft:stone=0","swget1 Cargo minecraft:stone=1.5","swget1 Cargo minecraft:stone=1 1234","swget1 Cargo minecraft:stone=1 123456","swget1 Cargo minecraft:stone=1\nplayer bad kill","swget1 Cargo minecraft:stone=1 id=bad","swget1 Cargo minecraft:stone=1,","swget1 Cargo minecraft:stone=1000001"))assertThrows(IllegalArgumentException.class,()->WhisperProtocol.parse(text),text);
        assertThrows(IllegalArgumentException.class,()->WhisperProtocol.command("@a","body"));assertThrows(IllegalArgumentException.class,()->WhisperProtocol.command("Bot","x".repeat(256)));
    }
    @Test void repliesNeverEchoKeyOrRawFailureText(){
        String id=WhisperProtocol.newId();var accepted=WhisperProtocol.parseReply(WhisperProtocol.accepted(id,"ord_test",2));assertTrue(accepted.accepted());assertEquals(2,accepted.position());
        var rejected=WhisperProtocol.parseReply(WhisperProtocol.rejected(id,"key Ab123 does not match"));assertEquals("INVALID_REQUEST",rejected.code());assertFalse(rejected.accepted());
    }
}

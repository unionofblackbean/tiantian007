package dev.superwy;

import dev.superwy.core.*;
import dev.superwy.game.WhisperInbox;
import dev.superwy.protocol.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Player orders by private message are retired: every request is refused with a hint, nothing is queued. */
class WhisperInboxTest {
    @TempDir Path dir;private Store store;private Config config;private WhisperInbox inbox;private final Queue<Runnable> commands=new ArrayDeque<>();private final List<String> replies=new ArrayList<>();
    @BeforeEach void setup()throws Exception{store=new Store(dir.resolve("db"));config=new Config();inbox=new WhisperInbox(config,store,()->true,()->"world",()->"dimension",commands,(name,body)->replies.add(body));}
    @AfterEach void close()throws Exception{store.close();}
    private void receive(UUID sender,String body){inbox.receive(new PrivateMessages.Message(sender,"PlayerA",body));while(!commands.isEmpty())commands.remove().run();}
    private String request(String id){return WhisperProtocol.encode("Cargo",List.of(new WhisperProtocol.Line("minecraft:stone",13)),"",id);}
    @Test void requestsAreRefusedWithHintAndNeverQueued(){
        String id=WhisperProtocol.newId();receive(UUID.randomUUID(),request(id));
        var reply=WhisperProtocol.parseReply(replies.get(0));assertFalse(reply.accepted());assertEquals("WHISPER_ORDERS_DISABLED",reply.code());assertEquals(id,reply.requestId());
        assertTrue(replies.get(1).contains("HTTP"));assertTrue(store.orders().isEmpty());
    }
    @Test void meshModeHintNamesTheAuthority(){
        config.mesh.enabled=true;receive(UUID.randomUUID(),request(WhisperProtocol.newId()));
        assertTrue(replies.get(1).contains("/swrequest mesh on"));assertTrue(replies.get(1).contains("tiantian996"));assertTrue(store.orders().isEmpty());
    }
    @Test void ordinaryChatIsIgnored(){assertFalse(inbox.receive(new PrivateMessages.Message(UUID.randomUUID(),"PlayerA","ordinary chat")));}
}

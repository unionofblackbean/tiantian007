package dev.superwy;

import dev.superwy.protocol.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SystemWhisperFormatTest {
    private final String body="swget1 sf1 minecraft:gray_carpet=64 - id=30e1f45ffdb64ffa824ab01b72378705";
    @Test void exactReportedServerLineIsRecognized(){
        var parsed=SystemWhisperFormat.parse("PlayerA悄悄地对你说："+body);assertNotNull(parsed);assertEquals("PlayerA",parsed.senderName());assertEquals(body,parsed.body());assertFalse(parsed.outgoing());
    }
    @Test void receiptsEnglishAndLegacyColorsAreRecognized(){
        var reply=SystemWhisperFormat.parse("§7tiantian996悄悄地对你说：swreply1 30e1f45ffdb64ffa824ab01b72378705 accepted ord_test 1");assertNotNull(reply);assertEquals("tiantian996",reply.senderName());assertTrue(WhisperProtocol.isReply(reply.body()));
        assertNotNull(SystemWhisperFormat.parse("Player whispers to you: "+body));
    }
    @Test void outgoingBadgeTemplateIsHiddenButNeverBecomesAnOrder(){
        var outgoing=SystemWhisperFormat.parse("你悄悄地对☷tiantian996说："+body);assertNotNull(outgoing);assertTrue(outgoing.outgoing());assertNull(outgoing.senderName());
    }
    @Test void ordinaryPublicChatSystemAnnouncementsAndSelectorsAreIgnored(){
        for(String text:new String[]{"<Bob> PlayerA悄悄地对你说："+body,"[Server] PlayerA悄悄地对你说："+body,"[Bob] "+body,"@a悄悄地对你说："+body,"PlayerA悄悄地对你说：hello",body,"PlayerA悄悄地对你说："+body+"\n"+body})assertNull(SystemWhisperFormat.parse(text),text);
    }
}

package dev.superwy;
import dev.superwy.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ErrorMessagesTest {
    @Test void reportedErrorsHaveChineseExplanationsAndNeverAskForAConfiguredWorkstation(){
        assertTrue(ErrorMessages.message("NO_INTERACTION_STAND").contains("不需要配置工作点"));
        var o=new Model.Order();var line=new Model.Line();line.itemId="minecraft:stone";o.lines.add(line);
        var say=OrderFailurePolicy.announcements(o,"FAKE_PLAYER_BUSY").getFirst();assertTrue(say.contains("收货假人已在线"));assertFalse(say.contains("FAKE_PLAYER_BUSY"));
    }
    @Test void unknownRawExceptionsAndIncompleteNbtAreLocalizedWithoutEchoingRawText(){
        assertEquals("服务器返回的库存数据不完整",ErrorMessages.message("INCOMPLETE_NBT_Inventory"));
        String result=ErrorMessages.message("raw exception\nsay another");assertTrue(result.contains("异常"));assertFalse(result.contains("raw"));assertFalse(result.contains("\n"));
        assertNull(ErrorMessages.order(new Model.Order()));
    }
}

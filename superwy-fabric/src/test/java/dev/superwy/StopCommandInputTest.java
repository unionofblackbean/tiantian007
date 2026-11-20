package dev.superwy;
import dev.superwy.core.StopCommandInput;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StopCommandInputTest {
    private void type(StopCommandInput input,String text){text.codePoints().forEach(input::character);}
    @Test void slashCommandIsAccepted(){var input=new StopCommandInput();input.open(true);type(input,"superwy stop");assertTrue(input.isStopCommand());}
    @Test void openingSlashCharacterIsNotDuplicated(){var input=new StopCommandInput();input.open(true);type(input,"/superwy stop");assertEquals("/superwy stop",input.text());assertTrue(input.isStopCommand());}
    @Test void chatKeyCharacterIsIgnored(){var input=new StopCommandInput();input.open(false);type(input,"t/superwy stop");assertTrue(input.isStopCommand());}
    @Test void customChatBindingWorks(){var input=new StopCommandInput();input.open(false,'y');type(input,"y/superwy stop");assertTrue(input.isStopCommand());}
    @Test void otherCommandsCannotTakeControl(){for(String command:new String[]{"/tp 0 80 0","/superwy resume","/superwy stop extra","hello","/superwy pause"}){var input=new StopCommandInput();input.open(false);input.paste(command);assertFalse(input.isStopCommand());}}
    @Test void editAndPasteWork(){var input=new StopCommandInput();input.open(true);type(input,"superwy stox");input.backspace();input.character('p');assertTrue(input.isStopCommand());input.selectAll();input.paste("/superwy stop\n");assertEquals("/superwy stop",input.text());assertTrue(input.isStopCommand());}
    @Test void closeStopsTypingAndLimitIsBounded(){var input=new StopCommandInput();input.open(false);type(input,"x".repeat(200));assertEquals(128,input.text().length());input.close();input.character('y');assertEquals(128,input.text().length());assertFalse(input.isOpen());}
}

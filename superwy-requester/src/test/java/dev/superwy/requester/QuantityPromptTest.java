package dev.superwy.requester;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class QuantityPromptTest {
    private QuantityPrompt open(){var p=new QuantityPrompt();p.open(0,0,0);return p;}
    @Test void firstDigitReplacesDefault(){var p=open();assertEquals(64,p.quantity());p.digit(1);p.digit(2);assertEquals(12,p.quantity());}
    @Test void bulkInputAcceptsLargeCountWithoutChangingSingleLimit(){var p=new QuantityPrompt();p.open(0,0,0,64,1_000_000);for(char digit:"1000000".toCharArray())p.digit(digit-'0');assertEquals(1_000_000,p.quantity());p.digit(1);assertEquals(1_000_000,p.quantity());p.open(0,0,0);p.digit(6);p.digit(5);assertEquals(6,p.quantity());}
    @Test void rejectsAbove64(){var p=open();p.digit(6);p.digit(5);assertEquals(6,p.quantity());p.digit(4);assertEquals(64,p.quantity());p.digit(1);assertEquals(64,p.quantity());}
    @Test void emptyAndZeroCannotSubmit(){var p=open();p.backspace();assertFalse(p.valid());p.digit(0);assertFalse(p.valid());p.digit(3);assertEquals(3,p.quantity());assertTrue(p.valid());}
    @Test void oneSecondContinuousMovementCloses(){var p=open();p.move(1,0,0,100);p.move(2,0,0,1099);assertTrue(p.isOpen());p.move(3,0,0,1100);assertFalse(p.isOpen());}
    @Test void materialAmountAcceptsNumbersAndStacks(){
        assertEquals(128,QuantityPrompt.parseAmount(" 128 ",64,1_000_000));
        assertEquals(0,QuantityPrompt.parseAmount("0",64,1_000_000));
        assertEquals(128,QuantityPrompt.parseAmount("2组",64,1_000_000));
        assertEquals(133,QuantityPrompt.parseAmount("2组+5",64,1_000_000));
        assertEquals(37,QuantityPrompt.parseAmount("2s5",16,1_000_000));
        assertEquals(3,QuantityPrompt.parseAmount("3",1,1_000_000));
    }
    @Test void materialAmountRejectsBadInput(){
        for(String bad:new String[]{"","abc","-1","1000001","1.5","2组x"})assertThrows(IllegalArgumentException.class,()->QuantityPrompt.parseAmount(bad,64,1_000_000),bad);
    }
    @Test void stationaryDoesNotClose(){var p=open();for(int i=0;i<100;i++)p.move(0,0,0,i*100);assertTrue(p.isOpen());}
    @Test void stoppingResetsTimer(){var p=open();p.move(1,0,0,100);p.move(2,0,0,900);p.move(2,0,0,950);p.move(3,0,0,1100);p.move(4,0,0,1600);assertTrue(p.isOpen());p.move(5,0,0,2100);assertFalse(p.isOpen());}
}

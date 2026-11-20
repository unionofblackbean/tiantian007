package dev.superwy;
import dev.superwy.core.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
class PackingPolicyTest {
    @Test void reservesWorkSlotsAndCapsEachRoundAndCarriedBoxes(){
        assertEquals(25,PackingPolicy.round(27,25,true));assertEquals(24,PackingPolicy.round(26,25,false));assertEquals(1,PackingPolicy.round(3,25,false));assertEquals(0,PackingPolicy.round(2,25,false));
        assertEquals(26,PackingPolicy.slots(1664,64));assertEquals(64,PackingPolicy.slots(64,1));
    }
    @Test void overflowNamesFitMinecraftLimitAndDefaultsAreRequestedCoordinates(){
        assertEquals("sf_1",PackingPolicy.recipient("sf",1));assertEquals("sf_25",PackingPolicy.recipient("sf",25));assertEquals(16,PackingPolicy.recipient("abcdefghijklmnop",100).length());
        var c=new Config();assertEquals(new Model.Pos(80,50,-23),c.returnEntry);assertEquals(new Model.Pos(129,53,-21),c.boxSupply);assertTrue(c.cleanupMainInventory);assertEquals(25,c.maxCarriedParcels);
    }
    @Test void packingBudgetDoesNotExpireAtNormalFiveHundredSeconds(){
        var now=new AtomicLong();var task=new TaskScope(now::get,86400);now.set(TimeUnit.SECONDS.toNanos(501));assertFalse(task.expired());now.set(TimeUnit.SECONDS.toNanos(86400));assertTrue(task.expired());
    }
}

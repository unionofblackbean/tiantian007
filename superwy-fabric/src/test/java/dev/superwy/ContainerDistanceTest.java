package dev.superwy;
import dev.superwy.core.ContainerDistance;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ContainerDistanceTest {
    @Test void reachUsesClosestFaceRatherThanBlockCenter(){assertEquals(10,ContainerDistance.toBlock(0,70.5,.5,10,70,0),1e-9);assertEquals(0,ContainerDistance.toBlock(10.5,70.5,.5,10,70,0),1e-9);}
    @Test void overheadAndNegativeCoordinateGoalsUseActualEyePosition(){assertEquals(2.38,ContainerDistance.toBlock(.5,71.62,.5,0,74,0),1e-8);assertEquals(Math.sqrt(8),ContainerDistance.toBlock(-3,72.5,-3,-1,72,-1),1e-9);}
}

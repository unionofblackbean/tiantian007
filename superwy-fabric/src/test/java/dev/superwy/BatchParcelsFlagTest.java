package dev.superwy;

import dev.superwy.protocol.BatchProtocol;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BatchParcelsFlagTest {
    private static String[] args(String text){return BatchProtocol.parse(text,false).args();}
    private static final String ID="0123456789abcdef0123456789abcdef";
    @Test void legacyBeginStillAccepted(){
        var a=args(BatchProtocol.message(false,ID,"begin","sf","-"));
        assertTrue(BatchProtocol.validBegin(a));assertFalse(BatchProtocol.parcelsRequested(a));
    }
    @Test void projectionBeginRequestsParcels(){
        var a=args(BatchProtocol.message(false,ID,"begin","sf","Ab123",BatchProtocol.PARCELS));
        assertTrue(BatchProtocol.validBegin(a));assertTrue(BatchProtocol.parcelsRequested(a));
    }
    @Test void unknownFlagsAndBadNamesRejected(){
        assertFalse(BatchProtocol.validBegin(args(BatchProtocol.message(false,ID,"begin","sf","-","loose"))));
        assertFalse(BatchProtocol.validBegin(args(BatchProtocol.message(false,ID,"begin","sf","-","parcels","x"))));
        assertFalse(BatchProtocol.validBegin(args(BatchProtocol.message(false,ID,"begin","bad-name!","-"))));
        assertFalse(BatchProtocol.validBegin(args(BatchProtocol.message(false,ID,"begin","sf","abc"))));
        assertFalse(BatchProtocol.validBegin(new String[]{"sf"}));
    }
}

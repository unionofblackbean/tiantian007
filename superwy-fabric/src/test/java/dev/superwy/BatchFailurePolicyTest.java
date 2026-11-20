package dev.superwy;

import dev.superwy.core.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BatchFailurePolicyTest {
    @Test void lineFailuresCanSkipButUncertainCargoAndCancellationCannot(){
        for(String code:new String[]{"OUT_OF_STOCK","MENU_DESYNC","CURSOR_OR_MENU_DESYNC","SOURCE_BOX_CHANGED","SOURCE_CHANGED"})assertTrue(BatchFailurePolicy.skippable(code));
        for(String code:new String[]{"USER_CANCELLED","ORDER_TIMEOUT","DISCONNECTED","STORAGE_UNAVAILABLE","DELIVERY_UNCERTAIN","RECONCILIATION_REQUIRED","ORDER_CARGO_CHANGED"})assertFalse(BatchFailurePolicy.skippable(code));
    }
    @Test void partialWithdrawalIsPreservedAndRemainingDemandIsSkipped(){
        var o=new Model.Order();var l=new Model.Line();l.requested=64;l.fetched=13;o.lines.add(l);
        BatchFailurePolicy.skip(l,"OUT_OF_STOCK");assertEquals(13,l.fetched);assertEquals(51,l.shortfall);assertEquals("OUT_OF_STOCK",l.errorCode);assertEquals(1,BatchFailurePolicy.processed(o));
        var restored=Jsons.GSON.fromJson(Jsons.GSON.toJson(o),Model.Order.class);assertTrue(restored.lines.getFirst().skipped);assertEquals(51,restored.lines.getFirst().shortfall);
    }
    @Test void partialDeliveryAndEmptyExhaustedManifestAreCompletions(){
        var o=new Model.Order();var l=new Model.Line();l.requested=1;o.lines.add(l);o.state="failed";o.errorCode="NOTHING_DELIVERED";
        assertFalse(BatchFailurePolicy.completed(o));BatchFailurePolicy.skip(l,"OUT_OF_STOCK");assertTrue(BatchFailurePolicy.completed(o));
        o.errorCode="RECONCILIATION_REQUIRED";assertFalse(BatchFailurePolicy.completed(o));o.state="partial_stored_offline";assertTrue(BatchFailurePolicy.completed(o));
    }
}

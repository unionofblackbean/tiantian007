package dev.superwy.requester;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BatchDraftTest {
    @Test void skippedRepliesAreBoundedAndDoNotInflateTheCount(){
        var draft=new BatchDraft();draft.id="d".repeat(32);draft.capacity(3);draft.add("minecraft:stone",1,64);draft.add("minecraft:dirt",1,64);
        assertTrue(draft.skip(0,"OUT_OF_STOCK"));assertTrue(draft.skip(0,"OUT_OF_STOCK"));assertEquals(1,draft.skipped.size());
        assertFalse(draft.skip(-1,"OUT_OF_STOCK"));assertFalse(draft.skip(2,"MENU_DESYNC"));assertFalse(draft.skip(1,"invalid reason"));
        assertTrue(draft.progress(2,2));assertEquals(2,draft.completed);
    }
    @Test void countsStacksAndAllowsPackingPastBackpackCapacity(){
        var draft=new BatchDraft();draft.id="a".repeat(32);draft.capacity(4);
        draft.add("minecraft:stone",64,64);draft.add("minecraft:ender_pearl",48,16);
        assertEquals(4,draft.used());draft.add("minecraft:stone",4096,64);assertTrue(draft.used()>25);draft.add("minecraft:dirt",1,64);
        draft.add("minecraft:stone",1,64);assertEquals(5,draft.items.size());
    }
    @Test void largeProvisionalSelectionSurvivesCapacityReply(){
        var draft=new BatchDraft();draft.id="b".repeat(32);draft.add("minecraft:diamond_sword",64,1);draft.capacity(128);
        assertEquals(64,draft.used());assertEquals(1,draft.items.size());draft.submitted=true;assertThrows(IllegalArgumentException.class,()->draft.add("minecraft:dirt",1,64));
    }
    @Test void zeroOrSmallLegacyCapacityNeverClearsOrLimitsTheDraft(){
        var draft=new BatchDraft();draft.id="e".repeat(32);
        draft.add("minecraft:stone",10000,64);draft.add("minecraft:dirt",1,64);
        draft.capacity(0);draft.capacity(1);assertEquals(2,draft.items.size());
        for(int i=2;i<128;i++)draft.add("minecraft:stone",1,64);
        assertThrows(IllegalArgumentException.class,()->draft.add("minecraft:dirt",1,64));
        assertEquals(128,draft.items.size());
    }
    @Test void deletingMiddleRowPreservesOrderAndFreesTheCountBudget(){
        var draft=new BatchDraft();draft.id="f".repeat(32);
        draft.add("minecraft:stone",1,64);draft.add("minecraft:dirt",999998,64);draft.add("minecraft:glass",1,64);
        draft.remove(1);assertEquals(java.util.List.of("minecraft:stone","minecraft:glass"),draft.items.stream().map(BatchDraft.Entry::item).toList());
        draft.add("minecraft:sand",999998,64);assertEquals(1_000_000,draft.items.stream().mapToInt(BatchDraft.Entry::count).sum());
        assertThrows(IllegalArgumentException.class,()->draft.remove(-1));assertThrows(IllegalArgumentException.class,()->draft.remove(3));
        draft.submitted=true;assertThrows(IllegalArgumentException.class,()->draft.remove(0));assertEquals(3,draft.items.size());
    }
    @Test void progressMustMatchManifestAndNeverMoveBackward(){
        var draft=new BatchDraft();draft.id="c".repeat(32);draft.capacity(3);draft.add("minecraft:stone",1,64);draft.add("minecraft:dirt",1,64);
        assertTrue(draft.progress(1,2));assertFalse(draft.progress(0,2));assertFalse(draft.progress(3,2));assertFalse(draft.progress(2,3));assertTrue(draft.progress(2,2));
    }
    @Test void stockCheckMarksShortLinesAndAnyEditInvalidatesIt(){
        var draft=new BatchDraft();draft.id="9".repeat(32);draft.add("minecraft:stone",64,64);draft.add("minecraft:dirt",5,64);draft.add("minecraft:stone",64,64);
        draft.stockChecked=true;draft.available.put("minecraft:stone",100L);draft.available.put("minecraft:dirt",5L);
        assertEquals(0,draft.missing(0));assertEquals(0,draft.missing(1));assertEquals(28,draft.missing(2),"the second stone line gets what the first left");assertEquals(1,draft.missingKinds());
        draft.add("minecraft:sand",1,64);assertFalse(draft.stockChecked);assertEquals(0,draft.missing(2));
    }
}

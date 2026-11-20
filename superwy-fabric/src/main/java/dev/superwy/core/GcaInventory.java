package dev.superwy.core;
import java.util.*;
/** GCA fakePlayerOpenInventory protocol, excluding its equipment and action buttons. */
public final class GcaInventory {
    private GcaInventory(){}
    public static int menuSlot(int inventorySlot){
        if(inventorySlot<0||inventorySlot>=36)throw new IllegalArgumentException("Not a storage slot");
        return inventorySlot<9?45+inventorySlot:9+inventorySlot;
    }
    public static List<Integer> storageSlots(){return java.util.stream.IntStream.range(18,54).boxed().toList();}
}

package dev.superwy.mesh;

import dev.superwy.protocol.WhisperProtocol;

/** Super-dispatch ("mesh") settings. Off by default; switched by hand with /superwy mesh on|off or the web page. */
public final class MeshSettings {
    public boolean enabled=false;
    /** The robot with this player name runs the scheduler; every other robot is a worker. */
    public String authorityName="tiantian996";
    /** A robot that stops reporting is considered offline after this many seconds. */
    public int offlineAfterSeconds=15;
    /** Its unfinished work is handed to other robots after it has been offline this long. */
    public int reassignAfterSeconds=120;
    /** Size of one batch work block in inventory slots (loose delivery). */
    public int chunkSlots=25;
    /** Size of one batch work block in full shulker boxes when the batch is delivered as parcels. */
    public int parcelChunkBoxes=2;
    public void validate(){
        if(!WhisperProtocol.name(authorityName))throw new IllegalArgumentException("mesh.authorityName 必须是 1–16 位字母数字下划线");
        if(offlineAfterSeconds<5||offlineAfterSeconds>120||reassignAfterSeconds<30||reassignAfterSeconds>3600)throw new IllegalArgumentException("mesh 离线/改派时间无效");
        if(chunkSlots<1||chunkSlots>25||parcelChunkBoxes<1||parcelChunkBoxes>25)throw new IllegalArgumentException("mesh 分块大小无效：散装 1–25 格，装盒 1–25 盒");
    }
}

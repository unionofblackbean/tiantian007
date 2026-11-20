package dev.superwy.mesh;

import java.util.concurrent.CompletableFuture;

/** What the order engine asks of super-dispatch. Outside mesh mode every call is a no-op. */
public interface MeshHooks {
    /** Completes once this robot may summon the named recipient (and its _1, _2 ... overflow names). */
    CompletableFuture<Void> acquireRecipient(String baseName);
    void releaseRecipient(String baseName);
    /** Another robot is working in this container right now. */
    boolean busyElsewhere(String containerKey);
    /** The container this robot is about to open, or null. */
    void using(String containerKey);
    MeshHooks NONE=new MeshHooks(){
        public CompletableFuture<Void> acquireRecipient(String baseName){return CompletableFuture.completedFuture(null);}
        public void releaseRecipient(String baseName){}
        public boolean busyElsewhere(String containerKey){return false;}
        public void using(String containerKey){}
    };
    /** Server-address independent key, so robots that joined via different hostnames agree. */
    static String key(String dimension,int x,int y,int z){return dimension+"|"+x+","+y+","+z;}
}

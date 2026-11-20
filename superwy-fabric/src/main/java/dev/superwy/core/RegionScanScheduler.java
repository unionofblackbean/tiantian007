package dev.superwy.core;

import java.util.*;

/** Runnable regions share scan steps instead of waiting for another region's whole round. */
public final class RegionScanScheduler {
    private final Set<String> running=new LinkedHashSet<>();
    private String last,preferred;
    public void add(String id){running.add(id);}
    public void start(String id){if(running.add(id))preferred=id;}
    public void stop(String id){running.remove(id);if(Objects.equals(preferred,id))preferred=null;}
    public boolean contains(String id){return running.contains(id);}
    public boolean isEmpty(){return running.isEmpty();}
    public void clear(){running.clear();last=preferred=null;}
    public String next(){
        if(running.isEmpty())return null;
        if(preferred!=null){last=preferred;preferred=null;return last;}
        var ids=List.copyOf(running);last=ids.get(((last==null?-1:ids.indexOf(last))+1)%ids.size());return last;
    }
}

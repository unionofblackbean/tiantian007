package dev.superwy.core;

import java.util.*;

/** Bounded nearest selection: never sorts the entire scan queue or builds per-frame geometry. */
public final class OverlayPositions {
    public static final int MAX_MARKERS=128;
    private OverlayPositions(){}
    public record View(List<Model.Pos> markers,List<Model.Pos> hud){}
    public static View select(List<Model.Pos> positions,Model.Pos player){
        var nearest=Comparator.comparingDouble((Model.Pos p)->p.distance(player));
        var markers=new PriorityQueue<Model.Pos>(MAX_MARKERS,nearest.reversed());
        var hud=new PriorityQueue<Model.Pos>(6,nearest.reversed());
        for(var p:positions){offer(hud,p,6,nearest);if(p.distance(player)<=128)offer(markers,p,MAX_MARKERS,nearest);}
        return new View(markers.stream().sorted(nearest).toList(),hud.stream().sorted(nearest).toList());
    }
    private static void offer(PriorityQueue<Model.Pos> heap,Model.Pos p,int limit,Comparator<Model.Pos> nearest){if(heap.size()<limit)heap.add(p);else if(nearest.compare(p,heap.peek())<0){heap.poll();heap.add(p);}}
}

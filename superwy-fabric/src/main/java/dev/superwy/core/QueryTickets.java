package dev.superwy.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/** Remembers ownership briefly after cancellation, without retaining response NBT or futures. */
public final class QueryTickets<C> {
    public record Ticket<C>(C connection,long expiresAt,AtomicReference<String> error){}
    private final ConcurrentMap<Integer,Ticket<C>> tickets=new ConcurrentHashMap<>();
    private final int capacity;private final long retention;private final LongSupplier clock;
    public QueryTickets(int capacity,long retention,LongSupplier clock){this.capacity=capacity;this.retention=retention;this.clock=clock;}
    public Ticket<C> remember(C connection,int id){
        prune();
        if(tickets.size()>=capacity)tickets.entrySet().stream().min(Comparator.comparingLong(e->e.getValue().expiresAt())).ifPresent(e->tickets.remove(e.getKey(),e.getValue()));
        var ticket=new Ticket<>(connection,clock.getAsLong()+retention,new AtomicReference<String>());tickets.put(id,ticket);return ticket;
    }
    public Ticket<C> find(C connection,int id){var ticket=tickets.get(id);return ticket!=null&&ticket.connection()==connection&&clock.getAsLong()<ticket.expiresAt()?ticket:null;}
    public void prune(){long now=clock.getAsLong();tickets.entrySet().removeIf(e->now>=e.getValue().expiresAt());}
    public void clear(){tickets.clear();}
}

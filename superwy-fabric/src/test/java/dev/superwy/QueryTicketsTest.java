package dev.superwy;

import dev.superwy.core.QueryTickets;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class QueryTicketsTest {
    @Test void ownershipUsesActualConnectionIdentityAndExpires(){var clock=new AtomicLong(10);var tickets=new QueryTickets<Object>(3,60,clock::get);var first=new Object();var second=new Object();tickets.remember(first,7);assertNotNull(tickets.find(first,7));assertNull(tickets.find(second,7));clock.set(70);assertNull(tickets.find(first,7));}
    @Test void retainedOwnershipIsBoundedAndDoesNotRetainAResponseFuture(){var clock=new AtomicLong();var tickets=new QueryTickets<Object>(2,60,clock::get);var c=new Object();tickets.remember(c,1);clock.incrementAndGet();tickets.remember(c,2);clock.incrementAndGet();tickets.remember(c,3);assertNull(tickets.find(c,1));assertNotNull(tickets.find(c,2));assertNotNull(tickets.find(c,3));tickets.clear();assertNull(tickets.find(c,3));}
    @Test void firstDecodeFailureIsRetainedForRoutingAndCannotAffectAnotherTransaction(){var clock=new AtomicLong();var tickets=new QueryTickets<Object>(3,60,clock::get);var c=new Object();var a=tickets.remember(c,1);var b=tickets.remember(c,2);a.error().compareAndSet(null,"NBT_RESPONSE_TOO_LARGE");assertFalse(a.error().compareAndSet(null,"OTHER"));assertEquals("NBT_RESPONSE_TOO_LARGE",a.error().get());assertNull(b.error().get());}
}

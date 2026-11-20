package dev.superwy.game;

import dev.superwy.core.*;
import dev.superwy.protocol.BatchProtocol;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Run only while this actor owns inventory actions. A queued draft never probes a live courier. */
public final class BatchCapacity {
    private final Minecraft mc=Minecraft.getInstance();private final Config config;private final Queries queries;private final Actions a;
    public BatchCapacity(Config config,Queries queries,Actions a){this.config=config;this.queries=queries;this.a=a;}
    public int provisional(){return Math.max(0,(int)java.util.stream.IntStream.range(9,36).filter(i->!config.protectedSlots.contains(i)).count()-5);}
    public CompletableFuture<Integer> probe(String name,Runnable guard,Executor executor){
        guard.run();return queries.entity(mc.player.getId()).thenApply(Items::playerInventory).thenComposeAsync(robot->{
            guard.run();int free=0;for(int i=9;i<36;i++)if(!config.protectedSlots.contains(i)&&robot.getOrDefault(i,ItemStack.EMPTY).isEmpty())free++;
            int robotFree=free;
            return clear(name,false,guard,executor).thenComposeAsync(v->summon(name,guard),executor).exceptionallyComposeAsync(error->{
                guard.run();if(!"FAKE_PLAYER_UNAVAILABLE".equals(Scanner.rootMessage(error)))return CompletableFuture.failedFuture(error);
                return clear(name,true,guard,executor).thenComposeAsync(v->summon(name,guard),executor);
            },executor).thenComposeAsync(v->{guard.run();Player p=find(name);if(p==null||p==mc.player||p.isSpectator())throw new IllegalStateException("INVALID_RECIPIENT");return queries.entity(p.getId());},executor)
              .thenApply(Items::playerInventory).thenComposeAsync(inv->{guard.run();int recipientFree=0;for(int i=0;i<36;i++)if(inv.getOrDefault(i,ItemStack.EMPTY).isEmpty())recipientFree++;
                  int limit=BatchProtocol.limit(robotFree,recipientFree);return clear(name,true,guard,executor).thenApply(v->limit);
              },executor);
        },executor);
    }
    private Player find(String name){return mc.level==null?null:mc.level.players().stream().filter(p->p.getName().getString().equalsIgnoreCase(name)).findFirst().orElse(null);}
    private CompletableFuture<Void> clear(String name,boolean force,Runnable guard,Executor executor){
        guard.run();if(!force&&mc.getConnection().getPlayerInfo(name)==null)return CompletableFuture.completedFuture(null);
        a.command("player "+name+" kill");return a.delay(250).thenComposeAsync(v->{guard.run();return a.await(()->mc.getConnection()!=null&&mc.getConnection().getPlayerInfo(name)==null&&find(name)==null,6000,"FAKE_PLAYER_DESPAWN_TIMEOUT");},executor);
    }
    private CompletableFuture<Void> summon(String name,Runnable guard){guard.run();var p=mc.player.position();a.command("player "+name+" spawn at "+p.x+" "+p.y+" "+p.z);return a.await(()->find(name)!=null,8000,"FAKE_PLAYER_UNAVAILABLE");}
}

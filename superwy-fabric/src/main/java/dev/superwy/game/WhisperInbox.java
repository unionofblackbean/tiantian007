package dev.superwy.game;

import com.google.gson.*;
import dev.superwy.core.*;
import dev.superwy.protocol.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Converts authenticated in-game sender identities into the same persistent FIFO as HTTP. */
public final class WhisperInbox {
    private final Config config;private final Store store;
    private final Supplier<Boolean> ready;private final Supplier<String> world,dimension;
    private final Queue<Runnable> commands;private final BiConsumer<String,String> reply;
    private final Map<UUID,ArrayDeque<Long>> rate=new HashMap<>();
    public WhisperInbox(Config config,Store store,Supplier<Boolean> ready,Supplier<String> world,Supplier<String> dimension,Queue<Runnable> commands,BiConsumer<String,String> reply){
        this.config=config;this.store=store;this.ready=ready;this.world=world;this.dimension=dimension;this.commands=commands;this.reply=reply;
    }
    public void register(){PrivateMessages.register(this::receive);}
    public boolean receive(PrivateMessages.Message message){
        if(!WhisperProtocol.isRequest(message.body()))return false;
        long now=System.currentTimeMillis();rate.entrySet().removeIf(e->e.getValue().isEmpty()||e.getValue().getLast()<now-60000);
        var attempts=rate.computeIfAbsent(message.senderId(),k->new ArrayDeque<>());while(!attempts.isEmpty()&&attempts.getFirst()<now-60000)attempts.removeFirst();
        if(attempts.size()>=30)return true;attempts.addLast(now);
        String id=WhisperProtocol.responseId(message.body());if(id==null)id=WhisperProtocol.newId();String requestId=id;
        if(commands.size()>=64){reply.accept(message.senderName(),WhisperProtocol.rejected(requestId,"QUEUE_FULL"));return true;}
        commands.add(()->accept(message,requestId));return true;
    }
    private void accept(PrivateMessages.Message message,String requestId){
        // Player orders by private message are retired: players use the HTTP connection only.
        reply.accept(message.senderName(),WhisperProtocol.rejected(requestId,"WHISPER_ORDERS_DISABLED"));
        reply.accept(message.senderName(),retiredHint(config));
    }
    /** Plain-text whisper old player mods still show. */
    public static String retiredHint(Config config){
        return config.mesh.enabled
            ?"私聊下单已停用。本机器人处于超级调度模式：请更新取货端，执行 /swrequest mesh on，任务会自动发给 "+config.mesh.authorityName
            :"私聊下单已停用，请更新取货端并用 HTTP 连接（找机器人主人要 /superwy connect 连接指令）";
    }
}

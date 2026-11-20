package dev.superwy.protocol;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.*;
import net.minecraft.network.chat.contents.TranslatableContents;
import java.util.UUID;
import java.util.function.Predicate;

/** Native private chat plus exact private-message components/templates delivered as system packets. */
public final class PrivateMessages {
    public record Message(UUID senderId,String senderName,String body){}
    private PrivateMessages(){}
    public static void register(Predicate<Message> handler){
        ClientReceiveMessageEvents.ALLOW_CHAT.register((display,signed,sender,type,time)->{
            String body=body(display,signed);
            if(type.chatType().is(ChatType.MSG_COMMAND_OUTGOING))return !(WhisperProtocol.isRequest(body)||WhisperProtocol.isReply(body)||BatchProtocol.request(body)||BatchProtocol.reply(body)||MeshProtocol.is(body));
            if(!type.chatType().is(ChatType.MSG_COMMAND_INCOMING)||body==null)return true;
            var connection=Minecraft.getInstance().getConnection();if(connection==null)return true;
            GameProfile identity=sender;
            if(identity==null&&signed!=null){var info=connection.getPlayerInfo(signed.sender());if(info!=null)identity=info.getProfile();}
            if(identity==null){String name=type.name().getStyle().getInsertion();if(!WhisperProtocol.name(name))name=type.name().getString();var info=connection.getPlayerInfo(name);if(info!=null)identity=info.getProfile();}
            if(identity==null||!WhisperProtocol.name(identity.name()))return true;
            return !handler.test(new Message(identity.id(),identity.name(),body));
        });
        ClientReceiveMessageEvents.ALLOW_GAME.register((display,overlay)->{
            if(overlay)return true;
            var parsed=component(display,0,new int[]{64});
            if(parsed==null)parsed=SystemWhisperFormat.parse(display.getString());
            if(parsed==null)return true;
            if(parsed.outgoing())return false;
            var connection=Minecraft.getInstance().getConnection();if(connection==null)return true;
            String name=parsed.senderName();
            var identity=connection.getOnlinePlayers().stream().map(info->info.getProfile()).filter(profile->profile.name().equalsIgnoreCase(name)).findFirst().orElse(null);
            if(identity==null)return true;
            return !handler.test(new Message(identity.id(),identity.name(),parsed.body()));
        });
    }
    private static SystemWhisperFormat.Parsed component(Component value,int depth,int[] budget){
        if(depth>8||--budget[0]<0)return null;
        if(value.getContents() instanceof TranslatableContents t&&t.getArgs().length==2){
            boolean incoming=t.getKey().equals("commands.message.display.incoming"),outgoing=t.getKey().equals("commands.message.display.outgoing");
            if(incoming||outgoing){
                String body=plain(t.getArgs()[1]);
                if(WhisperProtocol.isRequest(body)||WhisperProtocol.isReply(body)||BatchProtocol.request(body)||BatchProtocol.reply(body)||MeshProtocol.is(body)){
                    if(outgoing)return new SystemWhisperFormat.Parsed(null,body,true);
                    Object author=t.getArgs()[0];String name=author instanceof Component c?c.getStyle().getInsertion():null;
                    if(!WhisperProtocol.name(name))name=plain(author);
                    if(WhisperProtocol.name(name))return new SystemWhisperFormat.Parsed(name,body,false);
                }
            }
        }
        for(var child:value.getSiblings()){var parsed=component(child,depth+1,budget);if(parsed!=null)return parsed;}
        return null;
    }
    private static String plain(Object value){return value instanceof Component c?c.getString():value instanceof String s?s:null;}
    private static String body(Component display,PlayerChatMessage signed){
        if(signed!=null)return signed.signedContent();
        // Vanilla profileless /tell uses the same translated wrapper in every client language.
        if(display.getContents() instanceof TranslatableContents t&&t.getKey().startsWith("commands.message.display.")&&t.getArgs().length==2){
            Object value=t.getArgs()[1];return value instanceof Component c?c.getString():value instanceof String s?s:null;
        }
        return null;
    }
}

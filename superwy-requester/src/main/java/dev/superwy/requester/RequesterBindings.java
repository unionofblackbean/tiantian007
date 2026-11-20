package dev.superwy.requester;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Visible vanilla controls; config remains a mirror for the existing settings screen. */
public final class RequesterBindings {
    public final KeyMapping pickup,confirm,queue;
    public RequesterBindings(){
        var category=KeyMapping.Category.register(Identifier.fromNamespaceAndPath("superwy_requester","requester"));
        pickup=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.superwy_requester.pickup",InputConstants.Type.MOUSE,2,category));
        confirm=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.superwy_requester.confirm",257,category));
        queue=KeyMappingHelper.registerKeyMapping(new KeyMapping("key.superwy_requester.queue",92,category));
    }
    public RequesterConfig read(RequesterConfig config){
        var draft=config.copy();draft.nativeHotkeysInitialized=true;
        draft.pickupKey=code(pickup);draft.confirmKey=code(confirm);draft.queueKey=code(queue);return draft;
    }
    public void apply(RequesterConfig config){
        pickup.setKey(key(config.pickupKey));confirm.setKey(key(config.confirmKey));queue.setKey(key(config.queueKey));KeyMapping.resetMapping();
    }
    public boolean differs(RequesterConfig config){return config.pickupKey!=code(pickup)||config.confirmKey!=code(confirm)||config.queueKey!=code(queue);}
    private static int code(KeyMapping mapping){
        var key=KeyMappingHelper.getBoundKeyOf(mapping);
        if(mapping.isUnbound())return RequestHotkey.UNBOUND;
        return key.getType()==InputConstants.Type.MOUSE?RequestHotkey.mouse(key.getValue()):key.getType()==InputConstants.Type.SCANCODE?RequestHotkey.scanCode(key.getValue()):key.getValue();
    }
    private static InputConstants.Key key(int code){
        if(code==RequestHotkey.UNBOUND)return InputConstants.UNKNOWN;
        if(RequestHotkey.isScanCode(code))return InputConstants.Type.SCANCODE.getOrCreate(-code-1000);
        return code<0?InputConstants.Type.MOUSE.getOrCreate(-code-1):InputConstants.Type.KEYSYM.getOrCreate(code);
    }
}

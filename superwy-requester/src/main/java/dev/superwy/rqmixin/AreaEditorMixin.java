package dev.superwy.rqmixin;

import dev.superwy.requester.LitematicaAreaHook;
import fi.dy.masa.malilib.gui.GuiBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds "机器人取货" to Litematica's area selection editor (normal and simple mode); every re-init keeps it. */
@Pseudo
@Mixin(targets="fi.dy.masa.litematica.gui.GuiAreaSelectionEditorNormal",remap=false)
public abstract class AreaEditorMixin {
    @Inject(method="initGui",at=@At("TAIL"),require=0)
    private void superwy$robotPickup(CallbackInfo ci){LitematicaAreaHook.addButton((GuiBase)(Object)this);}
}

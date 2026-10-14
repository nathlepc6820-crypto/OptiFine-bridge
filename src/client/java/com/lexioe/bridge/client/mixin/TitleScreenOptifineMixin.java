package com.lexioe.bridge.client.mixin;

import com.lexioe.bridge.BridgeState;
import net.minecraft.client.gui.screens.TitleScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(TitleScreen.class)
public abstract class TitleScreenOptifineMixin {
    @ModifyArg(
            method = "extractRenderState",
            at = @At(value = "INVOKE", target = "text"),
            index = 1,
            require = 0
    )
    private String optifinebridge$addOptifineText(String original) {
        return BridgeState.optifineActive ? original + " OptiFine" : original;
    }
}
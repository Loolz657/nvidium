package me.cortex.nvidium.smoke.mixin;

import me.cortex.nvidium.smoke.FrameTimeProbe;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class FrameTimeMixin {
    @Inject(method = "runTick", at = @At("TAIL"))
    private void nvidium$recordFrame(CallbackInfo ci) { FrameTimeProbe.frame(); }
}

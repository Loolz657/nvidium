package me.cortex.nvidium.smoke.mixin;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Hidden SDL window for the separate, unattended development instance only. */
@Mixin(Window.class)
public abstract class HiddenTestWindow {
    @ModifyArg(method = "createWindow", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"), index = 3)
    private long nvidium$hideDevelopmentWindow(long flags) { return flags | SDLVideo.SDL_WINDOW_HIDDEN; }
}

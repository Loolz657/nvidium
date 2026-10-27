package me.cortex.nvidium.mixin.iris;

import me.cortex.nvidium.sodiumCompat.iris.IrisMeshBridge;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public abstract class MixinIrisRenderingPipeline {
    @Inject(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/uniforms/custom/CustomUniforms;optimise()V"))
    private void nvidium$initializeMeshPrograms(CallbackInfo ci) {
        IrisMeshBridge.initialize((IrisRenderingPipeline)(Object)this);
    }

    @Inject(method = "destroy", at = @At("HEAD"))
    private void nvidium$destroyMeshPrograms(CallbackInfo ci) {
        IrisMeshBridge.destroy((IrisRenderingPipeline)(Object)this);
    }
}

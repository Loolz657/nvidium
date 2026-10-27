package me.cortex.nvidium.mixin.iris;

import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import me.cortex.nvidium.sodiumCompat.iris.IrisMeshBridge;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.blending.BufferBlendOverride;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.state.FogMode;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import net.irisshaders.iris.pipeline.programs.IrisBindings;
import net.irisshaders.iris.pipeline.programs.PartialShader;
import net.irisshaders.iris.pipeline.programs.ShaderCreator;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.VanillaUniforms;
import net.irisshaders.iris.uniforms.builtin.BuiltinReplacementUniforms;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.io.IOException;
import java.util.ArrayList;
import java.util.function.Supplier;

@Mixin(value = ShaderCreator.class, remap = false)
public abstract class MixinShaderCreator {
    @WrapOperation(method = "create", at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/pipeline/programs/ShaderCreator;link(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/mojang/renderpearl/api/vertex/VertexFormat;Z)Lnet/irisshaders/iris/pipeline/programs/PartialShader;"))
    private static PartialShader nvidium$captureTerrain(String name, String vertex, String geometry,
            String tessControl, String tessEval, String fragment, VertexFormat format, boolean fallback,
            Operation<PartialShader> original,
            @Local(argsOnly = true) ShaderKey key,
            @Local(argsOnly = true) ProgramSource source,
            @Local(argsOnly = true) ProgramId programId,
            @Local(argsOnly = true) AlphaTest fallbackAlpha,
            @Local(argsOnly = true, ordinal = 0) GlFramebuffer before,
            @Local(argsOnly = true, ordinal = 1) GlFramebuffer after,
            @Local(argsOnly = true) ShaderAttributeInputs inputs,
            @Local(argsOnly = true) IrisRenderingPipeline parent,
            @Local(argsOnly = true) Supplier<ImmutableSet<Integer>> flipped,
            @Local(argsOnly = true) CustomUniforms customUniforms) {
        PartialShader normal = original.call(name, vertex, geometry, tessControl, tessEval, fragment, format, fallback);
        if (!Boolean.getBoolean("nvidium.iris.experimental") || key != ShaderKey.SODIUM_TERRAIN_CUTOUT ||
                geometry != null || tessControl != null || tessEval != null) return normal;

        var directives = source.getDirectives();
        AlphaTest alpha = directives.getAlphaTestOverride().orElse(fallbackAlpha);
        var blend = directives.getBlendModeOverride().orElse(programId.getBlendModeOverride());
        var overrides = new ArrayList<BufferBlendOverride>();
        directives.getBufferBlendOverrides().forEach(override -> {
            int index = Ints.indexOf(directives.getDrawBuffers(), override.index());
            if (index >= 0) overrides.add(new BufferBlendOverride(index, override.blendMode()));
        });
        IrisMeshBridge.register(parent, name, vertex, fragment, format, id -> {
            IrisBindings.apply(id);
            try {
                return new ExtendedShader(id, name + "/nvidium", format, false, before, after, blend, alpha,
                        uniforms -> {
                            CommonUniforms.addDynamicUniforms(uniforms, FogMode.PER_VERTEX);
                            customUniforms.assignTo(uniforms);
                            BuiltinReplacementUniforms.addBuiltinReplacementUniforms(uniforms);
                            VanillaUniforms.addVanillaUniforms(uniforms);
                        }, (samplers, images) -> parent.addGbufferOrShadowSamplers(samplers, images, flipped,
                                false, inputs.hasTex(), inputs.hasLight(), inputs.hasOverlay()),
                        key, parent, overrides, customUniforms, Patch.SODIUM);
            } catch (IOException failure) { throw new IllegalStateException("Cannot create Iris mesh state", failure); }
        });
        return normal;
    }
}

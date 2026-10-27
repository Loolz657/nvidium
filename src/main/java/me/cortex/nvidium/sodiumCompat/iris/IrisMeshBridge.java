package me.cortex.nvidium.sodiumCompat.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.opengl.GlSampler;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.config.TranslucencySortingLevel;
import me.cortex.nvidium.sodiumCompat.ShaderLoader;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import net.irisshaders.iris.pipeline.programs.IrisBindings;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import static me.cortex.nvidium.RenderPipeline.GL_DRAW_INDIRECT_ADDRESS_NV;
import static org.lwjgl.opengl.GL15C.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.NVUniformBufferUnifiedMemory.GL_UNIFORM_BUFFER_UNIFIED_NV;

/** Experimental hybrid: Nvidium for main terrain, unmodified Iris/Sodium for shadows and water. */
public final class IrisMeshBridge {
    private static final int GLOBAL_BINDING = 14;
    private static final Map<IrisRenderingPipeline, Programs> PROGRAMS = new IdentityHashMap<>();

    private static final class Programs {
        final int primaryId;
        final int temporalId;
        final IntFunction<ExtendedShader> factory;
        ExtendedShader primary;
        ExtendedShader temporal;
        int globals;
        int primaryCameraOffset;
        int temporalCameraOffset;
        int scene;
        int terrain;
        long terrainBytes;
        float offsetX, offsetY, offsetZ;
        boolean initialized;

        Programs(int primaryId, int temporalId, IntFunction<ExtendedShader> factory) {
            this.primaryId = primaryId;
            this.temporalId = temporalId;
            this.factory = factory;
        }

        void initialize() {
            primary = factory.apply(primaryId);
            temporal = factory.apply(temporalId);
            for (int id : new int[]{primaryId, temporalId}) {
                int index = GL31C.glGetUniformBlockIndex(id, "u_Globals");
                if (index != GL31C.GL_INVALID_INDEX) GL31C.glUniformBlockBinding(id, index, GLOBAL_BINDING);
            }
            globals = GL45C.glCreateBuffers();
            primaryCameraOffset = GL20C.glGetUniformLocation(primaryId, "nvidium_cameraOffset");
            temporalCameraOffset = GL20C.glGetUniformLocation(temporalId, "nvidium_cameraOffset");
            initialized = true;
        }

        void close() {
            if (primary != null) { primary.iris$clearState(); primary.close(); }
            else GL20C.glDeleteProgram(primaryId);
            if (temporal != null) { temporal.iris$clearState(); temporal.close(); }
            else GL20C.glDeleteProgram(temporalId);
            if (globals != 0) GL15C.glDeleteBuffers(globals);
        }
    }

    private IrisMeshBridge() {}

    public static boolean requested() {
        return Boolean.getBoolean("nvidium.iris.experimental") && Nvidium.config.translucency_sorting_level == TranslucencySortingLevel.SODIUM;
    }

    public static void register(IrisRenderingPipeline pipeline, String name, String vertex, String fragment,
                                VertexFormat format, IntFunction<ExtendedShader> factory) {
        if (!requested() || !GL.getCapabilities().GL_NV_mesh_shader) return;
        int primary = 0, temporal = 0;
        try {
            var sources = NvidiumIrisMeshAdapter.create(vertex, fragment, format);
            primary = IrisMeshProgramCompiler.compile(name + "/nvidium-primary",
                    ShaderLoader.parseIrisTask(Identifier.fromNamespaceAndPath("nvidium", "terrain/task.glsl")), sources);
            temporal = IrisMeshProgramCompiler.compile(name + "/nvidium-temporal",
                    ShaderLoader.parseIrisTask(Identifier.fromNamespaceAndPath("nvidium", "terrain/temporal_task.glsl")), sources);
            Programs previous = PROGRAMS.put(pipeline, new Programs(primary, temporal, factory));
            primary = temporal = 0; // ownership transferred
            if (previous != null) previous.close();
        } catch (RuntimeException failure) {
            Nvidium.LOGGER.warn("Experimental Iris mesh bridge unavailable; keeping Iris/Sodium terrain", failure);
        } finally {
            if (primary != 0) GL20C.glDeleteProgram(primary);
            if (temporal != 0) GL20C.glDeleteProgram(temporal);
        }
    }

    /** Called after Iris created its shader map, before it optimizes the custom uniform graph. */
    public static void initialize(IrisRenderingPipeline pipeline) {
        Programs programs = PROGRAMS.get(pipeline);
        if (programs == null) return;
        try {
            programs.initialize();
            Nvidium.LOGGER.info("Experimental Nvidium/Iris terrain bridge initialized (Sodium shadows and transparency)");
        } catch (RuntimeException failure) {
            PROGRAMS.remove(pipeline);
            programs.close();
            Nvidium.LOGGER.warn("Cannot initialize experimental Iris bridge; keeping Iris/Sodium terrain", failure);
        }
    }

    public static void destroy(IrisRenderingPipeline pipeline) {
        Programs programs = PROGRAMS.remove(pipeline);
        if (programs != null) programs.close();
    }

    private static Programs current() {
        var manager = Iris.getPipelineManager();
        var pipeline = manager == null ? null : manager.getPipelineNullable();
        return pipeline instanceof IrisRenderingPipeline iris ? PROGRAMS.get(iris) : null;
    }

    public static boolean ready() {
        Programs programs = current();
        return requested() && programs != null && programs.initialized;
    }

    public static boolean shadows() { return ShadowRenderingState.areShadowsCurrentlyBeingRendered(); }

    public static void prepareFrame(int sceneBuffer, int terrainBuffer, long terrainBytes, ChunkRenderMatrices matrices,
                                    FogParameters fog, double x, double y, double z,
                                    float texelX, float texelY, float shrinkX, float shrinkY) {
        Programs programs = current();
        if (programs == null || !programs.initialized) throw new IllegalStateException("Iris bridge not initialized");
        programs.scene = sceneBuffer;
        programs.terrain = terrainBuffer;
        programs.terrainBytes = terrainBytes;
        programs.offsetX = (float)(Math.floor(x / 16.0) * 16.0 - x);
        programs.offsetY = (float)(Math.floor(y / 16.0) * 16.0 - y);
        programs.offsetZ = (float)(Math.floor(z / 16.0) * 16.0 - z);
        try (var stack = MemoryStack.stackPush()) {
            var data = stack.calloc(192);
            matrices.projection().get(0, data);
            matrices.modelView().get(64, data);
            data.putFloat(128, fog.red()).putFloat(132, fog.green()).putFloat(136, fog.blue()).putFloat(140, fog.alpha());
            data.putFloat(144, fog.environmentalStart()).putFloat(148, fog.environmentalEnd());
            data.putFloat(152, fog.renderStart()).putFloat(156, fog.renderEnd());
            data.putFloat(160, texelX).putFloat(164, texelY).putFloat(168, shrinkX).putFloat(172, shrinkY);
            data.putFloat(176, 1.0f);
            GL45C.glNamedBufferData(programs.globals, data, GL_DYNAMIC_DRAW);
        }
    }

    public static boolean raster(TerrainRenderPass pass, int regionCount, long commandAddress,
                                  GpuSampler sampler, boolean temporal) {
        Programs programs = current();
        if (programs == null || !programs.initialized || shadows()) return false;
        ExtendedShader shader = temporal ? programs.temporal : programs.primary;
        BufferBinding oldSceneBinding = BufferBinding.capture(GL31C.GL_UNIFORM_BUFFER, 0);
        BufferBinding oldGlobalsBinding = BufferBinding.capture(GL31C.GL_UNIFORM_BUFFER, GLOBAL_BINDING);
        BufferBinding oldTerrainBinding = BufferBinding.capture(GL43C.GL_SHADER_STORAGE_BUFFER, NvidiumIrisMeshAdapter.VERTEX_STORAGE_BINDING);
        // Nvidium uses GPU addresses for uniform fetches; Iris uses ordinary UBO bindings.
        // Switch only for this draw, with SceneData bound as an ordinary buffer at slot 0.
        // These raster phases always enter with Nvidium's client state enabled.
        // glIsEnabled is not a valid query for this client-state capability.
        GL11.glDisableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        try {
            GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, 0, programs.scene);
            GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, GLOBAL_BINDING, programs.globals);
            GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, NvidiumIrisMeshAdapter.VERTEX_STORAGE_BINDING,
                    programs.terrain, 0, programs.terrainBytes);
            GL20C.glUseProgram(shader.getProgramId());
            shader.iris$setupState(List.of());
            GL20C.glUniform3f(temporal ? programs.temporalCameraOffset : programs.primaryCameraOffset, programs.offsetX, programs.offsetY, programs.offsetZ);
            setTexture(pass.getAtlas(), IrisBindings.ALBEDO_TEXTURE, sampler);
            setTexture(Minecraft.getInstance().gameRenderer.lightmap(), IrisBindings.LIGHTMAP_TEXTURE,
                    RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            NVVertexBufferUnifiedMemory.glBufferAddressRangeNV(GL_DRAW_INDIRECT_ADDRESS_NV, 0, commandAddress, regionCount * 8L);
            NVMeshShader.glMultiDrawMeshTasksIndirectNV(0, regionCount, 0);
        } finally {
            shader.iris$clearState();
            oldSceneBinding.restore();
            oldGlobalsBinding.restore();
            oldTerrainBinding.restore();
            GL11.glEnableClientState(GL_UNIFORM_BUFFER_UNIFIED_NV);
        }
        return true;
    }

    private record BufferBinding(int target, int index, int buffer, long offset, long size) {
        static BufferBinding capture(int target, int index) {
            boolean uniform = target == GL31C.GL_UNIFORM_BUFFER;
            return new BufferBinding(target, index,
                    GL30C.glGetIntegeri(uniform ? GL31C.GL_UNIFORM_BUFFER_BINDING : GL43C.GL_SHADER_STORAGE_BUFFER_BINDING, index),
                    GL32C.glGetInteger64i(uniform ? GL31C.GL_UNIFORM_BUFFER_START : GL43C.GL_SHADER_STORAGE_BUFFER_START, index),
                    GL32C.glGetInteger64i(uniform ? GL31C.GL_UNIFORM_BUFFER_SIZE : GL43C.GL_SHADER_STORAGE_BUFFER_SIZE, index));
        }
        void restore() {
            if (buffer != 0 && size > 0) GL30C.glBindBufferRange(target, index, buffer, offset, size);
            else GL30C.glBindBufferBase(target, index, buffer);
        }
    }

    private static void setTexture(GpuTextureView view, int binding, GpuSampler sampler) {
        var texture = (GlTexture)view.texture();
        GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + binding);
        GlStateManager._bindTexture(texture.glId());
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_BASE_LEVEL, view.baseMipLevel());
        GlStateManager._texParameter(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, view.baseMipLevel() + view.mipLevels() - 1);
        GL33C.glBindSampler(binding, ((GlSampler)sampler).getId());
    }
}

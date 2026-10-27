package me.cortex.nvidium.sodiumCompat;

import net.fabricmc.loader.api.FabricLoader;
import net.irisshaders.iris.api.v0.IrisApi;
import me.cortex.nvidium.Nvidium;
import me.cortex.nvidium.sodiumCompat.iris.IrisMeshBridge;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;

public class IrisCheck {
    public static final boolean IRIS_LOADED = FabricLoader.getInstance().isModLoaded("iris");
    public static final boolean SUPPORTED_IRIS = FabricLoader.getInstance().getModContainer("iris")
            .map(mod -> mod.getMetadata().getVersion().getFriendlyString().split("\\+", 2)[0].equals("1.11.7"))
            .orElse(false);

    private static boolean checkIrisShaders() {
        return IrisApi.getInstance().isShaderPackInUse();
    }

    public static boolean checkIrisShouldDisable() {
        return !shaderPackActive() || (SUPPORTED_IRIS && IrisMeshBridge.ready());
    }

    public static boolean shaderPackActive() { return IRIS_LOADED && checkIrisShaders(); }
    public static boolean bridgeActive() { return SUPPORTED_IRIS && shaderPackActive() && IrisMeshBridge.ready(); }
    public static boolean shadowPass() { return shaderPackActive() && IrisMeshBridge.shadows(); }
    public static boolean useSodiumVertexFormat() { return Nvidium.config.use_sodium_vertex_format || shaderPackActive(); }
    public static boolean useNvidiumFog() { return Nvidium.config.render_fog && !shaderPackActive(); }
    public static int vertexStride() {
        if (shaderPackActive()) return WorldRenderingSettings.INSTANCE.getVertexFormat().getVertexFormat().getVertexSize();
        return useSodiumVertexFormat() ? ChunkMeshFormats.COMPACT.getVertexFormat().getVertexSize() : NvidiumCompactChunkVertex.STRIDE;
    }
}

package me.cortex.nvidium.sodiumCompat.iris;

import com.mojang.renderpearl.api.vertex.VertexFormat;

import java.util.Map;

/** Emits the exact 16-quad workload consumed by Nvidium 26.3's primary/temporal task shaders. */
public final class NvidiumIrisMeshAdapter {
    public static final int VERTEX_STORAGE_BINDING = 15;

    public record ProgramSources(String mesh, String fragment, int stride) {}

    private NvidiumIrisMeshAdapter() {}

    public static ProgramSources create(String irisVertex, String irisFragment, VertexFormat format) {
        var converted = IrisMeshShaderTransformer.transform(irisVertex, irisFragment,
                Map.of("iris_RegionOffset", "nvidium_sectionOrigin"));
        var loader = IrisVertexAttributeLoader.create(format, converted.attributes(), VERTEX_STORAGE_BINDING);
        // _vert_init expects a region-local section number. The task payload already supplies
        // a section origin, so section 0 prevents adding the original section translation twice.
        String implementation = loader.implementation();
        if (converted.attributes().stream().anyMatch(attribute -> attribute.name().equals("a_LightAndData"))) {
            int end = implementation.lastIndexOf('}');
            implementation = implementation.substring(0, end) + "    a_LightAndData.w = 0u;\n" + implementation.substring(end);
        }
        String header = loader.declarations() + """
                layout(local_size_x = 16) in;
                layout(triangles, max_vertices = 64, max_primitives = 32) out;
                uniform vec3 nvidium_cameraOffset;
                vec3 nvidium_sectionOrigin;
                taskNV in Task {
                    uvec4 binStarts;
                    uvec4 binOffsets;
                    vec3 origin;
                    uint quadCount;
                    uint transformationId;
                };
                """;
        String body = implementation + """
                uint nvidium_quadOffset(uint index) {
                    uint offset = binOffsets.w;
                    if (index < binStarts.y) offset = binOffsets.x;
                    else if (index < binStarts.z) offset = binOffsets.y;
                    else if (index < binStarts.w) offset = binOffsets.z;
                    return offset + index;
                }
                void main() {
                    uint localQuad = gl_LocalInvocationIndex;
                    uint firstQuad = gl_WorkGroupID.x * 16u;
                    uint index = firstQuad + localQuad;
                    if (localQuad == 0u) gl_PrimitiveCountNV = quadCount > firstQuad ? min(quadCount - firstQuad, 16u) * 2u : 0u;
                    if (index >= quadCount) return;
                    uint quad = nvidium_quadOffset(index);
                    uint outputVertex = localQuad * 4u;
                    uint outputIndex = localQuad * 6u;
                    nvidium_sectionOrigin = origin + nvidium_cameraOffset;
                    for (uint corner = 0u; corner < 4u; corner++) {
                        uint vertexIndex = quad * 4u + corner;
                        nvidium_runVertex(vertexIndex, int(vertexIndex), 0);
                        nvidium_exportVertex(outputVertex + corner);
                        gl_MeshVerticesNV[outputVertex + corner].gl_Position = nvidium_position;
                    }
                    gl_PrimitiveIndicesNV[outputIndex + 0u] = outputVertex + 0u;
                    gl_PrimitiveIndicesNV[outputIndex + 1u] = outputVertex + 1u;
                    gl_PrimitiveIndicesNV[outputIndex + 2u] = outputVertex + 2u;
                    gl_PrimitiveIndicesNV[outputIndex + 3u] = outputVertex + 2u;
                    gl_PrimitiveIndicesNV[outputIndex + 4u] = outputVertex + 3u;
                    gl_PrimitiveIndicesNV[outputIndex + 5u] = outputVertex + 0u;
                    gl_MeshPrimitivesNV[localQuad * 2u].gl_PrimitiveID = int(quad * 2u);
                    gl_MeshPrimitivesNV[localQuad * 2u + 1u].gl_PrimitiveID = int(quad * 2u + 1u);
                }
                """;
        return new ProgramSources(converted.mesh(header, body), converted.fragment(), loader.stride());
    }
}

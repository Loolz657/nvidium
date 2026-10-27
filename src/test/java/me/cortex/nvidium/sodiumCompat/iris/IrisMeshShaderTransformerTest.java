package me.cortex.nvidium.sodiumCompat.iris;

import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IrisMeshShaderTransformerTest {
    private static final String VERTEX = """
            #version 330 core
            layout(location=0) in vec3 a_Position;
            in uint mc_Entity;
            flat out int mat;
            out vec2 texCoord;
            vec2 iris_Entity = vec2(int(mc_Entity >> 1u) - 1, mc_Entity & 1u);
            vec3 position = a_Position;
            void main() {
                mat = int(iris_Entity.x);
                texCoord = position.xy;
                gl_Position = vec4(position, 1.0);
            }
            """;
    private static final String FRAGMENT = """
            #version 330 core
            flat in int mat;
            in vec2 texCoord;
            layout(location=0) out vec4 color;
            void main() { color = vec4(texCoord, float(mat), 1.0); }
            """;

    @Test
    void preservesMaterialAttributesAndLinksVaryingsByLocation() {
        var converted = IrisMeshShaderTransformer.transform(VERTEX, FRAGMENT);
        assertEquals(List.of(new IrisMeshShaderTransformer.Attribute("a_Position", "vec3"),
                new IrisMeshShaderTransformer.Attribute("mc_Entity", "uint")), converted.attributes());
        assertEquals(2, converted.locationCount());
        assertTrue(converted.vertexBody().contains("nvidium_export[]"));
        assertTrue(converted.vertexBody().contains("nvidium_export[outputIndex].texCoord = texCoord"));
        assertTrue(compact(converted.fragment()).contains("layout(location=0)inNvidiumVaryings{flatintmat;vec2texCoord;};"));
        assertParses(converted.vertexBody());
        assertParses(converted.fragment());
    }

    @Test
    void keepsPackExtensionsBeforeAdapterDeclarations() {
        String vertex = VERTEX.replace("#version 330 core", "#version 330 core\n#extension GL_ARB_derivative_control : enable\n#extension GL_ARB_shader_image_load_store : enable");
        var converted = IrisMeshShaderTransformer.transform(vertex, FRAGMENT);
        String mesh = converted.mesh("layout(local_size_x=16) in;", "void main() {}");
        int adapter = mesh.indexOf("layout(local_size_x=16)");
        assertTrue(mesh.indexOf("#extension GL_NV_mesh_shader") < adapter);
        assertTrue(mesh.indexOf("#extension GL_ARB_derivative_control") < adapter);
        assertTrue(mesh.indexOf("#extension GL_ARB_shader_image_load_store") < adapter);
    }

    @Test
    void reinitializesAttributeDependentGlobalsForEachVertex() {
        var converted = IrisMeshShaderTransformer.transform(VERTEX, FRAGMENT);
        int load = converted.vertexBody().lastIndexOf("nvidium_loadVertex(vertexIndex)");
        int material = converted.vertexBody().lastIndexOf("iris_Entity =");
        int position = converted.vertexBody().lastIndexOf("position = a_Position");
        int call = converted.vertexBody().lastIndexOf("nvidium_vertexMain()");
        assertTrue(load >= 0 && load < material && material < position && position < call);
        var parser = new SingleASTTransformer<>();
        parser.setTransformation((tree, root) -> {
            for (var external : tree.getChildren()) {
                if (external instanceof DeclarationExternalDeclaration declaration &&
                        declaration.getDeclaration() instanceof TypeAndInitDeclaration variables) {
                    for (var member : variables.getMembers()) {
                        if (member.getName().getName().equals("iris_Entity") || member.getName().getName().equals("position")) {
                            assertNull(member.getInitializer());
                        }
                    }
                }
            }
        });
        parser.transform(converted.vertexBody());
    }

    @Test
    void reinitializesGlobalLookupArraysUsedByComplementary() {
        var converted = IrisMeshShaderTransformer.transform(VERTEX.replace("void main()", """
                float lookup[2] = float[2](0.0, 1.0);
                void main()
                """), FRAGMENT);
        assertTrue(compact(converted.vertexBody()).contains("lookup=float[2](0.0f,1.0f);"));
        assertParses(converted.vertexBody());
    }

    @Test
    void preservesInferredArraySizesAfterMovingTheInitializer() {
        var converted = IrisMeshShaderTransformer.transform(VERTEX.replace("void main()", """
                vec3[] colors = vec3[](vec3(0.0), vec3(1.0), vec3(0.5));
                void main()
                """), FRAGMENT);
        assertTrue(compact(converted.vertexBody()).contains("vec3[3]colors;"));
        assertTrue(compact(converted.vertexBody()).contains("colors=vec3[]("));
        assertParses(converted.vertexBody());
    }

    @Test
    void doesNotDemandLegacyAttributesThatIrisHasAlreadyReplaced() {
        var converted = IrisMeshShaderTransformer.transform(VERTEX.replace("in uint mc_Entity;", "in uint mc_Entity; in vec4 at_tangent;"), FRAGMENT);
        assertFalse(converted.attributes().stream().anyMatch(attribute -> attribute.name().equals("at_tangent")));
    }

    @Test
    void doesNotRenameLocalsThatShadowVaryings() {
        var converted = IrisMeshShaderTransformer.transform("""
                #version 330 core
                out vec3 normal;
                float f(float normal) { return normal * 2.0; }
                void main() { normal = vec3(f(1.0)); gl_Position = vec4(1.0); }
                """, """
                #version 330 core
                in vec3 normal;
                out vec4 color;
                void main() { color = vec4(normal, 1.0); }
                """);
        assertTrue(compact(converted.vertexBody()).contains("floatf(floatnormal){returnnormal*2.0f;}"), converted.vertexBody());
        assertFalse(converted.vertexBody().contains("nvidium_private"));
        assertParses(converted.vertexBody());
    }

    @Test
    void splitsDeclarationsAndAccountsForMatrixLocations() {
        var converted = IrisMeshShaderTransformer.transform("""
                #version 330 core
                out vec2 a, b;
                out mat3 basis;
                flat out int material;
                void main() { gl_Position = vec4(1.0); }
                """, """
                #version 330 core
                in vec2 a, b;
                in mat3 basis;
                flat in int material;
                out vec4 color;
                void main() { color = vec4(a + b, float(material), basis[0][0]); }
                """);
        assertEquals(List.of(0, 1, 2, 5), converted.varyings().stream().map(IrisMeshShaderTransformer.Varying::location).toList());
        assertEquals(6, converted.locationCount());
        assertParses(converted.fragment());
    }

    @Test
    void preservesVertexIdentityForPerQuadShaderLogic() {
        var converted = IrisMeshShaderTransformer.transform("""
                #version 330 core
                out float index;
                void main() { index = float(gl_VertexID % 4 + gl_InstanceID); gl_Position = vec4(index); }
                """, """
                #version 330 core
                in float index;
                out vec4 color;
                void main() { color = vec4(index); }
                """);
        assertTrue(compact(converted.vertexBody()).contains("nvidium_vertexID%4+nvidium_instanceID"));
        assertFalse(converted.vertexBody().contains("gl_VertexID"));
    }

    @Test
    void assemblesMeshExtensionsBeforeAdapterAndPreservesLiteralText() {
        var converted = IrisMeshShaderTransformer.transform(VERTEX, FRAGMENT);
        String source = converted.mesh("// $literal \\ test\nlayout(local_size_x=1) in;", "void main() {}\n");
        assertTrue(source.startsWith("#version 460 core\n#extension GL_NV_mesh_shader : require\n// $literal \\ test"));
        assertEquals(1, source.split("#version", -1).length - 1);
        assertTrue(source.endsWith("void main() {}\n"));
    }

    @Test
    void rejectsMissingInputsAndInterpolationMismatches() {
        assertThrows(UnsupportedOperationException.class, () -> IrisMeshShaderTransformer.transform(VERTEX,
                FRAGMENT.replace("in vec2 texCoord", "in vec3 texCoord")));
        assertThrows(UnsupportedOperationException.class, () -> IrisMeshShaderTransformer.transform(VERTEX,
                FRAGMENT.replace("flat in int mat", "in int mat")));
        assertThrows(UnsupportedOperationException.class, () -> IrisMeshShaderTransformer.transform(VERTEX,
                FRAGMENT.replace("in vec2 texCoord", "in vec2 other")));
    }

    @Test
    void rejectsUnsupportedInterfacesInsteadOfSilentlyDroppingData() {
        assertThrows(UnsupportedOperationException.class, () -> IrisMeshShaderTransformer.transform(
                VERTEX.replace("out vec2 texCoord;", "out vec2 texCoord[2];"), FRAGMENT));
        assertThrows(UnsupportedOperationException.class, () -> IrisMeshShaderTransformer.transform(
                VERTEX.replace("out vec2 texCoord;", "out Output { vec2 texCoord; } outputData;"), FRAGMENT));
        assertThrows(UnsupportedOperationException.class, () -> IrisMeshShaderTransformer.transform(
                VERTEX.replace("gl_Position =", "gl_PointSize = 1.0; gl_Position ="), FRAGMENT));
        assertThrows(UnsupportedOperationException.class, () -> IrisMeshShaderTransformer.transform(
                VERTEX.replace("position = a_Position", "nvidium_position = a_Position"), FRAGMENT));
    }

    @Test
    void leavesUniformsConstantsAndFragmentOutputsIntact() {
        var converted = IrisMeshShaderTransformer.transform(VERTEX.replace("void main()", """
                uniform vec4 tint = vec4(1.0);
                const float factor = 2.0;
                layout(std140) uniform u_Globals { mat4 u_ModelViewMatrix; };
                void main()
                """), FRAGMENT);
        assertTrue(compact(converted.vertexBody()).contains("uniformvec4tint=vec4(1.0f);"), converted.vertexBody());
        assertTrue(compact(converted.vertexBody()).contains("constfloatfactor=2.0f;"));
        assertTrue(converted.vertexBody().contains("u_ModelViewMatrix"));
        assertTrue(compact(converted.fragment()).contains("layout(location=0)outvec4color;"), converted.fragment());
    }

    private static void assertParses(String source) {
        var parser = new SingleASTTransformer<>();
        parser.setTransformation(tree -> {});
        parser.transform(source);
    }

    private static String compact(String text) { return text.replaceAll("\\s+", ""); }
}

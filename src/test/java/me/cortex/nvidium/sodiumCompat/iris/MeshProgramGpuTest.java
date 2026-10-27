package me.cortex.nvidium.sodiumCompat.iris;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.NVMeshShader;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: compile and link an actual NV mesh program in an invisible GL context. */
@EnabledIfSystemProperty(named = "nvidium.gpuTests", matches = "true")
class MeshProgramGpuTest {
    @Test
    void linksConvertedMeshAndFragmentOnRealDriver() {
        assertTrue(GLFW.glfwInit(), "No GLFW display/graphics context available");
        long window = 0;
        try {
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
            GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
            GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
            window = GLFW.glfwCreateWindow(16, 16, "Nvidium shader compiler test", 0, 0);
            assertNotEquals(0L, window, "No OpenGL 4.6 context available");
            GLFW.glfwMakeContextCurrent(window);
            var capabilities = GL.createCapabilities();
            System.out.println("GPU vendor: " + GL20C.glGetString(GL20C.GL_VENDOR));
            System.out.println("GPU renderer: " + GL20C.glGetString(GL20C.GL_RENDERER));
            assertTrue(capabilities.GL_NV_mesh_shader, "Driver lacks GL_NV_mesh_shader");

            var converted = IrisMeshShaderTransformer.transform("""
                    #version 330 core
                    in vec3 a_Position;
                    in uint mc_Entity;
                    flat out int mat;
                    out vec2 texCoord;
                    vec3 pos = a_Position;
                    void main() { mat = int(mc_Entity); texCoord = pos.xy; gl_Position = vec4(pos, 1.0); }
                    """, """
                    #version 330 core
                    flat in int mat;
                    in vec2 texCoord;
                    out vec4 color;
                    void main() { color = vec4(texCoord, float(mat), 1.0); }
                    """);
            String mesh = converted.mesh("""
                    layout(local_size_x=1) in;
                    layout(triangles, max_vertices=3, max_primitives=1) out;
                    """, """
                    void nvidium_loadVertex(uint i) {
                        a_Position = vec3(float(i & 1u), float(i >> 1u), 0.0);
                        mc_Entity = 7u;
                    }
                    void main() {
                        for (uint i=0u; i<3u; i++) {
                            nvidium_runVertex(i, int(i), 0);
                            nvidium_exportVertex(i);
                            gl_MeshVerticesNV[i].gl_Position = nvidium_position;
                            gl_PrimitiveIndicesNV[i] = i;
                        }
                        gl_PrimitiveCountNV = 1u;
                    }
                    """);
            int meshShader = compile(NVMeshShader.GL_MESH_SHADER_NV, mesh);
            try {
                int fragmentShader = compile(GL20C.GL_FRAGMENT_SHADER, converted.fragment());
                try {
                    int program = GL20C.glCreateProgram();
                    try {
                        GL20C.glAttachShader(program, meshShader);
                        GL20C.glAttachShader(program, fragmentShader);
                        GL20C.glLinkProgram(program);
                        assertEquals(GL20C.GL_TRUE, GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS), GL20C.glGetProgramInfoLog(program));
                        assertRenderedPixel(program);
                    } finally { GL20C.glDeleteProgram(program); }
                } finally { GL20C.glDeleteShader(fragmentShader); }
            } finally { GL20C.glDeleteShader(meshShader); }
        } finally {
            GL.setCapabilities(null);
            if (window != 0) GLFW.glfwDestroyWindow(window);
            GLFW.glfwTerminate();
        }
    }


    private static void assertRenderedPixel(int program) {
        int texture = GL20C.glGenTextures();
        int framebuffer = GL30C.glGenFramebuffers();
        try {
            GL20C.glBindTexture(GL20C.GL_TEXTURE_2D, texture);
            GL20C.glTexImage2D(GL20C.GL_TEXTURE_2D, 0, GL30C.GL_RGBA32F, 16, 16, 0, GL20C.GL_RGBA, GL20C.GL_FLOAT, (java.nio.ByteBuffer) null);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL20C.GL_TEXTURE_2D, texture, 0);
            assertEquals(GL30C.GL_FRAMEBUFFER_COMPLETE, GL30C.glCheckFramebufferStatus(GL30C.GL_FRAMEBUFFER));
            GL20C.glViewport(0, 0, 16, 16);
            GL20C.glClearColor(0, 0, 0, 0);
            GL20C.glClear(GL20C.GL_COLOR_BUFFER_BIT);
            GL20C.glUseProgram(program);
            NVMeshShader.glDrawMeshTasksNV(0, 1);
            try (var stack = MemoryStack.stackPush()) {
                var pixel = stack.mallocFloat(4);
                GL20C.glReadPixels(10, 10, 1, 1, GL20C.GL_RGBA, GL20C.GL_FLOAT, pixel);
                assertEquals(GL20C.GL_NO_ERROR, GL20C.glGetError());
                assertEquals(0.3125f, pixel.get(0), 0.0001f, "interpolated position x");
                assertEquals(0.3125f, pixel.get(1), 0.0001f, "interpolated position y");
                assertEquals(7.0f, pixel.get(2), 0.0001f, "material ID survived mesh-to-fragment handoff");
                assertEquals(1.0f, pixel.get(3), 0.0001f);
            }
        } finally {
            GL20C.glUseProgram(0);
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, 0);
            GL30C.glDeleteFramebuffers(framebuffer);
            GL20C.glDeleteTextures(texture);
        }
    }

    static int compile(int type, String source) {
        int shader = GL20C.glCreateShader(type);
        try {
            GL20C.glShaderSource(shader, source);
            GL20C.glCompileShader(shader);
            assertEquals(GL20C.GL_TRUE, GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS), GL20C.glGetShaderInfoLog(shader));
            return shader;
        } catch (Throwable failure) {
            GL20C.glDeleteShader(shader);
            throw failure;
        }
    }
}

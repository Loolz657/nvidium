package me.cortex.nvidium.sodiumCompat.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuDevice;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.config.IrisConfig;
import net.irisshaders.iris.gl.blending.AlphaTests;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.vertices.sodium.terrain.FormatAnalyzer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.NVMeshShader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Uses unmodified official reference ZIPs, never bundled into the mod or test JAR. */
@EnabledIfSystemProperty(named = "nvidium.gpuTests", matches = "true")
class ComplementaryReferenceTest {
    private static final Pattern INCLUDE = Pattern.compile("(?m)^\\s*#include\\s+\"([^\"]+)\"[^\\r\\n]*");
    private static final List<StringPair> ENVIRONMENT = List.of(
            new StringPair("MC_VERSION", "260300"), new StringPair("IS_IRIS", "1"),
            new StringPair("MC_OS_WINDOWS", "1"), new StringPair("MC_GL_VENDOR_NVIDIA", "1"),
            new StringPair("MC_GL_VERSION", "460"), new StringPair("MC_GLSL_VERSION", "460"),
            new StringPair("IRIS_FEATURE_FADE_VARIABLE", "1"),
            new StringPair("IRIS_FEATURE_CUSTOM_IMAGES", "1"));
    private static Object previousDevice;
    private static Object previousConfig;
    private static Object previousLauncher;
    private static boolean configInstalled;
    private static long window;

    @BeforeAll
    static void preparePureShaderEnvironment() throws Exception {
        assertTrue(GLFW.glfwInit());
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, 4);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, 6);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.GLFW_OPENGL_CORE_PROFILE);
        window = GLFW.glfwCreateWindow(16, 16, "Complementary compiler test", 0, 0);
        assertNotEquals(0, window);
        GLFW.glfwMakeContextCurrent(window);
        assertTrue(GL.createCapabilities().GL_NV_mesh_shader);
        // Only depth convention and config are needed by Iris's source transformer.
        // This is an isolated test process, not a replacement device in a game session.
        var launcherField = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase").getDeclaredField("launcher");
        launcherField.setAccessible(true);
        previousLauncher = launcherField.get(null);
        launcherField.set(null, Proxy.newProxyInstance(launcherField.getType().getClassLoader(), new Class<?>[]{launcherField.getType()},
                (proxy, method, args) -> {
                    if (method.getName().equals("isDevelopment")) return false;
                    throw new UnsupportedOperationException("No game launcher in source conversion test: " + method);
                }));
        var deviceField = RenderSystem.class.getDeclaredField("DEVICE");
        deviceField.setAccessible(true);
        previousDevice = deviceField.get(null);
        var info = new DeviceInfo("Shader source test", "NVIDIA", "test", true, "OpenGL", 1,
                null, null, Set.of(), null, null);
        var device = Proxy.newProxyInstance(GpuDevice.class.getClassLoader(), new Class<?>[]{GpuDevice.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getDeviceInfo")) return info;
                    throw new UnsupportedOperationException("Source test may not perform device operations: " + method);
                });
        deviceField.set(null, device);
        var configField = Iris.class.getDeclaredField("irisConfig");
        configField.setAccessible(true);
        previousConfig = configField.get(null);
        configField.set(null, new IrisConfig(Path.of("build/test-iris.properties"), Path.of("build/test-iris-excluded.json")));
        configInstalled = true;
    }

    @AfterAll
    static void restoreEnvironment() throws Exception {
        GL.setCapabilities(null);
        if (window != 0) GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        var deviceField = RenderSystem.class.getDeclaredField("DEVICE");
        deviceField.setAccessible(true);
        deviceField.set(null, previousDevice);
        if (configInstalled) {
            var configField = Iris.class.getDeclaredField("irisConfig");
            configField.setAccessible(true);
            configField.set(null, previousConfig);
        }
        var launcherField = Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase").getDeclaredField("launcher");
        launcherField.setAccessible(true);
        launcherField.set(null, previousLauncher);
    }

    static Stream<String[]> programs() {
        return Stream.of("ComplementaryReimagined_r5.9.3", "ComplementaryUnbound_r5.9.3",
                        "ComplementaryReimagined_r5.9.3 + EuphoriaPatches_1.10.5", "ComplementaryUnbound_r5.9.3 + EuphoriaPatches_1.10.5")
                .flatMap(pack -> (pack.contains("Euphoria") ? Stream.of("default", "pbr-colored-light") : Stream.of("default"))
                        .flatMap(profile -> Stream.of("world0", "world-1", "world1")
                        .flatMap(dimension -> Stream.of("gbuffers_terrain", "gbuffers_water", "shadow")
                                .map(program -> new String[]{pack, dimension, program, profile}))));
    }

    @ParameterizedTest(name = "{0}/{1}/{2}/{3}")
    @MethodSource("programs")
    void convertsOfficialProgramThroughActualIris(String pack, String dimension, String program, String profile) throws Exception {
        Path shaderRoot = Path.of(System.getProperty("nvidium.referencePacks"), pack, "shaders").toAbsolutePath().normalize();
        assertTrue(Files.isDirectory(shaderRoot), "Fetch the official reference ZIPs first");
        String vertex = preprocess(shaderRoot, dimension + "/" + program + ".vsh", profile);
        String fragment = preprocess(shaderRoot, dimension + "/" + program + ".fsh", profile);
        var patched = TransformPatcher.patchSodium(program, vertex, null, null, null, fragment,
                AlphaTests.ONE_TENTH_ALPHA, new Object2ObjectOpenHashMap<>(), Set.of(), program.equals("shadow"));
        var converted = IrisMeshShaderTransformer.transform(patched.get(PatchShaderType.VERTEX), patched.get(PatchShaderType.FRAGMENT));
        assertFalse(converted.attributes().isEmpty());
        assertFalse(converted.varyings().isEmpty());
        assertTrue(converted.attributes().stream().anyMatch(attribute -> attribute.name().equals("mc_Entity")), "material IDs must be retained");
        var loader = IrisVertexAttributeLoader.create(FormatAnalyzer.createFormat(true, true, true, true).getVertexFormat(), converted.attributes(), 15);
        String mesh = converted.mesh(loader.declarations() + """
                layout(local_size_x = 32) in;
                layout(triangles, max_vertices = 64, max_primitives = 32) out;
                """, loader.implementation() + """
                void main() {
                    uint i = gl_LocalInvocationIndex;
                    if (i < 3u) {
                        nvidium_runVertex(i, int(i), 0);
                        nvidium_exportVertex(i);
                        gl_MeshVerticesNV[i].gl_Position = nvidium_position;
                        gl_PrimitiveIndicesNV[i] = i;
                    }
                    if (i == 0u) gl_PrimitiveCountNV = 1u;
                }
                """);
        Path output = Path.of("build/reference-conversion", pack, profile, dimension);
        Files.createDirectories(output);
        Files.writeString(output.resolve(program + ".vertex-body.glsl"), converted.vertexBody());
        Files.writeString(output.resolve(program + ".fragment.glsl"), converted.fragment());
        Files.writeString(output.resolve(program + ".mesh.glsl"), mesh);
        int meshShader = MeshProgramGpuTest.compile(NVMeshShader.GL_MESH_SHADER_NV, mesh);
        try {
            int fragmentShader = MeshProgramGpuTest.compile(GL20C.GL_FRAGMENT_SHADER, converted.fragment());
            try {
                int linked = GL20C.glCreateProgram();
                try {
                    GL20C.glAttachShader(linked, meshShader);
                    GL20C.glAttachShader(linked, fragmentShader);
                    GL20C.glLinkProgram(linked);
                    assertEquals(GL20C.GL_TRUE, GL20C.glGetProgrami(linked, GL20C.GL_LINK_STATUS), GL20C.glGetProgramInfoLog(linked));
                } finally { GL20C.glDeleteProgram(linked); }
            } finally { GL20C.glDeleteShader(fragmentShader); }
        } finally { GL20C.glDeleteShader(meshShader); }
        var nativeSources = NvidiumIrisMeshAdapter.create(patched.get(PatchShaderType.VERTEX), patched.get(PatchShaderType.FRAGMENT),
                FormatAnalyzer.createFormat(true, true, true, true).getVertexFormat());
        Files.writeString(output.resolve(program + ".nvidium-mesh.glsl"), nativeSources.mesh());
        for (String taskFile : List.of("terrain/task.glsl", "terrain/temporal_task.glsl")) {
            String taskSource = expandNvidium(taskFile, 0).replace("#version 460", "#version 460\n#define USE_SODIUM_VERTEX_FORMAT");
            int nativeProgram = IrisMeshProgramCompiler.compile(pack + "/" + dimension + "/" + program + "/" + taskFile, taskSource, nativeSources);
            try {
                assertTrue(GL20C.glGetUniformLocation(nativeProgram, "nvidium_cameraOffset") >= 0, "native section coordinate adapter must be active");
                assertEquals(-1, GL20C.glGetUniformLocation(nativeProgram, "u_SectionTimeInfo"), "Nvidium must not sample Sodium section-time storage");
            } finally { GL20C.glDeleteProgram(nativeProgram); }
        }
    }

    private static String preprocess(Path root, String file, String profile) throws Exception {
        String source = expand(root, root.resolve(file), 0);
        if (profile.equals("pbr-colored-light")) {
            source = source.replaceAll("(?m)^(\\s*#define RP_MODE) 1\\b", "$1 3")
                    .replaceAll("(?m)^(\\s*#define COLORED_LIGHTING) 0\\b", "$1 128");
        }
        var environment = new java.util.ArrayList<>(ENVIRONMENT);
        net.irisshaders.iris.gl.shader.StandardMacros.getRenderStages().forEach((key, value) -> environment.add(new StringPair(key, value)));
        return JcppProcessor.glslPreprocessSource(source, environment);
    }

    private static String expand(Path root, Path source, int depth) throws Exception {
        Path normalized = source.toAbsolutePath().normalize();
        if (depth > 100 || !normalized.startsWith(root)) throw new IllegalArgumentException("Invalid shader include");
        var matcher = INCLUDE.matcher(Files.readString(normalized));
        var expanded = new StringBuilder();
        while (matcher.find()) {
            String requested = matcher.group(1);
            Path include = requested.startsWith("/") ? root.resolve(requested.substring(1)) : normalized.getParent().resolve(requested);
            matcher.appendReplacement(expanded, java.util.regex.Matcher.quoteReplacement(expand(root, include, depth + 1)));
        }
        matcher.appendTail(expanded);
        if (expanded.length() > 32_000_000) throw new IllegalArgumentException("Shader include size limit");
        return expanded.toString();
    }

    private static String expandNvidium(String file, int depth) throws Exception {
        if (depth > 30 || file.contains("..")) throw new IllegalArgumentException("Invalid native include");
        String resource = "/assets/nvidium/shaders/" + file;
        String source;
        try (var stream = ComplementaryReferenceTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, resource);
            source = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var matcher = Pattern.compile("(?m)^\\s*#import\\s+<nvidium:([^>]+)>[^\\r\\n]*").matcher(source);
        var result = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(expandNvidium(matcher.group(1), depth + 1)));
        matcher.appendTail(result);
        return result.toString();
    }
}

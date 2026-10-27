import com.euphoriapatches.euphoria_patcher.io.ArchiveUtils;
import com.euphoriapatches.shadow.io.sigpipe.jbsdiff.ui.FileUI;
import java.nio.file.*;

/** Local reference generation only. Requires the official EuphoriaPatcher 1.10.5 JAR. */
public class GenerateEuphoriaReferences {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath();
        Path work = Files.createTempDirectory(root, "euphoria-generation-");
        Path base = root.resolve("ComplementaryReimagined_r5.9.3");
        Path baseTar = work.resolve("base.tar");
        Path patch = work.resolve("euphoria.patch");
        Path outputTar = work.resolve("euphoria.tar");
        ArchiveUtils.archive(base, baseTar);
        try (var input = GenerateEuphoriaReferences.class.getResourceAsStream("/EuphoriaPatches_1.10.5.patch")) {
            if (input == null) throw new IllegalStateException("Use the official EuphoriaPatcher 1.10.5 JAR on the classpath");
            Files.copy(input, patch);
        }
        FileUI.patch(baseTar.toFile(), outputTar.toFile(), patch.toFile());
        for (String style : new String[]{"Reimagined", "Unbound"}) {
            Path output = root.resolve("Complementary" + style + "_r5.9.3 + EuphoriaPatches_1.10.5");
            if (Files.isRegularFile(output.resolve("shaders/lib/common.glsl"))) {
                System.out.println("EXISTING_EUPHORIA_REFERENCE " + output);
                continue;
            }
            if (Files.exists(output)) throw new IllegalStateException("Incomplete reference output: " + output);
            ArchiveUtils.extract(outputTar, output);
            Path common = output.resolve("shaders/lib/common.glsl");
            String contents = Files.readString(common).replaceFirst("SHADER_STYLE [14]", "SHADER_STYLE " + (style.equals("Unbound") ? "4" : "1"));
            Files.writeString(common, contents);
            System.out.println("GENERATED_EUPHORIA_REFERENCE " + output);
        }
        // Generated shaders remain local and must not be redistributed with Nvidium.
    }
}

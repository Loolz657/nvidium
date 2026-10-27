package me.cortex.nvidium.sodiumCompat.iris;

import com.mojang.renderpearl.api.vertex.VertexFormat;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Fetches the actual Iris vertex layout, including its material and PBR attributes. */
public final class IrisVertexAttributeLoader {
    private static final Pattern FORMAT = Pattern.compile("(R|RG|RGB|RGBA)(8|16|32)_(UINT|SINT|UNORM|SNORM|FLOAT)");
    private static final Pattern TYPE = Pattern.compile("(float|uint|int|vec([234])|uvec([234])|ivec([234]))");

    public record Loader(int stride, int binding, String declarations, String implementation) {}

    private IrisVertexAttributeLoader() {}

    public static Loader create(VertexFormat format, List<IrisMeshShaderTransformer.Attribute> attributes, int binding) {
        if (format.getVertexSize() <= 0 || format.getVertexSize() % 4 != 0 || binding < 0) {
            throw new IllegalArgumentException("Mesh vertex storage needs a positive, word-aligned stride and binding");
        }
        var assignments = new ArrayList<String>();
        for (var attribute : attributes) {
            var element = format.getElement(attribute.name());
            if (element == null) throw new UnsupportedOperationException("Iris layout lacks attribute " + attribute.name());
            var storage = FORMAT.matcher(element.format().name());
            var target = TYPE.matcher(attribute.type());
            if (!storage.matches() || !target.matches()) {
                throw new UnsupportedOperationException("Unsupported Iris attribute " + attribute + " in " + element.format());
            }
            int storedComponents = element.format().componentCount();
            int bits = Integer.parseInt(storage.group(2));
            String encoding = storage.group(3);
            String family = attribute.type().startsWith("u") ? "uint" : attribute.type().startsWith("i") ? "int" : "float";
            if ((encoding.equals("UINT") && !family.equals("uint")) ||
                    (encoding.equals("SINT") && !family.equals("int")) ||
                    (!encoding.endsWith("INT") && !family.equals("float"))) {
                throw new UnsupportedOperationException("Iris attribute numeric type mismatch: " + attribute);
            }
            int components = attribute.type().matches("(?:float|uint|int)") ? 1 : attribute.type().charAt(attribute.type().length() - 1) - '0';
            var values = new ArrayList<String>();
            for (int component = 0; component < components; component++) {
                if (component >= storedComponents) {
                    values.add((component == 3 ? "1" : "0") + (family.equals("uint") ? "u" : family.equals("float") ? ".0" : ""));
                    continue;
                }
                String address = "base + " + (element.offset() + component * bits / 8) + "u";
                String raw = "nvidium_read" + bits + "(" + address + ")";
                String signed = bits == 32 ? "int(" + raw + ")" : "(int(" + raw + " << " + (32 - bits) + "u) >> " + (32 - bits) + ")";
                values.add(switch (encoding) {
                    case "UINT" -> raw;
                    case "SINT" -> signed;
                    case "UNORM" -> "(float(" + raw + ") / " + ((1L << bits) - 1) + ".0)";
                    case "SNORM" -> "max(float(" + signed + ") / " + ((1L << (bits - 1)) - 1) + ".0, -1.0)";
                    case "FLOAT" -> bits == 32 ? "uintBitsToFloat(" + raw + ")" : bits == 16 ? "unpackHalf2x16(" + raw + ").x" : throwUnsupportedFloat();
                    default -> throw new IllegalStateException(encoding);
                });
            }
            assignments.add("    " + attribute.name() + " = " + attribute.type() + "(" + String.join(", ", values) + ");");
        }
        String declarations = "layout(std430, binding = " + binding + ") readonly buffer NvidiumVertexStorage { uint nvidium_vertexWords[]; };\n";
        String implementation = """
                uint nvidium_read8(uint byteOffset) {
                    return (nvidium_vertexWords[byteOffset >> 2u] >> ((byteOffset & 3u) * 8u)) & 255u;
                }
                uint nvidium_read16(uint byteOffset) {
                    return nvidium_read8(byteOffset) | (nvidium_read8(byteOffset + 1u) << 8u);
                }
                uint nvidium_read32(uint byteOffset) {
                    return nvidium_read16(byteOffset) | (nvidium_read16(byteOffset + 2u) << 16u);
                }
                void nvidium_loadVertex(uint vertexIndex) {
                """ + "    uint base = vertexIndex * " + format.getVertexSize() + "u;\n" + String.join("\n", assignments) + "\n}\n";
        return new Loader(format.getVertexSize(), binding, declarations, implementation);
    }

    private static String throwUnsupportedFloat() { throw new UnsupportedOperationException("8-bit float vertex attributes"); }
}

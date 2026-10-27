package me.cortex.nvidium.sodiumCompat.iris;

import io.github.douira.glsl_transformer.ast.node.Identifier;
import io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.type.initializer.ExpressionInitializer;
import io.github.douira.glsl_transformer.ast.node.type.specifier.ArraySpecifier;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.LayoutQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifierPart;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinNumericTypeSpecifier;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.print.PrintType;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.SingleASTTransformer;
import io.github.douira.glsl_transformer.util.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CPU-only conversion of an already preprocessed, Iris-patched vertex/fragment pair.
 * Does not activate Nvidium, create a GL program, or claim pack compatibility.
 * A backend must supply the attribute loader and invoke nvidium_runVertex once per vertex.
 *
 * Explicit locations connect the mesh exports to the fragment inputs. Keeping original
 * vertex variables private avoids renaming local variables which shadow their names.
 */
public final class IrisMeshShaderTransformer {
    private static final Set<String> UNSUPPORTED_BUILTINS = Set.of(
            "gl_ClipDistance", "gl_CullDistance", "gl_PointSize", "gl_BaseVertex",
            "gl_BaseInstance", "gl_DrawID", "gl_ViewportIndex", "gl_Layer");

    public record Attribute(String name, String type) {}
    public record Varying(String name, String type, int location, int slots, String qualifiers) {}
    public record Sources(String vertexBody, String fragment, List<Attribute> attributes,
                          List<Varying> varyings, int locationCount) {
        public Sources {
            attributes = List.copyOf(attributes);
            varyings = List.copyOf(varyings);
        }

        /** Definitions supplied here must implement nvidium_loadVertex(uint). */
        public String mesh(String adapterDeclarations, String adapterImplementation) {
            Objects.requireNonNull(adapterDeclarations);
            Objects.requireNonNull(adapterImplementation);
            // Euphoria adds its own extension directives. Keep all of them ahead of
            // the adapter's storage/interface declarations as required by GLSL.
            var extensions = java.util.regex.Pattern.compile("(?m)^\\s*#extension[^\\r\\n]*").matcher(vertexBody);
            var header = new StringBuilder("#version 460 core\n#extension GL_NV_mesh_shader : require\n");
            while (extensions.find()) header.append(extensions.group().strip()).append('\n');
            String body = extensions.replaceAll("").replaceFirst("(?m)^#version[^\\r\\n]*", "");
            return header + adapterDeclarations + "\n" + body + "\n" + adapterImplementation;
        }
    }

    private IrisMeshShaderTransformer() {}

    public static Sources transform(String vertex, String fragment) {
        return transform(vertex, fragment, Map.of());
    }

    static Sources transform(String vertex, String fragment, Map<String, String> expressionOverrides) {
        Objects.requireNonNull(vertex);
        Objects.requireNonNull(fragment);
        var inputs = new ArrayList<Attribute>();
        var outputs = new LinkedHashMap<String, Varying>();
        var initializers = new ArrayList<String>();
        var locationCount = new int[1];

        var vertexTransformer = new SingleASTTransformer<>();
        vertexTransformer.setPrintType(PrintType.INDENTED);
        vertexTransformer.setTransformation((tree, root) -> {
            if (root.nodeIndex.get(Identifier.class).stream().anyMatch(id -> id.getName().startsWith("nvidium_"))) {
                throw unsupported("reserved nvidium_ identifier prefix");
            }
            for (String builtin : UNSUPPORTED_BUILTINS) {
                if (root.identifierIndex.has(builtin)) throw unsupported(builtin);
            }
            if (!root.identifierIndex.has("main")) throw unsupported("missing vertex main");
            root.rename("main", "nvidium_vertexMain");
            root.rename("gl_Position", "nvidium_position");
            root.rename("gl_VertexID", "nvidium_vertexID");
            root.rename("gl_InstanceID", "nvidium_instanceID");
            for (var override : expressionOverrides.entrySet()) {
                root.replaceReferenceExpressions(vertexTransformer, override.getKey(), override.getValue());
            }
            if (!expressionOverrides.isEmpty()) {
                boolean mutableFade = root.nodeIndex.get(TypeAndInitDeclaration.class).stream().anyMatch(variables ->
                        !has(variables.getType().getTypeQualifier(), StorageQualifier.StorageType.CONST) &&
                                variables.getMembers().stream().anyMatch(member -> member.getName().getName().equals("mc_chunkFade")));
                if (mutableFade) for (var function : root.nodeIndex.get(FunctionDefinition.class)) {
                    if (function.getFunctionPrototype().getName().getName().equals("_vert_init")) {
                        // Nvidium owns section residency and does not have Sodium's per-region
                        // time texture. A full fade makes the old lookup dead code for the GL compiler.
                        function.getBody().getChildren().add(vertexTransformer.parseStatement(root, "mc_chunkFade = 1.0;"));
                    }
                }
            }

            for (var external : List.copyOf(tree.getChildren())) {
                if (!(external instanceof DeclarationExternalDeclaration declaration)) continue;
                if (!(declaration.getDeclaration() instanceof TypeAndInitDeclaration variables)) {
                    // Uniform/storage blocks stay intact; interface blocks need a separate adapter.
                    String text = ASTPrinter.printSimple(declaration);
                    if (text.matches("(?s).*\\b(in|out)\\b.*\\{.*")) {
                        throw unsupported("vertex interface block");
                    }
                    continue;
                }
                var qualifier = variables.getType().getTypeQualifier();
                boolean input = has(qualifier, StorageQualifier.StorageType.IN);
                boolean output = has(qualifier, StorageQualifier.StorageType.OUT);
                if (input || output) {
                    String type = interfaceType(variables);
                    String interpolation = interpolation(qualifier);
                    for (var member : variables.getMembers()) {
                        if (member.getArraySpecifier() != null || member.getInitializer() != null) {
                            throw unsupported("array or initialized vertex interface");
                        }
                        String name = member.getName().getName();
                        if (input) {
                            // Iris can leave the declaration of an attribute after replacing all
                            // its uses (e.g. legacy at_tangent with packed irs_Tangent).
                            if (root.identifierIndex.get(name).size() > 1) inputs.add(new Attribute(name, type));
                        } else {
                            int slots = slots(type);
                            if (outputs.putIfAbsent(name, new Varying(name, type, locationCount[0], slots, interpolation)) != null) {
                                throw unsupported("duplicate output " + name);
                            }
                            locationCount[0] += slots;
                        }
                    }
                    // The private variable must not retain interpolation or location qualifiers.
                    variables.getType().setTypeQualifier(null);
                } else if (isMutableGlobal(qualifier)) {
                    // One mesh invocation can execute several vertices. Attribute-dependent global
                    // initializers must be evaluated again after each vertex's inputs are loaded.
                    for (var member : variables.getMembers()) {
                        if (member.getInitializer() == null) continue;
                        resolveArraySize(member.getArraySpecifier(), member.getInitializer());
                        resolveArraySize(variables.getType().getTypeSpecifier().getArraySpecifier(), member.getInitializer());
                        String value = ASTPrinter.printSimple(member.getInitializer());
                        if (value.stripLeading().startsWith("{")) throw unsupported("aggregate global initializer");
                        initializers.add(member.getName().getName() + " = " + value + ";");
                        member.setInitializer(null);
                    }
                }
            }

            tree.parseAndInjectNodes(vertexTransformer, ASTInjectionPoint.BEFORE_DECLARATIONS,
                    "vec4 nvidium_position;", "int nvidium_vertexID;", "int nvidium_instanceID;",
                    "void nvidium_loadVertex(uint vertexIndex);");
            if (!outputs.isEmpty()) tree.parseAndInjectNode(vertexTransformer, ASTInjectionPoint.BEFORE_DECLARATIONS,
                    interfaceBlock(outputs.values(), "out", "nvidium_export[]"));
        });
        String vertexBody = vertexTransformer.transform(vertex);

        var consumed = new java.util.HashSet<String>();
        var fragmentTransformer = new SingleASTTransformer<>();
        fragmentTransformer.setPrintType(PrintType.INDENTED);
        fragmentTransformer.setTransformation((tree, root) -> {
            for (var external : List.copyOf(tree.getChildren())) {
                if (!(external instanceof DeclarationExternalDeclaration declaration)) continue;
                if (!(declaration.getDeclaration() instanceof TypeAndInitDeclaration variables)) {
                    if (ASTPrinter.printSimple(declaration).matches("(?s).*\\bin\\b.*\\{.*")) {
                        throw unsupported("fragment interface block");
                    }
                    continue;
                }
                var qualifier = variables.getType().getTypeQualifier();
                if (!has(qualifier, StorageQualifier.StorageType.IN)) continue;
                String type = interfaceType(variables);
                String interpolation = interpolation(qualifier);
                for (var member : List.copyOf(variables.getMembers())) {
                    if (member.getArraySpecifier() != null || member.getInitializer() != null) {
                        throw unsupported("array or initialized fragment input");
                    }
                    String name = member.getName().getName();
                    Varying varying = outputs.get(name);
                    if (varying == null || !varying.type.equals(type)) {
                        throw unsupported("unmatched fragment input " + name);
                    }
                    if (!interpolation.equals(varying.qualifiers)) {
                        throw unsupported("interpolation mismatch for " + name);
                    }
                    if (!consumed.add(name)) throw unsupported("duplicate fragment input " + name);
                }
                declaration.detachAndDelete();
            }
            if (!outputs.isEmpty()) tree.parseAndInjectNode(fragmentTransformer, ASTInjectionPoint.BEFORE_DECLARATIONS,
                    interfaceBlock(outputs.values(), "in", ""));
        });
        String fragmentBody = fragmentTransformer.transform(fragment);

        // Appended after the original program so normal GLSL function visibility applies.
        var wrapper = new StringBuilder("\nvoid nvidium_runVertex(uint vertexIndex, int vertexId, int instanceId) {\n")
                .append("    nvidium_vertexID = vertexId;\n    nvidium_instanceID = instanceId;\n")
                .append("    nvidium_loadVertex(vertexIndex);\n");
        for (String initializer : initializers) wrapper.append("    ").append(initializer).append('\n');
        wrapper.append("    nvidium_vertexMain();\n}\n");
        // Built-in mesh outputs are emitted by the backend. Keep this helper parseable as
        // ordinary GLSL too, so the entire rewritten pack body can be checked without a GPU.
        wrapper.append("void nvidium_exportVertex(uint outputIndex) {\n");
        for (Varying varying : outputs.values()) {
            wrapper.append("    nvidium_export[outputIndex].").append(varying.name).append(" = ")
                    .append(varying.name).append(";\n");
        }
        wrapper.append("}\n");
        return new Sources(vertexBody + wrapper, upgradeVersion(fragmentBody), inputs,
                new ArrayList<>(outputs.values()), locationCount[0]);
    }

    private static boolean has(TypeQualifier qualifier, StorageQualifier.StorageType type) {
        return qualifier != null && qualifier.getParts().stream().anyMatch(
                part -> part instanceof StorageQualifier storage && storage.storageType == type);
    }

    private static void resolveArraySize(ArraySpecifier array, io.github.douira.glsl_transformer.ast.node.type.initializer.Initializer initializer) {
        if (array == null || !array.getDimensions().contains(null)) return;
        if (array.getDimensions().size() != 1 || !(initializer instanceof ExpressionInitializer expression) ||
                !(expression.getExpression() instanceof FunctionCallExpression constructor) ||
                constructor.getFunctionSpecifier() == null || constructor.getFunctionSpecifier().getArraySpecifier() == null ||
                constructor.getParameters().isEmpty()) throw unsupported("cannot infer global array size");
        array.getDimensions().set(0, new LiteralExpression(Type.INT32, constructor.getParameters().size()));
    }

    private static String interfaceBlock(java.util.Collection<Varying> varyings, String direction, String instance) {
        var block = new StringBuilder("layout(location = 0) ").append(direction).append(" NvidiumVaryings {\n");
        for (var varying : varyings) block.append(varying.qualifiers).append(varying.type).append(' ').append(varying.name).append(";\n");
        return block.append("} ").append(instance).append(';').toString();
    }

    private static boolean isMutableGlobal(TypeQualifier qualifier) {
        return qualifier == null || qualifier.getParts().stream().noneMatch(part -> part instanceof StorageQualifier);
    }

    private static String interfaceType(TypeAndInitDeclaration variables) {
        if (!(variables.getType().getTypeSpecifier() instanceof BuiltinNumericTypeSpecifier numeric)
                || numeric.getArraySpecifier() != null) throw unsupported("non-scalar/vector/matrix interface");
        String type = numeric.type.getCompactName();
        slots(type); // Validate before changing either stage.
        return type;
    }

    private static int slots(String type) {
        if (type.matches("(?:float|int|uint|[iu]?vec[234])")) return 1;
        if (type.matches("mat[234](?:x[234])?")) return type.charAt(3) - '0';
        throw unsupported("interface type " + type);
    }

    private static String interpolation(TypeQualifier qualifier) {
        if (qualifier == null) return "";
        var parts = new ArrayList<String>();
        for (TypeQualifierPart part : qualifier.getParts()) {
            if (part instanceof StorageQualifier || part instanceof LayoutQualifier) continue;
            String value = ASTPrinter.printSimple(part).strip();
            if (!Set.of("flat", "smooth", "noperspective", "centroid", "sample", "highp", "mediump", "lowp", "invariant").contains(value)) {
                throw unsupported("interface qualifier " + value);
            }
            if (!value.equals("smooth")) parts.add(value);
        }
        parts.sort(String::compareTo);
        return parts.isEmpty() ? "" : String.join(" ", parts) + " ";
    }

    private static String upgradeVersion(String source) {
        return source.replaceFirst("(?m)^#version[^\\r\\n]*", "#version 460 core");
    }

    private static UnsupportedOperationException unsupported(String reason) {
        return new UnsupportedOperationException("Iris mesh bridge cannot safely convert: " + reason);
    }
}

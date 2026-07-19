package com.flowscope.service;

import com.flowscope.entity.CFG;
import com.flowscope.entity.EdgeKind;
import com.flowscope.entity.IREdge;
import com.flowscope.entity.IRNode;
import com.flowscope.entity.NodeKind;
import com.flowscope.entity.ExtractionResult;
import com.flowscope.service.Extractor;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.flowscope.util.SpringEndpoints;

/**
 * JavaParser-based extraction for Java sources (Requirement 17). Produces CLASS
 * and FUNCTION IR nodes, structural CONTAINS edges, per-method CFGs, and Spring
 * MVC endpoint metadata. Cross-file {@code calls} edges are added later by
 * {@link com.flowscope.service.JavaCallResolver}.
 *
 * <p>Stateless and thread-safe: a fresh {@link JavaParser} is created per call.
 */
public class JavaExtractor implements Extractor {

    @Override
    public String languageId() {
        return "java";
    }

    @Override
    public ExtractionResult extract(String relativePath, byte[] source) {
        List<IRNode> nodes = new ArrayList<>();
        List<IREdge> edges = new ArrayList<>();
        Map<String, CFG> cfgs = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        ParserConfiguration config = new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21);
        JavaParser parser = new JavaParser(config);

        ParseResult<CompilationUnit> result;
        try {
            result = parser.parse(new String(source, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            errors.add(relativePath + ": parse failed: " + e.getMessage());
            return ExtractionResult.empty();
        }

        Optional<CompilationUnit> maybeCu = result.getResult();
        if (maybeCu.isEmpty()) {
            // Requirement 17.7: unparseable file → caller may fall back; we skip.
            errors.add(relativePath + ": no compilation unit produced");
            return ExtractionResult.empty();
        }
        CompilationUnit cu = maybeCu.get();

        // findAll(TypeDeclaration) covers classes, interfaces, enums, and records
        // at any nesting depth.
        for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
            String className = type.getNameAsString();
            String classId = "class:" + relativePath + "#" + className;
            nodes.add(new IRNode(
                    classId, NodeKind.CLASS, className, relativePath,
                    lineOf(type, true), lineOf(type, false), "java",
                    null, classMeta(type)));

            String basePath = SpringEndpoints.basePath(type);

            List<CallableDeclaration<?>> callables = new ArrayList<>();
            callables.addAll(type.getMethods());
            callables.addAll(type.findAll(ConstructorDeclaration.class,
                    c -> c.getParentNode().orElse(null) == type));

            for (CallableDeclaration<?> callable : callables) {
                String name = callable.getNameAsString();
                int startLine = lineOf(callable, true);
                String funcId = "func:" + relativePath + "#" + name + "@" + startLine;

                Map<String, Object> meta = endpointMeta(callable, basePath);
                if (isMainMethod(callable)) {
                    if (meta == null) {
                        meta = new LinkedHashMap<>();
                    }
                    meta.put("entryPoint", "main");
                }
                nodes.add(new IRNode(
                        funcId, NodeKind.FUNCTION, name, relativePath,
                        startLine, lineOf(callable, false), "java",
                        signatureOf(callable), meta));
                edges.add(new IREdge(classId, funcId, EdgeKind.CONTAINS, 1.0, 0));

                bodyOf(callable).ifPresent(body -> {
                    int entryLine = body.getBegin().map(p -> p.line).orElse(startLine);
                    int exitLine = body.getEnd().map(p -> p.line).orElse(entryLine);
                    JavaCfgBuilder.build(funcId, name, entryLine, exitLine, body, relativePath)
                            .ifPresent(cfg -> cfgs.put(funcId, cfg));
                });
            }
        }

        return new ExtractionResult(nodes, edges, cfgs, errors);
    }

    /** True for a {@code public static void main(String[])} entry point (non-web apps). */
    private static boolean isMainMethod(CallableDeclaration<?> callable) {
        if (!(callable instanceof MethodDeclaration m)) {
            return false;
        }
        return m.getNameAsString().equals("main")
                && m.isStatic()
                && m.getParameters().size() == 1;
    }

    private static Optional<BlockStmt> bodyOf(CallableDeclaration<?> callable) {
        if (callable instanceof MethodDeclaration m) {
            return m.getBody();
        }
        if (callable instanceof ConstructorDeclaration c) {
            return Optional.of(c.getBody());
        }
        return Optional.empty();
    }

    private static int lineOf(com.github.javaparser.ast.Node n, boolean begin) {
        return (begin ? n.getBegin() : n.getEnd()).map(p -> p.line).orElse(0);
    }

    private static String signatureOf(CallableDeclaration<?> callable) {
        try {
            return callable.getDeclarationAsString(false, false, true).trim();
        } catch (RuntimeException e) {
            return callable.getNameAsString();
        }
    }

    private static Map<String, Object> classMeta(TypeDeclaration<?> type) {
        String kind;
        if (type.isEnumDeclaration()) {
            kind = "enum";
        } else if (type.isRecordDeclaration()) {
            kind = "record";
        } else if (type instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration coi) {
            kind = coi.isInterface() ? "interface"
                    : (coi.isAbstract() ? "abstract" : "class");
        } else {
            kind = "class";
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", kind);
        return meta;
    }

    /** Endpoint metadata for a method, or null when it is not an endpoint. */
    private static Map<String, Object> endpointMeta(CallableDeclaration<?> callable, String basePath) {
        return SpringEndpoints.endpoint(callable, basePath)
                .<Map<String, Object>>map(vp -> {
                    Map<String, Object> meta = new LinkedHashMap<>();
                    meta.put("httpMethod", vp[0]);
                    meta.put("endpoint", vp[1]);
                    return meta;
                })
                .orElse(null);
    }
}

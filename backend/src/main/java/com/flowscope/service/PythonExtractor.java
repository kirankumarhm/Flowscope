package com.flowscope.service;

import com.flowscope.entity.CFG;
import com.flowscope.entity.EdgeKind;
import com.flowscope.entity.ExtractionResult;
import com.flowscope.entity.IREdge;
import com.flowscope.entity.IRNode;
import com.flowscope.entity.NodeKind;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterPython;

/**
 * tree-sitter-based extraction for Python sources. Emits a CLASS node per
 * {@code class} (plus a module-level CLASS for top-level functions), a FUNCTION
 * node per {@code def} (methods qualified {@code Class.method}), CONTAINS edges,
 * and per-function CFGs via {@link PythonCfgBuilder}. Same id contract as Java/Go
 * ({@code func:<rel>#<name>@<line>}). Decorated defs ({@code @app.route}) are
 * unwrapped. Cross-file call edges are not resolved.
 *
 * <p>Stateless/thread-safe: the immutable grammar is shared; a fresh parser per call.
 */
public class PythonExtractor implements Extractor {

    private static final TSLanguage PYTHON = new TreeSitterPython();

    @Override
    public String languageId() {
        return "python";
    }

    @Override
    public ExtractionResult extract(String relativePath, byte[] source) {
        List<IRNode> nodes = new ArrayList<>();
        List<IREdge> edges = new ArrayList<>();
        Map<String, CFG> cfgs = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        TSParser parser = new TSParser();
        parser.setLanguage(PYTHON);
        TSTree tree;
        try {
            tree = parser.parseString(null, new String(source, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            errors.add(relativePath + ": parse failed: " + e.getMessage());
            return ExtractionResult.empty();
        }
        TSNode root = tree.getRootNode();
        if (root.isNull()) {
            return ExtractionResult.empty();
        }

        // Module-level container for top-level functions (named after the file stem).
        String moduleName = moduleName(relativePath);
        String moduleId = "class:" + relativePath + "#" + moduleName;
        boolean moduleUsed = false;

        for (int i = 0; i < root.getNamedChildCount(); i++) {
            TSNode decl = unwrapDecorated(root.getNamedChild(i));
            switch (decl.getType()) {
                case "function_definition" -> {
                    emitFunction(decl, null, moduleId, relativePath, source, nodes, edges, cfgs);
                    moduleUsed = true;
                }
                case "class_definition" -> {
                    String className = fieldText(decl, "name", source);
                    String classId = "class:" + relativePath + "#" + className;
                    nodes.add(new IRNode(classId, NodeKind.CLASS, className, relativePath,
                            decl.getStartPoint().getRow() + 1, decl.getEndPoint().getRow() + 1,
                            "python", null, Map.of("type", "class")));
                    TSNode body = decl.getChildByFieldName("body");
                    if (body != null && !body.isNull()) {
                        for (int j = 0; j < body.getNamedChildCount(); j++) {
                            TSNode member = unwrapDecorated(body.getNamedChild(j));
                            if ("function_definition".equals(member.getType())) {
                                emitFunction(member, className, classId, relativePath, source,
                                        nodes, edges, cfgs);
                            }
                        }
                    }
                }
                default -> {
                    // module-level statements ignored for the structural graph
                }
            }
        }

        if (moduleUsed) {
            nodes.add(0, new IRNode(moduleId, NodeKind.CLASS, moduleName, relativePath,
                    1, root.getEndPoint().getRow() + 1, "python", null, Map.of("type", "module")));
        }
        return new ExtractionResult(nodes, edges, cfgs, errors);
    }

    private void emitFunction(TSNode def, String className, String containerId, String relativePath,
                              byte[] source, List<IRNode> nodes, List<IREdge> edges,
                              Map<String, CFG> cfgs) {
        String simple = fieldText(def, "name", source);
        if (simple.isEmpty()) {
            return;
        }
        String name = className != null ? className + "." + simple : simple;
        int startLine = def.getStartPoint().getRow() + 1;
        String funcId = "func:" + relativePath + "#" + name + "@" + startLine;

        Map<String, Object> meta = new LinkedHashMap<>();
        if (className == null && simple.equals("main")) {
            meta.put("entryPoint", "main");
        }
        TSNode body = def.getChildByFieldName("body");
        nodes.add(new IRNode(funcId, NodeKind.FUNCTION, name, relativePath,
                startLine, def.getEndPoint().getRow() + 1, "python",
                signature(def, body, source), meta.isEmpty() ? null : meta));
        edges.add(new IREdge(containerId, funcId, EdgeKind.CONTAINS, 1.0, 0));

        PythonCfgBuilder.build(funcId, name, startLine, body, source, relativePath)
                .ifPresent(cfg -> cfgs.put(funcId, cfg));
    }

    /** A {@code decorated_definition} wraps the real def; unwrap to it. */
    private static TSNode unwrapDecorated(TSNode n) {
        if (!"decorated_definition".equals(n.getType())) {
            return n;
        }
        TSNode def = n.getChildByFieldName("definition");
        return def != null && !def.isNull() ? def : n;
    }

    private static String moduleName(String relativePath) {
        String p = relativePath.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        String file = slash >= 0 ? p.substring(slash + 1) : p;
        return file.endsWith(".py") ? file.substring(0, file.length() - 3) : file;
    }

    private static String signature(TSNode def, TSNode body, byte[] src) {
        int start = def.getStartByte();
        int end = (body != null && !body.isNull()) ? body.getStartByte() : def.getEndByte();
        if (end <= start) {
            return text(def, src);
        }
        return new String(src, start, end - start, StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ").replaceAll(":\\s*$", "").trim();
    }

    private static String fieldText(TSNode n, String field, byte[] src) {
        TSNode c = n.getChildByFieldName(field);
        return c != null && !c.isNull() ? text(c, src) : "";
    }

    private static String text(TSNode n, byte[] src) {
        return new String(src, n.getStartByte(), n.getEndByte() - n.getStartByte(), StandardCharsets.UTF_8);
    }
}

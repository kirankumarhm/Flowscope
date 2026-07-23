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
import org.treesitter.TreeSitterGo;

/**
 * tree-sitter-based extraction for Go sources. Produces a package-level CLASS
 * node per file, a FUNCTION node per top-level {@code func}/method (with the same
 * {@code func:<rel>#<name>@<line>} id contract as Java so the picker, analyze
 * {@code cfgs} map, and flow/sequence lookups interoperate), CONTAINS edges, and
 * a per-function CFG via {@link GoCfgBuilder}. Cross-file call edges are not
 * resolved (Go analyze graph is structural for now).
 *
 * <p>Stateless/thread-safe: the grammar ({@link TSLanguage}) is immutable and
 * shared; a fresh {@link TSParser} is created per call.
 */
public class GoExtractor implements Extractor {

    /** The Go grammar is immutable and safe to share across parsers/threads. */
    private static final TSLanguage GO = new TreeSitterGo();

    @Override
    public String languageId() {
        return "go";
    }

    @Override
    public ExtractionResult extract(String relativePath, byte[] source) {
        List<IRNode> nodes = new ArrayList<>();
        List<IREdge> edges = new ArrayList<>();
        Map<String, CFG> cfgs = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        TSParser parser = new TSParser();
        parser.setLanguage(GO);
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

        String pkg = packageName(root, source);
        String classId = "class:" + relativePath + "#" + pkg;
        nodes.add(new IRNode(classId, NodeKind.CLASS, pkg, relativePath,
                1, root.getEndPoint().getRow() + 1, "go", null, Map.of("type", "package")));

        for (int i = 0; i < root.getNamedChildCount(); i++) {
            TSNode decl = root.getNamedChild(i);
            String type = decl.getType();
            boolean func = type.equals("function_declaration");
            boolean method = type.equals("method_declaration");
            if (!func && !method) {
                continue;
            }
            TSNode nameNode = decl.getChildByFieldName("name");
            if (nameNode == null || nameNode.isNull()) {
                continue;
            }
            String simpleName = text(nameNode, source);
            String name = method ? receiverType(decl, source) + "." + simpleName : simpleName;
            int startLine = decl.getStartPoint().getRow() + 1;
            String funcId = "func:" + relativePath + "#" + name + "@" + startLine;

            Map<String, Object> meta = new LinkedHashMap<>();
            if (simpleName.equals("main") && func) {
                meta.put("entryPoint", "main");
            }
            TSNode body = decl.getChildByFieldName("body");
            nodes.add(new IRNode(funcId, NodeKind.FUNCTION, name, relativePath,
                    startLine, decl.getEndPoint().getRow() + 1, "go",
                    signature(decl, body, source), meta.isEmpty() ? null : meta));
            edges.add(new IREdge(classId, funcId, EdgeKind.CONTAINS, 1.0, 0));

            GoCfgBuilder.build(funcId, name, startLine, body, source, relativePath)
                    .ifPresent(cfg -> cfgs.put(funcId, cfg));
        }

        return new ExtractionResult(nodes, edges, cfgs, errors);
    }

    /** {@code package foo} → "foo"; defaults to "main" if absent. */
    private static String packageName(TSNode root, byte[] src) {
        for (int i = 0; i < root.getNamedChildCount(); i++) {
            TSNode c = root.getNamedChild(i);
            if ("package_clause".equals(c.getType()) && c.getNamedChildCount() > 0) {
                return text(c.getNamedChild(0), src);
            }
        }
        return "main";
    }

    /** Receiver type of a method, without pointer/parens: {@code (s *Server)} → "Server". */
    private static String receiverType(TSNode method, byte[] src) {
        TSNode recv = method.getChildByFieldName("receiver");
        if (recv == null || recv.isNull()) {
            return "";
        }
        // receiver is a parameter_list → parameter_declaration → [type]
        TSNode typeNode = findFirst(recv, "pointer_type");
        if (typeNode != null) {
            TSNode inner = findFirst(typeNode, "type_identifier");
            if (inner != null) {
                return text(inner, src);
            }
        }
        TSNode id = findFirst(recv, "type_identifier");
        return id != null ? text(id, src) : "";
    }

    /** First descendant of the given node type (depth-first), or null. */
    private static TSNode findFirst(TSNode n, String type) {
        if (type.equals(n.getType())) {
            return n;
        }
        for (int i = 0; i < n.getNamedChildCount(); i++) {
            TSNode found = findFirst(n.getNamedChild(i), type);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Declaration text up to (not including) the body block — the signature line. */
    private static String signature(TSNode decl, TSNode body, byte[] src) {
        int start = decl.getStartByte();
        int end = (body != null && !body.isNull()) ? body.getStartByte() : decl.getEndByte();
        if (end <= start) {
            return text(decl, src);
        }
        return new String(src, start, end - start, StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ").trim();
    }

    private static String text(TSNode n, byte[] src) {
        return new String(src, n.getStartByte(), n.getEndByte() - n.getStartByte(), StandardCharsets.UTF_8);
    }
}

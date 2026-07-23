package com.flowscope.service;

import com.flowscope.entity.CFG;
import com.flowscope.entity.CFGEdge;
import com.flowscope.entity.CFGNode;
import com.flowscope.entity.CFGNodeKind;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.treesitter.TSNode;

/**
 * Builds a per-function control-flow graph for a Go function/method from its
 * tree-sitter AST body block. Intra-procedural (no callee inlining) — it mirrors
 * the language-neutral {@link CFGNodeKind} contract and branch-label conventions
 * ("yes"/"no"/"default"/case value) used by the Java CFG builder so the Flow
 * Chart renders Go and Java identically.
 *
 * <p>Handled: sequential statements, {@code if/else if/else}, {@code for} (all
 * forms), {@code switch}/{@code type switch}/{@code select}, {@code return},
 * call sites (as CALL nodes), {@code go}/{@code defer}, and {@code panic(...)}
 * (as THROW). Anything else becomes a plain STATEMENT node.
 */
final class GoCfgBuilder {

    private static final int LABEL_MAX = 52;
    private static final int NODE_BUDGET = 1200;

    private final byte[] src;
    private final String relPath;
    private final List<CFGNode> nodes = new ArrayList<>();
    private final List<CFGEdge> edges = new ArrayList<>();
    private final List<Pending> toExit = new ArrayList<>();
    private int counter;
    private boolean truncated;

    /** A dangling out-edge waiting to be connected to the next node. */
    private record Pending(String from, String label) {
    }

    private GoCfgBuilder(byte[] src, String relPath) {
        this.src = src;
        this.relPath = relPath;
    }

    /**
     * Build the CFG for one function body.
     *
     * @param funcId    the {@code func:<rel>#<name>@<line>} id (also the CFG key)
     * @param entryLabel text shown on the ENTRY node (function name/signature)
     * @param entryLine  1-based line of the declaration
     * @param body       the function's {@code block} node (may be null → abstract)
     */
    static Optional<CFG> build(String funcId, String entryLabel, int entryLine,
                               TSNode body, byte[] src, String relPath) {
        if (body == null || body.isNull()) {
            return Optional.empty();
        }
        GoCfgBuilder b = new GoCfgBuilder(src, relPath);
        return Optional.of(b.run(funcId, entryLabel, entryLine, body));
    }

    private CFG run(String funcId, String entryLabel, int entryLine, TSNode body) {
        String entry = node(CFGNodeKind.ENTRY, entryLabel, entryLine, null);
        List<Pending> preds = processBlock(body, List.of(new Pending(entry, null)));

        String exit = node(CFGNodeKind.EXIT, "end", null, null);
        for (Pending p : preds) {
            edge(p, exit);
        }
        for (Pending p : toExit) {
            edge(p, exit);
        }
        return new CFG(funcId, nodes, edges, null);
    }

    // --- statement processing ---------------------------------------------

    /** Process every statement in a {@code block} (unwrapping its statement_list). */
    private List<Pending> processBlock(TSNode block, List<Pending> preds) {
        List<TSNode> stmts = new ArrayList<>();
        collectStatements(block, stmts);
        return processStmts(stmts, preds);
    }

    /** A block contains a statement_list; flatten one level to the real statements. */
    private void collectStatements(TSNode block, List<TSNode> out) {
        for (int i = 0; i < block.getNamedChildCount(); i++) {
            TSNode c = block.getNamedChild(i);
            if ("statement_list".equals(c.getType())) {
                for (int j = 0; j < c.getNamedChildCount(); j++) {
                    out.add(c.getNamedChild(j));
                }
            } else {
                out.add(c);
            }
        }
    }

    private List<Pending> processStmts(List<TSNode> stmts, List<Pending> preds) {
        for (TSNode s : stmts) {
            preds = processStmt(s, preds);
        }
        return preds;
    }

    private List<Pending> processStmt(TSNode s, List<Pending> preds) {
        if (counter > NODE_BUDGET) {
            truncated = true;
            return preds;
        }
        return switch (s.getType()) {
            case "if_statement" -> ifStmt(s, preds);
            case "for_statement" -> forStmt(s, preds);
            case "expression_switch_statement", "type_switch_statement", "select_statement" ->
                    switchStmt(s, preds);
            case "return_statement" -> returnStmt(s, preds);
            case "labeled_statement" -> {
                TSNode inner = s.getNamedChild(s.getNamedChildCount() - 1);
                yield inner != null && !inner.isNull() ? processStmt(inner, preds) : preds;
            }
            case "block" -> processBlock(s, preds);
            case "comment", "empty_statement" -> preds;
            default -> simpleStmt(s, preds);
        };
    }

    private List<Pending> ifStmt(TSNode s, List<Pending> preds) {
        TSNode cond = s.getChildByFieldName("condition");
        String head = "if " + oneLine(cond);
        String id = node(CFGNodeKind.BRANCH, head, line(s), fullIf(cond));
        connectAll(preds, id);

        List<Pending> out = new ArrayList<>();
        TSNode cons = s.getChildByFieldName("consequence");
        if (cons != null && !cons.isNull()) {
            out.addAll(processBlock(cons, List.of(new Pending(id, "yes"))));
        } else {
            out.add(new Pending(id, "yes"));
        }

        TSNode alt = s.getChildByFieldName("alternative");
        if (alt != null && !alt.isNull()) {
            List<Pending> elsePreds = "block".equals(alt.getType())
                    ? processBlock(alt, List.of(new Pending(id, "no")))
                    : processStmt(alt, List.of(new Pending(id, "no"))); // else-if chain
            out.addAll(elsePreds);
        } else {
            out.add(new Pending(id, "no"));
        }
        return out;
    }

    private List<Pending> forStmt(TSNode s, List<Pending> preds) {
        TSNode body = s.getChildByFieldName("body");
        String head = headBefore(s, body);
        String id = node(CFGNodeKind.LOOP, head.isBlank() ? "for" : head, line(s), null);
        connectAll(preds, id);

        if (body != null && !body.isNull()) {
            List<Pending> bodyPreds = processBlock(body, List.of(new Pending(id, "body")));
            connectAll(bodyPreds, id); // back-edge
        }
        return List.of(new Pending(id, null)); // loop exit → next
    }

    private List<Pending> switchStmt(TSNode s, List<Pending> preds) {
        TSNode value = s.getChildByFieldName("value");
        String head = "switch" + (value != null && !value.isNull() ? " " + oneLine(value) : "");
        String id = node(CFGNodeKind.BRANCH, head, line(s), null);
        connectAll(preds, id);

        List<Pending> out = new ArrayList<>();
        boolean hasDefault = false;
        for (int i = 0; i < s.getNamedChildCount(); i++) {
            TSNode c = s.getNamedChild(i);
            String t = c.getType();
            boolean isCase = t.equals("expression_case") || t.equals("type_case")
                    || t.equals("communication_case");
            boolean isDefault = t.equals("default_case");
            if (!isCase && !isDefault) {
                continue;
            }
            hasDefault |= isDefault;
            String label = isDefault ? "default" : caseLabel(c);
            out.addAll(processCaseBody(c, List.of(new Pending(id, label))));
        }
        if (!hasDefault) {
            out.add(new Pending(id, "default"));
        }
        return out;
    }

    /** A case's body = its children that are NOT the case value/type field. */
    private List<Pending> processCaseBody(TSNode caseNode, List<Pending> preds) {
        List<TSNode> body = new ArrayList<>();
        for (int i = 0; i < caseNode.getNamedChildCount(); i++) {
            if (caseNode.getFieldNameForNamedChild(i) == null) {
                body.add(caseNode.getNamedChild(i));
            }
        }
        return processStmts(body, preds);
    }

    private List<Pending> returnStmt(TSNode s, List<Pending> preds) {
        String id = node(CFGNodeKind.RETURN, oneLine(s), line(s), fullIfLong(s));
        connectAll(preds, id);
        toExit.add(new Pending(id, null));
        return List.of();
    }

    private List<Pending> simpleStmt(TSNode s, List<Pending> preds) {
        TSNode call = firstCall(s);
        CFGNodeKind kind = CFGNodeKind.STATEMENT;
        String label = oneLine(s);
        if (call != null) {
            String callee = calleeName(call);
            if (callee.equals("panic") || callee.endsWith(".Fatal") || callee.endsWith(".Fatalf")
                    || callee.endsWith(".Panic")) {
                kind = CFGNodeKind.THROW;
            } else {
                kind = CFGNodeKind.CALL;
            }
        }
        String id = node(kind, label, line(s), fullIfLong(s));
        connectAll(preds, id);
        if (kind == CFGNodeKind.THROW) {
            toExit.add(new Pending(id, null));
            return List.of();
        }
        return List.of(new Pending(id, null));
    }

    // --- AST helpers -------------------------------------------------------

    /** Depth-first search for the first {@code call_expression} in a subtree. */
    private TSNode firstCall(TSNode n) {
        if ("call_expression".equals(n.getType())) {
            return n;
        }
        for (int i = 0; i < n.getNamedChildCount(); i++) {
            TSNode found = firstCall(n.getNamedChild(i));
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private String calleeName(TSNode call) {
        TSNode fn = call.getChildByFieldName("function");
        return fn != null && !fn.isNull() ? oneLine(fn) : "";
    }

    private String caseLabel(TSNode caseNode) {
        TSNode v = caseNode.getChildByFieldName("value");
        if (v == null || v.isNull()) {
            v = caseNode.getChildByFieldName("type");
        }
        if (v == null || v.isNull()) {
            // first field-named child, else first named child
            v = caseNode.getNamedChildCount() > 0 ? caseNode.getNamedChild(0) : null;
        }
        String s = v != null && !v.isNull() ? oneLine(v) : "case";
        return truncate(s, 24);
    }

    /** Text between a statement's start and a child's start (e.g. the {@code for} header). */
    private String headBefore(TSNode parent, TSNode child) {
        if (child == null || child.isNull()) {
            return norm(text(parent));
        }
        int start = parent.getStartByte();
        int end = child.getStartByte();
        if (end <= start) {
            return "for";
        }
        return norm(new String(src, start, end - start, StandardCharsets.UTF_8));
    }

    // --- node/edge construction -------------------------------------------

    private String node(CFGNodeKind kind, String rawLabel, Integer line, String title) {
        String id = "c" + (counter++);
        String label = truncate(norm(rawLabel), LABEL_MAX);
        nodes.add(new CFGNode(id, kind, label, line, title, null, relPath));
        return id;
    }

    private void connectAll(List<Pending> preds, String target) {
        for (Pending p : preds) {
            edge(p, target);
        }
    }

    private void edge(Pending p, String target) {
        edges.add(new CFGEdge(p.from(), target, p.label()));
    }

    private String text(TSNode n) {
        return new String(src, n.getStartByte(), n.getEndByte() - n.getStartByte(), StandardCharsets.UTF_8);
    }

    private String oneLine(TSNode n) {
        if (n == null || n.isNull()) {
            return "";
        }
        String t = text(n);
        int nl = t.indexOf('\n');
        return norm(nl < 0 ? t : t.substring(0, nl));
    }

    /** Full text as a hover title when the statement spans multiple lines / is long. */
    private String fullIfLong(TSNode n) {
        String t = text(n);
        return (t.contains("\n") || t.length() > LABEL_MAX) ? norm(t) : null;
    }

    private String fullIf(TSNode n) {
        return n == null || n.isNull() ? null : fullIfLong(n);
    }

    private static Integer line(TSNode n) {
        return n.getStartPoint().getRow() + 1;
    }

    private static String norm(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}

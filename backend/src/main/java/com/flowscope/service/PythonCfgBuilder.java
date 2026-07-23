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
 * Per-function control-flow graph for a Python function/method from its
 * tree-sitter AST body block. Intra-procedural, emitting the same neutral
 * {@link CFGNodeKind} set + "yes"/"no"/case-label conventions as the Java/Go
 * builders so the Flow Chart renders identically.
 *
 * <p>Handled: sequential statements, {@code if/elif/else}, {@code for}/{@code while},
 * {@code try/except/else/finally}, {@code with}, {@code match}, {@code return},
 * {@code raise} (THROW), and call sites (CALL). Python blocks hold their
 * statements directly (no {@code statement_list} wrapper, unlike Go).
 */
final class PythonCfgBuilder {

    private static final int LABEL_MAX = 52;
    private static final int NODE_BUDGET = 1200;

    private final byte[] src;
    private final String relPath;
    private final List<CFGNode> nodes = new ArrayList<>();
    private final List<CFGEdge> edges = new ArrayList<>();
    private final List<Pending> toExit = new ArrayList<>();
    private int counter;

    private record Pending(String from, String label) {
    }

    private PythonCfgBuilder(byte[] src, String relPath) {
        this.src = src;
        this.relPath = relPath;
    }

    static Optional<CFG> build(String funcId, String entryLabel, int entryLine,
                               TSNode body, byte[] src, String relPath) {
        if (body == null || body.isNull()) {
            return Optional.empty();
        }
        PythonCfgBuilder b = new PythonCfgBuilder(src, relPath);
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

    /** A Python {@code block} holds statements directly as its named children. */
    private List<Pending> processBlock(TSNode block, List<Pending> preds) {
        if (block == null || block.isNull()) {
            return preds;
        }
        for (int i = 0; i < block.getNamedChildCount(); i++) {
            preds = processStmt(block.getNamedChild(i), preds);
        }
        return preds;
    }

    private List<Pending> processStmt(TSNode s, List<Pending> preds) {
        if (counter > NODE_BUDGET) {
            return preds;
        }
        return switch (s.getType()) {
            case "if_statement" -> ifStmt(s, preds);
            case "for_statement", "while_statement" -> loopStmt(s, preds);
            case "try_statement" -> tryStmt(s, preds);
            case "with_statement" -> withStmt(s, preds);
            case "match_statement" -> matchStmt(s, preds);
            case "return_statement" -> terminal(CFGNodeKind.RETURN, s, preds);
            case "raise_statement" -> terminal(CFGNodeKind.THROW, s, preds);
            case "comment" -> preds;
            case "block" -> processBlock(s, preds);
            default -> simpleStmt(s, preds);
        };
    }

    private List<Pending> ifStmt(TSNode s, List<Pending> preds) {
        TSNode cond = s.getChildByFieldName("condition");
        String id = node(CFGNodeKind.BRANCH, "if " + oneLine(cond), line(s), fullIf(cond));
        connectAll(preds, id);

        List<Pending> out = new ArrayList<>(processBlock(s.getChildByFieldName("consequence"),
                List.of(new Pending(id, "yes"))));
        Pending pendingNo = new Pending(id, "no");

        for (int i = 0; i < s.getNamedChildCount(); i++) {
            if (!"alternative".equals(s.getFieldNameForNamedChild(i))) {
                continue;
            }
            TSNode alt = s.getNamedChild(i);
            if ("elif_clause".equals(alt.getType())) {
                TSNode ec = alt.getChildByFieldName("condition");
                String eb = node(CFGNodeKind.BRANCH, "elif " + oneLine(ec), line(alt), fullIf(ec));
                edge(pendingNo, eb);
                out.addAll(processBlock(alt.getChildByFieldName("consequence"),
                        List.of(new Pending(eb, "yes"))));
                pendingNo = new Pending(eb, "no");
            } else if ("else_clause".equals(alt.getType())) {
                out.addAll(processBlock(alt.getChildByFieldName("body"), List.of(pendingNo)));
                pendingNo = null;
            }
        }
        if (pendingNo != null) {
            out.add(pendingNo);
        }
        return out;
    }

    private List<Pending> loopStmt(TSNode s, List<Pending> preds) {
        TSNode body = s.getChildByFieldName("body");
        String head = stripColon(headBefore(s, body));
        String id = node(CFGNodeKind.LOOP, head, line(s), null);
        connectAll(preds, id);
        List<Pending> bodyPreds = processBlock(body, List.of(new Pending(id, "body")));
        connectAll(bodyPreds, id); // back-edge
        return List.of(new Pending(id, null)); // loop exit (for/while-else ignored)
    }

    private List<Pending> tryStmt(TSNode s, List<Pending> preds) {
        List<Pending> exits = new ArrayList<>(processBlock(s.getChildByFieldName("body"), preds));
        // except handlers branch from the try entry (an exception may fire anytime).
        for (int i = 0; i < s.getNamedChildCount(); i++) {
            TSNode c = s.getNamedChild(i);
            String t = c.getType();
            if ("except_clause".equals(t) || "except_group_clause".equals(t)) {
                String label = "except " + oneLine(c.getChildByFieldName("value"));
                String h = node(CFGNodeKind.BRANCH, label, line(c), null);
                connectAll(preds, h);
                exits.addAll(processBlock(firstBlock(c), List.of(new Pending(h, "raised"))));
            } else if ("else_clause".equals(t)) {
                exits.addAll(processBlock(c.getChildByFieldName("body"), new ArrayList<>(exits)));
            }
        }
        // finally runs after everything.
        for (int i = 0; i < s.getNamedChildCount(); i++) {
            if ("finally_clause".equals(s.getNamedChild(i).getType())) {
                return processBlock(firstBlock(s.getNamedChild(i)), exits);
            }
        }
        return exits;
    }

    private List<Pending> withStmt(TSNode s, List<Pending> preds) {
        TSNode body = s.getChildByFieldName("body");
        String id = node(CFGNodeKind.STATEMENT, stripColon(headBefore(s, body)), line(s), null);
        connectAll(preds, id);
        return processBlock(body, List.of(new Pending(id, null)));
    }

    private List<Pending> matchStmt(TSNode s, List<Pending> preds) {
        TSNode body = s.getChildByFieldName("body");
        String id = node(CFGNodeKind.BRANCH, stripColon(headBefore(s, body)), line(s), null);
        connectAll(preds, id);
        List<Pending> out = new ArrayList<>();
        TSNode cases = body != null && !body.isNull() ? body : s;
        for (int i = 0; i < cases.getNamedChildCount(); i++) {
            TSNode c = cases.getNamedChild(i);
            if (!"case_clause".equals(c.getType())) {
                continue;
            }
            String label = truncate(oneLine(c).replaceFirst("^case\\s+", "").replaceAll(":.*$", ""), 24);
            out.addAll(processBlock(firstBlock(c), List.of(new Pending(id, label))));
        }
        out.add(new Pending(id, "default"));
        return out;
    }

    private List<Pending> terminal(CFGNodeKind kind, TSNode s, List<Pending> preds) {
        String id = node(kind, oneLine(s), line(s), fullIfLong(s));
        connectAll(preds, id);
        toExit.add(new Pending(id, null));
        return List.of();
    }

    private List<Pending> simpleStmt(TSNode s, List<Pending> preds) {
        TSNode call = firstCall(s);
        CFGNodeKind kind = call != null ? CFGNodeKind.CALL : CFGNodeKind.STATEMENT;
        String callee = call != null ? calleeName(call) : "";
        if (callee.equals("exit") || callee.endsWith(".exit") || callee.equals("sys.exit")) {
            kind = CFGNodeKind.THROW;
        }
        String id = node(kind, oneLine(s), line(s), fullIfLong(s));
        connectAll(preds, id);
        if (kind == CFGNodeKind.THROW) {
            toExit.add(new Pending(id, null));
            return List.of();
        }
        return List.of(new Pending(id, null));
    }

    // --- AST helpers -------------------------------------------------------

    private TSNode firstCall(TSNode n) {
        if ("call".equals(n.getType())) {
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

    /** First child block (used for except/finally/case bodies that aren't a named field). */
    private static TSNode firstBlock(TSNode n) {
        TSNode byField = n.getChildByFieldName("body");
        if (byField != null && !byField.isNull()) {
            return byField;
        }
        for (int i = 0; i < n.getNamedChildCount(); i++) {
            if ("block".equals(n.getNamedChild(i).getType())) {
                return n.getNamedChild(i);
            }
        }
        return null;
    }

    private String headBefore(TSNode parent, TSNode child) {
        if (child == null || child.isNull()) {
            return norm(text(parent));
        }
        int start = parent.getStartByte();
        int end = child.getStartByte();
        return end <= start ? norm(text(parent))
                : norm(new String(src, start, end - start, StandardCharsets.UTF_8));
    }

    // --- node/edge construction -------------------------------------------

    private String node(CFGNodeKind kind, String rawLabel, Integer line, String title) {
        String id = "c" + (counter++);
        nodes.add(new CFGNode(id, kind, truncate(norm(rawLabel), LABEL_MAX), line, title, null, relPath));
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

    private static String stripColon(String s) {
        return s.endsWith(":") ? s.substring(0, s.length() - 1).trim() : s;
    }

    private static String norm(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}

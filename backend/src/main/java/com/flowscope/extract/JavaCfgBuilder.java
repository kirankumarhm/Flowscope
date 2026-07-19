package com.flowscope.extract;

import com.flowscope.ir.CFG;
import com.flowscope.ir.CFGEdge;
import com.flowscope.ir.CFGNode;
import com.flowscope.ir.CFGNodeKind;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.ReturnStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds a control-flow graph for a single Java method/constructor body, using
 * the standard "each statement returns (entry, exits)" algorithm ported from the
 * Go prototype. Exits are the open control-flow ports the following statement
 * wires into.
 *
 * <p>Node kinds and edge labels follow the language-neutral conventions in
 * Requirement 4.12 so CFGs render identically across languages.
 */
public class JavaCfgBuilder {

    private static final int MAX_NODES = 400;
    private static final int LABEL_MAX = 52;
    private static final int CASE_LABEL_MAX = 24;

    private final String functionId;
    private final String file;
    private final List<CFGNode> nodes = new ArrayList<>();
    private final List<CFGEdge> edges = new ArrayList<>();
    private int counter;
    private boolean overflow;

    private String entryId;
    private String exitId;

    private JavaCfgBuilder(String functionId, String file) {
        this.functionId = functionId;
        this.file = file;
    }

    /**
     * Build the CFG for a function body.
     *
     * @return the CFG, or empty if construction exceeded the 400-node cap
     *         (Requirement 4.10) — the partial CFG is discarded.
     */
    public static Optional<CFG> build(String functionId, String functionName,
                                      int entryLine, int exitLine, BlockStmt body, String file) {
        JavaCfgBuilder b = new JavaCfgBuilder(functionId, file);
        b.entryId = b.add(CFGNodeKind.ENTRY, functionName + "()", entryLine);
        b.exitId = b.add(CFGNodeKind.EXIT, "end", exitLine);

        Frag whole = b.block(body.getStatements());
        if (whole.entry != null) {
            b.edge(b.entryId, whole.entry, null);
        } else {
            // Empty body: connect entry directly to exit (Requirement 4.11).
            b.edge(b.entryId, b.exitId, null);
        }
        for (String x : whole.exits) {
            b.edge(x, b.exitId, null);
        }

        if (b.overflow) {
            return Optional.empty();
        }
        return Optional.of(new CFG(functionId, b.nodes, b.edges, null));
    }

    /** Open control-flow ports produced by a statement or block. */
    private static final class Frag {
        final String entry;          // first node id, or null if empty
        final List<String> exits;    // open exit ports to wire into the next statement

        Frag(String entry, List<String> exits) {
            this.entry = entry;
            this.exits = exits;
        }
    }

    private String add(CFGNodeKind kind, String label, Integer line) {
        if (counter > MAX_NODES) {
            overflow = true;
            return null;
        }
        String id = "c" + counter;
        counter++;
        String norm = TextUtil.normalize(label);
        String display = TextUtil.truncate(norm, LABEL_MAX);
        String title = norm.length() > LABEL_MAX ? norm : null; // full text for hover
        nodes.add(new CFGNode(id, kind, display, line, title, null, file));
        return id;
    }

    private void edge(String from, String to, String label) {
        if (from == null || to == null) {
            return;
        }
        edges.add(new CFGEdge(from, to, label));
    }

    private static Integer lineOf(com.github.javaparser.ast.Node n) {
        return n.getBegin().map(p -> p.line).orElse(null);
    }

    private static String text(com.github.javaparser.ast.Node n) {
        return TextUtil.normalize(n.toString()); // add() truncates for the label, keeps full for title
    }

    /** Chain a list of statements sequentially. */
    private Frag block(List<Statement> statements) {
        String first = null;
        List<String> open = new ArrayList<>();
        for (Statement s : statements) {
            Frag f = stmt(s);
            if (f.entry == null) {
                // Overflow, or a construct that produced no node — but a terminal
                // (return/throw) legitimately has no open exits; only skip when it
                // also has no entry.
                if (f.exits.isEmpty()) {
                    continue;
                }
            }
            if (f.entry != null) {
                if (first == null) {
                    first = f.entry;
                }
                for (String o : open) {
                    edge(o, f.entry, null);
                }
            }
            open = f.exits;
        }
        return new Frag(first, open);
    }

    private Frag stmt(Statement s) {
        if (s instanceof BlockStmt b) {
            return block(b.getStatements());
        }
        if (s instanceof IfStmt ifs) {
            return ifStmt(ifs);
        }
        if (s instanceof ForStmt || s instanceof ForEachStmt
                || s instanceof WhileStmt || s instanceof DoStmt) {
            return loop(s);
        }
        if (s instanceof SwitchStmt sw) {
            return switchStmt(sw);
        }
        if (s instanceof ReturnStmt) {
            String r = add(CFGNodeKind.RETURN, text(s), lineOf(s));
            edge(r, exitId, null);
            return new Frag(r, List.of()); // no fall-through (Requirement 4.6)
        }
        if (s instanceof ThrowStmt) {
            String t = add(CFGNodeKind.THROW, text(s), lineOf(s));
            edge(t, exitId, null);
            return new Frag(t, List.of()); // no fall-through (Requirement 4.7)
        }
        if (s instanceof TryStmt trys) {
            return tryStmt(trys);
        }
        // Default: a plain statement or call site.
        CFGNodeKind kind = s.findFirst(MethodCallExpr.class).isPresent()
                ? CFGNodeKind.CALL : CFGNodeKind.STATEMENT;
        String id = add(kind, text(s), lineOf(s));
        return new Frag(id, list(id));
    }

    private Frag ifStmt(IfStmt ifs) {
        String cond = ifs.getCondition().toString();
        String d = add(CFGNodeKind.BRANCH, "if " + cond, lineOf(ifs));
        List<String> out = new ArrayList<>();

        Frag then = stmt(ifs.getThenStmt());
        edge(d, then.entry, "yes");
        out.addAll(then.exits);

        Optional<Statement> elseStmt = ifs.getElseStmt();
        if (elseStmt.isPresent()) {
            Frag alt = stmt(elseStmt.get());
            edge(d, alt.entry, "no");
            out.addAll(alt.exits);
        } else {
            out.add(d); // no else: the "no" branch falls through from d (Requirement 4.8)
        }
        return new Frag(d, out);
    }

    private Frag loop(Statement s) {
        String head;
        Statement body;
        if (s instanceof WhileStmt w) {
            head = "while " + w.getCondition();
            body = w.getBody();
        } else if (s instanceof DoStmt d) {
            head = "do while " + d.getCondition();
            body = d.getBody();
        } else if (s instanceof ForStmt f) {
            head = "for " + f.getCompare().map(Expression::toString).orElse("");
            body = f.getBody();
        } else {
            ForEachStmt fe = (ForEachStmt) s;
            head = "for " + fe.getVariable() + " : " + fe.getIterable();
            body = fe.getBody();
        }
        String l = add(CFGNodeKind.LOOP, head, lineOf(s));
        Frag bf = stmt(body);
        edge(l, bf.entry, "body");
        for (String e : bf.exits) {
            edge(e, l, null); // back-edge (Requirement 4.5)
        }
        return new Frag(l, list(l)); // exit when the loop condition is false
    }

    private Frag switchStmt(SwitchStmt sw) {
        String d = add(CFGNodeKind.BRANCH, "switch " + sw.getSelector(), lineOf(sw));
        List<String> out = new ArrayList<>();
        boolean hasDefault = false;

        for (SwitchEntry entry : sw.getEntries()) {
            boolean isDefault = entry.getLabels().isEmpty();
            if (isDefault) {
                hasDefault = true;
            }
            String label = isDefault ? "default" : caseLabel(entry);
            Frag body = caseBody(entry);
            if (body.entry != null) {
                edge(d, body.entry, label);
                out.addAll(body.exits);
            } else {
                out.add(d);
            }
        }
        if (!hasDefault) {
            // No default: unmatched values fall through past the switch (Requirement 4.4).
            out.add(d);
        }
        return new Frag(d, out);
    }

    private String caseLabel(SwitchEntry entry) {
        StringBuilder sb = new StringBuilder();
        for (Expression label : entry.getLabels()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(label.toString());
        }
        return TextUtil.oneLine(sb.toString(), CASE_LABEL_MAX);
    }

    private Frag caseBody(SwitchEntry entry) {
        return block(new ArrayList<>(entry.getStatements()));
    }

    private Frag tryStmt(TryStmt trys) {
        String d = add(CFGNodeKind.BRANCH, "try", lineOf(trys));
        List<String> out = new ArrayList<>();

        Frag tryBody = block(trys.getTryBlock().getStatements());
        if (tryBody.entry != null) {
            edge(d, tryBody.entry, null);
            out.addAll(tryBody.exits);
        } else {
            out.add(d);
        }

        for (CatchClause c : trys.getCatchClauses()) {
            Frag cb = block(c.getBody().getStatements());
            if (cb.entry != null) {
                edge(d, cb.entry, "catch " + simpleType(c.getParameter().getType().toString()));
                out.addAll(cb.exits);
            }
        }

        // finally runs after every path; funnel open exits through it.
        if (trys.getFinallyBlock().isPresent()) {
            Frag fin = block(trys.getFinallyBlock().get().getStatements());
            if (fin.entry != null) {
                for (String o : out) {
                    edge(o, fin.entry, null);
                }
                out = new ArrayList<>(fin.exits);
            }
        }
        return new Frag(d, out);
    }

    private static String simpleType(String type) {
        int cut = indexOfAny(type, "<[ ");
        if (cut >= 0) {
            type = type.substring(0, cut);
        }
        int dot = type.lastIndexOf('.');
        if (dot >= 0) {
            type = type.substring(dot + 1);
        }
        return type;
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> list(String id) {
        List<String> l = new ArrayList<>(1);
        if (id != null) {
            l.add(id);
        }
        return l;
    }
}

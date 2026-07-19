package com.flowscope.service;

import com.flowscope.entity.JavaProgramModel.MethodInfo;
import com.flowscope.entity.CFG;
import com.flowscope.entity.CFGEdge;
import com.flowscope.entity.CFGGroup;
import com.flowscope.entity.CFGNode;
import com.flowscope.entity.CFGNodeKind;
import com.github.javaparser.ast.Node;
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
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.flowscope.entity.JavaProgramModel;
import com.flowscope.util.TextUtil;

/**
 * Builds an <b>inter-procedural</b> flow chart rooted at one method (typically a
 * REST endpoint). It follows the same control-flow rules as {@link JavaCfgBuilder}
 * but, when a statement calls a method it can resolve to internal source, it
 * <b>inlines that method's flow</b> in place — so the diagram traces the request
 * from the endpoint down through service/repository calls to what it returns.
 *
 * <p>Guards: inlining stops at {@code maxDepth}, never re-enters a method already
 * on the call stack (recursion-safe), and stops entirely past a node budget so a
 * large codebase cannot explode the diagram. Unresolved/external calls remain
 * leaf nodes.
 */
public class JavaFlowBuilder {

    private static final int LABEL_MAX = 52;
    private static final int CASE_LABEL_MAX = 24;
    private static final int MAX_NODES = 1200;

    private final JavaProgramModel model;
    private final int maxDepth;
    private final List<CFGNode> nodes = new ArrayList<>();
    private final List<CFGEdge> edges = new ArrayList<>();
    private final List<CFGGroup> groups = new ArrayList<>();
    private int counter;
    private int groupCounter;
    private boolean overflow;
    private String exitId;
    private String activeGroup; // group nodes created right now belong to (null = top level)
    private String activeFile;  // source file of the method currently being built

    private JavaFlowBuilder(JavaProgramModel model, int maxDepth) {
        this.model = model;
        this.maxDepth = maxDepth;
    }

    /** Build the endpoint-rooted inter-procedural flow for {@code entry}. */
    public static CFG build(JavaProgramModel model, MethodInfo entry, int maxDepth) {
        JavaFlowBuilder b = new JavaFlowBuilder(model, maxDepth);
        String entryLabel = entry.endpointLabel != null ? entry.endpointLabel : entry.name + "()";
        b.exitId = null;
        b.activeFile = entry.owner.rel;
        String entryId = b.add(CFGNodeKind.ENTRY, entryLabel, lineOf(entry.decl));
        b.exitId = b.add(CFGNodeKind.EXIT, "end", null);

        Optional<BlockStmt> body = entry.body();
        Ctx root = new Ctx(entry, entry.owner, setOf(entry.id), 0, b.exitId, null);
        if (body.isPresent()) {
            Frag f = b.block(body.get().getStatements(), root);
            b.edge(entryId, f.entry != null ? f.entry : b.exitId, null);
            for (String x : f.exits) {
                b.edge(x, b.exitId, null);
            }
        } else {
            b.edge(entryId, b.exitId, null);
        }
        return new CFG(entry.id, b.nodes, b.edges, b.groups.isEmpty() ? null : b.groups);
    }

    /** Per-method context threaded through the build. {@code thisType} is the
     *  concrete type the method runs on (for virtual dispatch of {@code this} calls);
     *  {@code group} is the collapsible group its nodes belong to (null = top level). */
    private record Ctx(MethodInfo method, JavaProgramModel.ClassInfo thisType,
                       Set<String> stack, int depth, String returnTarget, String group) {
    }

    private static final class Frag {
        final String entry;
        final List<String> exits;

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
        String id = "c" + counter++;
        String norm = TextUtil.normalize(label);
        String display = TextUtil.truncate(norm, LABEL_MAX);
        String title = norm.length() > LABEL_MAX ? norm : null; // full text for hover
        nodes.add(new CFGNode(id, kind, display, line, title, activeGroup, activeFile));
        return id;
    }

    private void edge(String from, String to, String label) {
        if (from == null || to == null) {
            return;
        }
        edges.add(new CFGEdge(from, to, label));
    }

    private Frag block(List<Statement> statements, Ctx ctx) {
        String first = null;
        List<String> open = new ArrayList<>();
        for (Statement s : statements) {
            Frag f = stmt(s, ctx);
            if (f.entry == null && f.exits.isEmpty()) {
                continue;
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

    private Frag stmt(Statement s, Ctx ctx) {
        activeGroup = ctx.group(); // nodes created for this statement belong to ctx's group
        activeFile = ctx.method().owner.rel; // ...and to the current method's source file
        if (s instanceof BlockStmt b) {
            return block(b.getStatements(), ctx);
        }
        if (s instanceof IfStmt ifs) {
            return ifStmt(ifs, ctx);
        }
        if (s instanceof ForStmt || s instanceof ForEachStmt
                || s instanceof WhileStmt || s instanceof DoStmt) {
            return loop(s, ctx);
        }
        if (s instanceof SwitchStmt sw) {
            return switchStmt(sw, ctx);
        }
        if (s instanceof ReturnStmt rs) {
            return returnStmt(rs, ctx);
        }
        if (s instanceof ThrowStmt) {
            String t = add(CFGNodeKind.THROW, text(s), lineOf(s));
            edge(t, ctx.returnTarget, null);
            return new Frag(t, List.of());
        }
        if (s instanceof TryStmt trys) {
            return tryStmt(trys, ctx);
        }
        return callOrStatement(s, ctx);
    }

    /** A plain statement — inlining every resolvable internal call it makes, in order. */
    private Frag callOrStatement(Statement s, Ctx ctx) {
        List<MethodCallExpr> calls = selectedCalls(s, ctx);
        if (calls.isEmpty()) {
            CFGNodeKind kind = s.findFirst(MethodCallExpr.class).isPresent()
                    ? CFGNodeKind.CALL : CFGNodeKind.STATEMENT;
            String id = add(kind, text(s), lineOf(s));
            return new Frag(id, listOf(id));
        }
        // Chain a call node per resolvable call; a single call keeps the whole
        // statement text, multiple calls are each labeled with their own expression.
        boolean single = calls.size() == 1;
        String first = null;
        List<String> open = new ArrayList<>();
        for (MethodCallExpr call : calls) {
            String c = add(CFGNodeKind.CALL, single ? text(s) : text(call), lineOf(call));
            if (first == null) {
                first = c;
            }
            for (String o : open) {
                edge(o, c, null);
            }
            open = inlineCallees(resolvableCallees(call, ctx), ctx, c);
        }
        return new Frag(first, open);
    }

    private Frag returnStmt(ReturnStmt rs, Ctx ctx) {
        Expression value = rs.getExpression().orElse(null);
        List<MethodCallExpr> calls = value != null ? selectedCalls(value, ctx) : List.of();
        String r = add(CFGNodeKind.RETURN, text(rs), lineOf(rs));
        edge(r, ctx.returnTarget, null);
        if (calls.isEmpty()) {
            return new Frag(r, List.of());
        }
        // Evaluate the calls (in order), then perform the return.
        String first = null;
        List<String> open = new ArrayList<>();
        for (MethodCallExpr call : calls) {
            String c = add(CFGNodeKind.CALL, text(call), lineOf(rs));
            if (first == null) {
                first = c;
            }
            for (String o : open) {
                edge(o, c, null);
            }
            open = inlineCallees(resolvableCallees(call, ctx), ctx, c);
        }
        for (String o : open) {
            edge(o, r, null); // after the inlined calls return, perform the return
        }
        return new Frag(first, List.of());
    }

    /**
     * Wire a call node to the inlined body of each resolved callee. A single callee
     * flows straight through; multiple implementations of an interface fan out (one
     * labeled edge per impl) and reconverge at a MERGE node.
     *
     * @return the open exit ports after the call(s) return
     */
    private List<String> inlineCallees(List<JavaProgramModel.Callee> callees, Ctx ctx, String callNode) {
        if (callees.size() == 1) {
            JavaProgramModel.Callee callee = callees.get(0);
            Frag f = inlineCallee(callee, ctx);
            edge(callNode, f.entry, "→ " + callee.method().name);
            return f.exits;
        }
        String merge = add(CFGNodeKind.MERGE, "join", null);
        for (JavaProgramModel.Callee callee : callees) {
            Frag f = inlineCallee(callee, ctx);
            edge(callNode, f.entry, "impl: " + callee.thisType().name);
            for (String e : f.exits) {
                edge(e, merge, null);
            }
        }
        return listOf(merge);
    }

    /**
     * Inline a callee's body. Its {@code return}/{@code throw} and normal exits all
     * flow into a single MERGE "return-join" node, which becomes this fragment's
     * exit so control resumes in the caller. The sub-context's {@code thisType} is
     * the concrete receiver, so {@code this} calls dispatch to the right override.
     */
    private Frag inlineCallee(JavaProgramModel.Callee callee, Ctx ctx) {
        MethodInfo method = callee.method();
        // The return-join marker belongs to the CALLER's group.
        activeGroup = ctx.group();
        String join = add(CFGNodeKind.MERGE, "↵ " + method.name, null);
        Optional<BlockStmt> body = method.body();
        if (body.isEmpty() || join == null) {
            return new Frag(join, listOf(join));
        }
        // The callee's body forms a new collapsible group nested under the caller's.
        String gid = "grp" + groupCounter++;
        groups.add(new CFGGroup(gid, callee.thisType().name + "." + method.name, ctx.group()));
        Ctx sub = new Ctx(method, callee.thisType(),
                plus(ctx.stack, method.id), ctx.depth + 1, join, gid);
        Frag f = block(body.get().getStatements(), sub);
        for (String e : f.exits) {
            edge(e, join, null);
        }
        return new Frag(f.entry != null ? f.entry : join, listOf(join));
    }

    /** Callees of {@code call} that are inlinable here (have a body, not already on the stack). */
    private List<JavaProgramModel.Callee> resolvableCallees(MethodCallExpr call, Ctx ctx) {
        List<JavaProgramModel.Callee> out = new ArrayList<>();
        for (JavaProgramModel.Callee callee : model.resolveCallees(call, ctx.method, ctx.thisType)) {
            if (callee.method().body().isPresent() && !ctx.stack.contains(callee.method().id)) {
                out.add(callee);
            }
        }
        return out;
    }

    /**
     * All method calls within {@code scope} that resolve to inlinable internal
     * method(s), in source order — skipping calls nested inside an already-selected
     * call (those are its arguments, covered by recursion). Mirrors the sequence
     * builder so the two views stay consistent.
     */
    private List<MethodCallExpr> selectedCalls(Node scope, Ctx ctx) {
        if (overflow || ctx.depth >= maxDepth) {
            return List.of();
        }
        List<MethodCallExpr> out = new ArrayList<>();
        Set<MethodCallExpr> consumed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (MethodCallExpr call : scope.findAll(MethodCallExpr.class)) {
            if (consumed.contains(call)) {
                continue;
            }
            if (!resolvableCallees(call, ctx).isEmpty()) {
                out.add(call);
                call.findAll(MethodCallExpr.class).forEach(consumed::add);
            }
        }
        return out;
    }

    private Frag ifStmt(IfStmt ifs, Ctx ctx) {
        String d = add(CFGNodeKind.BRANCH, "if " + ifs.getCondition(), lineOf(ifs));
        List<String> out = new ArrayList<>();
        Frag then = stmt(ifs.getThenStmt(), ctx);
        edge(d, then.entry, "yes");
        out.addAll(then.exits);
        Optional<Statement> elseStmt = ifs.getElseStmt();
        if (elseStmt.isPresent()) {
            Frag alt = stmt(elseStmt.get(), ctx);
            edge(d, alt.entry, "no");
            out.addAll(alt.exits);
        } else {
            out.add(d);
        }
        return new Frag(d, out);
    }

    private Frag loop(Statement s, Ctx ctx) {
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
        Frag bf = stmt(body, ctx);
        edge(l, bf.entry, "body");
        for (String e : bf.exits) {
            edge(e, l, null);
        }
        return new Frag(l, listOf(l));
    }

    private Frag switchStmt(SwitchStmt sw, Ctx ctx) {
        String d = add(CFGNodeKind.BRANCH, "switch " + sw.getSelector(), lineOf(sw));
        List<String> out = new ArrayList<>();
        boolean hasDefault = false;
        for (SwitchEntry entry : sw.getEntries()) {
            boolean isDefault = entry.getLabels().isEmpty();
            if (isDefault) {
                hasDefault = true;
            }
            String label = isDefault ? "default" : caseLabel(entry);
            Frag body = block(new ArrayList<>(entry.getStatements()), ctx);
            if (body.entry != null) {
                edge(d, body.entry, label);
                out.addAll(body.exits);
            } else {
                out.add(d);
            }
        }
        if (!hasDefault) {
            out.add(d);
        }
        return new Frag(d, out);
    }

    private Frag tryStmt(TryStmt trys, Ctx ctx) {
        String d = add(CFGNodeKind.BRANCH, "try", lineOf(trys));
        List<String> out = new ArrayList<>();
        Frag tryBody = block(trys.getTryBlock().getStatements(), ctx);
        if (tryBody.entry != null) {
            edge(d, tryBody.entry, null);
            out.addAll(tryBody.exits);
        } else {
            out.add(d);
        }
        for (CatchClause c : trys.getCatchClauses()) {
            Frag cb = block(c.getBody().getStatements(), ctx);
            if (cb.entry != null) {
                edge(d, cb.entry, "catch " + JavaProgramModel.simpleType(c.getParameter().getType().toString()));
                out.addAll(cb.exits);
            }
        }
        if (trys.getFinallyBlock().isPresent()) {
            Frag fin = block(trys.getFinallyBlock().get().getStatements(), ctx);
            if (fin.entry != null) {
                for (String o : out) {
                    edge(o, fin.entry, null);
                }
                out = new ArrayList<>(fin.exits);
            }
        }
        return new Frag(d, out);
    }

    private String caseLabel(SwitchEntry entry) {
        StringBuilder sb = new StringBuilder();
        for (Expression label : entry.getLabels()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(label);
        }
        return TextUtil.oneLine(sb.toString(), CASE_LABEL_MAX);
    }

    private static Integer lineOf(Node n) {
        return n.getBegin().map(p -> p.line).orElse(null);
    }

    private static String text(Node n) {
        return TextUtil.normalize(n.toString()); // add() truncates for the label, keeps full for title
    }

    private static Set<String> setOf(String s) {
        Set<String> set = new HashSet<>();
        set.add(s);
        return set;
    }

    private static Set<String> plus(Set<String> base, String s) {
        Set<String> set = new HashSet<>(base);
        set.add(s);
        return set;
    }

    private static List<String> listOf(String id) {
        List<String> l = new ArrayList<>(1);
        if (id != null) {
            l.add(id);
        }
        return l;
    }
}

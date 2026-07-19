package com.flowscope.service;

import com.flowscope.entity.JavaProgramModel.Callee;
import com.flowscope.entity.JavaProgramModel.ClassInfo;
import com.flowscope.entity.JavaProgramModel.MethodInfo;
import com.flowscope.dto.SequenceDiagram;
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
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import com.flowscope.entity.JavaProgramModel;
import com.flowscope.util.TextUtil;

/**
 * Generates a Mermaid {@code sequenceDiagram} for a request rooted at one entry
 * function. It follows the same resolved call graph as the flow builder (through
 * interfaces, inheritance, and virtual dispatch), emitting a message per call and
 * recursing into the callee. Conditionals become {@code alt} fragments and loops
 * become {@code loop} fragments; unresolved calls that look like DB or external
 * API access appear as messages to a {@code Database} / {@code ExternalAPI}
 * participant. Recursion-safe and depth-bounded.
 */
public class JavaSequenceBuilder {

    private static final int MSG_MAX = 60;
    private static final Pattern DB = Pattern.compile(
            "(repository|\\brepo\\b|\\.save|\\.find|\\.delete|\\.query|\\.insert|\\.update"
                    + "|entitymanager|jdbc|\\.persist|\\bdao\\b)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXTERNAL = Pattern.compile(
            "(resttemplate|webclient|\\bfeign\\b|\\.exchange\\b|getforobject|postforobject"
                    + "|httpclient|okhttp|\\brestclient\\b|\\bapiclient\\b)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> ASYNC_TYPES = Set.of(
            "CompletableFuture", "Future", "Mono", "Flux", "ListenableFuture");

    private final JavaProgramModel model;
    private final int maxDepth;
    private final LinkedHashSet<String> participants = new LinkedHashSet<>();

    private JavaSequenceBuilder(JavaProgramModel model, int maxDepth) {
        this.model = model;
        this.maxDepth = maxDepth;
    }

    public static SequenceDiagram build(JavaProgramModel model, MethodInfo entry, int maxDepth) {
        JavaSequenceBuilder b = new JavaSequenceBuilder(model, maxDepth);
        String root = participant(entry.owner);
        b.participants.add(root);

        List<String> lines = new ArrayList<>();
        entry.body().ifPresent(body ->
                lines.addAll(b.walk(body.getStatements(), new Ctx(entry, entry.owner, setOf(entry.id), 0))));

        StringBuilder sb = new StringBuilder("sequenceDiagram\n");
        sb.append("    autonumber\n");
        for (String p : b.participants) {
            sb.append("    participant ").append(p).append("\n");
        }
        if (lines.isEmpty()) {
            sb.append("    note over ").append(root).append(": no resolved outgoing calls\n");
        }
        for (String line : lines) {
            sb.append("    ").append(line).append("\n");
        }
        return new SequenceDiagram(entry.id, sb.toString(), new ArrayList<>(b.participants));
    }

    private record Ctx(MethodInfo method, ClassInfo thisType, Set<String> stack, int depth) {
    }

    private List<String> walk(List<Statement> statements, Ctx ctx) {
        List<String> out = new ArrayList<>();
        for (Statement s : statements) {
            out.addAll(stmt(s, ctx));
        }
        return out;
    }

    private List<String> stmt(Statement s, Ctx ctx) {
        if (s instanceof BlockStmt b) {
            return walk(b.getStatements(), ctx);
        }
        if (s instanceof IfStmt ifs) {
            return ifStmt(ifs, ctx);
        }
        if (s instanceof WhileStmt || s instanceof DoStmt
                || s instanceof ForStmt || s instanceof ForEachStmt) {
            return loop(s, ctx);
        }
        if (s instanceof SwitchStmt sw) {
            List<String> out = new ArrayList<>();
            sw.getEntries().forEach(e -> out.addAll(walk(new ArrayList<>(e.getStatements()), ctx)));
            return out;
        }
        if (s instanceof TryStmt t) {
            List<String> out = new ArrayList<>(walk(t.getTryBlock().getStatements(), ctx));
            for (CatchClause c : t.getCatchClauses()) {
                out.addAll(walk(c.getBody().getStatements(), ctx));
            }
            t.getFinallyBlock().ifPresent(f -> out.addAll(walk(f.getStatements(), ctx)));
            return out;
        }
        if (s instanceof ReturnStmt r) {
            return r.getExpression().map(e -> emitCalls(e, ctx)).orElseGet(ArrayList::new);
        }
        return emitCalls(s, ctx);
    }

    private List<String> ifStmt(IfStmt ifs, Ctx ctx) {
        List<String> thenLines = stmt(ifs.getThenStmt(), ctx);
        List<String> elseLines = ifs.getElseStmt().map(e -> stmt(e, ctx)).orElseGet(ArrayList::new);
        if (thenLines.isEmpty() && elseLines.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add("alt " + guard(ifs.getCondition().toString()));
        out.addAll(thenLines.isEmpty() ? List.of("note right of " + participant(ctx.thisType) + ": …") : thenLines);
        if (!elseLines.isEmpty()) {
            out.add("else");
            out.addAll(elseLines);
        }
        out.add("end");
        return out;
    }

    private List<String> loop(Statement s, Ctx ctx) {
        String cond;
        Statement body;
        if (s instanceof WhileStmt w) {
            cond = w.getCondition().toString();
            body = w.getBody();
        } else if (s instanceof DoStmt d) {
            cond = d.getCondition().toString();
            body = d.getBody();
        } else if (s instanceof ForStmt f) {
            cond = f.getCompare().map(Expression::toString).orElse("");
            body = f.getBody();
        } else {
            ForEachStmt fe = (ForEachStmt) s;
            cond = fe.getVariable() + " : " + fe.getIterable();
            body = fe.getBody();
        }
        List<String> bodyLines = stmt(body, ctx);
        if (bodyLines.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        out.add("loop " + guard(cond));
        out.addAll(bodyLines);
        out.add("end");
        return out;
    }

    /** Emit messages for the calls in an expression/statement (skipping nested arg calls). */
    private List<String> emitCalls(Node scope, Ctx ctx) {
        List<String> out = new ArrayList<>();
        Set<MethodCallExpr> consumed = Collections.newSetFromMap(new IdentityHashMap<>());
        for (MethodCallExpr call : scope.findAll(MethodCallExpr.class)) {
            if (consumed.contains(call)) {
                continue;
            }
            if (emitCall(call, ctx, out)) {
                call.findAll(MethodCallExpr.class).forEach(consumed::add); // its args are covered
            }
        }
        return out;
    }

    private boolean emitCall(MethodCallExpr call, Ctx ctx, List<String> out) {
        String caller = participant(ctx.thisType);
        List<Callee> callees = model.resolveCallees(call, ctx.method, ctx.thisType);

        if (callees.size() == 1) {
            Callee c = callees.get(0);
            MethodInfo m = c.method();
            if (m.body().isPresent() && !ctx.stack.contains(m.id) && ctx.depth < maxDepth) {
                String callee = participant(c.thisType());
                participants.add(caller);
                participants.add(callee);
                boolean async = isAsync(m);
                out.add(caller + (async ? " -) " : " ->> ") + callee + ": " + message(call));
                out.addAll(walk(m.body().get().getStatements(),
                        new Ctx(m, c.thisType(), plus(ctx.stack, m.id), ctx.depth + 1)));
                out.add(callee + (async ? " --) " : " -->> ") + caller + ": " + returnType(m));
                return true;
            }
        }

        // Unresolved: surface only DB / external-looking calls (skip trivial getters etc.).
        // Classify on this call's own receiver + name — NOT the whole chain text, so an
        // outer .map() on a .find() result isn't mislabeled as a DB call.
        String recv = call.getScope()
                .filter(sc -> sc instanceof com.github.javaparser.ast.expr.NameExpr
                        || sc instanceof com.github.javaparser.ast.expr.FieldAccessExpr)
                .map(Object::toString)
                .orElse("");
        String probe = recv + "." + call.getNameAsString();
        String target = DB.matcher(probe).find() ? "Database"
                : (EXTERNAL.matcher(probe).find() ? "ExternalAPI" : null);
        if (target != null) {
            participants.add(caller);
            participants.add(target);
            out.add(caller + " ->> " + target + ": " + message(call));
            out.add(target + " -->> " + caller + ": result");
            return true;
        }
        return false;
    }

    private static boolean isAsync(MethodInfo m) {
        if (m.returnType != null && ASYNC_TYPES.contains(m.returnType)) {
            return true;
        }
        return m.decl.getAnnotations().stream()
                .anyMatch(a -> a.getNameAsString().equals("Async"));
    }

    private static String returnType(MethodInfo m) {
        return m.returnType == null || m.returnType.isBlank() ? "void" : m.returnType;
    }

    /** Mermaid participant name — class name is a safe identifier already. */
    private static String participant(ClassInfo cls) {
        return cls != null ? cls.name : "Unknown";
    }

    private static String message(MethodCallExpr call) {
        StringBuilder sb = new StringBuilder(call.getNameAsString()).append("(");
        List<Expression> args = call.getArguments();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(args.get(i).toString());
        }
        sb.append(")");
        return sanitize(sb.toString());
    }

    private static String guard(String cond) {
        return sanitize(cond);
    }

    /** One line, no Mermaid-breaking characters, truncated. */
    private static String sanitize(String s) {
        String t = TextUtil.oneLine(s, MSG_MAX)
                .replace(";", "")
                .replace(":", " -")
                .replace("#", "")
                .replace("\"", "'");
        return t.isBlank() ? "call" : t;
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
}

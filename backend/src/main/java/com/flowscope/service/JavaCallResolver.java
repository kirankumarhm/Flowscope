package com.flowscope.service;

import com.flowscope.entity.SourceFile;
import com.flowscope.entity.EdgeKind;
import com.flowscope.entity.IREdge;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Type-aware Java call resolution (Requirement 9.1). Resolves {@code recv.method()}
 * by the receiver's declared type — resolved through local variables, method
 * parameters, and fields — to the target class and method, producing high-confidence
 * (0.97) {@code calls} edges. Ambiguous or unresolvable receivers (method chains,
 * deep field access, unknown types) are conservatively omitted (Requirements 9.5, 9.9).
 *
 * <p>Ported from the Go prototype's {@code resolveJavaCalls}. Runs as a post-pass
 * once every file has been parsed, since it needs the complete symbol table.
 */
public class JavaCallResolver {

    private static final double CONFIDENCE = 0.97;

    private static final class MethodModel {
        final String id;
        final String name;
        final Map<String, String> params = new HashMap<>();
        final CallableDeclaration<?> decl;

        MethodModel(String id, String name, CallableDeclaration<?> decl) {
            this.id = id;
            this.name = name;
            this.decl = decl;
        }
    }

    private static final class ClassModel {
        final String name;
        final Map<String, String> fields = new HashMap<>();
        final List<MethodModel> methods = new ArrayList<>();

        ClassModel(String name) {
            this.name = name;
        }
    }

    /** Resolve calls across all Java files, returning deduplicated CALLS edges. */
    public List<IREdge> resolve(List<SourceFile> javaFiles) {
        Map<String, ClassModel> classByName = new LinkedHashMap<>();
        List<ClassModel> classes = new ArrayList<>();

        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

        for (SourceFile file : javaFiles) {
            if (!"java".equals(file.language())) {
                continue;
            }
            CompilationUnit cu = parse(parser, file);
            if (cu == null) {
                continue;
            }
            for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
                ClassModel cm = buildClassModel(type, file.relativePath());
                classes.add(cm);
                classByName.put(cm.name, cm); // last-wins on name collision
            }
        }

        List<IREdge> edges = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ClassModel cls : classes) {
            for (MethodModel m : cls.methods) {
                resolveMethod(m, cls, classByName, edges, seen);
            }
        }
        return edges;
    }

    private CompilationUnit parse(JavaParser parser, SourceFile file) {
        try {
            byte[] src = Files.readAllBytes(file.absolutePath());
            return parser.parse(new String(src, StandardCharsets.UTF_8)).getResult().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private ClassModel buildClassModel(TypeDeclaration<?> type, String rel) {
        ClassModel cm = new ClassModel(type.getNameAsString());

        for (FieldDeclaration field : type.findAll(FieldDeclaration.class,
                f -> f.getParentNode().orElse(null) == type)) {
            for (VariableDeclarator v : field.getVariables()) {
                cm.fields.put(v.getNameAsString(), simpleType(v.getType().toString()));
            }
        }

        List<CallableDeclaration<?>> callables = new ArrayList<>();
        callables.addAll(type.getMethods());
        callables.addAll(type.findAll(ConstructorDeclaration.class,
                c -> c.getParentNode().orElse(null) == type));

        for (CallableDeclaration<?> callable : callables) {
            String name = callable.getNameAsString();
            int startLine = callable.getBegin().map(p -> p.line).orElse(0);
            MethodModel mm = new MethodModel(
                    "func:" + rel + "#" + name + "@" + startLine, name, callable);
            for (Parameter p : callable.getParameters()) {
                mm.params.put(p.getNameAsString(), simpleType(p.getType().toString()));
            }
            cm.methods.add(mm);
        }
        return cm;
    }

    private void resolveMethod(MethodModel m, ClassModel cls,
                               Map<String, ClassModel> classByName,
                               List<IREdge> edges, Set<String> seen) {
        Optional<BlockStmt> body = bodyOf(m.decl);
        if (body.isEmpty()) {
            return;
        }

        // Method-wide local variable types (matches the Go prototype's scope model).
        Map<String, String> locals = new HashMap<>();
        for (VariableDeclarationExpr decl : body.get().findAll(VariableDeclarationExpr.class)) {
            for (VariableDeclarator v : decl.getVariables()) {
                String type = simpleType(v.getType().toString());
                if ((type.isEmpty() || type.equals("var")) && v.getInitializer().isPresent()) {
                    Expression init = v.getInitializer().get();
                    if (init instanceof ObjectCreationExpr oce) {
                        type = simpleType(oce.getType().toString());
                    }
                }
                locals.put(v.getNameAsString(), type);
            }
        }

        int order = 0;
        for (MethodCallExpr call : body.get().findAll(MethodCallExpr.class)) {
            order++;
            ClassModel target = resolveReceiver(call, cls, locals, m.params, classByName);
            if (target == null) {
                continue;
            }
            String callee = findMethod(target, call.getNameAsString());
            if (callee == null || callee.equals(m.id)) {
                continue; // unresolved or self-recursive (Requirement 9.8)
            }
            String key = m.id + "|" + callee;
            if (seen.add(key)) {
                edges.add(new IREdge(m.id, callee, EdgeKind.CALLS, CONFIDENCE, order));
            }
        }
    }

    /** The class a call's receiver refers to, or the enclosing class for implicit this. */
    private ClassModel resolveReceiver(MethodCallExpr call, ClassModel cls,
                                       Map<String, String> locals, Map<String, String> params,
                                       Map<String, ClassModel> classByName) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            return cls; // implicit this
        }
        Expression obj = scope.get();
        if (obj instanceof ThisExpr) {
            return cls;
        }
        if (obj instanceof NameExpr name) {
            String id = name.getNameAsString();
            String type = locals.get(id);
            if (type == null || type.isEmpty()) {
                type = params.get(id);
            }
            if (type == null || type.isEmpty()) {
                type = cls.fields.get(id);
            }
            if (type != null && !type.isEmpty()) {
                return classByName.get(type);
            }
        }
        // Chained/complex receiver (method chains, deep field access): omit (Requirement 9.9).
        return null;
    }

    private static String findMethod(ClassModel c, String name) {
        for (MethodModel m : c.methods) {
            if (m.name.equals(name)) {
                return m.id;
            }
        }
        return null;
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

    /** List<Foo> -> List ; com.a.Foo[] -> Foo ; Bar -> Bar. */
    private static String simpleType(String s) {
        int cut = -1;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '<' || ch == '[' || ch == ' ') {
                cut = i;
                break;
            }
        }
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        int dot = s.lastIndexOf('.');
        if (dot >= 0) {
            s = s.substring(dot + 1);
        }
        return s;
    }
}

package com.flowscope.entity;

import com.flowscope.entity.SourceFile;
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
import com.flowscope.util.SpringEndpoints;

/**
 * A whole-program Java symbol model built by parsing every Java file once.
 * Powers inter-procedural flow: it can map a call site to the internal method it
 * targets using the same conservative, type-aware rules as {@link resolver.JavaCallResolver}.
 *
 * <p>Building this is moderately expensive (it holds the parsed ASTs), so callers
 * should cache instances by source root.
 */
public class JavaProgramModel {

    /** A parsed method/constructor plus the context needed to resolve its calls. */
    public static final class MethodInfo {
        public final String id;
        public final String name;
        public final ClassInfo owner;
        public final Map<String, String> params = new HashMap<>();
        public final Map<String, String> locals = new HashMap<>();
        public final CallableDeclaration<?> decl;
        /** "VERB /path" if this is a Spring endpoint handler, else null. */
        public final String endpointLabel;
        /** Simple return type name (for method-chain resolution), or null for constructors/void. */
        public final String returnType;

        MethodInfo(String id, String name, ClassInfo owner, CallableDeclaration<?> decl,
                   String endpointLabel, String returnType) {
            this.id = id;
            this.name = name;
            this.owner = owner;
            this.decl = decl;
            this.endpointLabel = endpointLabel;
            this.returnType = returnType;
        }

        public Optional<BlockStmt> body() {
            if (decl instanceof MethodDeclaration m) {
                return m.getBody();
            }
            if (decl instanceof ConstructorDeclaration c) {
                return Optional.of(c.getBody());
            }
            return Optional.empty();
        }
    }

    public static final class ClassInfo {
        public final String name;
        public final String rel;
        public final boolean isInterface;
        public final int startLine;
        /** Dotted package name, or "" for the default package. */
        public final String pkg;
        /** Simple names of annotations on the type (e.g. "Service", "RestController"). */
        public final List<String> annotations = new ArrayList<>();
        public final Map<String, String> fields = new HashMap<>();
        public final List<MethodInfo> methods = new ArrayList<>();
        /** Simple names of extended classes + implemented interfaces. */
        public final List<String> superTypes = new ArrayList<>();

        ClassInfo(String name, String rel, boolean isInterface, int startLine, String pkg) {
            this.name = name;
            this.rel = rel;
            this.isInterface = isInterface;
            this.startLine = startLine;
            this.pkg = pkg;
        }
    }

    /** All classes/interfaces discovered in the program, in source order. */
    public List<ClassInfo> classes() {
        return allClasses;
    }

    /** Concrete bean implementations of interface/superclass {@code simpleName}. */
    public List<ClassInfo> implementationsOf(String simpleName) {
        return implsBySuperType.getOrDefault(simpleName, List.of());
    }

    /** The class/interface with this simple name, or null (last-wins on collision). */
    public ClassInfo classByName(String simpleName) {
        return classByName.get(simpleName);
    }

    private final Map<String, ClassInfo> classByName = new HashMap<>();
    private final Map<String, MethodInfo> methodById = new LinkedHashMap<>();
    private final List<ClassInfo> allClasses = new ArrayList<>();
    /** interface/superclass simple name -> concrete types that extend/implement it. */
    private final Map<String, List<ClassInfo>> implsBySuperType = new HashMap<>();

    public MethodInfo method(String id) {
        return methodById.get(id);
    }

    /** Build the model from the Java files among {@code files}. */
    public static JavaProgramModel build(List<SourceFile> files) {
        JavaProgramModel model = new JavaProgramModel();
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

        for (SourceFile file : files) {
            if (!"java".equals(file.language())) {
                continue;
            }
            CompilationUnit cu;
            try {
                byte[] src = Files.readAllBytes(file.absolutePath());
                cu = parser.parse(new String(src, StandardCharsets.UTF_8)).getResult().orElse(null);
            } catch (Exception e) {
                continue;
            }
            if (cu == null) {
                continue;
            }
            for (TypeDeclaration<?> type : cu.findAll(TypeDeclaration.class)) {
                model.addType(type, file.relativePath());
            }
        }
        model.buildSupertypeIndex();
        return model;
    }

    /** Index concrete types by the interfaces/superclasses they declare. */
    private void buildSupertypeIndex() {
        for (ClassInfo cls : allClasses) {
            if (cls.isInterface) {
                continue;
            }
            for (String sup : cls.superTypes) {
                implsBySuperType.computeIfAbsent(sup, k -> new ArrayList<>()).add(cls);
            }
        }
    }

    private void addType(TypeDeclaration<?> type, String rel) {
        boolean isInterface = type instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration coi
                && coi.isInterface();
        int typeLine = type.getBegin().map(p -> p.line).orElse(0);
        String pkg = type.findCompilationUnit()
                .flatMap(cu -> cu.getPackageDeclaration())
                .map(pd -> pd.getNameAsString())
                .orElse("");
        ClassInfo cls = new ClassInfo(type.getNameAsString(), rel, isInterface, typeLine, pkg);
        type.getAnnotations().forEach(a -> cls.annotations.add(a.getNameAsString()));
        if (type instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration coi) {
            coi.getExtendedTypes().forEach(t -> cls.superTypes.add(simpleType(t.getNameAsString())));
            coi.getImplementedTypes().forEach(t -> cls.superTypes.add(simpleType(t.getNameAsString())));
        }
        String basePath = SpringEndpoints.basePath(type);

        for (FieldDeclaration field : type.findAll(FieldDeclaration.class,
                f -> f.getParentNode().orElse(null) == type)) {
            for (VariableDeclarator v : field.getVariables()) {
                cls.fields.put(v.getNameAsString(), simpleType(v.getType().toString()));
            }
        }

        List<CallableDeclaration<?>> callables = new ArrayList<>();
        callables.addAll(type.getMethods());
        callables.addAll(type.findAll(ConstructorDeclaration.class,
                c -> c.getParentNode().orElse(null) == type));

        for (CallableDeclaration<?> callable : callables) {
            String name = callable.getNameAsString();
            int startLine = callable.getBegin().map(p -> p.line).orElse(0);
            String endpointLabel = SpringEndpoints.endpoint(callable, basePath)
                    .map(vp -> vp[0] + " " + vp[1]).orElse(null);
            String returnType = callable instanceof MethodDeclaration md
                    ? simpleType(md.getType().toString()) : null;
            MethodInfo mi = new MethodInfo(
                    "func:" + rel + "#" + name + "@" + startLine, name, cls, callable,
                    endpointLabel, returnType);
            for (Parameter p : callable.getParameters()) {
                mi.params.put(p.getNameAsString(), simpleType(p.getType().toString()));
            }
            mi.body().ifPresent(body -> collectLocals(body, mi.locals));
            cls.methods.add(mi);
            methodById.put(mi.id, mi);
        }
        // last-wins on name collision (matches JavaCallResolver)
        classByName.put(cls.name, cls);
        allClasses.add(cls);
    }

    private static void collectLocals(BlockStmt body, Map<String, String> locals) {
        for (VariableDeclarationExpr decl : body.findAll(VariableDeclarationExpr.class)) {
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
    }

    /** A resolved call target: the method to inline plus the concrete type its
     *  body runs on (used for virtual dispatch of {@code this} calls within it). */
    public record Callee(MethodInfo method, ClassInfo thisType) {
    }

    /**
     * Resolve a call site to the internal method(s) whose body should be inlined.
     *
     * @param thisType the concrete type the CURRENT method is running on — used to
     *                 dispatch {@code this}/implicit calls to the right override
     *                 (e.g. an abstract {@code getRepository()} in a base class
     *                 resolves to the concrete subclass's override, not every one).
     * @return one target for a direct/inherited/static/chain hit; several when an
     *         interface/abstract type has multiple implementations; empty when
     *         external or unresolvable.
     */
    public List<Callee> resolveCallees(MethodCallExpr call, MethodInfo caller, ClassInfo thisType) {
        ClassInfo recv = call.getScope()
                .map(scope -> resolveType(scope, caller, thisType))
                .orElse(thisType); // implicit this
        if (recv == null) {
            return List.of();
        }
        String name = call.getNameAsString();

        // Direct/inherited hit: a method with a body on the concrete receiver or up
        // its hierarchy. The body runs on the concrete receiver type.
        MethodInfo direct = methodWithBody(recv, name);
        if (direct != null) {
            return List.of(new Callee(direct, recv));
        }

        // Interface / abstract with no concrete body up-chain: fan out to impls.
        List<Callee> impls = new ArrayList<>();
        for (ClassInfo impl : implsBySuperType.getOrDefault(recv.name, List.of())) {
            MethodInfo m = methodWithBody(impl, name);
            if (m != null && impls.stream().noneMatch(x -> x.method.id.equals(m.id))) {
                impls.add(new Callee(m, impl));
            }
        }
        return impls;
    }

    /** Convenience: the unique callee, or null when zero or ambiguous. */
    public MethodInfo resolveCallee(MethodCallExpr call, MethodInfo caller, ClassInfo thisType) {
        List<Callee> callees = resolveCallees(call, caller, thisType);
        return callees.size() == 1 ? callees.get(0).method : null;
    }

    /**
     * The first method named {@code name} with a body, searching {@code cls} and
     * then walking UP its {@code extends}/{@code implements} chain — so inherited
     * methods (e.g. a call to {@code find()} defined in a {@code ServiceBase}
     * superclass) resolve. Cycle-guarded.
     */
    private MethodInfo methodWithBody(ClassInfo cls, String name) {
        return methodWithBody(cls, name, new HashSet<>());
    }

    private MethodInfo methodWithBody(ClassInfo cls, String name, Set<String> visited) {
        if (cls == null || !visited.add(cls.name)) {
            return null;
        }
        for (MethodInfo m : cls.methods) {
            if (m.name.equals(name) && m.body().isPresent()) {
                return m;
            }
        }
        for (String sup : cls.superTypes) {
            MethodInfo m = methodWithBody(classByName.get(sup), name, visited);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    /**
     * Resolve the {@link ClassInfo} an expression evaluates to — covering the
     * receiver forms that show up in real code: {@code this}, variables/fields/params,
     * static class references ({@code Utils.x()}), {@code new Foo()}, multi-level
     * field access ({@code this.a.b}), and method chains ({@code a.find(id).run()}
     * via the callee's return type). Returns null when it cannot be determined.
     */
    private ClassInfo resolveType(Expression expr, MethodInfo caller, ClassInfo thisType) {
        if (expr instanceof com.github.javaparser.ast.expr.EnclosedExpr e) {
            return resolveType(e.getInner(), caller, thisType);
        }
        if (expr instanceof com.github.javaparser.ast.expr.CastExpr c) {
            return classByName.get(simpleType(c.getType().toString()));
        }
        if (expr instanceof ThisExpr) {
            return thisType;
        }
        if (expr instanceof ObjectCreationExpr oce) {
            return classByName.get(simpleType(oce.getType().toString()));
        }
        if (expr instanceof NameExpr name) {
            String id = name.getNameAsString();
            String type = firstNonEmpty(caller.locals.get(id), caller.params.get(id),
                    fieldType(thisType, id));
            if (type != null) {
                return classByName.get(type);
            }
            // Static call target, e.g. Utils.doThing(): the name is a class itself.
            return classByName.get(id);
        }
        if (expr instanceof com.github.javaparser.ast.expr.FieldAccessExpr fa) {
            ClassInfo base = resolveType(fa.getScope(), caller, thisType);
            if (base != null) {
                String type = fieldType(base, fa.getNameAsString());
                if (type != null) {
                    return classByName.get(type);
                }
            }
            return null;
        }
        if (expr instanceof MethodCallExpr chain) {
            // Chain (a.find(id).run()): resolve via the callee's DECLARED return type,
            // which works even when the intermediate method has no inlinable body
            // (e.g. a repository interface method).
            ClassInfo recv = chain.getScope()
                    .map(s -> resolveType(s, caller, thisType)).orElse(thisType);
            if (recv != null) {
                String rt = declaredReturnType(recv, chain.getNameAsString());
                if (rt != null) {
                    return classByName.get(rt);
                }
            }
            return null;
        }
        return null;
    }

    /** Type of field {@code name} on {@code cls}, searching up the hierarchy. */
    private String fieldType(ClassInfo cls, String name) {
        return fieldType(cls, name, new HashSet<>());
    }

    private String fieldType(ClassInfo cls, String name, Set<String> visited) {
        if (cls == null || !visited.add(cls.name)) {
            return null;
        }
        String t = cls.fields.get(name);
        if (t != null && !t.isEmpty()) {
            return t;
        }
        for (String sup : cls.superTypes) {
            String r = fieldType(classByName.get(sup), name, visited);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** Declared return type of a method named {@code name} on {@code cls}, its
     *  superclasses/interfaces, or its implementations. */
    private String declaredReturnType(ClassInfo cls, String name) {
        String up = declaredReturnTypeUp(cls, name, new HashSet<>());
        if (up != null) {
            return up;
        }
        for (ClassInfo impl : implsBySuperType.getOrDefault(cls.name, List.of())) {
            String r = declaredReturnTypeUp(impl, name, new HashSet<>());
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private String declaredReturnTypeUp(ClassInfo cls, String name, Set<String> visited) {
        if (cls == null || !visited.add(cls.name)) {
            return null;
        }
        for (MethodInfo m : cls.methods) {
            if (m.name.equals(name) && m.returnType != null) {
                return m.returnType;
            }
        }
        for (String sup : cls.superTypes) {
            String r = declaredReturnTypeUp(classByName.get(sup), name, visited);
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    public static String simpleType(String s) {
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

package com.flowscope.service;

import com.flowscope.dto.ComponentMap.Component;
import com.flowscope.dto.ComponentMap.Dependency;
import com.flowscope.dto.ComponentMap.Stats;
import com.flowscope.entity.JavaProgramModel;
import com.flowscope.entity.JavaProgramModel.ClassInfo;
import com.flowscope.entity.JavaProgramModel.Callee;
import com.flowscope.entity.JavaProgramModel.MethodInfo;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import com.flowscope.dto.ComponentMap;

/**
 * Builds the intra-app {@link ComponentMap} from a parsed {@link JavaProgramModel}.
 * Nodes are Spring beans classified by architectural layer; edges are dependency
 * relationships derived from field/constructor injection and resolved cross-class
 * method calls (via the same {@code resolveCallees} engine the flow/sequence
 * builders use, so the three views stay consistent).
 */
public final class ComponentMapBuilder {

    /** Spring Data repository base types (interface extends one of these -> repository). */
    private static final Set<String> REPOSITORY_BASES = Set.of(
            "JpaRepository", "CrudRepository", "PagingAndSortingRepository", "MongoRepository",
            "Repository", "ReactiveCrudRepository", "R2dbcRepository", "ElasticsearchRepository",
            "CassandraRepository", "KeyValueRepository");

    private ComponentMapBuilder() {
    }

    public static ComponentMap build(String project, JavaProgramModel model) {
        // 1. Node set: classes with a recognised layer, folding an interface with a
        //    single implementation into that implementation.
        Map<String, Component> nodes = new LinkedHashMap<>();
        Map<String, ClassInfo> classById = new LinkedHashMap<>();
        for (ClassInfo c : model.classes()) {
            if (layerOf(c) == null || isTestClass(c)) {
                continue;
            }
            ClassInfo canon = canonical(model, c);
            if (!canon.name.equals(c.name)) {
                continue; // folded into its implementation
            }
            if (nodes.containsKey(c.name)) {
                continue;
            }
            List<String> endpoints = c.methods.stream()
                    .map(m -> m.endpointLabel)
                    .filter(x -> x != null)
                    .distinct()
                    .toList();
            nodes.put(c.name, new Component(c.name, c.name, layerOf(c), c.pkg, c.rel,
                    c.startLine, c.methods.size(), endpoints, 0, 0));
            classById.put(c.name, c);
        }

        // 2. Edges: field injection (strong) + resolved calls (weighted).
        //    key = "src->tgt" -> [kind, weight]
        Map<String, int[]> weights = new LinkedHashMap<>();
        Map<String, String> kinds = new LinkedHashMap<>();
        for (ClassInfo a : classById.values()) {
            if (a.isInterface) {
                continue; // interfaces have no bodies/injection sites
            }
            // field / constructor injection
            for (String fieldType : a.fields.values()) {
                ClassInfo b = model.classByName(fieldType);
                addTarget(model, nodes, weights, kinds, a.name, b, "inject", 0);
            }
            // cross-class calls
            for (MethodInfo m : a.methods) {
                m.body().ifPresent(body -> {
                    for (MethodCallExpr call : body.findAll(MethodCallExpr.class)) {
                        for (Callee callee : model.resolveCallees(call, m, a)) {
                            addTarget(model, nodes, weights, kinds, a.name,
                                    callee.method().owner, "call", 1);
                        }
                    }
                });
            }
        }

        // 3. Materialise dependencies + fan-in/out.
        List<Dependency> deps = new ArrayList<>();
        Map<String, Integer> fanIn = new LinkedHashMap<>();
        Map<String, Integer> fanOut = new LinkedHashMap<>();
        for (Map.Entry<String, int[]> e : weights.entrySet()) {
            String[] st = e.getKey().split("->", 2);
            String kind = kinds.get(e.getKey());
            deps.add(new Dependency(e.getKey(), st[0], st[1], kind, Math.max(1, e.getValue()[0])));
            fanOut.merge(st[0], 1, Integer::sum);
            fanIn.merge(st[1], 1, Integer::sum);
        }

        // Rebuild components with fan-in/out filled in.
        List<Component> components = new ArrayList<>();
        Map<String, Integer> byLayer = new TreeMap<>();
        for (Component c : nodes.values()) {
            components.add(new Component(c.id(), c.name(), c.layer(), c.pkg(), c.file(),
                    c.startLine(), c.methods(), c.endpoints(),
                    fanIn.getOrDefault(c.id(), 0), fanOut.getOrDefault(c.id(), 0)));
            byLayer.merge(c.layer(), 1, Integer::sum);
        }

        List<String> notes = List.of(
                components.size() + " components across " + byLayer.size() + " layers, "
                        + deps.size() + " dependencies");
        return new ComponentMap(project, components, deps,
                new Stats(components.size(), deps.size(), byLayer, notes));
    }

    /** Record an edge a -> canonical(b), if b is a distinct component node. */
    private static void addTarget(JavaProgramModel model, Map<String, Component> nodes,
                                  Map<String, int[]> weights, Map<String, String> kinds,
                                  String from, ClassInfo b, String kind, int addWeight) {
        if (b == null) {
            return;
        }
        ClassInfo target = canonical(model, b);
        if (layerOf(target) == null || !nodes.containsKey(target.name) || target.name.equals(from)) {
            return;
        }
        String key = from + "->" + target.name;
        weights.computeIfAbsent(key, k -> new int[1])[0] += addWeight;
        // "inject" is the stronger relationship and wins the label.
        kinds.merge(key, kind, (existing, incoming) ->
                existing.equals("inject") || incoming.equals("inject") ? "inject" : "call");
    }

    /** Fold an interface with exactly one component implementation into that impl. */
    private static ClassInfo canonical(JavaProgramModel model, ClassInfo c) {
        if (!c.isInterface) {
            return c;
        }
        List<ClassInfo> impls = model.implementationsOf(c.name).stream()
                .filter(x -> layerOf(x) != null)
                .toList();
        return impls.size() == 1 ? impls.get(0) : c;
    }

    /** Architectural layer for a class, or null when it is not a component (DTO, entity, util…). */
    static String layerOf(ClassInfo c) {
        Set<String> a = new LinkedHashSet<>(c.annotations);
        boolean hasEndpoint = c.methods.stream().anyMatch(m -> m.endpointLabel != null);
        // Strong, specific stereotype annotations win outright.
        if (a.contains("RestController") || a.contains("Controller") || hasEndpoint) {
            return "controller";
        }
        if (a.contains("Service")) {
            return "service";
        }
        if (a.contains("FeignClient")) {
            return "client";
        }
        if (a.contains("Repository") || isRepositoryType(c)) {
            return "repository";
        }
        if (a.contains("Configuration")) {
            return "config";
        }
        // Name-based classification comes BEFORE the generic @Component: a class named
        // *Repository annotated @Component is still a repository.
        String n = c.name;
        if (endsAny(n, "Controller", "Resource", "Endpoint")) {
            return "controller";
        }
        if (endsAny(n, "ServiceImpl", "Service")) {
            return "service";
        }
        if (endsAny(n, "Client", "Gateway", "ApiClient")) {
            return "client";
        }
        if (endsAny(n, "RepositoryImpl", "Repository", "Dao", "DAO", "Repo")) {
            return "repository";
        }
        if (endsAny(n, "Configuration", "Config", "Properties")) {
            return "config";
        }
        // Generic @Component / ControllerAdvice, or a component-ish name.
        if (a.contains("Component") || a.contains("ControllerAdvice")
                || a.contains("RestControllerAdvice")) {
            return "component";
        }
        if (endsAny(n, "Manager", "Handler", "Listener", "Consumer", "Producer", "Scheduler",
                "Job", "Filter", "Interceptor", "Aspect", "Validator", "Mapper", "Factory",
                "Provider", "Component")) {
            return "component";
        }
        return null;
    }

    /** True for classes that live in test source trees (excluded from the map). */
    private static boolean isTestClass(ClassInfo c) {
        String rel = c.rel == null ? "" : c.rel.replace('\\', '/');
        return rel.contains("/test/") || rel.startsWith("test/") || rel.contains("src/test");
    }

    private static boolean isRepositoryType(ClassInfo c) {
        for (String sup : c.superTypes) {
            if (REPOSITORY_BASES.contains(sup)) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsAny(String name, String... suffixes) {
        for (String s : suffixes) {
            if (name.endsWith(s)) {
                return true;
            }
        }
        // Also treat an all-caps trailing token match case-insensitively for DAO/Repo.
        String lower = name.toLowerCase(Locale.ROOT);
        for (String s : suffixes) {
            if (lower.endsWith(s.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}

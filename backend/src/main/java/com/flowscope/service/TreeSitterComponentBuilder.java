package com.flowscope.service;

import com.flowscope.dto.ComponentMap;
import com.flowscope.dto.ComponentMap.Component;
import com.flowscope.dto.ComponentMap.Dependency;
import com.flowscope.dto.ComponentMap.Stats;
import com.flowscope.entity.SourceFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterGo;
import org.treesitter.TreeSitterPython;

/**
 * Builds an intra-app {@link ComponentMap} for Go or Python via tree-sitter — the
 * language-neutral counterpart to the Spring-annotation-driven Java
 * {@link ComponentMapBuilder}. Go/Python have no bean stereotypes, so components
 * are the concrete types (Go {@code struct}s, Python {@code class}es, plus a
 * module component for function-only files) and their layer is inferred by naming
 * convention. Dependencies are derived structurally: Go struct field types,
 * Python base classes and {@code self.x = Ctor(...)} constructions — resolved to
 * other known components. Necessarily more heuristic than the Java version.
 */
public final class TreeSitterComponentBuilder {

    private static final TSLanguage GO = new TreeSitterGo();
    private static final TSLanguage PYTHON = new TreeSitterPython();

    // Layer classification keywords, checked in priority order.
    private static final String[][] LAYER_RULES = {
        {"controller", "handler|controller|router|route|endpoint|resource|view|rest_api|restcontroller"},
        {"repository", "repository|repo|dao|store|storage|persistence|entity|model|dal|schema"},
        {"client", "client|gateway|adapter|connector|provider|remote|sdk|external|proxy"},
        {"config", "config|settings|option|conf|env|constants"},
        {"service", "service|svc|usecase|manager|processor|compiler|engine|worker|orchestrator|producer|consumer|publisher|listener"},
    };

    private TreeSitterComponentBuilder() {
    }

    /** Component under construction. */
    private static final class Comp {
        final String name;
        String layer;
        final String pkg;
        final String rel;
        final int startLine;
        int methods;
        final Set<String> deps = new LinkedHashSet<>(); // target names (unresolved)

        Comp(String name, String layer, String pkg, String rel, int startLine) {
            this.name = name;
            this.layer = layer;
            this.pkg = pkg;
            this.rel = rel;
            this.startLine = startLine;
        }
    }

    public static ComponentMap build(String project, List<SourceFile> files, String lang) {
        Map<String, Comp> comps = new LinkedHashMap<>();
        TSLanguage grammar = "go".equals(lang) ? GO : PYTHON;

        for (SourceFile f : files) {
            if (!lang.equals(f.language())) {
                continue;
            }
            byte[] src;
            try {
                src = Files.readAllBytes(f.absolutePath());
            } catch (Exception e) {
                continue;
            }
            TSParser parser = new TSParser();
            parser.setLanguage(grammar);
            TSNode root;
            try {
                root = parser.parseString(null, new String(src, StandardCharsets.UTF_8)).getRootNode();
            } catch (RuntimeException e) {
                continue;
            }
            if (root.isNull()) {
                continue;
            }
            if ("go".equals(lang)) {
                collectGo(root, src, f.relativePath(), comps);
            } else {
                collectPython(root, src, f.relativePath(), comps);
            }
        }

        return assemble(project, comps);
    }

    // --- Go collection -----------------------------------------------------

    private static void collectGo(TSNode root, byte[] src, String rel, Map<String, Comp> comps) {
        String pkg = goPackage(root, src);
        // Pass 1: struct/interface types → components + their field-type deps.
        for (int i = 0; i < root.getNamedChildCount(); i++) {
            TSNode decl = root.getNamedChild(i);
            if (!"type_declaration".equals(decl.getType())) {
                continue;
            }
            for (int j = 0; j < decl.getNamedChildCount(); j++) {
                TSNode spec = decl.getNamedChild(j);
                if (!"type_spec".equals(spec.getType())) {
                    continue;
                }
                TSNode nameNode = spec.getChildByFieldName("name");
                TSNode typeNode = spec.getChildByFieldName("type");
                if (!ok(nameNode) || !ok(typeNode)) {
                    continue;
                }
                String t = typeNode.getType();
                if (!"struct_type".equals(t) && !"interface_type".equals(t)) {
                    continue;
                }
                String name = text(nameNode, src);
                Comp c = comps.computeIfAbsent(name,
                        k -> new Comp(name, layerOf(name, pkg), pkg, rel, line(spec)));
                collectGoFieldDeps(typeNode, src, c);
            }
        }
        // Pass 2: method receivers → method counts + call-target deps (light).
        for (int i = 0; i < root.getNamedChildCount(); i++) {
            TSNode decl = root.getNamedChild(i);
            if (!"method_declaration".equals(decl.getType())) {
                continue;
            }
            String recv = goReceiverType(decl, src);
            Comp c = recv.isEmpty() ? null : comps.get(recv);
            if (c != null) {
                c.methods++;
            }
        }
    }

    private static void collectGoFieldDeps(TSNode structOrIface, byte[] src, Comp c) {
        // struct_type → field_declaration_list → field_declaration → [type]
        TSNode body = firstChildOfType(structOrIface, "field_declaration_list");
        if (body == null) {
            body = structOrIface;
        }
        for (int i = 0; i < body.getNamedChildCount(); i++) {
            TSNode fd = body.getNamedChild(i);
            if (!"field_declaration".equals(fd.getType())) {
                continue;
            }
            TSNode typeNode = fd.getChildByFieldName("type");
            if (ok(typeNode)) {
                String dep = bareTypeName(text(typeNode, src));
                if (!dep.isEmpty()) {
                    c.deps.add(dep);
                }
            }
        }
    }

    private static String goReceiverType(TSNode method, byte[] src) {
        TSNode recv = method.getChildByFieldName("receiver");
        if (!ok(recv)) {
            return "";
        }
        TSNode ptr = firstChildOfType(recv, "pointer_type");
        TSNode id = ptr != null ? firstChildOfType(ptr, "type_identifier")
                : firstChildOfType(recv, "type_identifier");
        return id != null ? text(id, src) : "";
    }

    private static String goPackage(TSNode root, byte[] src) {
        TSNode pc = firstChildOfType(root, "package_clause");
        if (pc != null && pc.getNamedChildCount() > 0) {
            return text(pc.getNamedChild(0), src);
        }
        return "";
    }

    // --- Python collection -------------------------------------------------

    private static void collectPython(TSNode root, byte[] src, String rel, Map<String, Comp> comps) {
        int topLevelFuncs = 0;
        int firstFuncLine = 1;
        for (int i = 0; i < root.getNamedChildCount(); i++) {
            TSNode decl = unwrapDecorated(root.getNamedChild(i));
            switch (decl.getType()) {
                case "class_definition" -> collectPyClass(decl, src, rel, comps);
                case "function_definition" -> {
                    if (topLevelFuncs == 0) {
                        firstFuncLine = line(decl);
                    }
                    topLevelFuncs++;
                }
                default -> { }
            }
        }
        // Function-only module (e.g. a Flask/FastAPI app.py) → one module component.
        if (topLevelFuncs > 0) {
            String modName = moduleName(rel);
            int startLine = firstFuncLine;
            Comp c = comps.computeIfAbsent(modName,
                    k -> new Comp(modName, layerOf(modName, ""), modName, rel, startLine));
            c.methods += topLevelFuncs;
        }
    }

    private static void collectPyClass(TSNode cls, byte[] src, String rel, Map<String, Comp> comps) {
        TSNode nameNode = cls.getChildByFieldName("name");
        if (!ok(nameNode)) {
            return;
        }
        String name = text(nameNode, src);
        // Classify by the class NAME only — the file path contains the project dir
        // (e.g. "…-compiler/…"), which would false-match layer keywords.
        String mod = moduleName(rel);
        Comp c = comps.computeIfAbsent(name, k -> new Comp(name, layerOf(name, ""), mod, rel, line(cls)));
        // Base classes → dependency (inherits).
        TSNode supers = cls.getChildByFieldName("superclasses");
        if (ok(supers)) {
            for (int i = 0; i < supers.getNamedChildCount(); i++) {
                String base = bareTypeName(text(supers.getNamedChild(i), src));
                if (!base.isEmpty()) {
                    c.deps.add(base);
                }
            }
        }
        // Method count + `self.x = Ctor(...)` construction deps.
        TSNode body = cls.getChildByFieldName("body");
        if (ok(body)) {
            for (int i = 0; i < body.getNamedChildCount(); i++) {
                TSNode m = unwrapDecorated(body.getNamedChild(i));
                if ("function_definition".equals(m.getType())) {
                    c.methods++;
                    collectPyCtorDeps(m, src, c);
                }
            }
        }
    }

    /** Find {@code self.attr = SomeClass(...)} assignments → SomeClass dependency. */
    private static void collectPyCtorDeps(TSNode n, byte[] src, Comp c) {
        if ("assignment".equals(n.getType())) {
            TSNode left = n.getChildByFieldName("left");
            TSNode right = n.getChildByFieldName("right");
            if (ok(left) && ok(right) && text(left, src).startsWith("self.")
                    && "call".equals(right.getType())) {
                TSNode fn = right.getChildByFieldName("function");
                if (ok(fn)) {
                    String dep = bareTypeName(lastSegment(text(fn, src)));
                    if (!dep.isEmpty() && Character.isUpperCase(dep.charAt(0))) {
                        c.deps.add(dep);
                    }
                }
            }
        }
        for (int i = 0; i < n.getNamedChildCount(); i++) {
            collectPyCtorDeps(n.getNamedChild(i), src, c);
        }
    }

    // --- assembly ----------------------------------------------------------

    private static ComponentMap assemble(String project, Map<String, Comp> comps) {
        List<Dependency> deps = new ArrayList<>();
        Map<String, Integer> fanOut = new LinkedHashMap<>();
        Map<String, Integer> fanIn = new LinkedHashMap<>();
        Set<String> seenEdge = new LinkedHashSet<>();

        for (Comp c : comps.values()) {
            for (String target : c.deps) {
                if (!comps.containsKey(target) || target.equals(c.name)) {
                    continue;
                }
                String key = c.name + ">" + target;
                if (!seenEdge.add(key)) {
                    continue;
                }
                deps.add(new Dependency(key, c.name, target, "uses", 1));
                fanOut.merge(c.name, 1, Integer::sum);
                fanIn.merge(target, 1, Integer::sum);
            }
        }

        List<Component> components = new ArrayList<>();
        Map<String, Integer> byLayer = new TreeMap<>();
        for (Comp c : comps.values()) {
            byLayer.merge(c.layer, 1, Integer::sum);
            components.add(new Component(c.name, c.name, c.layer, c.pkg, c.rel, c.startLine,
                    c.methods, List.of(),
                    fanIn.getOrDefault(c.name, 0), fanOut.getOrDefault(c.name, 0)));
        }

        List<String> notes = List.of(components.size() + " components, " + deps.size()
                + " dependencies (heuristic: classified by naming convention)");
        return new ComponentMap(project, components, deps,
                new Stats(components.size(), deps.size(), byLayer, notes));
    }

    static String layerOf(String name, String pkg) {
        String s = (name + " " + (pkg == null ? "" : pkg)).toLowerCase(Locale.ROOT);
        for (String[] rule : LAYER_RULES) {
            for (String kw : rule[1].split("\\|")) {
                if (s.contains(kw)) {
                    return rule[0];
                }
            }
        }
        return "component";
    }

    // --- small helpers -----------------------------------------------------

    /** Strip pointer/slice/map/package qualifiers to the bare type name. */
    private static String bareTypeName(String raw) {
        String s = raw.trim();
        int lt = s.indexOf('[');
        if (s.startsWith("map[")) {
            int close = s.indexOf(']');
            s = close >= 0 ? s.substring(close + 1) : s;
        } else if (lt == 0) { // slice/array prefix
            int close = s.indexOf(']');
            s = close >= 0 ? s.substring(close + 1) : s;
        }
        s = s.replace("*", "").replace("&", "").trim();
        s = lastSegment(s); // pkg.Type -> Type
        // strip generics / call args
        int paren = s.indexOf('(');
        if (paren >= 0) {
            s = s.substring(0, paren);
        }
        int angle = s.indexOf('[');
        if (angle >= 0) {
            s = s.substring(0, angle);
        }
        return s.replaceAll("[^A-Za-z0-9_].*$", "").trim();
    }

    private static String lastSegment(String s) {
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    private static TSNode unwrapDecorated(TSNode n) {
        if (!"decorated_definition".equals(n.getType())) {
            return n;
        }
        TSNode def = n.getChildByFieldName("definition");
        return def != null && !def.isNull() ? def : n;
    }

    /** True when a node is present and usable — {@code getChildByFieldName} returns
     *  a <em>null node</em> (not Java null) for an absent field, and calling
     *  navigation methods on it throws. */
    private static boolean ok(TSNode n) {
        return n != null && !n.isNull();
    }

    private static TSNode firstChildOfType(TSNode n, String type) {
        for (int i = 0; i < n.getNamedChildCount(); i++) {
            if (type.equals(n.getNamedChild(i).getType())) {
                return n.getNamedChild(i);
            }
        }
        return null;
    }

    private static String moduleName(String rel) {
        String p = rel.replace('\\', '/');
        int slash = p.lastIndexOf('/');
        String file = slash >= 0 ? p.substring(slash + 1) : p;
        return file.endsWith(".py") ? file.substring(0, file.length() - 3) : file;
    }

    private static int line(TSNode n) {
        return n.getStartPoint().getRow() + 1;
    }

    private static String text(TSNode n, byte[] src) {
        return new String(src, n.getStartByte(), n.getEndByte() - n.getStartByte(), StandardCharsets.UTF_8);
    }
}

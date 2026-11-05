package com.flowscope.service;

import com.flowscope.dto.Architecture;
import com.flowscope.dto.ComponentMap;
import com.flowscope.dto.ServiceMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The architecture of one application: its components grouped by layer in the
 * middle, the services and messaging it connects to on the left, and its data
 * stores, external systems and cloud services on the right.
 *
 * <p>Built from the project's {@link Architecture} (so connections and their
 * evidence are the same as in the project view) plus the application's
 * {@link ComponentMap}. Each outside connection starts at the layer group whose
 * code makes it, found by matching the connection's file:line evidence to the
 * component declared in that file. Evidence that matches no component (YAML
 * configuration, configuration classes, plain helpers) attaches the connection to
 * the application itself. All Kafka topics the application uses go into one Kafka
 * box that lists them.
 */
final class AppArchitectureBuilder {

    static final String KAFKA = "app:kafka";
    static final String CONNECTED = "connected";
    static final String APP = "app";

    /** lane id, lane title, then "layer:group title" pairs. Configuration classes are left out. */
    private static final List<String[]> LANES = List.of(
            new String[]{"entry", "Entry points", "controller:Controllers", "component:Listeners & components"},
            new String[]{"logic", "Business logic", "service:Services"},
            new String[]{"access", "Data access", "repository:Repositories", "client:Clients"});

    private static final Map<String, String> LAYER_NAME = Map.of(
            "controller", "Controller", "component", "Component", "service", "Service",
            "repository", "Repository", "client", "Client", "config", "Configuration");

    /** Databases reached through repositories (JPA, JDBC): their calls are drawn from the repositories. */
    private static final Set<String> RELATIONAL = Set.of(
            "postgresql", "mysql", "mariadb", "oracle", "sqlserver", "mssql", "db2", "h2");

    private static final List<String> CONFIDENCE = List.of("declared", "code", "config", "inferred", "unresolved");

    private AppArchitectureBuilder() {
    }

    /**
     * @param project the project-scope architecture the application belongs to
     * @param appId   id of the application's node in {@code project}
     * @param cmp     the application's components, or null when its runtime has no component view
     */
    static Architecture build(Architecture project, String appId, ComponentMap cmp) {
        Map<String, Architecture.Node> byId = new HashMap<>();
        project.nodes().forEach(n -> byId.put(n.id(), n));
        Architecture.Node app = byId.get(appId);
        String appPath = app.meta() != null && app.meta().get("path") instanceof String p ? p : null;

        // --- components and their layer groups -------------------------------------------------
        Map<String, Architecture.Node> components = new LinkedHashMap<>();
        Map<String, List<String>> groupMembers = new LinkedHashMap<>();
        Map<String, String> layerByFile = new LinkedHashMap<>();
        int hiddenConfig = 0;
        if (cmp != null) {
            for (ComponentMap.Component c : cmp.components()) {
                if (c.file() != null) {
                    layerByFile.put(c.file(), c.layer());
                }
                if ("config".equals(c.layer())) {
                    hiddenConfig++;
                    continue;
                }
                String id = "cmp:" + c.id();
                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("file", c.file());
                meta.put("line", c.startLine());
                meta.put("pkg", c.pkg());
                if (c.endpoints() != null && !c.endpoints().isEmpty()) {
                    meta.put("endpoints", c.endpoints());
                }
                meta.put("group", "grp:" + c.layer());
                components.put(id, new Architecture.Node(id, c.name(), "component", c.layer(), APP,
                        LAYER_NAME.getOrDefault(c.layer(), c.layer()), meta));
                groupMembers.computeIfAbsent("grp:" + c.layer(), k -> new ArrayList<>()).add(id);
            }
        }
        groupMembers.values().forEach(l -> l.sort((a, b) -> components.get(a).label()
                .compareToIgnoreCase(components.get(b).label())));
        boolean hasRepositories = groupMembers.containsKey("grp:repository");

        // --- outside connections, split by the layer group that makes them --------------------
        Map<String, Architecture.Edge> edges = new LinkedHashMap<>();
        Map<String, String> topics = new LinkedHashMap<>(); // topic -> in | out | both
        Set<String> others = new LinkedHashSet<>();
        Set<String> queues = new LinkedHashSet<>();
        for (Architecture.Edge e : project.edges()) {
            boolean out = e.source().equals(appId);
            if (!out && !e.target().equals(appId)) {
                continue;
            }
            String otherId = out ? e.target() : e.source();
            Architecture.Node other = byId.get(otherId);
            if (other == null) {
                continue;
            }
            // Evidence in the application's own files decides the group; the rest belongs to the other side.
            Map<String, List<ServiceMap.Evidence>> byGroup = new LinkedHashMap<>();
            List<ServiceMap.Evidence> appRest = new ArrayList<>();
            List<ServiceMap.Evidence> otherSide = new ArrayList<>();
            for (ServiceMap.Evidence ev : e.evidence() == null ? List.<ServiceMap.Evidence>of() : e.evidence()) {
                String layer = layerOf(ev.file(), layerByFile);
                if (layer == null) {
                    (inApp(ev.file(), appPath) ? appRest : otherSide).add(ev);
                    continue;
                }
                if ("config".equals(layer)) {
                    appRest.add(ev);
                    continue;
                }
                String group = "grp:" + layer;
                if (hasRepositories && !"repository".equals(layer) && "datastore".equals(other.type())
                        && RELATIONAL.contains(Objects.toString(other.subtype(), ""))) {
                    group = "grp:repository";
                }
                byGroup.computeIfAbsent(group, k -> new ArrayList<>()).add(ev);
            }
            if (byGroup.isEmpty()) {
                byGroup.put(appId, new ArrayList<>(appRest));
            } else {
                byGroup.values().iterator().next().addAll(appRest);
            }

            if ("async".equals(e.kind())) {
                List<String> names = e.items() != null && !e.items().isEmpty() ? e.items()
                        : e.label() != null ? List.of(e.label()) : List.of();
                names.forEach(t -> topics.merge(t, out ? "out" : "in", (a, b) -> a.equals(b) ? a : "both"));
                for (Map.Entry<String, List<ServiceMap.Evidence>> g : byGroup.entrySet()) {
                    add(edges, out ? g.getKey() : KAFKA, out ? KAFKA : g.getKey(), e, names, g.getValue());
                }
                if (!otherId.equals(ArchitectureBuilder.KAFKA)) {
                    others.add(otherId);
                    add(edges, out ? KAFKA : otherId, out ? otherId : KAFKA, e, names, otherSide);
                }
                continue;
            }
            others.add(otherId);
            if ("queue".equals(other.type())) {
                queues.add(otherId);
            }
            for (Map.Entry<String, List<ServiceMap.Evidence>> g : byGroup.entrySet()) {
                List<ServiceMap.Evidence> evs = new ArrayList<>(g.getValue());
                evs.addAll(otherSide);
                add(edges, out ? g.getKey() : otherId, out ? otherId : g.getKey(), e, e.items(), evs);
            }
        }
        // One hop through the queues: who else sends to or reads from them.
        for (Architecture.Edge e : project.edges()) {
            String q = queues.contains(e.source()) ? e.source() : queues.contains(e.target()) ? e.target() : null;
            if (q == null) {
                continue;
            }
            String svc = q.equals(e.source()) ? e.target() : e.source();
            Architecture.Node n = byId.get(svc);
            if (svc.equals(appId) || n == null || !(n.type().equals("service") || n.type().equals("ui"))) {
                continue;
            }
            others.add(svc);
            add(edges, e.source(), e.target(), e, e.items(), e.evidence());
        }

        // --- nodes and columns -----------------------------------------------------------------
        List<Architecture.Node> nodes = new ArrayList<>();
        Map<String, List<String>> columnNodes = new LinkedHashMap<>();
        for (String col : List.of(CONNECTED, "messaging", APP, "data", "external", "cloud")) {
            columnNodes.put(col, new ArrayList<>());
        }
        nodes.add(withColumn(app, APP));
        columnNodes.get(APP).add(appId);
        if (!topics.isEmpty()) {
            List<Map<String, String>> list = new ArrayList<>();
            topics.forEach((t, d) -> list.add(Map.of("name", t, "direction", d)));
            nodes.add(new Architecture.Node(KAFKA, "Apache Kafka", "kafka", "kafka", "messaging",
                    topics.size() == 1 ? "1 topic" : topics.size() + " topics", Map.of("topics", list)));
            columnNodes.get("messaging").add(KAFKA);
        }
        // Keep the project's order inside each column.
        for (Architecture.Column c : project.columns()) {
            for (String id : c.nodes()) {
                if (!others.contains(id)) {
                    continue;
                }
                Architecture.Node n = byId.get(id);
                String col = n.type().equals("service") || n.type().equals("ui") ? CONNECTED : n.column();
                columnNodes.computeIfAbsent(col, k -> new ArrayList<>()).add(id);
                nodes.add(withColumn(n, col));
            }
        }
        List<Architecture.Lane> lanes = new ArrayList<>();
        for (String[] lane : LANES) {
            List<Architecture.Group> groups = new ArrayList<>();
            for (int i = 2; i < lane.length; i++) {
                String[] lt = lane[i].split(":", 2);
                List<String> members = groupMembers.get("grp:" + lt[0]);
                if (members == null) {
                    continue;
                }
                groups.add(new Architecture.Group("grp:" + lt[0], lt[1], members));
                nodes.add(new Architecture.Node("grp:" + lt[0], lt[1], "group", lt[0], APP,
                        members.size() == 1 ? "1 component" : members.size() + " components",
                        Map.of("lane", lane[0])));
            }
            if (!groups.isEmpty()) {
                lanes.add(new Architecture.Lane(lane[0], lane[1], groups));
            }
        }
        nodes.addAll(components.values());
        List<Architecture.Column> columns = new ArrayList<>();
        Map<String, String> titles = new HashMap<>();
        project.columns().forEach(c -> titles.put(c.id(), c.title()));
        titles.put(CONNECTED, "Connected services");
        titles.put(APP, app.label());
        columnNodes.forEach((id, list) -> {
            if (!list.isEmpty()) {
                columns.add(new Architecture.Column(id, titles.getOrDefault(id, id), list));
            }
        });

        // --- inside connections ----------------------------------------------------------------
        List<Architecture.Edge> all = new ArrayList<>(edges.values());
        int outside = all.size();
        if (cmp != null) {
            int i = 0;
            for (ComponentMap.Dependency d : cmp.dependencies()) {
                String s = "cmp:" + d.source();
                String t = "cmp:" + d.target();
                if (components.containsKey(s) && components.containsKey(t)) {
                    all.add(new Architecture.Edge("dep:" + i++, s, t, d.kind(), null, "code", null, null));
                }
            }
        }

        // --- stats -----------------------------------------------------------------------------
        int services = columnNodes.get(CONNECTED).size();
        int stores = columnNodes.get("data").size();
        int messaging = columnNodes.get("messaging").size();
        int externals = columnNodes.get("external").size() + columnNodes.get("cloud").size();
        List<String> notes = new ArrayList<>();
        if (components.isEmpty()) {
            notes.add(app.label() + ": components by layer are shown for Java, Go and Python applications; "
                    + "here every connection attaches to the application");
        } else {
            notes.add(app.label() + ": " + components.size() + " components in " + groupMembers.size() + " layers"
                    + (hiddenConfig == 1 ? ", 1 configuration class not shown"
                            : hiddenConfig > 1 ? ", " + hiddenConfig + " configuration classes not shown" : ""));
        }
        notes.add(count(services, "connected service") + ", " + count(messaging, "messaging box", "messaging boxes")
                + ", " + count(stores, "data store") + ", " + count(externals, "external or cloud service"));
        if (!components.isEmpty()) {
            notes.add("Each connection starts at the layer whose code makes it; connections set up only in "
                    + "configuration start at the application");
        }
        return new Architecture(app.label(), project.workspace(), appId, columns, nodes, all,
                new Architecture.Stats(services, stores, messaging, externals, outside, notes),
                Architecture.APPLICATION, new Architecture.App(appId, lanes));
    }

    private static String count(int n, String one) {
        return count(n, one, one + "s");
    }

    private static String count(int n, String one, String many) {
        return n + " " + (n == 1 ? one : many);
    }

    private static Architecture.Node withColumn(Architecture.Node n, String column) {
        return new Architecture.Node(n.id(), n.label(), n.type(), n.subtype(), column, n.detail(), n.meta());
    }

    /** Layer of the component declared in {@code file}, or null. Paths may be relative to different roots. */
    private static String layerOf(String file, Map<String, String> layerByFile) {
        if (file == null) {
            return null;
        }
        String exact = layerByFile.get(file);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, String> e : layerByFile.entrySet()) {
            if (file.endsWith("/" + e.getKey()) || e.getKey().endsWith("/" + file)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static boolean inApp(String file, String appPath) {
        return file != null && appPath != null && (file.startsWith(appPath + "/") || file.equals(appPath));
    }

    /** Add a connection, merging it into an existing one with the same ends and kind. */
    private static void add(Map<String, Architecture.Edge> edges, String source, String target,
            Architecture.Edge like, List<String> items, List<ServiceMap.Evidence> evidence) {
        String key = source + "|" + like.kind() + "|" + target;
        Architecture.Edge prev = edges.get(key);
        List<String> its = new ArrayList<>();
        List<ServiceMap.Evidence> evs = new ArrayList<>();
        String confidence = like.confidence();
        if (prev != null) {
            if (prev.items() != null) {
                its.addAll(prev.items());
            }
            evs.addAll(prev.evidence() == null ? List.of() : prev.evidence());
            confidence = stronger(prev.confidence(), confidence);
        }
        if (items != null) {
            items.stream().filter(t -> !its.contains(t)).forEach(its::add);
        }
        if (evidence != null) {
            evidence.stream().filter(ev -> !evs.contains(ev)).forEach(evs::add);
        }
        String label = "async".equals(like.kind())
                ? (its.size() == 1 ? its.get(0) : its.size() + " topics")
                : like.label();
        String id = prev != null ? prev.id() : "app-e" + edges.size();
        edges.put(key, new Architecture.Edge(id, source, target, like.kind(), label, confidence,
                its.isEmpty() ? null : its, evs.isEmpty() ? null : evs));
    }

    private static String stronger(String a, String b) {
        int ia = CONFIDENCE.indexOf(a);
        int ib = CONFIDENCE.indexOf(b);
        if (ia < 0) {
            return b;
        }
        if (ib < 0) {
            return a;
        }
        return ia <= ib ? a : b;
    }
}

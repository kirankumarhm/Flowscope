package com.flowscope.mcp.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.flowscope.dto.ServiceMap;
import com.flowscope.dto.ServiceMap.Edge;
import com.flowscope.dto.ServiceMap.Evidence;
import com.flowscope.dto.ServiceMap.Node;
import com.flowscope.dto.ServiceMap.Stats;
import com.flowscope.entity.Graph;
import com.flowscope.entity.IRNode;
import com.flowscope.entity.NodeKind;

/**
 * Agent-sized renderings of FlowScope results. A workspace service map as full
 * JSON easily exceeds agent tool-output limits, so the MCP tools default to a
 * compact text summary (one line per edge, with confidence and the first piece
 * of evidence) and accept filters.
 */
public final class McpFormats {

    private static final int MAX_ENTRY_POINTS = 200;

    private McpFormats() {
    }

    // --- service map filters --------------------------------------------------------

    /**
     * Keep only edges that touch {@code service} (comma-separated names, ids or
     * paths), have one of the {@code kind}s (kafka | rest | soap | datastore | grpc |
     * cloud | trigger | reference, or a raw edge kind) and one of the
     * {@code confidence} levels. Null/blank filters match everything.
     */
    public static ServiceMap filter(ServiceMap map, String service, String kind, String confidence) {
        if (blank(service) && blank(kind) && blank(confidence)) {
            return map;
        }
        Map<String, Node> byId = new HashMap<>();
        map.nodes().forEach(n -> byId.put(n.id(), n));
        Set<String> services = split(service);
        Set<String> kinds = split(kind);
        Set<String> confidences = split(confidence);
        Set<String> serviceIds = new LinkedHashSet<>();
        for (Node n : map.nodes()) {
            if (!services.isEmpty() && (n.type().equals("service") || n.type().equals("ui"))) {
                for (String s : services) {
                    if (n.id().equalsIgnoreCase(s) || n.id().equalsIgnoreCase("svc:" + s)
                            || n.label().equalsIgnoreCase(s) || n.label().toLowerCase(Locale.ROOT).startsWith(s + ":")
                            || (n.path() != null && n.path().equalsIgnoreCase(s))) {
                        serviceIds.add(n.id());
                    }
                }
            }
        }
        List<Edge> edges = new ArrayList<>();
        for (Edge e : map.edges()) {
            if (!services.isEmpty() && !serviceIds.contains(e.source()) && !serviceIds.contains(e.target())) {
                continue;
            }
            if (!kinds.isEmpty() && kinds.stream().noneMatch(k -> kindMatches(k, e, byId))) {
                continue;
            }
            if (!confidences.isEmpty() && (e.confidence() == null || !confidences.contains(e.confidence()))) {
                continue;
            }
            edges.add(e);
        }
        Set<String> keep = new LinkedHashSet<>(serviceIds);
        edges.forEach(e -> {
            keep.add(e.source());
            keep.add(e.target());
        });
        List<Node> nodes = map.nodes().stream().filter(n -> keep.contains(n.id())).toList();
        Stats s = map.stats();
        Stats stats = new Stats(
                (int) nodes.stream().filter(n -> n.type().equals("service") || n.type().equals("ui")).count(),
                (int) nodes.stream().filter(n -> n.type().equals("topic")).count(),
                (int) nodes.stream().filter(n -> n.type().equals("datastore")).count(),
                (int) nodes.stream().filter(n -> n.type().equals("external")).count(),
                edges.size(), filteredNotes(s.notes(), nodes));
        return new ServiceMap(map.workspace(), nodes, edges, stats);
    }

    private static List<String> filteredNotes(List<String> notes, List<Node> nodes) {
        List<String> out = new ArrayList<>();
        if (notes.isEmpty()) {
            return out;
        }
        out.add("(filtered view) " + notes.get(0));
        Set<String> labels = new LinkedHashSet<>();
        nodes.forEach(n -> labels.add(n.label()));
        for (String n : notes.subList(1, notes.size())) {
            if (labels.stream().anyMatch(l -> n.startsWith(l + ":") || n.startsWith(l + " "))) {
                out.add(n);
            }
        }
        return out;
    }

    private static boolean kindMatches(String k, Edge e, Map<String, Node> byId) {
        String other = otherEndType(e, byId);
        return switch (k) {
            case "kafka" -> e.kind().equals("produces") || e.kind().equals("consumes");
            case "rest", "http" -> e.kind().equals("rest") || e.kind().equals("soap") || e.kind().equals("reference");
            case "datastore", "db", "data" -> "datastore".equals(other);
            case "cloud", "sdk" -> e.kind().equals("uses") && "external".equals(other);
            case "trigger", "triggers" -> e.kind().equals("triggers");
            default -> e.kind().equals(k);
        };
    }

    private static String otherEndType(Edge e, Map<String, Node> byId) {
        Node s = byId.get(e.source());
        Node t = byId.get(e.target());
        if (s != null && !s.type().equals("service") && !s.type().equals("ui")) {
            return s.type();
        }
        return t == null ? null : t.type();
    }

    // --- service map text formats ------------------------------------------------------

    /** Counts, notes, then one line per edge: {@code source -kind-> target label [confidence] file:line}. */
    public static String summary(ServiceMap map) {
        Map<String, Node> byId = new HashMap<>();
        map.nodes().forEach(n -> byId.put(n.id(), n));
        Stats s = map.stats();
        StringBuilder sb = new StringBuilder();
        sb.append("FlowScope service map: ").append(map.workspace()).append('\n');
        sb.append(s.services()).append(" services, ").append(s.topics()).append(" topics, ")
                .append(s.datastores()).append(" datastores, ").append(s.externals()).append(" externals, ")
                .append(s.connections()).append(" connections\n");
        Map<String, Integer> byConfidence = new TreeMap<>();
        map.edges().forEach(e -> byConfidence.merge(String.valueOf(e.confidence()), 1, Integer::sum));
        sb.append("Confidence: ").append(byConfidence).append('\n');
        if (s.notes().size() > 1) {
            sb.append("\nNotes:\n");
            for (String n : s.notes().subList(1, s.notes().size())) {
                sb.append("- ").append(n).append('\n');
            }
        }
        sb.append("\nEdges (source -kind-> target label [confidence] evidence; pkg:{a,b} = Lambda handlers a and b;"
                + " Java paths shortened with …, format=json has full paths):\n");
        // Identical edges of several handlers of one Lambda package print as one line.
        Map<String, List<Edge>> groups = new LinkedHashMap<>();
        Map<String, String[]> groupEnds = new HashMap<>();
        List<Edge> edges = new ArrayList<>(map.edges());
        edges.sort((a, b) -> label(byId, a.source()).compareToIgnoreCase(label(byId, b.source())));
        for (Edge e : edges) {
            String[] src = handlerSplit(byId.get(e.source()));
            String[] dst = handlerSplit(byId.get(e.target()));
            String srcKey = src != null && dst == null ? src[0] + ":{}" : label(byId, e.source());
            String dstKey = dst != null && src == null ? dst[0] + ":{}" : label(byId, e.target());
            String key = srcKey + "|" + e.kind() + "|" + dstKey + "|" + e.label() + "|" + e.confidence();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
            groupEnds.putIfAbsent(key, new String[]{srcKey, dstKey});
        }
        for (Map.Entry<String, List<Edge>> g : groups.entrySet()) {
            List<Edge> members = g.getValue();
            Edge e = members.get(0);
            String[] ends = groupEnds.get(g.getKey());
            String source = ends[0].endsWith(":{}") ? handlers(ends[0], members, byId, true) : ends[0];
            String target = ends[1].endsWith(":{}") ? handlers(ends[1], members, byId, false) : ends[1];
            sb.append(source).append(" -").append(e.kind()).append("-> ").append(target);
            String targetLabel = label(byId, e.target());
            if (e.label() != null && !e.label().isBlank() && !e.label().equals(targetLabel)) {
                sb.append(' ').append(e.label());
            }
            sb.append(" [").append(e.confidence()).append(']');
            if (e.evidence() != null && !e.evidence().isEmpty()) {
                Evidence ev = e.evidence().get(0);
                if (ev.file() != null) {
                    sb.append(' ').append(shortPath(ev.file())).append(':').append(ev.line());
                }
            }
            sb.append('\n');
        }
        Set<String> connected = new LinkedHashSet<>();
        map.edges().forEach(e -> {
            connected.add(e.source());
            connected.add(e.target());
        });
        List<String> isolated = map.nodes().stream()
                .filter(n -> (n.type().equals("service") || n.type().equals("ui")) && !connected.contains(n.id()))
                .map(Node::label).toList();
        if (!isolated.isEmpty()) {
            sb.append("\nServices with no detected connections: ").append(String.join(", ", isolated)).append('\n');
        }
        return sb.toString();
    }

    /** A Mermaid flowchart; inferred edges dotted, unresolved placeholders dotted and labelled "?". */
    public static String mermaid(ServiceMap map) {
        Map<String, String> ids = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder("flowchart LR\n");
        int i = 0;
        for (Node n : map.nodes()) {
            String id = "n" + (i++);
            ids.put(n.id(), id);
            String l = esc(n.label());
            String shape = switch (n.type()) {
                case "topic" -> "{{\"" + l + "\"}}";
                case "datastore" -> "[(\"" + l + "\")]";
                case "external" -> "([\"" + l + "\"])";
                case "trigger" -> ">\"" + l + "\"]";
                default -> "[\"" + l + "\"]";
            };
            sb.append("  ").append(id).append(shape).append('\n');
        }
        for (Edge e : map.edges()) {
            String s = ids.get(e.source());
            String t = ids.get(e.target());
            if (s == null || t == null) {
                continue;
            }
            boolean weak = "inferred".equals(e.confidence()) || "unresolved".equals(e.confidence());
            String text = esc(e.kind() + ("unresolved".equals(e.confidence()) ? " ?" : ""));
            sb.append("  ").append(s).append(weak ? " -.->|\"" : " -->|\"").append(text).append("\"| ")
                    .append(t).append('\n');
        }
        return sb.toString();
    }

    // --- architecture summary ------------------------------------------------------------------

    /**
     * Columns with their boxes, then one line per connection. For one application, also its
     * layers with their components; dependencies between components are counted, not listed.
     */
    public static String architectureSummary(com.flowscope.dto.Architecture a) {
        Map<String, com.flowscope.dto.Architecture.Node> byId = new HashMap<>();
        a.nodes().forEach(n -> byId.put(n.id(), n));
        StringBuilder sb = new StringBuilder();
        sb.append("FlowScope architecture (").append(a.scope()).append("): ").append(a.project())
                .append(" (").append(a.workspace()).append(")\n");
        for (String note : a.stats().notes()) {
            sb.append("- ").append(note).append('\n');
        }
        for (com.flowscope.dto.Architecture.Column c : a.columns()) {
            sb.append('\n').append(c.title()).append(" (").append(c.nodes().size()).append("):\n");
            for (String id : c.nodes()) {
                com.flowscope.dto.Architecture.Node n = byId.get(id);
                sb.append("  ").append(id.equals(a.focus()) ? "* " : "- ").append(n.label());
                if (n.detail() != null && !n.detail().isBlank()) {
                    sb.append(" — ").append(n.detail());
                }
                if (n.meta() != null && n.meta().get("topics") instanceof List<?> topics && !topics.isEmpty()) {
                    sb.append(": ").append(String.join(", ", topics.stream()
                            .map(t -> t instanceof Map<?, ?> m ? m.get("direction") + " " + m.get("name") : String.valueOf(t))
                            .toList()));
                }
                sb.append('\n');
            }
        }
        if (a.app() != null) {
            sb.append("\nInside ").append(byId.get(a.app().id()).label()).append(":\n");
            for (com.flowscope.dto.Architecture.Lane lane : a.app().lanes()) {
                sb.append("  ").append(lane.title()).append('\n');
                for (com.flowscope.dto.Architecture.Group g : lane.groups()) {
                    List<String> names = g.nodes().stream().map(id -> byId.get(id).label()).toList();
                    sb.append("    - ").append(g.title()).append(" (").append(names.size()).append("): ")
                            .append(String.join(", ", names)).append('\n');
                }
            }
        }
        long internal = a.edges().stream().filter(e -> e.kind().equals("inject") || e.kind().equals("call")).count();
        sb.append("\nConnections (source -kind-> target label [confidence]):\n");
        for (com.flowscope.dto.Architecture.Edge e : a.edges()) {
            if (e.kind().equals("inject") || e.kind().equals("call")) {
                continue;
            }
            String s = byId.containsKey(e.source()) ? byId.get(e.source()).label() : e.source();
            String t = byId.containsKey(e.target()) ? byId.get(e.target()).label() : e.target();
            sb.append(s).append(" -").append(e.kind()).append("-> ").append(t);
            if (e.label() != null && !e.label().isBlank()) {
                sb.append(' ').append(e.label());
            }
            if (e.items() != null && e.items().size() > 1) {
                sb.append(" (").append(String.join(", ", e.items())).append(')');
            }
            sb.append(" [").append(e.confidence()).append("]\n");
        }
        if (internal > 0) {
            sb.append(internal).append(" dependencies between components not listed\n");
        }
        return sb.toString();
    }

    // --- analyze summary + entry points -------------------------------------------------------

    /** Modules, languages, counts and entry points, instead of the whole IR. */
    public static String analyzeSummary(Graph g) {
        StringBuilder sb = new StringBuilder();
        sb.append("Project: ").append(g.project()).append('\n');
        sb.append("Languages: ").append(String.join(", ", g.languages())).append('\n');
        sb.append("Modules: ").append(String.join(", ", g.modules())).append('\n');
        Map<String, Integer> kinds = new TreeMap<>();
        g.nodes().forEach(n -> kinds.merge(n.kind().json(), 1, Integer::sum));
        sb.append("Nodes: ").append(kinds).append(", edges: ").append(g.edges().size())
                .append(", CFGs: ").append(g.cfgs() == null ? 0 : g.cfgs().size()).append('\n');
        List<String> eps = entryPointLines(g, null);
        sb.append("\nEntry points (").append(eps.size()).append("), use the functionId with flowscope_flow, ")
                .append("flowscope_sequence or flowscope_business_logic:\n");
        appendCapped(sb, eps);
        return sb.toString();
    }

    /**
     * Functions matching {@code query} (case-insensitive substring of the endpoint
     * path, function name, file or id). With no query, every endpoint and main().
     */
    public static String findEntryPoints(Graph g, String query) {
        List<String> lines = entryPointLines(g, blank(query) ? null : query.toLowerCase(Locale.ROOT));
        StringBuilder sb = new StringBuilder();
        sb.append(lines.size()).append(" match").append(lines.size() == 1 ? "" : "es")
                .append(" (VERB /path | name — functionId — file:line):\n");
        appendCapped(sb, lines);
        return sb.toString();
    }

    private static List<String> entryPointLines(Graph g, String query) {
        List<IRNode> endpoints = new ArrayList<>();
        List<IRNode> others = new ArrayList<>();
        for (IRNode n : g.nodes()) {
            if (n.kind() != NodeKind.FUNCTION && n.kind() != NodeKind.ENDPOINT) {
                continue;
            }
            boolean endpoint = n.meta() != null && n.meta().containsKey("endpoint");
            boolean main = n.meta() != null && "main".equals(n.meta().get("entryPoint"));
            if (query == null) {
                if (endpoint || main) {
                    endpoints.add(n);
                }
                continue;
            }
            String hay = (n.name() + " " + n.id() + " " + n.file() + " "
                    + (endpoint ? n.meta().get("httpMethod") + " " + n.meta().get("endpoint") : ""))
                    .toLowerCase(Locale.ROOT);
            if (hay.contains(query)) {
                (endpoint || main ? endpoints : others).add(n);
            }
        }
        List<String> out = new ArrayList<>();
        for (IRNode n : endpoints) {
            out.add(line(n));
        }
        for (IRNode n : others) {
            out.add(line(n));
        }
        return out;
    }

    private static String line(IRNode n) {
        String head;
        if (n.meta() != null && n.meta().containsKey("endpoint")) {
            head = n.meta().get("httpMethod") + " " + n.meta().get("endpoint");
        } else if (n.meta() != null && "main".equals(n.meta().get("entryPoint"))) {
            head = "main " + n.name();
        } else {
            head = n.name();
        }
        return head + " — " + n.id() + " — " + n.file() + ":" + n.startLine();
    }

    private static void appendCapped(StringBuilder sb, List<String> lines) {
        int shown = Math.min(lines.size(), MAX_ENTRY_POINTS);
        for (String l : lines.subList(0, shown)) {
            sb.append("- ").append(l).append('\n');
        }
        if (lines.size() > shown) {
            sb.append("… ").append(lines.size() - shown).append(" more; narrow with flowscope_find_entry_points\n");
        }
    }

    // --- helpers ------------------------------------------------------------------------------

    /** [package label, handler] for a Lambda handler node, else null. */
    private static String[] handlerSplit(Node n) {
        if (n == null || n.meta() == null || !(n.meta().get("handler") instanceof String h)) {
            return null;
        }
        String suffix = ":" + h;
        return n.label().endsWith(suffix)
                ? new String[]{n.label().substring(0, n.label().length() - suffix.length()), h}
                : null;
    }

    /** {@code pkg:h} for one handler, {@code pkg:{a,b}} for several. */
    private static String handlers(String key, List<Edge> members, Map<String, Node> byId, boolean source) {
        String pkg = key.substring(0, key.length() - 3);
        Set<String> names = new LinkedHashSet<>();
        for (Edge e : members) {
            String[] hs = handlerSplit(byId.get(source ? e.source() : e.target()));
            if (hs != null) {
                names.add(hs[1]);
            }
        }
        return names.size() == 1 ? pkg + ":" + names.iterator().next() : pkg + ":{" + String.join(",", names) + "}";
    }

    /** {@code svc/src/main/java/com/acme/app/repo/X.java} → {@code svc/…/repo/X.java}. */
    static String shortPath(String file) {
        for (String marker : List.of("/src/main/java/", "/src/main/kotlin/", "/src/test/java/")) {
            int i = file.indexOf(marker);
            if (i >= 0) {
                String[] tail = file.substring(i + marker.length()).split("/");
                if (tail.length > 2) {
                    return file.substring(0, i) + "/…/" + tail[tail.length - 2] + "/" + tail[tail.length - 1];
                }
            }
        }
        return file;
    }

    private static String label(Map<String, Node> byId, String id) {
        Node n = byId.get(id);
        return n == null ? id : n.label();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\"", "#quot;");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static Set<String> split(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (!blank(s)) {
            Arrays.stream(s.split(",")).map(x -> x.trim().toLowerCase(Locale.ROOT))
                    .filter(x -> !x.isEmpty()).forEach(out::add);
        }
        return out;
    }
}

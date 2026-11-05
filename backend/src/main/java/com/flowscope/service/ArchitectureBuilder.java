package com.flowscope.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.flowscope.dto.Architecture;
import com.flowscope.dto.Architecture.Column;
import com.flowscope.dto.ServiceMap;
import com.flowscope.dto.ServiceMap.Evidence;

/**
 * Turns a workspace {@link ServiceMap} into the whole-project {@link Architecture}:
 *
 * <ul>
 *   <li>one box per deployable service (Lambda handlers fold into their package;
 *       libraries are left out), placed in Front ends, APIs (services that serve
 *       HTTP routes) or Processing;</li>
 *   <li>one box per SQS queue, SNS topic and schedule (Messaging), per table,
 *       bucket, database and keyspace (Data stores), per external system and per
 *       cloud service;</li>
 *   <li>Kafka topics and gRPC services become direct producer → consumer and
 *       client → server connections; a topic whose other side is outside the
 *       workspace connects to one shared "Apache Kafka" box;</li>
 *   <li>placeholders for names that could not be resolved, and link-only URL
 *       references, are left out and counted in the notes.</li>
 * </ul>
 *
 * Boxes in each column are ordered to reduce crossing lines.
 */
public final class ArchitectureBuilder {

    static final String KAFKA = "arch:kafka";

    private static final List<String[]> COLUMNS = List.of(
            new String[]{"frontends", "Front ends"},
            new String[]{"apis", "APIs"},
            new String[]{"processing", "Processing"},
            new String[]{"messaging", "Messaging"},
            new String[]{"data", "Data stores"},
            new String[]{"external", "External systems"},
            new String[]{"cloud", "Cloud services"});

    private static final Map<String, String> RUNTIME = Map.ofEntries(
            Map.entry("spring-boot", "Spring Boot"), Map.entry("node-lambda", "AWS Lambda"),
            Map.entry("node-service", "Node.js"), Map.entry("nestjs", "NestJS"), Map.entry("nextjs", "Next.js"),
            Map.entry("react", "React"), Map.entry("angular", "Angular"), Map.entry("vue", "Vue"),
            Map.entry("go-service", "Go"), Map.entry("python-service", "Python"),
            Map.entry("container", "Container job"));

    private static final Map<String, String> STORE = Map.ofEntries(
            Map.entry("dynamodb", "DynamoDB table"), Map.entry("s3", "S3 bucket"), Map.entry("sqs", "SQS queue"),
            Map.entry("postgresql", "PostgreSQL"), Map.entry("mysql", "MySQL"), Map.entry("mariadb", "MariaDB"),
            Map.entry("oracle", "Oracle"), Map.entry("sqlserver", "SQL Server"), Map.entry("db2", "DB2"),
            Map.entry("sql", "Relational database"), Map.entry("cassandra", "Cassandra keyspace"),
            Map.entry("mongodb", "MongoDB"), Map.entry("redis", "Redis"), Map.entry("elasticsearch", "Elasticsearch"));

    /** Data-store groups, top to bottom: databases first, then AWS stores. */
    private static final List<String> DATA_ORDER = List.of(
            "postgresql", "mysql", "mariadb", "oracle", "sqlserver", "db2", "sql", "cassandra", "mongodb",
            "elasticsearch", "redis", "dynamodb", "s3");

    private static final class Box {
        final String id;
        final String label;
        final String type;
        final String subtype;
        final String column;
        String detail;
        final Map<String, Object> meta = new TreeMap<>();
        final Set<String> handlers = new TreeSet<>();

        Box(String id, String label, String type, String subtype, String column, String detail) {
            this.id = id;
            this.label = label;
            this.type = type;
            this.subtype = subtype;
            this.column = column;
            this.detail = detail;
        }
    }

    private static final class Conn {
        final String source;
        final String target;
        final String kind;
        final Set<String> items = new TreeSet<>();
        String label;
        String confidence;
        final List<Evidence> evidence = new ArrayList<>();

        Conn(String source, String target, String kind) {
            this.source = source;
            this.target = target;
            this.kind = kind;
        }
    }

    private ArchitectureBuilder() {
    }

    /**
     * @param focus   service-map id of the service to highlight (a handler id is fine), or null
     * @param project directory of the project the map was built for
     */
    public static Architecture build(ServiceMap map, String focus, Path project) {
        Map<String, ServiceMap.Node> byId = new HashMap<>();
        map.nodes().forEach(n -> byId.put(n.id(), n));
        Map<String, Box> boxes = new LinkedHashMap<>();
        Map<String, Conn> conns = new LinkedHashMap<>();
        int libraries = 0;
        int hidden = 0;
        int references = 0;

        // --- services -----------------------------------------------------------------
        for (ServiceMap.Node n : map.nodes()) {
            if (!n.type().equals("service") && !n.type().equals("ui")) {
                continue;
            }
            if ("library".equals(n.subtype())) {
                libraries++;
                continue;
            }
            String id = pkg(n.id());
            Map<String, Object> meta = n.meta() == null ? Map.of() : n.meta();
            String label = packageLabel(n);
            Box b = boxes.computeIfAbsent(id, k -> new Box(id, label, n.type(), n.subtype(),
                    column(n, meta), RUNTIME.getOrDefault(n.subtype(), n.subtype())));
            if (meta.get("handler") instanceof String h) {
                b.handlers.add(h);
            }
            if (n.path() != null) {
                b.meta.put("path", n.path());
            }
            if (n.language() != null) {
                b.meta.put("language", n.language());
            }
            if (meta.get("httpRoutes") instanceof Number r && r.intValue() > 0) {
                b.meta.put("httpRoutes", r.intValue());
            }
            if (meta.get("appName") != null) {
                b.meta.put("appName", meta.get("appName"));
            }
        }
        for (Box b : boxes.values()) {
            if (b.handlers.size() > 1) {
                b.detail = b.detail + " · " + b.handlers.size() + " handlers";
            }
            if (!b.handlers.isEmpty()) {
                b.meta.put("handlers", new ArrayList<>(b.handlers));
            }
        }

        // --- connections ------------------------------------------------------------------
        Map<String, Set<String>> producers = new LinkedHashMap<>();
        Map<String, Set<String>> consumers = new LinkedHashMap<>();
        Map<String, Set<String>> grpcClients = new LinkedHashMap<>();
        Map<String, Set<String>> grpcServers = new LinkedHashMap<>();
        Map<String, List<ServiceMap.Edge>> topicEdges = new HashMap<>();
        for (ServiceMap.Edge e : map.edges()) {
            ServiceMap.Node s = byId.get(e.source());
            ServiceMap.Node t = byId.get(e.target());
            if (s == null || t == null) {
                continue;
            }
            String sp = serviceBox(boxes, s);
            String tp = serviceBox(boxes, t);
            if (sp == null && tp == null) {
                continue;
            }
            if (sp != null && tp != null) { // service → service
                if (e.kind().equals("reference")) {
                    references++;
                } else if (!sp.equals(tp)) {
                    add(conns, sp, tp, e.kind(), e.kind().toUpperCase(), null, e);
                }
                continue;
            }
            String svc = sp != null ? sp : tp;
            ServiceMap.Node other = sp != null ? t : s;
            boolean kafkaSource = other.id().equals("ext:self-managed-kafka");
            if (!kafkaSource && (unresolved(other) || "unresolved".equals(e.confidence()))) {
                hidden++;
                continue;
            }
            switch (other.type()) {
                case "topic" -> {
                    if ("sns".equals(other.subtype())) {
                        Box q = box(boxes, other, "queue", "messaging", "SNS topic");
                        add(conns, q.id, svc, "triggers", "SNS", null, e);
                    } else {
                        (sp != null ? producers : consumers).computeIfAbsent(other.id(), k -> new LinkedHashSet<>())
                                .add(svc);
                        topicEdges.computeIfAbsent(other.id(), k -> new ArrayList<>()).add(e);
                    }
                }
                case "grpc" -> (sp != null ? grpcClients : grpcServers)
                        .computeIfAbsent(other.id(), k -> new LinkedHashSet<>()).add(svc);
                case "datastore" -> {
                    boolean queue = "sqs".equals(other.subtype());
                    Box d = box(boxes, other, queue ? "queue" : "datastore", queue ? "messaging" : "data",
                            STORE.getOrDefault(other.subtype(), other.subtype()));
                    if (queue) {
                        boolean consume = e.kind().equals("reads");
                        add(conns, consume ? d.id : svc, consume ? svc : d.id, consume ? "consumes" : "sends",
                                consume ? "consumes" : "sends", null, e);
                    } else {
                        add(conns, svc, d.id, "data:" + e.kind(), e.kind(), null, e);
                    }
                }
                case "trigger" -> {
                    Box tr = box(boxes, other, "trigger", "messaging", "Schedule");
                    add(conns, tr.id, svc, "triggers", "triggers", null, e);
                }
                case "external" -> {
                    if (other.id().equals("ext:self-managed-kafka")) {
                        kafkaBox(boxes);
                        add(conns, KAFKA, svc, "async", "event-source mapping", "event-source mapping", e);
                    } else if ("cloud".equals(other.subtype())) {
                        Box c = box(boxes, other, "cloud", "cloud", "Cloud service");
                        add(conns, svc, c.id, "uses", "SDK", null, e);
                    } else if (e.kind().equals("reference")) {
                        references++;
                    } else {
                        Box x = box(boxes, other, "external", "external",
                                "soap".equals(e.kind()) ? "SOAP service" : "External system");
                        add(conns, svc, x.id, e.kind(), e.kind().toUpperCase(), null, e);
                    }
                }
                default -> {
                }
            }
        }
        // Kafka topics: producer → consumer; an outside side goes through the Kafka box.
        Set<String> topics = new LinkedHashSet<>(producers.keySet());
        topics.addAll(consumers.keySet());
        for (String topic : topics) {
            String name = byId.get(topic).label();
            Set<String> ps = producers.getOrDefault(topic, Set.of());
            Set<String> cs = consumers.getOrDefault(topic, Set.of());
            List<ServiceMap.Edge> evidence = topicEdges.getOrDefault(topic, List.of());
            for (String p : ps.isEmpty() ? Set.of(KAFKA) : ps) {
                for (String c : cs.isEmpty() ? Set.of(KAFKA) : cs) {
                    if (p.equals(c)) {
                        continue;
                    }
                    if (p.equals(KAFKA) || c.equals(KAFKA)) {
                        kafkaBox(boxes);
                    }
                    for (ServiceMap.Edge e : evidence) {
                        if (pkg(e.source()).equals(p) || pkg(e.target()).equals(c)) {
                            add(conns, p, c, "async", null, name, e);
                        }
                    }
                }
            }
        }
        for (String g : grpcClients.keySet()) {
            String name = byId.get(g).label();
            for (String c : grpcClients.get(g)) {
                for (String s : grpcServers.getOrDefault(g, Set.of())) {
                    if (!c.equals(s)) {
                        Conn conn = conns.computeIfAbsent(c + "|" + s + "|grpc", k -> new Conn(c, s, "grpc"));
                        conn.items.add(name);
                        conn.confidence = ServiceMapBuilder.stronger(conn.confidence, "code");
                    }
                }
            }
        }

        // --- finish labels ------------------------------------------------------------------
        List<Architecture.Edge> edges = new ArrayList<>();
        for (Conn c : mergeData(conns).values()) {
            if (!boxes.containsKey(c.source) || !boxes.containsKey(c.target)) {
                continue;
            }
            String label = c.label;
            if (c.kind.equals("async") || c.kind.equals("grpc")) {
                label = c.items.size() == 1 ? c.items.iterator().next() : c.items.size() + " topics";
                if (c.kind.equals("grpc")) {
                    label = "gRPC " + String.join(", ", c.items);
                }
            }
            edges.add(new Architecture.Edge(c.source + "->" + c.target + ":" + c.kind, c.source, c.target, c.kind,
                    label == null ? "" : label, c.confidence, new ArrayList<>(c.items), List.copyOf(c.evidence)));
        }

        // --- columns, ordered to reduce crossings ---------------------------------------------
        String focusBox = focus == null ? null : pkg(focus);
        if (focusBox != null && !boxes.containsKey(focusBox)) {
            focusBox = null;
        }
        List<Column> columns = order(boxes, edges);
        List<Architecture.Node> nodes = new ArrayList<>();
        for (Column col : columns) {
            for (String id : col.nodes()) {
                Box b = boxes.get(id);
                nodes.add(new Architecture.Node(b.id, b.label, b.type, b.subtype, b.column, b.detail, b.meta));
            }
        }

        int services = (int) boxes.values().stream().filter(b -> b.type.equals("service") || b.type.equals("ui"))
                .count();
        int stores = (int) boxes.values().stream().filter(b -> b.column.equals("data")).count();
        int messaging = (int) boxes.values().stream().filter(b -> b.column.equals("messaging")).count();
        int externals = (int) boxes.values().stream()
                .filter(b -> b.column.equals("external") || b.column.equals("cloud")).count();
        List<String> notes = new ArrayList<>();
        String name = project.getFileName() == null ? project.toString() : project.getFileName().toString();
        notes.add(services + " services, " + stores + " data stores, " + messaging + " queues and topics, "
                + externals + " external and cloud services");
        if (focusBox != null) {
            notes.add("Whole project " + name + " shown; " + boxes.get(focusBox).label + " is highlighted");
        }
        if (hidden > 0) {
            notes.add(hidden + " connections to names FlowScope could not resolve are left out (see the Service Map)");
        }
        if (references > 0) {
            notes.add(references + " URLs that are only used to build links are left out");
        }
        if (libraries > 0) {
            notes.add(libraries + " shared libraries are left out; their code runs inside the services using them");
        }
        return new Architecture(name, project.toString(), focusBox, columns, nodes, edges,
                new Architecture.Stats(services, stores, messaging, externals, edges.size(), notes),
                Architecture.PROJECT, null);
    }

    // --- helpers --------------------------------------------------------------------------

    /** The service-map id of a handler node's package ({@code svc:x#h} → {@code svc:x}). */
    static String pkg(String id) {
        int hash = id.indexOf('#');
        return id.startsWith("svc:") && hash > 0 ? id.substring(0, hash) : id;
    }

    private static String packageLabel(ServiceMap.Node n) {
        Object h = n.meta() == null ? null : n.meta().get("handler");
        String suffix = h == null ? null : ":" + h;
        return suffix != null && n.label().endsWith(suffix)
                ? n.label().substring(0, n.label().length() - suffix.length()) : n.label();
    }

    private static String column(ServiceMap.Node n, Map<String, Object> meta) {
        if (n.type().equals("ui")) {
            return "frontends";
        }
        boolean routes = meta.get("httpRoutes") instanceof Number r && r.intValue() > 0;
        return routes && !"node-lambda".equals(n.subtype()) ? "apis" : "processing";
    }

    private static String serviceBox(Map<String, Box> boxes, ServiceMap.Node n) {
        if (!n.type().equals("service") && !n.type().equals("ui")) {
            return null;
        }
        String id = pkg(n.id());
        return boxes.containsKey(id) ? id : null;
    }

    private static boolean unresolved(ServiceMap.Node n) {
        return n.meta() != null && Boolean.TRUE.equals(n.meta().get("unresolved"));
    }

    private static Box box(Map<String, Box> boxes, ServiceMap.Node n, String type, String column, String detail) {
        return boxes.computeIfAbsent(n.id(), k -> new Box(n.id(), n.label(), type, n.subtype(), column, detail));
    }

    private static void kafkaBox(Map<String, Box> boxes) {
        boxes.computeIfAbsent(KAFKA, k -> {
            Box b = new Box(KAFKA, "Apache Kafka", "kafka", "kafka", "messaging",
                    "topics shared with systems outside this project");
            b.meta.put("note", "Producers or consumers of these topics are not in the scanned workspace");
            return b;
        });
    }

    private static void add(Map<String, Conn> conns, String from, String to, String kind, String label,
                            String item, ServiceMap.Edge e) {
        Conn c = conns.computeIfAbsent(from + "|" + to + "|" + kind, k -> new Conn(from, to, kind));
        if (c.label == null) {
            c.label = label;
        }
        if (item != null) {
            c.items.add(item);
        }
        c.confidence = c.confidence == null ? e.confidence() : ServiceMapBuilder.stronger(c.confidence, e.confidence());
        if (e.evidence() != null) {
            for (Evidence ev : e.evidence()) {
                ServiceMapBuilder.addEvidence(c.evidence, ev);
            }
        }
    }

    /** Service → store connections ({@code data:reads}, {@code data:writes}, …) become one
     *  arrow per pair: reads, writes, read/write or uses. */
    private static Map<String, Conn> mergeData(Map<String, Conn> conns) {
        Map<String, Conn> result = new LinkedHashMap<>();
        Map<String, Set<String>> modes = new LinkedHashMap<>();
        Map<String, Conn> data = new LinkedHashMap<>();
        for (Conn c : conns.values()) {
            if (!c.kind.startsWith("data:")) {
                result.put(c.source + "|" + c.target + "|" + c.kind, c);
                continue;
            }
            String key = c.source + "|" + c.target;
            modes.computeIfAbsent(key, k -> new TreeSet<>()).add(c.kind.substring(5));
            Conn m = data.computeIfAbsent(key, k -> new Conn(c.source, c.target, "data"));
            m.confidence = m.confidence == null ? c.confidence : ServiceMapBuilder.stronger(m.confidence, c.confidence);
            c.evidence.forEach(ev -> ServiceMapBuilder.addEvidence(m.evidence, ev));
        }
        for (Map.Entry<String, Conn> e : data.entrySet()) {
            Set<String> m = modes.get(e.getKey());
            String kind = m.contains("reads") && m.contains("writes") ? "readwrite"
                    : m.contains("writes") ? "writes" : m.contains("reads") ? "reads" : "uses";
            Conn c = e.getValue();
            Conn merged = new Conn(c.source, c.target, kind);
            merged.label = kind.equals("readwrite") ? "read/write" : kind;
            merged.confidence = c.confidence;
            merged.evidence.addAll(c.evidence);
            result.put(c.source + "|" + c.target + "|" + kind, merged);
        }
        return result;
    }

    /** Columns in fixed order, boxes ordered by the average position of their neighbours. */
    private static List<Column> order(Map<String, Box> boxes, List<Architecture.Edge> edges) {
        Map<String, List<String>> byCol = new LinkedHashMap<>();
        for (String[] c : COLUMNS) {
            byCol.put(c[0], new ArrayList<>());
        }
        boxes.values().stream()
                .sorted(Comparator.comparing((Box b) -> group(b)).thenComparing(b -> b.label.toLowerCase()))
                .forEach(b -> byCol.get(b.column).add(b.id));
        Map<String, Set<String>> nbrs = new HashMap<>();
        for (Architecture.Edge e : edges) {
            nbrs.computeIfAbsent(e.source(), k -> new LinkedHashSet<>()).add(e.target());
            nbrs.computeIfAbsent(e.target(), k -> new LinkedHashSet<>()).add(e.source());
        }
        for (int pass = 0; pass < 8; pass++) {
            Map<String, Double> pos = new HashMap<>();
            for (List<String> ids : byCol.values()) {
                for (int i = 0; i < ids.size(); i++) {
                    pos.put(ids.get(i), ids.size() <= 1 ? 0.5 : i / (double) (ids.size() - 1));
                }
            }
            for (Map.Entry<String, List<String>> col : byCol.entrySet()) {
                List<String> ids = col.getValue();
                Map<String, Double> key = new HashMap<>();
                for (String id : ids) {
                    double sum = 0;
                    int n = 0;
                    for (String o : nbrs.getOrDefault(id, Set.of())) {
                        Box ob = boxes.get(o);
                        if (ob != null && !ob.column.equals(col.getKey())) {
                            sum += pos.get(o);
                            n++;
                        }
                    }
                    key.put(id, n == 0 ? pos.get(id) : sum / n);
                }
                ids.sort(Comparator.comparing((String id) -> group(boxes.get(id))).thenComparing(key::get));
            }
        }
        List<Column> out = new ArrayList<>();
        for (String[] c : COLUMNS) {
            List<String> ids = byCol.get(c[0]);
            if (!ids.isEmpty()) {
                out.add(new Column(c[0], c[1], ids));
            }
        }
        return out;
    }

    /** Sort group inside a column: data stores by technology, Kafka box first in Messaging. */
    private static int group(Box b) {
        if (b.column.equals("data")) {
            int i = DATA_ORDER.indexOf(Objects.toString(b.subtype, ""));
            return i < 0 ? DATA_ORDER.size() : i;
        }
        if (b.column.equals("messaging")) {
            return b.type.equals("kafka") ? 0 : b.type.equals("queue") ? 1 : 2;
        }
        return 0;
    }
}

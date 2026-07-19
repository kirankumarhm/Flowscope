package com.flowscope.servicemap;

import com.flowscope.servicemap.ServiceMap.Edge;
import com.flowscope.servicemap.ServiceMap.Node;
import com.flowscope.servicemap.ServiceMap.Stats;
import com.flowscope.servicemap.WorkspaceScanner.Service;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the whole-workspace {@link ServiceMap} by scanning every service for
 * communication primitives and wiring them together:
 *
 * <ul>
 *   <li><b>Kafka</b> — producer→topic→consumer, matched on the resolved topic name;</li>
 *   <li><b>REST</b> — outbound base URLs resolved to an internal service (host token
 *       match) or an external system;</li>
 *   <li><b>Datastores</b> — DynamoDB tables, S3 buckets, SQS queues;</li>
 *   <li><b>External systems</b> — third-party integrations (NSL, Effie, Apple, …).</li>
 * </ul>
 *
 * Detection is deliberately pattern-based (not full ASTs), so it works uniformly
 * across Java and Node/TypeScript.
 */
public final class ServiceMapBuilder {

    /** Known third-party systems keyed by a substring found in host or config key. */
    private static final Map<String, String> EXTERNALS = new LinkedHashMap<>();
    static {
        EXTERNALS.put("appattest", "Apple DeviceCheck");
        EXTERNALS.put("apple", "Apple DeviceCheck");
        EXTERNALS.put("playintegrity", "Google Play Integrity");
        EXTERNALS.put("googleapis", "Google Play Integrity");
        EXTERNALS.put("firebaseio", "Firebase FCM");
        EXTERNALS.put("firebase", "Firebase FCM");
        EXTERNALS.put("fcm", "Firebase FCM");
        EXTERNALS.put("techmobile", "Techmobile (Nokia)");
        EXTERNALS.put("nokia", "Techmobile (Nokia)");
        EXTERNALS.put("effie", "Effie GraphQL");
        EXTERNALS.put("pinxt", "PiNXT / MDPO");
        EXTERNALS.put("mdpo", "PiNXT / MDPO");
        EXTERNALS.put("nsl", "NSL");
        EXTERNALS.put("scp", "SCP");
        EXTERNALS.put("rr.com", "AAA Auth DB");
        EXTERNALS.put("smartsheet", "Smartsheet");
        EXTERNALS.put("datadoghq", "Datadog");
        EXTERNALS.put("cognito", "AWS Cognito");
    }

    private static final Pattern URL_RE = Pattern.compile("https?://([A-Za-z0-9._-]+)");
    private static final Pattern KAFKA_SEND_RE = Pattern.compile(
            "(?:new\\s+ProducerRecord\\s*<[^>]*>\\s*\\(|new\\s+ProducerRecord\\s*\\(|\\.send\\s*\\()\\s*([^,)\\s][^,)]*)");
    // The annotation body may contain parentheses (e.g. SpEL ".split(',')"), so we
    // anchor the closing ')' to the method declaration that follows it rather than
    // the first ')' encountered.
    private static final Pattern KAFKA_LISTENER_RE = Pattern.compile(
            "@KafkaListener\\s*\\(([\\s\\S]*?)\\)\\s*(?:public|private|protected|final|static|void|@)",
            Pattern.MULTILINE);
    private static final Pattern TOPICS_ATTR_RE = Pattern.compile(
            "topics\\s*=\\s*(\\{[^}]*}|\"[^\"]*\"|#\\{[^}]*}[^,)]*|[A-Za-z0-9_.]+)");
    private static final Pattern NODE_TOPIC_RE = Pattern.compile(
            "topic\\s*:\\s*(\"[^\"]*\"|'[^']*'|[A-Za-z0-9_.]+)");

    private ServiceMapBuilder() {
    }

    /** An outbound host reference and where it came from. */
    private record HostRef(String host, String key, boolean literal) {
    }

    /** Per-service scan result of everything it produces/consumes/calls/stores. */
    private static final class Scan {
        final Service svc;
        final ConfigIndex cfg;
        final Set<String> produces = new LinkedHashSet<>();
        final Set<String> consumes = new LinkedHashSet<>();
        final List<HostRef> hosts = new ArrayList<>();
        // datastore kind -> set of resource names
        final Map<String, Set<String>> stores = new LinkedHashMap<>();
        boolean writesData;

        Scan(Service svc, ConfigIndex cfg) {
            this.svc = svc;
            this.cfg = cfg;
        }

        void store(String kind, String name) {
            if (name != null && !name.isBlank()) {
                String n = name.trim();
                // Reject numbers/flags (e.g. a TTL "365") and non-resource values.
                if (n.length() >= 3 && !n.matches("\\d+") && !n.equalsIgnoreCase("true")
                        && !n.equalsIgnoreCase("false") && !n.contains("${")) {
                    stores.computeIfAbsent(kind, k -> new LinkedHashSet<>()).add(n);
                }
            }
        }
    }

    /** A single service's outbound communication, for the Architecture view. */
    public record ServiceComms(
            Set<String> produces,
            Set<String> consumes,
            Map<String, Set<String>> stores,
            List<String> externals) {
    }

    /** Scan one application directory for its Kafka/datastore/external usage. */
    public static ServiceComms comms(Path dir) {
        String name = dir.getFileName() != null ? dir.getFileName().toString() : dir.toString();
        Service svc = new Service("", name, name, name, dir, "java", "spring-boot", false);
        Scan scan = new Scan(svc, ConfigIndex.forService(dir));
        scanKafka(scan);
        scanDatastores(scan);
        scanUrls(scan);
        LinkedHashSet<String> ext = new LinkedHashSet<>();
        for (HostRef ref : scan.hosts) {
            String extName = ref.literal()
                    ? classifyKnownExternal(ref.host())
                    : classifyExternal(ref.host(), ref.key());
            if (extName != null) {
                ext.add(extName);
            }
        }
        return new ServiceComms(scan.produces, scan.consumes, scan.stores, new ArrayList<>(ext));
    }

    public static ServiceMap build(Path workspace, Set<String> excludeDirs) {
        List<Service> services = WorkspaceScanner.scan(workspace, excludeDirs);

        // 1. Scan each service.
        List<Scan> scans = new ArrayList<>();
        for (Service svc : services) {
            Scan scan = new Scan(svc, ConfigIndex.forService(svc.dir));
            scanKafka(scan);
            scanDatastores(scan);
            scanUrls(scan);
            scans.add(scan);
        }

        // 2. Assemble nodes + edges.
        Map<String, Node> nodes = new LinkedHashMap<>();
        List<Edge> edges = new ArrayList<>();
        List<String> notes = new ArrayList<>();

        Map<String, Service> byId = new HashMap<>();
        for (Service s : services) {
            byId.put(svcId(s), s);
        }
        // Token index for resolving REST hosts to internal services.
        Map<String, String> tokenToSvc = buildTokenIndex(services);

        // Service nodes (skip pure libraries with no comm at all — keeps the map clean).
        for (Scan sc : scans) {
            Service s = sc.svc;
            boolean isolated = sc.produces.isEmpty() && sc.consumes.isEmpty()
                    && sc.hosts.isEmpty() && sc.stores.isEmpty();
            if (s.library && isolated) {
                continue;
            }
            Map<String, Object> meta = new TreeMap<>();
            meta.put("appName", s.appName);
            meta.put("produces", sc.produces.size());
            meta.put("consumes", sc.consumes.size());
            meta.put("runtime", s.subtype);
            nodes.put(svcId(s), new Node(svcId(s), s.displayName, s.subtype.equals("react") ? "ui" : "service",
                    s.subtype, s.language, s.id, meta));
        }

        // Kafka topic nodes + produce/consume edges.
        Set<String> allTopics = new LinkedHashSet<>();
        for (Scan sc : scans) {
            allTopics.addAll(sc.produces);
            allTopics.addAll(sc.consumes);
        }
        for (String topic : allTopics) {
            String tid = "topic:" + topic;
            nodes.putIfAbsent(tid, new Node(tid, topic, "topic", "kafka", null, null,
                    Map.of("kind", "Kafka topic")));
        }
        for (Scan sc : scans) {
            if (!nodes.containsKey(svcId(sc.svc))) {
                continue;
            }
            for (String topic : sc.produces) {
                edges.add(new Edge(edgeId(svcId(sc.svc), "topic:" + topic, "produces"),
                        svcId(sc.svc), "topic:" + topic, "produces", topic));
            }
            for (String topic : sc.consumes) {
                edges.add(new Edge(edgeId("topic:" + topic, svcId(sc.svc), "consumes"),
                        "topic:" + topic, svcId(sc.svc), "consumes", topic));
            }
        }

        // Datastore nodes + edges (oriented by the service's read/write posture).
        for (Scan sc : scans) {
            if (!nodes.containsKey(svcId(sc.svc))) {
                continue;
            }
            for (Map.Entry<String, Set<String>> e : sc.stores.entrySet()) {
                for (String name : e.getValue()) {
                    String did = "ds:" + e.getKey() + ":" + name;
                    nodes.putIfAbsent(did, new Node(did, name, "datastore", e.getKey(), null, null,
                            Map.of("kind", storeLabel(e.getKey()))));
                    if (sc.writesData) {
                        edges.add(new Edge(edgeId(svcId(sc.svc), did, "writes"),
                                svcId(sc.svc), did, "writes", ""));
                    } else {
                        edges.add(new Edge(edgeId(did, svcId(sc.svc), "reads"),
                                did, svcId(sc.svc), "reads", ""));
                    }
                }
            }
        }

        // REST / external edges.
        for (Scan sc : scans) {
            if (!nodes.containsKey(svcId(sc.svc))) {
                continue;
            }
            for (HostRef ref : sc.hosts) {
                // A literal URL in source is only trusted when it names a known external.
                if (ref.literal()) {
                    addExternalEdge(nodes, edges, sc, classifyKnownExternal(ref.host()), ref.host());
                    continue;
                }
                String targetSvc = matchService(ref.host(), tokenToSvc, sc.svc);
                if (targetSvc != null && nodes.containsKey(targetSvc)) {
                    edges.add(new Edge(edgeId(svcId(sc.svc), targetSvc, "rest"),
                            svcId(sc.svc), targetSvc, "rest", "REST"));
                    continue;
                }
                addExternalEdge(nodes, edges, sc, classifyExternal(ref.host(), ref.key()), ref.host());
            }
        }

        // Dedupe edges (same source/target/kind).
        List<Edge> deduped = dedupeEdges(edges);

        int topicCount = (int) nodes.values().stream().filter(n -> n.type().equals("topic")).count();
        int dsCount = (int) nodes.values().stream().filter(n -> n.type().equals("datastore")).count();
        int extCount = (int) nodes.values().stream().filter(n -> n.type().equals("external")).count();
        int svcCount = (int) nodes.values().stream()
                .filter(n -> n.type().equals("service") || n.type().equals("ui")).count();

        notes.add(svcCount + " services, " + topicCount + " Kafka topics, "
                + dsCount + " datastores, " + extCount + " external systems detected");

        Stats stats = new Stats(svcCount, topicCount, dsCount, extCount, deduped.size(), notes);
        return new ServiceMap(workspace.toString(), new ArrayList<>(nodes.values()), deduped, stats);
    }

    // --- scanning ----------------------------------------------------------

    private static void scanKafka(Scan scan) {
        forEachSourceFile(scan.svc, (path, text, isTest) -> {
            if (isTest) {
                return;
            }
            String name = path.getFileName().toString();
            boolean java = name.endsWith(".java");
            boolean node = name.endsWith(".ts") || name.endsWith(".js");

            if (java) {
                boolean producer = text.contains("KafkaTemplate") || text.contains("ProducerRecord");
                if (producer) {
                    Matcher m = KAFKA_SEND_RE.matcher(text);
                    while (m.find()) {
                        for (String t : scan.cfg.resolve(m.group(1))) {
                            if (looksLikeTopic(t)) {
                                scan.produces.add(t);
                            }
                        }
                    }
                }
                Matcher l = KAFKA_LISTENER_RE.matcher(text);
                while (l.find()) {
                    Matcher ta = TOPICS_ATTR_RE.matcher(l.group(1));
                    if (ta.find()) {
                        for (String t : resolveTopicsAttr(scan.cfg, ta.group(1))) {
                            if (looksLikeTopic(t)) {
                                scan.consumes.add(t);
                            }
                        }
                    }
                }
            } else if (node) {
                boolean consumer = text.contains(".subscribe(") || text.contains("eachMessage")
                        || text.contains("createConsumer");
                boolean producer = text.contains("producer") || text.contains(".send(");
                Matcher m = NODE_TOPIC_RE.matcher(text);
                while (m.find()) {
                    for (String t : scan.cfg.resolve(m.group(1))) {
                        if (!looksLikeTopic(t)) {
                            continue;
                        }
                        if (consumer) {
                            scan.consumes.add(t);
                        } else if (producer) {
                            scan.produces.add(t);
                        }
                    }
                }
            }
        });
    }

    private static void scanDatastores(Scan scan) {
        ConfigIndex cfg = scan.cfg;
        // Java: from config properties.
        for (String[] kv : cfg.matching(cfg.props(), "dynamodb\\.table\\.")) {
            scan.store("dynamodb", kv[1]);
        }
        for (String[] kv : cfg.matching(cfg.props(), "aws\\.queues\\.|\\.queue$|\\.queue\\.")) {
            scan.store("sqs", kv[1]);
        }
        for (String[] kv : cfg.matching(cfg.props(), "\\.bucket|s3\\.")) {
            if (looksLikeName(kv[1])) {
                scan.store("s3", kv[1]);
            }
        }
        // Node: from env variables.
        for (String[] kv : cfg.matching(cfg.env(), "DYNAMODB_TABLE|_TABLE$|TABLE_NAME")) {
            scan.store("dynamodb", kv[1]);
        }
        for (String[] kv : cfg.matching(cfg.env(), "BUCKET")) {
            scan.store("s3", kv[1]);
        }
        for (String[] kv : cfg.matching(cfg.env(), "QUEUE_URL|_QUEUE|QUEUE_NAME")) {
            scan.store("sqs", queueName(kv[1]));
        }
        // Determine read vs write posture from code verbs (cheap, service-level).
        forEachSourceFile(scan.svc, (path, text, isTest) -> {
            if (isTest || scan.writesData) {
                return;
            }
            if (Pattern.compile("\\.(save|put|putItem|update|updateItem|insert|delete|deleteItem|"
                    + "write|send|publish)\\s*\\(|PutObjectCommand|PutItemCommand|SendMessageCommand")
                    .matcher(text).find()) {
                scan.writesData = true;
            }
        });
    }

    private static void scanUrls(Scan scan) {
        ConfigIndex cfg = scan.cfg;
        // Outbound base URLs from config (Java + Node). Only genuine "base URL"
        // keys — never Spring management/actuator or generic flags.
        for (String[] kv : cfg.matching(cfg.props(), "(base-url|baseurl|base_url)")) {
            addConfigHost(scan, kv[1], kv[0]);
        }
        for (String[] kv : cfg.matching(cfg.env(), "(BASEURL|BASE_URL|_ENDPOINT$|_HOST$)")) {
            addConfigHost(scan, kv[1], kv[0]);
        }
        // Literal URLs hard-coded in source (e.g. Apple/Google endpoints).
        forEachSourceFile(scan.svc, (path, text, isTest) -> {
            if (isTest) {
                return;
            }
            Matcher m = URL_RE.matcher(text);
            while (m.find()) {
                String host = m.group(1).toLowerCase(Locale.ROOT);
                if (!isInfraHost(host)) {
                    scan.hosts.add(new HostRef(host, null, true));
                }
            }
        });
    }

    /** Register an outbound host from a config base-url value + its key. */
    private static void addConfigHost(Scan scan, String value, String key) {
        String host = null;
        if (value != null) {
            Matcher m = URL_RE.matcher(value);
            if (m.find()) {
                host = m.group(1).toLowerCase(Locale.ROOT);
            } else if (value.contains(".") && !value.contains(" ")) {
                host = value.toLowerCase(Locale.ROOT); // bare hostname value
            }
        }
        // Fall back to the config key (base URL is often a localhost default in git).
        if (host == null || isInfraHost(host)) {
            host = null;
        }
        scan.hosts.add(new HostRef(host, key == null ? null : key.toLowerCase(Locale.ROOT), false));
    }

    // --- resolution helpers ------------------------------------------------

    private static List<String> resolveTopicsAttr(ConfigIndex cfg, String attr) {
        // attr may be {A, B}, "literal", #{'${prop:def}'.split(',')}, or IDENT.
        String a = attr.trim();
        if (a.startsWith("{") && a.endsWith("}")) {
            List<String> out = new ArrayList<>();
            for (String part : a.substring(1, a.length() - 1).split(",")) {
                out.addAll(cfg.resolve(part.trim()));
            }
            return out;
        }
        return cfg.resolve(a);
    }

    /** Config-key segments too generic to name an external system. */
    private static final Set<String> GENERIC_KEYS = Set.of(
            "management", "spring", "server", "logging", "cloud", "eureka", "feign",
            "ribbon", "endpoints", "endpoint", "base", "url", "api", "app", "http",
            "client", "service", "config", "aws", "kafka", "datasource", "db", "host",
            "gateway", "internal", "external");

    private static final String NON_ALNUM = "[^a-z0-9]+";

    /** A token index mapping distinctive service name tokens to service ids
     *  (libraries excluded — they are not REST endpoints). */
    private static Map<String, String> buildTokenIndex(List<Service> services) {
        Map<String, String> index = new HashMap<>();
        for (Service s : services) {
            if (s.library) {
                continue;
            }
            for (String tok : tokensOf(s.dirName)) {
                index.putIfAbsent(tok, svcId(s));
            }
        }
        return index;
    }

    private static Set<String> tokensOf(String name) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : name.toLowerCase(Locale.ROOT).split(NON_ALNUM)) {
            // Distinctive domain tokens only; skip generic scaffolding words.
            if (part.length() >= 4 && !GENERIC_KEYS.contains(part)) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    /** Match a REST host to another internal service by shared domain token. */
    private static String matchService(String host, Map<String, String> tokenToSvc, Service self) {
        if (host == null) {
            return null;
        }
        for (String label : host.split(NON_ALNUM)) {
            String svc = tokenToSvc.get(label);
            if (svc != null && !svc.equals(svcId(self))) {
                return svc;
            }
        }
        return null;
    }

    /** Dictionary lookup only — used for literal URLs (avoids trusting stray links). */
    private static String classifyKnownExternal(String host) {
        if (host == null) {
            return null;
        }
        for (Map.Entry<String, String> e : EXTERNALS.entrySet()) {
            if (host.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Classify a config base-url host (or, if the value was localhost, its key). */
    private static String classifyExternal(String host, String key) {
        String known = classifyKnownExternal(host);
        if (known != null) {
            return known;
        }
        if (key != null) {
            String byKey = classifyKnownExternal(key);
            if (byKey != null) {
                return byKey;
            }
        }
        // A real external hostname we don't specifically recognise.
        if (host != null && host.contains(".") && !isInfraHost(host)) {
            String pd = prettyDomain(host);
            if (!GENERIC_KEYS.contains(pd)
                    && !Set.of("spectrum", "net", "com", "www", "wtg", "prod").contains(pd)) {
                return pd;
            }
        }
        // Otherwise name it from the first distinctive segment of the config key.
        if (key != null) {
            for (String seg : key.split(NON_ALNUM)) {
                if (seg.length() >= 3 && !GENERIC_KEYS.contains(seg)) {
                    return seg.toUpperCase(Locale.ROOT);
                }
            }
        }
        return null;
    }

    private static String prettyDomain(String host) {
        String[] parts = host.split("\\.");
        if (parts.length >= 2) {
            return parts[parts.length - 2];
        }
        return host;
    }

    /** Infrastructure/loopback hosts that are not meaningful external systems. */
    private static boolean isInfraHost(String host) {
        return host == null || host.isBlank() || host.contains("localhost")
                || host.startsWith("127.") || host.startsWith("0.0.0.0")
                || host.contains("example.com") || host.contains("amazonaws")
                || host.endsWith(".rds") || host.contains("spectrum.net") && host.contains("kafka");
    }

    /** Add (or reuse) an external node and an edge to it; no-op if unclassified. */
    private static void addExternalEdge(Map<String, Node> nodes, List<Edge> edges,
                                        Scan sc, String extName, String host) {
        if (extName == null || extName.isBlank()) {
            return;
        }
        String eid = "ext:" + slug(extName);
        nodes.putIfAbsent(eid, new Node(eid, extName, "external", "http", null, null,
                Map.of("kind", "External system")));
        edges.add(new Edge(edgeId(svcId(sc.svc), eid, "rest"),
                svcId(sc.svc), eid, "rest", host == null ? "" : host));
    }

    // --- small utilities ---------------------------------------------------

    private static boolean looksLikeTopic(String s) {
        return s != null && s.length() >= 3 && s.length() <= 120
                && !s.contains("/") && !s.contains(" ") && !s.startsWith("${");
    }

    private static boolean looksLikeName(String s) {
        return s != null && s.length() >= 3 && !s.contains("/") && !s.startsWith("${");
    }

    private static String queueName(String urlOrName) {
        if (urlOrName == null) {
            return null;
        }
        int slash = urlOrName.lastIndexOf('/');
        return slash >= 0 ? urlOrName.substring(slash + 1) : urlOrName;
    }

    private static String storeLabel(String kind) {
        return switch (kind) {
            case "dynamodb" -> "DynamoDB table";
            case "s3" -> "S3 bucket";
            case "sqs" -> "SQS queue";
            default -> kind;
        };
    }

    private static List<Edge> dedupeEdges(List<Edge> edges) {
        Map<String, Edge> seen = new LinkedHashMap<>();
        for (Edge e : edges) {
            seen.putIfAbsent(e.source() + "|" + e.target() + "|" + e.kind(), e);
        }
        return new ArrayList<>(seen.values());
    }

    private static String svcId(Service s) {
        return "svc:" + s.id;
    }

    private static String edgeId(String from, String to, String kind) {
        return from + "->" + to + ":" + kind;
    }

    private static String slug(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    // --- file walking ------------------------------------------------------

    private interface FileVisitor {
        void visit(Path path, String text, boolean isTest);
    }

    private static void forEachSourceFile(Service svc, FileVisitor visitor) {
        Path base = svc.dir.resolve("src");
        if (!Files.isDirectory(base)) {
            base = svc.dir;
        }
        WorkspaceScanner.walkSourceFiles(base,
                name -> (name.endsWith(".java") || name.endsWith(".ts") || name.endsWith(".js"))
                        && !name.endsWith(".d.ts"),
                p -> {
                    String path = p.toString().replace('\\', '/');
                    String name = p.getFileName().toString();
                    boolean isTest = path.contains("/test/") || path.contains("/tests/")
                            || path.contains("/__tests__/") || name.contains(".test.")
                            || name.contains(".spec.") || name.endsWith("Test.java")
                            || name.endsWith("Tests.java") || name.endsWith("IT.java");
                    visitor.visit(p, WorkspaceScanner.read(p), isTest);
                });
    }
}

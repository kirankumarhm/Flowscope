package com.flowscope.service;

import com.flowscope.dto.ServiceMap.Edge;
import com.flowscope.dto.ServiceMap.Node;
import com.flowscope.dto.ServiceMap.Stats;
import com.flowscope.service.WorkspaceScanner.Service;
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
import com.flowscope.dto.ServiceMap;

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
    // Node services rarely inline the topic; they bind it to a local const / class
    // field first: `const kafkaTopic = process.env.KAFKA_TOPIC` or
    // `this.kafkaTopic = process.env.KAFKA_TOPIC_MSO`, then reference `topic: kafkaTopic`.
    // Capture ident -> env-var so a topic token can be chased back to config.
    private static final Pattern NODE_ENV_BIND_RE = Pattern.compile(
            "(?:(?:const|let|var)\\s+|this\\.)(\\w+)\\s*(?::\\s*[\\w<>\\[\\], ]+?)?\\s*=\\s*process\\.env\\.(\\w+)");
    // A Lambda handler typed with one of these aws-lambda events is triggered by a
    // Kafka event-source mapping — i.e. it CONSUMES a topic. The topic itself is set
    // by the (external) event-source mapping, not the code, so it isn't recoverable
    // here; we record the service as an external-Kafka consumer instead.
    private static final Pattern LAMBDA_KAFKA_EVENT_RE = Pattern.compile(
            ":\\s*(?:SelfManagedKafkaEvent|MSKEvent|KafkaEvent)\\b");

    // --- Go (confluent-kafka-go, Sarama, segmentio/kafka-go) --------------
    // Topic in a struct literal: kafka.TopicPartition{Topic: &topic}, sarama
    // .ProducerMessage{Topic: "x"}, kafka.ReaderConfig{Topic: "x"}, WriterConfig{...}.
    private static final Pattern GO_TOPIC_FIELD_RE = Pattern.compile(
            "Topic\\s*:\\s*(&?\"[^\"]*\"|&?[A-Za-z_][\\w.]*)");
    // Consumer subscription: c.SubscribeTopics([]string{a, b}, ...).
    private static final Pattern GO_SUBSCRIBE_RE = Pattern.compile(
            "SubscribeTopics\\s*\\(\\s*\\[\\]string\\s*\\{([^}]*)}");
    // Local binding to an env var: topic := os.Getenv("KAFKA_TOPIC").
    private static final Pattern GO_ENV_BIND_RE = Pattern.compile(
            "(\\w+)\\s*:?=\\s*os\\.Getenv\\(\\s*\"(\\w+)\"");

    // --- Python (kafka-python, confluent_kafka, aiokafka) -----------------
    // Producer: producer.send('topic', ...) / producer.produce('topic', ...).
    private static final Pattern PY_SEND_RE = Pattern.compile(
            "\\.(?:send|produce)\\s*\\(\\s*(\"[^\"]*\"|'[^']*'|[A-Za-z_][\\w.]*)");
    // Consumer ctor first arg: KafkaConsumer('topic', ...) / AIOKafkaConsumer('topic').
    private static final Pattern PY_CONSUMER_CTOR_RE = Pattern.compile(
            "(?:KafkaConsumer|AIOKafkaConsumer)\\s*\\(\\s*(\"[^\"]*\"|'[^']*'|[A-Za-z_][\\w.]*)");
    // Explicit subscribe: consumer.subscribe(['topic', ...]) or subscribe('topic').
    private static final Pattern PY_SUBSCRIBE_RE = Pattern.compile(
            "\\.subscribe\\s*\\(\\s*\\[?\\s*(\"[^\"]*\"|'[^']*'|[A-Za-z_][\\w.]*)");

    // --- NestJS microservice (@nestjs/microservices) ----------------------
    // Consumer handlers: @EventPattern('topic') / @MessagePattern('topic').
    private static final Pattern NEST_PATTERN_RE = Pattern.compile(
            "@(?:EventPattern|MessagePattern)\\s*\\(\\s*(\"[^\"]*\"|'[^']*'|`[^`]*`|[A-Za-z_][\\w.]*)");
    // Producer: client.emit('topic', ...) / client.send('topic', ...) /
    // subscribeToResponseOf('topic') (registers an outbound reply topic).
    private static final Pattern NEST_EMIT_RE = Pattern.compile(
            "(?:\\.(?:emit|send)|subscribeToResponseOf)\\s*\\(\\s*(\"[^\"]*\"|'[^']*'|`[^`]*`|[A-Za-z_][\\w.]*)");
    /** Node id + label for the shared "external Kafka source" node. */
    private static final String EXT_KAFKA_ID = "ext:self-managed-kafka";
    private static final String EXT_KAFKA_LABEL = "Self-managed Kafka (event source)";

    // --- gRPC -------------------------------------------------------------
    // Modelled like Kafka: the RPC service is its own node, with client → service
    // → server edges. Service names come from the generated stub/registration
    // symbols, which embed the proto service name identically across languages:
    //   Go     server pb.RegisterGreetServiceServer(...)  client pb.NewGreetServiceClient(...)
    //   Java   server class X extends GreetServiceGrpc.GreetServiceImplBase
    //          client GreetServiceGrpc.newBlockingStub(...)
    //   Python server add_GreetServiceServicer_to_server(...) / class X(..GreetServiceServicer)
    //          client greet_pb2_grpc.GreetServiceStub(channel)
    // All scans are GATED on a real gRPC marker in the file so lookalikes (e.g. the
    // AWS SDK's dynamodb.NewClient, a *ImplBase in unrelated code) never register.
    private static final Pattern GRPC_SERVER_RE = Pattern.compile(
            "Register([A-Za-z0-9_]+?)Server\\b"           // Go / Java registration
                    + "|([A-Za-z0-9_]+?)ImplBase\\b"       // Java base class
                    + "|add_([A-Za-z0-9_]+?)Servicer_to_server"  // Python registration
                    + "|\\(\\s*[A-Za-z0-9_.]*?([A-Za-z0-9_]+?)Servicer\\s*\\)");  // Python servicer base
    private static final Pattern GRPC_CLIENT_RE = Pattern.compile(
            "New([A-Za-z0-9_]+?)Client\\b"                // Go client ctor
                    + "|([A-Za-z0-9_]+?)Grpc\\s*\\.\\s*new[A-Za-z]*Stub"  // Java stub
                    + "|([A-Za-z0-9_]+?)Stub\\s*\\(");     // Python / generic stub ctor

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
        // gRPC services this app implements (serves) / calls (as a client).
        final Set<String> grpcServers = new LinkedHashSet<>();
        final Set<String> grpcClients = new LinkedHashSet<>();
        final List<HostRef> hosts = new ArrayList<>();
        // datastore kind -> set of resource names
        final Map<String, Set<String>> stores = new LinkedHashMap<>();
        boolean writesData;
        // Consumes Kafka via a Lambda event-source mapping (topic set externally).
        boolean consumesExternalKafka;

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
        if (scan.consumesExternalKafka) {
            ext.add(EXT_KAFKA_LABEL);
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
            scanGrpc(scan);
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
                    && sc.hosts.isEmpty() && sc.stores.isEmpty() && !sc.consumesExternalKafka
                    && sc.grpcServers.isEmpty() && sc.grpcClients.isEmpty();
            if (s.library && isolated) {
                continue;
            }
            Map<String, Object> meta = new TreeMap<>();
            meta.put("appName", s.appName);
            meta.put("produces", sc.produces.size());
            meta.put("consumes", sc.consumes.size());
            meta.put("runtime", s.subtype);
            boolean ui = s.subtype.equals("react") || s.subtype.equals("nextjs");
            nodes.put(svcId(s), new Node(svcId(s), s.displayName, ui ? "ui" : "service",
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

        // Kafka-consumer Lambdas: consumed topic is set by the (external) event-source
        // mapping, not the code, so wire them to a shared external Kafka source node.
        for (Scan sc : scans) {
            if (!sc.consumesExternalKafka || !nodes.containsKey(svcId(sc.svc))) {
                continue;
            }
            nodes.putIfAbsent(EXT_KAFKA_ID, new Node(EXT_KAFKA_ID, EXT_KAFKA_LABEL, "external", "kafka",
                    null, null, Map.of("kind", "External Kafka event source")));
            edges.add(new Edge(edgeId(EXT_KAFKA_ID, svcId(sc.svc), "consumes"),
                    EXT_KAFKA_ID, svcId(sc.svc), "consumes", "event-source mapping"));
        }

        // gRPC service nodes + edges (client → service → server, mirroring Kafka).
        Set<String> allGrpc = new LinkedHashSet<>();
        for (Scan sc : scans) {
            allGrpc.addAll(sc.grpcServers);
            allGrpc.addAll(sc.grpcClients);
        }
        for (String svcName : allGrpc) {
            String gid = "grpc:" + svcName;
            nodes.putIfAbsent(gid, new Node(gid, svcName, "grpc", "grpc", null, null,
                    Map.of("kind", "gRPC service")));
        }
        for (Scan sc : scans) {
            if (!nodes.containsKey(svcId(sc.svc))) {
                continue;
            }
            for (String svcName : sc.grpcClients) {
                edges.add(new Edge(edgeId(svcId(sc.svc), "grpc:" + svcName, "grpc"),
                        svcId(sc.svc), "grpc:" + svcName, "grpc", "gRPC"));
            }
            for (String svcName : sc.grpcServers) {
                edges.add(new Edge(edgeId("grpc:" + svcName, svcId(sc.svc), "grpc"),
                        "grpc:" + svcName, svcId(sc.svc), "grpc", "serves"));
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
                if (targetSvc == null) {
                    // Base-url values are often a localhost default in git; the config
                    // KEY (e.g. cortex.config-manager.base-url) still names the target.
                    targetSvc = matchServiceByKey(ref.key(), tokenToSvc, sc.svc);
                }
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

        int grpcCount = (int) nodes.values().stream().filter(n -> n.type().equals("grpc")).count();
        notes.add(svcCount + " services, " + topicCount + " Kafka topics, "
                + dsCount + " datastores, " + extCount + " external systems"
                + (grpcCount > 0 ? ", " + grpcCount + " gRPC services" : "") + " detected");

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
            boolean go = name.endsWith(".go");
            boolean python = name.endsWith(".py");

            if (go) {
                scanKafkaGo(scan, text);
                return;
            }
            if (python) {
                scanKafkaPython(scan, text);
                return;
            }
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
                // A Kafka-triggered Lambda consumes its topic via an event-source
                // mapping; the topic name lives in external infra, so we flag the
                // service as an external-Kafka consumer rather than a topic edge.
                if (LAMBDA_KAFKA_EVENT_RE.matcher(text).find()) {
                    scan.consumesExternalKafka = true;
                }
                boolean consumer = text.contains(".subscribe(") || text.contains("eachMessage")
                        || text.contains("createConsumer");
                boolean producer = text.contains("producer") || text.contains(".send(");
                Map<String, String> envBinds = nodeEnvBindings(text);
                Matcher m = NODE_TOPIC_RE.matcher(text);
                while (m.find()) {
                    for (String t : resolveNodeTopic(scan.cfg, m.group(1), envBinds)) {
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
                // NestJS Kafka microservice. Gated on a @nestjs/microservices marker so
                // socket.io `server.emit(...)` and other generic .emit/.send never
                // register phantom Kafka topics (real Nest apps here use websockets).
                if (text.contains("@nestjs/microservices") || text.contains("ClientKafka")
                        || text.contains("Transport.KAFKA")) {
                    Matcher np = NEST_PATTERN_RE.matcher(text);
                    while (np.find()) {
                        for (String t : scan.cfg.resolve(np.group(1))) {
                            if (looksLikeTopic(t)) {
                                scan.consumes.add(t);
                            }
                        }
                    }
                    Matcher ne = NEST_EMIT_RE.matcher(text);
                    while (ne.find()) {
                        for (String t : scan.cfg.resolve(ne.group(1))) {
                            if (looksLikeTopic(t)) {
                                scan.produces.add(t);
                            }
                        }
                    }
                }
            }
        });
    }

    /**
     * Go Kafka (confluent-kafka-go v1/v2, Sarama, segmentio/kafka-go). Gated on a
     * kafka-client marker so generic {@code .Produce}/{@code Topic:} lookalikes in
     * non-Kafka code never register phantom topics.
     */
    private static void scanKafkaGo(Scan scan, String text) {
        boolean kafkaMarker = text.contains("confluent-kafka-go") || text.contains("segmentio/kafka-go")
                || text.contains("sarama") || text.contains("kafka.NewProducer")
                || text.contains("kafka.NewConsumer") || text.contains("kafka.Message")
                || text.contains("kafka.TopicPartition") || text.contains("kafka.ReaderConfig")
                || text.contains("kafka.WriterConfig");
        if (!kafkaMarker) {
            return;
        }
        boolean consumerMarker = text.contains("NewConsumer") || text.contains("SubscribeTopics")
                || text.contains("NewReader") || text.contains("ConsumerGroup")
                || text.contains("ReadMessage") || text.contains(".Consume(");
        boolean producerMarker = text.contains("NewProducer") || text.contains(".Produce(")
                || text.contains("NewWriter") || text.contains("WriteMessages")
                || text.contains("SendMessage") || text.contains("NewSyncProducer")
                || text.contains("NewAsyncProducer");
        Map<String, String> binds = goEnvBindings(text);
        // Explicit subscriptions are unambiguously consumes.
        Matcher sub = GO_SUBSCRIBE_RE.matcher(text);
        while (sub.find()) {
            for (String part : sub.group(1).split(",")) {
                for (String t : resolveVarTopic(scan.cfg, part.trim(), binds)) {
                    if (looksLikeTopic(t)) {
                        scan.consumes.add(t);
                    }
                }
            }
        }
        // Topic: fields in struct literals (message/writer/reader config). Orientation
        // is taken from the file's role; a consumer-only file reads, otherwise produces.
        Matcher m = GO_TOPIC_FIELD_RE.matcher(text);
        while (m.find()) {
            String token = m.group(1).replace("&", "").trim();
            for (String t : resolveVarTopic(scan.cfg, token, binds)) {
                if (!looksLikeTopic(t)) {
                    continue;
                }
                if (consumerMarker && !producerMarker) {
                    scan.consumes.add(t);
                } else {
                    scan.produces.add(t);
                }
            }
        }
    }

    /**
     * Python Kafka (kafka-python, confluent_kafka, aiokafka, faust). Gated on a
     * kafka import so generic {@code .send}/{@code .subscribe} (requests, RxJS-style
     * observables, …) never register phantom topics.
     */
    private static void scanKafkaPython(Scan scan, String text) {
        boolean kafkaMarker = text.contains("from kafka") || text.contains("import kafka")
                || text.contains("confluent_kafka") || text.contains("aiokafka")
                || text.contains("import faust") || text.contains("faust.");
        if (!kafkaMarker) {
            return;
        }
        for (Pattern re : List.of(PY_CONSUMER_CTOR_RE, PY_SUBSCRIBE_RE)) {
            Matcher c = re.matcher(text);
            while (c.find()) {
                for (String t : scan.cfg.resolve(c.group(1))) {
                    if (looksLikeTopic(t)) {
                        scan.consumes.add(t);
                    }
                }
            }
        }
        Matcher s = PY_SEND_RE.matcher(text);
        while (s.find()) {
            for (String t : scan.cfg.resolve(s.group(1))) {
                if (looksLikeTopic(t)) {
                    scan.produces.add(t);
                }
            }
        }
    }

    /**
     * gRPC client/server detection across Go, Java, Python (and Node best-effort).
     * Gated per file on a real gRPC marker so unrelated {@code New*Client} /
     * {@code *ImplBase} / {@code *Stub(} lookalikes never register a phantom service.
     * Skips generated stub files ({@code *_grpc.pb.go}, {@code *_pb2_grpc.py},
     * {@code *Grpc.java}) — they define the symbols for everyone and would attribute
     * the service to whichever app happens to vendor them.
     */
    private static void scanGrpc(Scan scan) {
        forEachSourceFile(scan.svc, (path, text, isTest) -> {
            if (isTest) {
                return;
            }
            String name = path.getFileName().toString();
            if (name.endsWith("_grpc.pb.go") || name.endsWith("_pb2_grpc.py")
                    || name.endsWith(".pb.go") || name.endsWith("_pb2.py")
                    || name.endsWith("Grpc.java")) {
                return;
            }
            boolean grpcMarker = text.contains("google.golang.org/grpc") || text.contains("grpc.Dial")
                    || text.contains("grpc.NewClient") || text.contains("grpc.NewServer")
                    || text.contains("io.grpc") || text.contains("import grpc")
                    || text.contains("grpc.aio") || text.contains("@grpc/grpc-js")
                    || text.contains("_pb2_grpc") || text.contains("ManagedChannel");
            if (!grpcMarker) {
                return;
            }
            Matcher srv = GRPC_SERVER_RE.matcher(text);
            while (srv.find()) {
                String n = firstGroup(srv);
                if (isGrpcName(n)) {
                    scan.grpcServers.add(stripGrpcSuffix(n));
                }
            }
            Matcher cli = GRPC_CLIENT_RE.matcher(text);
            while (cli.find()) {
                String n = firstGroup(cli);
                if (isGrpcName(n)) {
                    scan.grpcClients.add(stripGrpcSuffix(n));
                }
            }
        });
    }

    /** The first non-null capturing group of a match (patterns are alternations). */
    private static String firstGroup(Matcher m) {
        for (int i = 1; i <= m.groupCount(); i++) {
            if (m.group(i) != null) {
                return m.group(i);
            }
        }
        return null;
    }

    /** A plausible proto service name — rejects generic stubs like "Client"/"Server". */
    private static boolean isGrpcName(String n) {
        if (n == null || n.length() < 3) {
            return false;
        }
        return !Set.of("Grpc", "Client", "Server", "Base", "Service", "Stub").contains(n);
    }

    private static String stripGrpcSuffix(String n) {
        return n.endsWith("Grpc") ? n.substring(0, n.length() - 4) : n;
    }

    /** Map Go local vars bound to an env var: {@code topic := os.Getenv("KAFKA_TOPIC")}. */
    private static Map<String, String> goEnvBindings(String text) {
        Map<String, String> binds = new HashMap<>();
        Matcher m = GO_ENV_BIND_RE.matcher(text);
        while (m.find()) {
            binds.putIfAbsent(m.group(1), m.group(2));
        }
        return binds;
    }

    /** Resolve a topic reference token, chasing a local env-var binding if the
     *  direct config lookup yields nothing (mirrors the Node resolver). */
    private static List<String> resolveVarTopic(ConfigIndex cfg, String token, Map<String, String> binds) {
        List<String> out = cfg.resolve(token);
        if (!out.isEmpty()) {
            return out;
        }
        String simple = token;
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) {
            simple = simple.substring(dot + 1);
        }
        String envVar = binds.get(simple);
        return envVar != null ? cfg.resolve("process.env." + envVar) : out;
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
                    + "write|send|publish|put_item|put_object|update_item|send_message|delete_item)\\s*\\(|"
                    + "PutObjectCommand|PutItemCommand|SendMessageCommand")
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

    /** Map local const/field names to the {@code process.env.*} var they are bound to. */
    private static Map<String, String> nodeEnvBindings(String text) {
        Map<String, String> binds = new HashMap<>();
        Matcher m = NODE_ENV_BIND_RE.matcher(text);
        while (m.find()) {
            binds.putIfAbsent(m.group(1), m.group(2));
        }
        return binds;
    }

    /**
     * Resolve a Node {@code topic:} reference. Tries direct resolution first
     * (literal, {@code process.env.X}, config key); if that yields nothing and the
     * token is a local variable bound to an env var, chase it back to config.
     */
    private static List<String> resolveNodeTopic(ConfigIndex cfg, String token, Map<String, String> envBinds) {
        List<String> out = cfg.resolve(token);
        if (!out.isEmpty()) {
            return out;
        }
        String simple = token.trim();
        int dot = simple.lastIndexOf('.'); // this.kafkaTopic -> kafkaTopic
        if (dot >= 0) {
            simple = simple.substring(dot + 1);
        }
        String envVar = envBinds.get(simple);
        return envVar != null ? cfg.resolve("process.env." + envVar) : out;
    }

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

    /** Match an internal service by the distinctive tokens of a base-url config key
     *  (e.g. {@code cortex.config-manager.base-url} → {@code config-manager-service}). */
    private static String matchServiceByKey(String key, Map<String, String> tokenToSvc, Service self) {
        if (key == null) {
            return null;
        }
        for (String tok : tokensOf(key)) {
            String svc = tokenToSvc.get(tok);
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
                name -> (name.endsWith(".java") || name.endsWith(".ts") || name.endsWith(".js")
                        || name.endsWith(".go") || name.endsWith(".py"))
                        && !name.endsWith(".d.ts"),
                p -> {
                    String path = p.toString().replace('\\', '/');
                    String name = p.getFileName().toString();
                    boolean isTest = path.contains("/test/") || path.contains("/tests/")
                            || path.contains("/__tests__/") || name.contains(".test.")
                            || name.contains(".spec.") || name.endsWith("Test.java")
                            || name.endsWith("Tests.java") || name.endsWith("IT.java")
                            || name.endsWith("_test.go")
                            || name.startsWith("test_") || name.endsWith("_test.py");
                    visitor.visit(p, WorkspaceScanner.read(p), isTest);
                });
    }
}

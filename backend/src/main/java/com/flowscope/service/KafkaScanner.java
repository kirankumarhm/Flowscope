package com.flowscope.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.flowscope.service.CommScan.CODE;
import static com.flowscope.service.CommScan.INFERRED;
import com.flowscope.service.SourceIndex.Src;

/**
 * Kafka producers and consumers across Java (Spring Kafka), Node (kafkajs and
 * friends, NestJS microservices), Go (confluent, Sarama, segmentio) and Python
 * (kafka-python, confluent_kafka, aiokafka). Every topic is recorded with the
 * call site it came from. A send/listen site whose topic cannot be resolved
 * becomes a placeholder topic instead of disappearing.
 */
final class KafkaScanner {

    private static final Pattern KAFKA_SEND_RE = Pattern.compile(
            "(?:new\\s+ProducerRecord\\s*<[^>]*>\\s*\\(|new\\s+ProducerRecord\\s*\\(|\\.send\\s*\\()\\s*([^,)\\s][^,)]*)");
    // The annotation body may contain parentheses (e.g. SpEL ".split(',')"), so the
    // closing ')' is anchored to the method declaration that follows it.
    private static final Pattern KAFKA_LISTENER_RE = Pattern.compile(
            "@KafkaListener\\s*\\(([\\s\\S]*?)\\)\\s*(?:public|private|protected|final|static|void|@)",
            Pattern.MULTILINE);
    private static final Pattern TOPICS_ATTR_RE = Pattern.compile(
            "topics\\s*=\\s*(\\{[^}]*}|\"[^\"]*\"|#\\{[^}]*}[^,)]*|[A-Za-z0-9_.]+)");
    // `topic: x` or the object shorthand `{ topic, messages }` / `{ messages, topic }`.
    private static final Pattern NODE_TOPIC_RE = Pattern.compile(
            "\\btopic\\s*:\\s*(\"[^\"]*\"|'[^']*'|`[^`$]*`|[A-Za-z0-9_.$]+)|[{,]\\s*(topic)\\s*(?=[,}])");
    // kafkajs v2: consumer.subscribe({ topics: [a, b] }).
    private static final Pattern NODE_TOPICS_RE = Pattern.compile("\\btopics\\s*:\\s*\\[([^\\]]*)]");
    private static final Pattern NODE_ENV_BIND_RE = Pattern.compile(
            "(?:(?:const|let|var)\\s+|this\\.)(\\w+)\\s*(?::\\s*[\\w<>\\[\\], |]+?)?\\s*=\\s*process\\.env\\.(\\w+)");
    private static final Pattern TOPIC_ENV_NAME = Pattern.compile("(?i)(?:KAFKA_)?\\w*TOPIC\\w*");
    private static final Set<String> TS_TYPES = Set.of("string", "String", "number", "any", "unknown", "never");

    private static final Pattern GO_TOPIC_FIELD_RE = Pattern.compile(
            "Topic\\s*:\\s*(&?\"[^\"]*\"|&?[A-Za-z_][\\w.]*)");
    private static final Pattern GO_SUBSCRIBE_RE = Pattern.compile(
            "SubscribeTopics\\s*\\(\\s*\\[\\]string\\s*\\{([^}]*)}");
    private static final Pattern GO_ENV_BIND_RE = Pattern.compile(
            "(\\w+)\\s*:?=\\s*os\\.Getenv\\(\\s*\"(\\w+)\"");

    private static final Pattern PY_SEND_RE = Pattern.compile(
            "\\.(?:send|produce)\\s*\\(\\s*(\"[^\"]*\"|'[^']*'|[A-Za-z_][\\w.]*)");
    private static final Pattern PY_CONSUMER_CTOR_RE = Pattern.compile(
            "(?:KafkaConsumer|AIOKafkaConsumer)\\s*\\(\\s*(\"[^\"]*\"|'[^']*'|[A-Za-z_][\\w.]*)");
    private static final Pattern PY_SUBSCRIBE_RE = Pattern.compile(
            "\\.subscribe\\s*\\(\\s*\\[?\\s*(\"[^\"]*\"|'[^']*'|[A-Za-z_][\\w.]*)");

    private static final Pattern NEST_PATTERN_RE = Pattern.compile(
            "@(?:EventPattern|MessagePattern)\\s*\\(\\s*(\"[^\"]*\"|'[^']*'|`[^`]*`|[A-Za-z_][\\w.]*)");
    private static final Pattern NEST_EMIT_RE = Pattern.compile(
            "(?:\\.(?:emit|send)|subscribeToResponseOf)\\s*\\(\\s*(\"[^\"]*\"|'[^']*'|`[^`]*`|[A-Za-z_][\\w.]*)");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_$][\\w$.]*");

    private KafkaScanner() {
    }

    static void scan(CommScan scan) {
        for (Src f : scan.src.prod()) {
            if (f.isGo()) {
                go(scan, f);
            } else if (f.isPython()) {
                python(scan, f);
            } else if (f.isJava()) {
                java(scan, f);
            } else if (f.isNode()) {
                node(scan, f);
            }
        }
    }

    // --- Java ----------------------------------------------------------------

    private static void java(CommScan scan, Src f) {
        String text = f.text;
        if (text.contains("KafkaTemplate") || text.contains("ProducerRecord")) {
            boolean any = false;
            String firstToken = null;
            int firstAt = -1;
            Matcher m = KAFKA_SEND_RE.matcher(text);
            while (m.find()) {
                String token = m.group(1).trim();
                List<String> topics = topics(scan.cfg.resolve(token));
                if (!topics.isEmpty()) {
                    any = true;
                    for (String t : topics) {
                        scan.produce(t, CODE, f.evidence(m.start(), "code"));
                    }
                    continue;
                }
                List<ParamFlow.Site<String>> sites = traced(scan, f, m.start(), token,
                        (g, arg) -> scan.cfg.resolve(arg));
                if (sites != null) {
                    any = true;
                    for (ParamFlow.Site<String> s : sites) {
                        scan.produce(s.value(), CODE, s.file().evidence(s.callOffset(), TRACED));
                    }
                } else if (firstToken == null && IDENT.matcher(token).matches()) {
                    firstToken = token;
                    firstAt = m.start();
                }
            }
            if (!any && firstToken != null) {
                scan.unresolvedTopic(firstToken, true, f.evidence(firstAt, "code (topic name not resolved)"));
            }
        }
        Matcher l = KAFKA_LISTENER_RE.matcher(text);
        while (l.find()) {
            Matcher ta = TOPICS_ATTR_RE.matcher(l.group(1));
            if (!ta.find()) {
                continue;
            }
            List<String> ts = topics(resolveTopicsAttr(scan.cfg, ta.group(1)));
            if (ts.isEmpty()) {
                scan.unresolvedTopic(shortToken(ta.group(1)), false,
                        f.evidence(l.start(), "code (topic name not resolved)"));
            }
            for (String t : ts) {
                scan.consume(t, CODE, f.evidence(l.start(), "code"));
            }
        }
    }

    // --- Node / TypeScript ---------------------------------------------------

    private static void node(CommScan scan, Src f) {
        String text = f.text;
        boolean consumer = text.contains(".subscribe(") || text.contains("eachMessage")
                || text.contains("createConsumer");
        boolean producer = text.contains("producer") || text.contains(".send(");
        boolean kafka = text.contains("kafkajs") || text.contains("Kafka") || text.contains("kafka")
                || text.contains("producer") || text.contains("consumer");
        if (kafka && (consumer || producer)) {
            nodeTopics(scan, f, consumer, producer);
        }
        // NestJS Kafka microservice. Gated on a @nestjs/microservices marker so
        // socket.io `server.emit(...)` and other generic .emit/.send never register
        // phantom Kafka topics.
        if (text.contains("@nestjs/microservices") || text.contains("ClientKafka")
                || text.contains("Transport.KAFKA")) {
            Matcher np = NEST_PATTERN_RE.matcher(text);
            while (np.find()) {
                for (String t : topics(scan.cfg.resolve(np.group(1)))) {
                    scan.consume(t, CODE, f.evidence(np.start(), "code"));
                }
            }
            Matcher ne = NEST_EMIT_RE.matcher(text);
            while (ne.find()) {
                for (String t : topics(scan.cfg.resolve(ne.group(1)))) {
                    scan.produce(t, CODE, f.evidence(ne.start(), "code"));
                }
            }
        }
    }

    private static void nodeTopics(CommScan scan, Src f, boolean consumer, boolean producer) {
        String text = f.text;
        Map<String, String> envBinds = nodeEnvBindings(text);
        boolean unresolvedSite = false;
        boolean tracedSite = false;
        String firstToken = null;
        int firstAt = -1;
        Set<String> found = new LinkedHashSet<>();
        Matcher m = NODE_TOPIC_RE.matcher(text);
        while (m.find()) {
            String token = m.group(1) != null ? m.group(1) : m.group(2);
            if (TS_TYPES.contains(token) || token.startsWith("$") || !inObjectLiteral(text, m)) {
                continue; // a type annotation, or "topic:" inside a log string
            }
            List<String> ts = topics(resolveNodeTopic(scan.cfg, token, envBinds));
            if (!ts.isEmpty()) {
                for (String t : ts) {
                    found.add(t);
                    record(scan, t, consumer, producer, f.evidence(m.start(), "code"));
                }
                continue;
            }
            List<ParamFlow.Site<String>> sites = traced(scan, f, m.start(), token,
                    (g, arg) -> resolveNodeTopic(scan.cfg, arg, nodeEnvBindings(g.text)));
            if (sites == null) {
                unresolvedSite = true;
                if (firstToken == null) {
                    firstToken = token;
                    firstAt = m.start();
                }
                continue;
            }
            tracedSite = true;
            for (ParamFlow.Site<String> s : sites) {
                found.add(s.value());
                record(scan, s.value(), consumer, producer, s.file().evidence(s.callOffset(), TRACED));
            }
        }
        Matcher tm = NODE_TOPICS_RE.matcher(text);
        while (tm.find()) {
            for (String part : tm.group(1).split(",")) {
                for (String t : topics(resolveNodeTopic(scan.cfg, part.trim(), envBinds))) {
                    found.add(t);
                    record(scan, t, consumer, producer, f.evidence(tm.start(), "code"));
                }
            }
        }
        // Fallback: a producer file that binds KAFKA_TOPIC* env vars produces them,
        // even when the send site itself could not be traced.
        boolean fellBack = false;
        if (unresolvedSite && !tracedSite && producer && !consumer) {
            Matcher b = NODE_ENV_BIND_RE.matcher(text);
            while (b.find()) {
                if (!TOPIC_ENV_NAME.matcher(b.group(2)).matches()) {
                    continue;
                }
                for (String t : topics(scan.cfg.resolve("process.env." + b.group(2)))) {
                    if (found.add(t)) {
                        fellBack = true;
                        scan.produce(t, INFERRED, f.evidence(b.start(),
                                "inferred: producer file binds env " + b.group(2)));
                    }
                }
            }
        }
        if (unresolvedSite && !tracedSite && found.isEmpty() && !fellBack && firstToken != null) {
            scan.unresolvedTopic(firstToken, !consumer, f.evidence(firstAt, "code (topic name not resolved)"));
        }
    }

    private static final String TRACED = "code (topic traced through call-site arguments)";

    /**
     * Topics that reach a send site as a parameter of its enclosing helper, from
     * the helper's call sites. Null when no call site resolves; empty when call
     * sites resolve but none is reachable from this scan.
     *
     * <p>In one handler of a split Lambda package, a topic only counts when the
     * handler's own code calls the method that passes it: a shared producer class
     * with {@code produceMnoCommand} and {@code produceMsoEvent} wrappers would
     * otherwise give every handler every topic.
     */
    private static List<ParamFlow.Site<String>> traced(CommScan scan, Src f, int at, String token,
                                                       BiFunction<Src, String, List<String>> resolver) {
        List<ParamFlow.Site<String>> sites = new ArrayList<>();
        for (ParamFlow.Site<String> s : ParamFlow.resolveSites(scan.src, f, at, token, resolver, 0)) {
            if (looksLikeTopic(s.value())) {
                sites.add(s);
            }
        }
        if (sites.isEmpty()) {
            return null;
        }
        if (!scan.partOfSplit) {
            return sites;
        }
        Map<String, Boolean> called = new HashMap<>();
        return sites.stream()
                .filter(s -> s.caller() == null
                        || called.computeIfAbsent(s.caller(), c -> ParamFlow.isCalled(scan.src, c)))
                .toList();
    }

    /** True when a {@code topic:} match is an object-literal property, not text in a string. */
    private static boolean inObjectLiteral(String text, Matcher m) {
        int i = (m.group(1) != null ? m.start() : m.start(2)) - 1;
        while (i >= 0 && Character.isWhitespace(text.charAt(i))) {
            i--;
        }
        if (m.group(1) == null) {
            return true; // the shorthand alternative already required '{' or ','
        }
        char c = i >= 0 ? text.charAt(i) : '{';
        return c == '{' || c == ',' || c == '(';
    }

    private static void record(CommScan scan, String topic, boolean consumer, boolean producer,
                               com.flowscope.dto.ServiceMap.Evidence ev) {
        if (consumer) {
            scan.consume(topic, CODE, ev);
        } else if (producer) {
            scan.produce(topic, CODE, ev);
        }
    }

    // --- Go ------------------------------------------------------------------

    private static void go(CommScan scan, Src f) {
        String text = f.text;
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
        Map<String, String> binds = new HashMap<>();
        Matcher bm = GO_ENV_BIND_RE.matcher(text);
        while (bm.find()) {
            binds.putIfAbsent(bm.group(1), bm.group(2));
        }
        Matcher sub = GO_SUBSCRIBE_RE.matcher(text);
        while (sub.find()) {
            for (String part : sub.group(1).split(",")) {
                for (String t : topics(resolveVar(scan.cfg, part.trim(), binds))) {
                    scan.consume(t, CODE, f.evidence(sub.start(), "code"));
                }
            }
        }
        Matcher m = GO_TOPIC_FIELD_RE.matcher(text);
        while (m.find()) {
            String token = m.group(1).replace("&", "").trim();
            for (String t : topics(resolveVar(scan.cfg, token, binds))) {
                if (consumerMarker && !producerMarker) {
                    scan.consume(t, CODE, f.evidence(m.start(), "code"));
                } else {
                    scan.produce(t, CODE, f.evidence(m.start(), "code"));
                }
            }
        }
    }

    // --- Python --------------------------------------------------------------

    private static void python(CommScan scan, Src f) {
        String text = f.text;
        boolean kafkaMarker = text.contains("from kafka") || text.contains("import kafka")
                || text.contains("confluent_kafka") || text.contains("aiokafka")
                || text.contains("import faust") || text.contains("faust.");
        if (!kafkaMarker) {
            return;
        }
        for (Pattern re : List.of(PY_CONSUMER_CTOR_RE, PY_SUBSCRIBE_RE)) {
            Matcher c = re.matcher(text);
            while (c.find()) {
                for (String t : topics(scan.cfg.resolve(c.group(1)))) {
                    scan.consume(t, CODE, f.evidence(c.start(), "code"));
                }
            }
        }
        Matcher s = PY_SEND_RE.matcher(text);
        while (s.find()) {
            for (String t : topics(scan.cfg.resolve(s.group(1)))) {
                scan.produce(t, CODE, f.evidence(s.start(), "code"));
            }
        }
    }

    // --- resolution helpers --------------------------------------------------

    static Map<String, String> nodeEnvBindings(String text) {
        Map<String, String> binds = new HashMap<>();
        Matcher m = NODE_ENV_BIND_RE.matcher(text);
        while (m.find()) {
            binds.putIfAbsent(m.group(1), m.group(2));
        }
        return binds;
    }

    /** Literal, process.env.X, config key, or a local/field bound to an env var. */
    static List<String> resolveNodeTopic(ConfigIndex cfg, String token, Map<String, String> envBinds) {
        List<String> out = cfg.resolve(token);
        if (!out.isEmpty()) {
            return out;
        }
        String simple = token.trim();
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) {
            simple = simple.substring(dot + 1);
        }
        String envVar = envBinds.get(simple);
        return envVar != null ? cfg.resolve("process.env." + envVar) : out;
    }

    private static List<String> resolveVar(ConfigIndex cfg, String token, Map<String, String> binds) {
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

    private static List<String> resolveTopicsAttr(ConfigIndex cfg, String attr) {
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

    private static String shortToken(String attr) {
        String t = attr.replaceAll("[{}#'\"\\s]", "");
        return t.length() > 40 ? t.substring(0, 40) : t;
    }

    static List<String> topics(List<String> candidates) {
        List<String> out = new ArrayList<>();
        for (String s : candidates) {
            if (looksLikeTopic(s)) {
                out.add(s);
            }
        }
        return out;
    }

    static boolean looksLikeTopic(String s) {
        return s != null && s.length() >= 3 && s.length() <= 120
                && !s.contains("/") && !s.contains(" ") && !s.startsWith("${");
    }
}

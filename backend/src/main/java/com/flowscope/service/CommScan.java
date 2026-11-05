package com.flowscope.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.flowscope.dto.ServiceMap.Evidence;
import com.flowscope.service.WorkspaceScanner.Service;

/**
 * Everything one service (or one Lambda handler of a service) talks to, as a list
 * of {@link Link}s plus the nodes those links point at. Scanners only append to
 * this; {@link ServiceMapBuilder} turns it into graph edges and the Architecture
 * view into layer edges.
 */
final class CommScan {

    // Confidence levels, strongest first.
    static final String DECLARED = "declared";
    static final String CODE = "code";
    static final String CONFIG = "config";
    static final String INFERRED = "inferred";
    static final String UNRESOLVED = "unresolved";

    static int rank(String confidence) {
        if (confidence == null) {
            return 0;
        }
        return switch (confidence) {
            case DECLARED -> 5;
            case CODE -> 4;
            case CONFIG -> 3;
            case INFERRED -> 2;
            case UNRESOLVED -> 1;
            default -> 0;
        };
    }

    /** A node a scan refers to (topic, datastore, external system, trigger, …). */
    record NodeDef(String id, String label, String type, String subtype, Map<String, Object> meta) {
    }

    /**
     * One relationship of the scanned service.
     *
     * @param inbound true when data flows from the target into the service
     *                (consumes, reads, gRPC serves, triggers)
     */
    record Link(String kind, String targetId, String label, String confidence,
                Evidence evidence, boolean inbound) {
    }

    /** An outbound URL waiting for service/external resolution once every service is scanned. */
    record HostRef(String host, String path, String key, boolean literal, String usage,
                   String confidence, Evidence evidence) {
    }

    /** A relative API path called from UI code (matched against back-end routes). */
    record PathRef(String path, Evidence evidence) {
    }

    final Service svc;
    final ConfigIndex cfg;
    final SourceIndex src;
    /** Base directory evidence paths are relative to. */
    final Path relBase;
    /** Handler name when this scan covers one exported Lambda handler, else null. */
    final String handler;
    /** True when the package was split into several handler scans: config-only
     *  resources then attach only to the handler whose code references them. */
    boolean partOfSplit;

    final Map<String, NodeDef> nodes = new LinkedHashMap<>();
    final List<Link> links = new ArrayList<>();
    final List<HostRef> hosts = new ArrayList<>();
    final List<PathRef> apiPaths = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    final Map<String, Object> meta = new TreeMap<>();

    CommScan(Service svc, ConfigIndex cfg, SourceIndex src, Path relBase, String handler) {
        this.svc = svc;
        this.cfg = cfg;
        this.src = src;
        this.relBase = relBase;
        this.handler = handler;
    }

    /** Graph id of the service (or handler) node this scan describes. */
    String selfId() {
        return "svc:" + svc.id + (handler == null ? "" : "#" + handler);
    }

    String selfLabel() {
        return handler == null ? svc.displayName : svc.displayName + ":" + handler;
    }

    // --- node + link helpers ------------------------------------------------

    void node(String id, String label, String type, String subtype, Map<String, Object> meta) {
        NodeDef existing = nodes.get(id);
        if (existing == null) {
            nodes.put(id, new NodeDef(id, label, type, subtype, new TreeMap<>(meta)));
        } else {
            mergeMeta(existing.meta(), meta);
        }
    }

    void link(String kind, String targetId, String label, String confidence, Evidence ev, boolean inbound) {
        links.add(new Link(kind, targetId, label == null ? "" : label, confidence, ev, inbound));
    }

    void produce(String topic, String confidence, Evidence ev) {
        String id = "topic:" + topic;
        node(id, topic, "topic", "kafka", Map.of("kind", "Kafka topic"));
        link("produces", id, topic, confidence, ev, false);
    }

    void consume(String topic, String confidence, Evidence ev) {
        String id = "topic:" + topic;
        node(id, topic, "topic", "kafka", Map.of("kind", "Kafka topic"));
        link("consumes", id, topic, confidence, ev, true);
    }

    /** Placeholder topic for a send/listen site whose topic name could not be resolved. */
    void unresolvedTopic(String token, boolean produces, Evidence ev) {
        String id = "topic:?" + svc.id + ":" + token;
        node(id, "Kafka topic (unresolved: " + token + ")", "topic", "kafka",
                Map.of("kind", "Unresolved Kafka topic", "unresolved", true));
        link(produces ? "produces" : "consumes", id, token, UNRESOLVED, ev, !produces);
    }

    /** A datastore node id; {@code name} null makes a per-service placeholder. */
    String storeId(String kind, String name, String placeholderKey) {
        if (name != null) {
            return "ds:" + kind + ":" + name;
        }
        return "ds:" + kind + ":?" + svc.id + ":" + placeholderKey;
    }

    void storeNode(String id, String kind, String label, boolean placeholder, Map<String, Object> extra) {
        Map<String, Object> meta = new TreeMap<>(extra);
        meta.put("kind", storeLabel(kind));
        if (placeholder) {
            meta.put("unresolved", true);
        }
        node(id, label, "datastore", kind, meta);
    }

    void external(String name, String subtype, String kind, String label, String confidence, Evidence ev) {
        if (name == null || name.isBlank()) {
            return;
        }
        String id = "ext:" + slug(name);
        node(id, name, "external", subtype, Map.of("kind", "cloud".equals(subtype) ? "Cloud service" : "External system"));
        link(kind, id, label, confidence, ev, false);
    }

    boolean isEmpty() {
        return links.isEmpty() && hosts.isEmpty() && apiPaths.isEmpty();
    }

    // --- small shared utilities ---------------------------------------------

    static String storeLabel(String kind) {
        return switch (kind) {
            case "dynamodb" -> "DynamoDB table";
            case "s3" -> "S3 bucket";
            case "sqs" -> "SQS queue";
            case "postgresql" -> "PostgreSQL database";
            case "mysql" -> "MySQL database";
            case "mariadb" -> "MariaDB database";
            case "oracle" -> "Oracle database";
            case "sqlserver" -> "SQL Server database";
            case "db2" -> "DB2 database";
            case "sql" -> "Relational database";
            case "cassandra" -> "Cassandra keyspace";
            case "mongodb" -> "MongoDB database";
            case "redis" -> "Redis";
            case "elasticsearch" -> "Elasticsearch";
            default -> kind;
        };
    }

    static String slug(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    @SuppressWarnings("unchecked")
    static void mergeMeta(Map<String, Object> into, Map<String, Object> from) {
        for (Map.Entry<String, Object> e : from.entrySet()) {
            Object cur = into.get(e.getKey());
            if (cur instanceof List<?> curList && e.getValue() instanceof List<?> add) {
                Set<Object> merged = new LinkedHashSet<>((List<Object>) curList);
                merged.addAll(add);
                into.put(e.getKey(), new ArrayList<>(merged));
            } else if (cur == null) {
                into.put(e.getKey(), e.getValue());
            }
        }
    }

    /** Evidence for a config key definition, relative to {@code relBase}. */
    Evidence configEvidence(ConfigIndex.Loc loc, String key) {
        if (loc == null) {
            return new Evidence(null, 0, key, "config key " + key);
        }
        String rel = relBase.relativize(loc.file()).toString().replace('\\', '/');
        return new Evidence(rel, loc.line(), loc.text(), "config key " + key);
    }

    Evidence propEvidence(String key) {
        return configEvidence(cfg.propLocation(key), key);
    }

    Evidence envEvidence(String key) {
        return configEvidence(cfg.envLocation(key), key);
    }
}

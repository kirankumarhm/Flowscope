package com.flowscope.service;

import static com.flowscope.service.CommScan.CONFIG;

import com.flowscope.dto.ServiceMap.Evidence;
import com.flowscope.service.DatastoreScanner.Res;
import com.flowscope.service.SourceIndex.Src;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Relational and NoSQL datastores declared in configuration: JDBC / R2DBC URLs
 * (engine + database name, so services sharing a database share one node),
 * Cassandra keyspaces, MongoDB / Redis / Elasticsearch connection settings, and
 * Node / Python {@code DB_HOST} + {@code DB_NAME} style environment variables.
 */
final class RelationalConfig {

    private static final Pattern URL_DB = Pattern.compile("//[^/?;]+/([^?;/]+)");
    private static final Pattern SQLSERVER_DB = Pattern.compile("(?i)(?:databaseName|database)=([^;]+)");
    private static final Pattern ORACLE_SERVICE = Pattern.compile(
            "(?i)SERVICE_NAME\\s*=\\s*([\\w.]+)|@//[^/]+/([\\w.]+)|@[^:/@]+:\\d+[:/]([\\w.]+)");

    private static final Pattern CASSANDRA_KEYSPACE = Pattern.compile(
            "(?i)(?:^|\\.)(?:cassandra|datastax-java-driver)(?:\\.[\\w-]+)*\\.(?:keyspace-name|keyspace|session-keyspace)$");
    private static final Pattern CASSANDRA_CONTACT = Pattern.compile("(?i)cassandra(?:\\.[\\w-]+)*\\.contact-points$");
    private static final Pattern MONGO_URI = Pattern.compile("(?i)(?:^|\\.)mongodb\\.(?:uri|url)$");
    private static final Pattern MONGO_DB = Pattern.compile("(?i)(?:^|\\.)mongodb\\.database$");
    private static final Pattern REDIS_KEY = Pattern.compile("(?i)(?:^|\\.)redis\\.(?:host|url|cluster\\.nodes)$");
    private static final Pattern ES_KEY = Pattern.compile(
            "(?i)(?:^|\\.)(?:elasticsearch|opensearch)(?:\\.rest)?\\.(?:uris|cluster-nodes|host|hosts|url)$");

    private static final Pattern ENV_DB_URL = Pattern.compile("(?i)^(\\w+_)?(DATABASE_URL|DB_URL|JDBC_URL|DB_URI)$");
    private static final Pattern ENV_DB_HOST = Pattern.compile(
            "(?i)^(\\w+_)?(DB_HOST|DATABASE_HOST|PGHOST|POSTGRES_HOST|MYSQL_HOST|RDS_HOSTNAME|DB_HOSTNAME)$");
    private static final List<String> ENV_DB_NAMES = List.of(
            "DB_NAME", "DATABASE_NAME", "PGDATABASE", "POSTGRES_DB", "MYSQL_DATABASE", "DB_DATABASE", "RDS_DB_NAME");
    private static final Pattern ENV_MONGO = Pattern.compile("(?i)^(\\w+_)?(MONGO|MONGODB)_(URI|URL)$");
    private static final Pattern ENV_REDIS = Pattern.compile("(?i)^(\\w+_)?REDIS_(URL|HOST)$");
    private static final Pattern ENV_ES = Pattern.compile(
            "(?i)^(\\w+_)?(ELASTICSEARCH|ES|OPENSEARCH)_(URL|HOST|NODE|ENDPOINT|URIS)$");
    private static final Pattern HOST_RE = Pattern.compile("(?:[a-z]+s?://)?(?:[^@/]*@)?([A-Za-z0-9._-]+)");

    private RelationalConfig() {
    }

    static void collect(CommScan scan, Map<String, Res> res) {
        ConfigIndex cfg = scan.cfg;
        boolean cassandraContact = false;
        String cassandraKey = null;
        for (Map.Entry<String, String> e : cfg.props().entrySet()) {
            String key = e.getKey();
            String v = cfg.expand(e.getValue());
            if (v == null || v.isBlank()) {
                continue;
            }
            Evidence ev = scan.propEvidence(key);
            if (v.startsWith("jdbc:") || v.startsWith("r2dbc:")) {
                String[] j = parseJdbc(v);
                if (j != null) {
                    add(scan, res, j[0], j[1], key, ev);
                }
            } else if (CASSANDRA_KEYSPACE.matcher(key).find()) {
                add(scan, res, "cassandra", v, key, ev);
            } else if (CASSANDRA_CONTACT.matcher(key).find()) {
                cassandraContact = true;
                cassandraKey = key;
            } else if (MONGO_URI.matcher(key).find() || v.startsWith("mongodb://") || v.startsWith("mongodb+srv://")) {
                add(scan, res, "mongodb", urlDb(v), key, ev);
            } else if (MONGO_DB.matcher(key).find()) {
                add(scan, res, "mongodb", v, key, ev);
            } else if (REDIS_KEY.matcher(key).find() || v.startsWith("redis://") || v.startsWith("rediss://")) {
                add(scan, res, "redis", hostName(v), key, ev);
            } else if (ES_KEY.matcher(key).find()) {
                add(scan, res, "elasticsearch", hostName(v.split(",")[0]), key, ev);
            }
        }
        if (cassandraContact && res.values().stream().noneMatch(r -> r.kind.equals("cassandra"))) {
            Res p = DatastoreScanner.placeholder(scan, res, "cassandra", "keyspace",
                    "Cassandra (keyspace not configured)", scan.propEvidence(cassandraKey));
            p.defConfidence = CONFIG;
        }
        for (Map.Entry<String, String> e : cfg.env().entrySet()) {
            String var = e.getKey();
            String v = e.getValue();
            if (v == null || v.isBlank()) {
                continue;
            }
            Evidence ev = scan.envEvidence(var);
            Matcher host;
            if (ENV_DB_URL.matcher(var).matches()) {
                String[] j = v.startsWith("jdbc:") ? parseJdbc(v) : parseUrl(v);
                if (j != null) {
                    addEnv(scan, res, j[0], j[1], var, ev);
                }
            } else if ((host = ENV_DB_HOST.matcher(var)).matches()) {
                String prefix = host.group(1) == null ? "" : host.group(1);
                String db = null;
                for (String n : ENV_DB_NAMES) {
                    db = cfg.env().get(prefix + n);
                    if (db != null) {
                        break;
                    }
                }
                String engine = engineFor(var + " " + v, scan);
                if (db == null) {
                    db = hostName(v);
                }
                addEnv(scan, res, engine, db, var, ev);
            } else if (ENV_MONGO.matcher(var).matches()) {
                addEnv(scan, res, "mongodb", urlDb(v), var, ev);
            } else if (ENV_REDIS.matcher(var).matches()) {
                addEnv(scan, res, "redis", hostName(v), var, ev);
            } else if (ENV_ES.matcher(var).matches()) {
                addEnv(scan, res, "elasticsearch", hostName(v), var, ev);
            }
        }
    }

    private static void add(CommScan scan, Map<String, Res> res, String kind, String name, String key, Evidence ev) {
        Res r = name == null ? null : DatastoreScanner.named(scan, res, kind, name);
        if (r == null) {
            r = DatastoreScanner.placeholder(scan, res, kind, key,
                    CommScan.storeLabel(kind) + " (name unresolved)", ev);
        } else {
            r.defs.add(ev);
        }
        r.keys.add(key);
        r.defConfidence = DatastoreScanner.max(r.placeholder ? null : r.defConfidence, CONFIG);
    }

    private static void addEnv(CommScan scan, Map<String, Res> res, String kind, String name, String var,
                               Evidence ev) {
        add(scan, res, kind, name, var, ev);
    }

    /** [engine, database] from a JDBC/R2DBC URL; null for embedded/test databases. */
    static String[] parseJdbc(String url) {
        String u = url;
        if (u.startsWith("jdbc:")) {
            u = u.substring(5);
        } else if (u.startsWith("r2dbc:")) {
            u = u.substring(6);
        }
        for (String wrapper : List.of("aws-wrapper:", "p6spy:", "log4jdbc:", "otel:", "pool:")) {
            if (u.startsWith(wrapper)) {
                u = u.substring(wrapper.length());
            }
        }
        if (u.startsWith("tc:")) {
            return null; // Testcontainers
        }
        int colon = u.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String engine = normalizeEngine(u.substring(0, colon));
        if (engine == null) {
            return null;
        }
        String db = switch (engine) {
            case "sqlserver" -> group(SQLSERVER_DB.matcher(u));
            case "oracle" -> group(ORACLE_SERVICE.matcher(u));
            default -> group(URL_DB.matcher(u));
        };
        if (db != null && db.contains("${")) {
            db = null;
        }
        return new String[]{engine, db};
    }

    /** [engine, database] from a {@code postgres://user:pw@host/db} style URL. */
    private static String[] parseUrl(String url) {
        int colon = url.indexOf("://");
        if (colon <= 0) {
            return null;
        }
        String engine = normalizeEngine(url.substring(0, colon));
        return engine == null ? null : new String[]{engine, group(URL_DB.matcher(url))};
    }

    private static String normalizeEngine(String raw) {
        String e = raw.toLowerCase(Locale.ROOT);
        return switch (e) {
            case "postgres", "postgresql", "pgsql" -> "postgresql";
            case "mysql" -> "mysql";
            case "mariadb" -> "mariadb";
            case "sqlserver", "jtds", "mssql" -> "sqlserver";
            case "oracle" -> "oracle";
            case "db2" -> "db2";
            case "h2", "hsqldb", "derby", "sqlite" -> null; // embedded / in-memory
            default -> e.matches("[a-z0-9]+") ? e : null;
        };
    }

    private static String group(Matcher m) {
        if (!m.find()) {
            return null;
        }
        for (int i = 1; i <= m.groupCount(); i++) {
            if (m.group(i) != null) {
                return m.group(i).trim();
            }
        }
        return null;
    }

    private static String urlDb(String uri) {
        return group(URL_DB.matcher(uri));
    }

    /** A stable short name for a host: first DNS label, minus ElastiCache role prefixes. */
    static String hostName(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = HOST_RE.matcher(value.trim());
        if (!m.find()) {
            return null;
        }
        String host = m.group(1).toLowerCase(Locale.ROOT);
        if (host.contains("localhost") || host.startsWith("127.") || host.contains("${")) {
            return null;
        }
        String[] labels = host.split("\\.");
        int i = 0;
        while (i < labels.length - 1 && List.of("master", "clustercfg", "replica", "primary", "reader", "writer")
                .contains(labels[i])) {
            i++;
        }
        return labels[i];
    }

    private static String engineFor(String hint, CommScan scan) {
        String h = hint.toLowerCase(Locale.ROOT);
        if (h.contains("mysql")) {
            return "mysql";
        }
        if (h.contains("pg") || h.contains("postgres")) {
            return "postgresql";
        }
        return engineFromCode(scan);
    }

    /** Engine from the driver the code imports; "sql" when it can't be told. */
    static String engineFromCode(CommScan scan) {
        for (Src f : scan.src.prod()) {
            String t = f.text;
            if (t.contains("'pg'") || t.contains("\"pg\"") || t.contains("psycopg") || t.contains("jackc/pgx")
                    || t.contains("lib/pq") || t.contains("org.postgresql")) {
                return "postgresql";
            }
            if (t.contains("mysql")) {
                return "mysql";
            }
        }
        return "sql";
    }
}

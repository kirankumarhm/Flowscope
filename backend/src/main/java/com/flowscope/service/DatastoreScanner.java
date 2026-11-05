package com.flowscope.service;

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

import com.flowscope.dto.ServiceMap.Evidence;
import static com.flowscope.service.CommScan.CODE;
import static com.flowscope.service.CommScan.CONFIG;
import static com.flowscope.service.CommScan.INFERRED;
import static com.flowscope.service.CommScan.UNRESOLVED;
import com.flowscope.service.SourceIndex.Src;

/**
 * Datastores a service reads or writes.
 *
 * <ul>
 *   <li><b>AWS</b> (DynamoDB, S3, SQS): resources come from config and code
 *       literals. Each SDK call is classified as a read or a write by its
 *       operation ({@code GetCommand} reads, {@code PutItemCommand} writes, …;
 *       {@code client.send(...)} itself says nothing) and tied to the table whose
 *       reference is nearest to the call.</li>
 *   <li><b>Relational and NoSQL</b> (JDBC/R2DBC databases, Cassandra, MongoDB,
 *       Redis, Elasticsearch): resources come from config; direction is decided
 *       per service from repository / SQL / driver calls.</li>
 * </ul>
 *
 * When direction can't be established the edge is a neutral {@code uses}. When a
 * call is found but no resource name resolves, a placeholder datastore is drawn.
 */
final class DatastoreScanner {

    private static final int WINDOW_BEFORE = 30;
    private static final int WINDOW_AFTER = 10;
    private static final int MAX_EVIDENCE = 5;

    /** A datastore resource and the tokens code uses to refer to it. */
    static final class Res {
        final String kind;
        final String id;
        final String label;
        final boolean placeholder;
        final Set<String> keys = new LinkedHashSet<>();     // props keys (substring match)
        final Set<String> idents = new LinkedHashSet<>();   // field/const names, env vars (word match)
        final Set<String> literals = new LinkedHashSet<>(); // quoted resource names
        final Set<String> envVars = new LinkedHashSet<>();
        /** Names bound to this resource only within one file (@Value fields such as "tableName"). */
        final Map<java.nio.file.Path, Set<String>> fileIdents = new HashMap<>();
        String weak;
        final List<Evidence> defs = new ArrayList<>();
        String defConfidence;
        final List<Evidence> reads = new ArrayList<>();
        final List<Evidence> writes = new ArrayList<>();
        String readConfidence = CODE;
        String writeConfidence = CODE;
        final Map<String, Object> meta = new TreeMap<>();

        Res(String kind, String id, String label, boolean placeholder) {
            this.kind = kind;
            this.id = id;
            this.label = label;
            this.placeholder = placeholder;
        }
    }

    private record Op(String kind, boolean write, int offset) {
    }

    // --- AWS operation classifiers -------------------------------------------

    private static final Pattern DDB_MARKER = Pattern.compile("(?i)dynamo|DocumentClient");
    // Java SDK v2 builds requests (GetItemRequest, Update.builder() for transactions)
    // next to their .tableName(...), so the builders are classified too.
    private static final Pattern DDB_READ = Pattern.compile(
            "\\b(?:Get|BatchGet|Query|Scan|TransactGet)(?:Item|Items)?Command\\b"
                    + "|\\.(?:getItem|batchGetItem|transactGetItems|get_item|batch_get_item|GetItem|BatchGetItem"
                    + "|TransactGetItems|query|scan|Query|Scan|batchLoad)\\s*\\("
                    + "|\\b(?:GetItem|BatchGetItem|Query|Scan|TransactGetItems)Request\\s*\\.\\s*builder\\b"
                    + "|\\bGet\\s*\\.\\s*builder\\s*\\(");
    private static final Pattern DDB_WRITE = Pattern.compile(
            "\\b(?:Put|Update|Delete|BatchWrite|TransactWrite)(?:Item|Items)?Command\\b"
                    + "|\\.(?:putItem|updateItem|deleteItem|batchWriteItem|transactWriteItems|put_item|update_item"
                    + "|delete_item|batch_write_item|batch_writer|PutItem|UpdateItem|DeleteItem|BatchWriteItem"
                    + "|TransactWriteItems|batchSave|batchDelete|batchWrite)\\s*\\("
                    + "|\\b(?:PutItem|UpdateItem|DeleteItem|BatchWriteItem|TransactWriteItems)Request\\s*\\.\\s*builder\\b"
                    + "|\\b(?:Put|Update|Delete)\\s*\\.\\s*builder\\s*\\(");
    // DocumentClient (SDK v2) and DynamoDBMapper use short generic names; only
    // trusted in files that construct those clients.
    private static final Pattern DOC_CLIENT_V2 = Pattern.compile(
            "DynamoDB\\.DocumentClient|new\\s+DocumentClient\\s*\\(|aws-sdk/clients/dynamodb");
    private static final Pattern DOC_CLIENT_READ = Pattern.compile("\\.(?:get|batchGet|transactGet)\\s*\\(");
    private static final Pattern DOC_CLIENT_WRITE = Pattern.compile(
            "\\.(?:put|update|delete|transactWrite)\\s*\\(");
    private static final Pattern MAPPER_READ = Pattern.compile("\\.load\\s*\\(");
    private static final Pattern MAPPER_WRITE = Pattern.compile("\\.(?:save|delete)\\s*\\(");

    private static final Pattern S3_MARKER = Pattern.compile(
            "S3Client|AmazonS3|S3AsyncClient|client-s3|lib-storage|new\\s+S3\\s*\\(|AWS\\.S3|S3Template"
                    + "|boto3\\.(?:client|resource)\\(\\s*['\"]s3['\"]|s3Client|s3client|minio");
    private static final Pattern S3_READ = Pattern.compile(
            "\\b(?:GetObject|HeadObject|ListObjects|ListObjectsV2|GetObjectAttributes|SelectObjectContent)Command\\b"
                    + "|\\.(?:getObject|headObject|listObjects|listObjectsV2|getObjectAsBytes|get_object|download_file"
                    + "|download_fileobj|list_objects|list_objects_v2|head_object|GetObject|HeadObject|ListObjectsV2"
                    + "|presignedGetObject)\\s*\\(");
    private static final Pattern S3_WRITE = Pattern.compile(
            "\\b(?:PutObject|DeleteObject|DeleteObjects|CopyObject|CreateMultipartUpload|UploadPart"
                    + "|CompleteMultipartUpload)Command\\b"
                    + "|\\.(?:putObject|deleteObject|deleteObjects|copyObject|upload|put_object|upload_file"
                    + "|upload_fileobj|delete_object|copy_object|PutObject|DeleteObject|presignedPutObject)\\s*\\("
                    + "|new\\s+Upload\\s*\\(");

    private static final Pattern SQS_MARKER = Pattern.compile("(?i)sqs");
    private static final Pattern SQS_READ = Pattern.compile(
            "\\b(?:ReceiveMessage|DeleteMessage|DeleteMessageBatch)Command\\b"
                    + "|\\.(?:receiveMessage|receive_message|ReceiveMessage|deleteMessage|delete_message)\\s*\\("
                    + "|@SqsListener\\b");
    private static final Pattern SQS_WRITE = Pattern.compile(
            "\\bSendMessage(?:Batch)?Command\\b"
                    + "|\\.(?:sendMessage|sendMessageBatch|send_message|send_message_batch|SendMessage"
                    + "|SendMessageBatch)\\s*\\("
                    + "|sqsTemplate\\.send|queueMessagingTemplate\\.(?:convertAndSend|send)");

    // --- AWS resource names in code --------------------------------------------

    private record CodeName(String kind, Pattern pattern) {
    }

    private static final List<CodeName> CODE_NAMES = List.of(
            new CodeName("dynamodb", Pattern.compile("TableName\\s*:\\s*['\"]([\\w.-]{3,})['\"]")),
            new CodeName("dynamodb", Pattern.compile("\\.tableName\\(\\s*\"([\\w.-]{3,})\"\\s*\\)")),
            new CodeName("dynamodb", Pattern.compile("@DynamoDBTable\\(\\s*tableName\\s*=\\s*\"([^\"$]{3,})\"")),
            new CodeName("dynamodb", Pattern.compile("\\.Table\\(\\s*['\"]([\\w.-]{3,})['\"]\\s*\\)")),
            new CodeName("s3", Pattern.compile("\\bBucket\\s*:\\s*['\"]([a-z0-9][a-z0-9.-]{2,})['\"]")),
            new CodeName("s3", Pattern.compile("\\.bucket\\(\\s*\"([a-z0-9][a-z0-9.-]{2,})\"\\s*\\)")),
            new CodeName("sqs", Pattern.compile("QueueUrl\\s*:\\s*['\"]https://sqs[^'\"]*/([\\w.-]+)['\"]")));

    private static final List<CodeName> ENV_NAMES = List.of(
            new CodeName("dynamodb", Pattern.compile("TableName\\s*:\\s*process\\.env\\.(\\w+)")),
            new CodeName("s3", Pattern.compile("\\bBucket\\s*:\\s*process\\.env\\.(\\w+)")),
            new CodeName("sqs", Pattern.compile("QueueUrl\\s*:\\s*process\\.env\\.(\\w+)")),
            new CodeName("sqs", Pattern.compile("`[^`]*sqs[^`]*\\$\\{process\\.env\\.(\\w+)}[^`]*`")));

    // --- relational / NoSQL families --------------------------------------------

    private record Family(String name, Pattern marker, Pattern read, Pattern write) {
    }

    // SQL statements at the start of a string literal (or text block), not prose.
    private static final Pattern SQL_TEXT_WRITE = Pattern.compile(
            "(?i)[\"'`]\\s*(?:INSERT\\s+INTO|UPDATE\\s+[\\w.\"]+\\s+SET|DELETE\\s+FROM|MERGE\\s+INTO)\\b");
    private static final Pattern SQL_TEXT_READ = Pattern.compile(
            "(?i)[\"'`]\\s*SELECT\\b[^;]{0,300}?\\bFROM\\b");

    private static final List<Family> FAMILIES = List.of(
            new Family("sql",
                    Pattern.compile("JpaRepository|CrudRepository|PagingAndSortingRepository|ListCrudRepository"
                            + "|JdbcTemplate|JdbcClient|EntityManager|R2dbcRepository|R2dbcEntityTemplate"
                            + "|DatabaseClient|java\\.sql\\.|javax\\.persistence|jakarta\\.persistence|org\\.hibernate"
                            + "|from\\s+['\"]pg['\"]|require\\(\\s*['\"]pg['\"]\\s*\\)|['\"]mysql2?['\"]|\\bknex\\b"
                            + "|sequelize|typeorm|@prisma/client|psycopg|sqlalchemy|pymysql|\"database/sql\""
                            + "|gorm\\.io|jmoiron/sqlx|jackc/pgx|lib/pq"),
                    Pattern.compile("\\.(?:find\\w*|getById|getReferenceById|existsBy\\w*|countBy\\w*"
                            + "|queryForObject|queryForList|queryForMap|queryForRowSet|createQuery|createNativeQuery"
                            + "|executeQuery|findByPk)\\s*\\(|@Query\\b"),
                    Pattern.compile("\\.(?:save|saveAll|saveAndFlush|saveAllAndFlush|delete|deleteAll|deleteById"
                            + "|deleteAllById|deleteInBatch|persist|merge|remove|batchUpdate|executeUpdate|bulkCreate"
                            + "|destroy|upsert)\\s*\\(|@Modifying\\b")),
            new Family("cassandra",
                    Pattern.compile("CassandraRepository|CassandraTemplate|CassandraOperations|ReactiveCassandra"
                            + "|CqlSession|com\\.datastax|cassandra-driver|gocql|from\\s+cassandra"),
                    Pattern.compile("\\.(?:find\\w*|select|selectOne|count\\w*)\\s*\\("),
                    Pattern.compile("\\.(?:save|saveAll|insert|update|delete|deleteById|deleteAll)\\s*\\(")),
            new Family("mongodb",
                    Pattern.compile("MongoRepository|MongoTemplate|MongoOperations|ReactiveMongo|MongoClient"
                            + "|mongoose|pymongo|mongo-driver"),
                    Pattern.compile("\\.(?:find|findOne|findById|findAll|findBy\\w*|aggregate|countDocuments"
                            + "|count|distinct|exists\\w*)\\s*\\("),
                    Pattern.compile("\\.(?:save|saveAll|insert|insertOne|insertMany|updateOne|updateMany"
                            + "|updateFirst|updateMulti|replaceOne|deleteOne|deleteMany|remove|upsert|findAndModify"
                            + "|findOneAndUpdate|findOneAndReplace|findOneAndDelete|bulkWrite)\\s*\\(")),
            new Family("redis",
                    Pattern.compile("RedisTemplate|StringRedisTemplate|ReactiveRedisTemplate|\\bJedis\\b|Lettuce"
                            + "|RedissonClient|ioredis|from\\s+['\"]redis['\"]|require\\(\\s*['\"]redis['\"]\\s*\\)"
                            + "|import\\s+redis|go-redis"),
                    Pattern.compile("\\.(?:get|hget|hgetall|mget|lrange|smembers|zrange|exists|keys)\\s*\\("
                            + "|opsFor\\w+\\(\\)\\.get"),
                    Pattern.compile("\\.(?:set|setex|setnx|hset|hmset|lpush|rpush|sadd|zadd|del|expire)\\s*\\("
                            + "|opsFor\\w+\\(\\)\\.(?:set|put)")),
            new Family("elasticsearch",
                    Pattern.compile("ElasticsearchRepository|ElasticsearchOperations|ElasticsearchTemplate"
                            + "|RestHighLevelClient|ElasticsearchClient|@elastic/elasticsearch|@opensearch-project"
                            + "|opensearchpy|from\\s+elasticsearch"),
                    Pattern.compile("\\.(?:search|count|find\\w*|msearch|scroll)\\s*\\("),
                    Pattern.compile("\\.(?:index|save|saveAll|bulk|update|delete|deleteById)\\s*\\(")));

    private static final Set<String> SQL_KINDS = Set.of(
            "postgresql", "mysql", "mariadb", "oracle", "sqlserver", "db2", "sql");

    private static final Pattern DB_ENV_REF = Pattern.compile(
            "(?:process\\.env\\.|getenv\\(\\s*['\"]|Getenv\\(\\s*\"|environ(?:\\.get)?\\(?\\[?\\s*['\"])"
                    + "((?:\\w+_)?(?:DB_HOST|DATABASE_HOST|PGHOST|POSTGRES_HOST|MYSQL_HOST|RDS_HOSTNAME"
                    + "|DATABASE_URL|DB_URL))\\b");
    private static final Pattern ENTITY_TABLE = Pattern.compile(
            "@Table\\(\\s*(?:name\\s*=\\s*)?\"([\\w.]+)\"");

    private DatastoreScanner() {
    }

    static void scan(CommScan scan) {
        Map<String, Res> res = new LinkedHashMap<>();
        awsFromConfig(scan, res);
        awsFromCode(scan, res);
        RelationalConfig.collect(scan, res);
        attributeAwsOps(scan, res);
        familyOps(scan, res);
        entities(scan, res);
        emit(scan, res);
    }

    // --- resource discovery ---------------------------------------------------

    private static void awsFromConfig(CommScan scan, Map<String, Res> res) {
        ConfigIndex cfg = scan.cfg;
        for (String[] kv : cfg.matching(cfg.props(), "dynamodb\\.table\\.")) {
            fromProp(scan, res, "dynamodb", kv[1], kv[0]);
        }
        for (String[] kv : cfg.matching(cfg.props(), "aws\\.queues\\.|\\.queue$|\\.queue\\.")) {
            fromProp(scan, res, "sqs", queueName(kv[1]), kv[0]);
        }
        for (String[] kv : cfg.matching(cfg.props(), "\\.bucket|s3\\.")) {
            if (kv[1] != null && !kv[1].contains("/")) {
                fromProp(scan, res, "s3", kv[1], kv[0]);
            }
        }
        for (String[] kv : cfg.matching(cfg.env(), "DYNAMODB_TABLE|_TABLE$|TABLE_NAME")) {
            fromEnv(scan, res, "dynamodb", kv[1], kv[0]);
        }
        for (String[] kv : cfg.matching(cfg.env(), "BUCKET")) {
            fromEnv(scan, res, "s3", kv[1], kv[0]);
        }
        for (String[] kv : cfg.matching(cfg.env(), "QUEUE_URL|_QUEUE|QUEUE_NAME")) {
            fromEnv(scan, res, "sqs", queueName(kv[1]), kv[0]);
        }
    }

    private static void fromProp(CommScan scan, Map<String, Res> res, String kind, String name, String key) {
        Res r = named(scan, res, kind, name);
        if (r == null) {
            return;
        }
        r.keys.add(key);
        scan.cfg.valueFieldKeysByFile().forEach((file, fields) -> fields.forEach((field, k) -> {
            if (k.equals(key)) {
                r.fileIdents.computeIfAbsent(file.normalize(), x -> new LinkedHashSet<>()).add(field);
            }
        }));
        String last = key.substring(key.lastIndexOf('.') + 1);
        setWeak(r, last);
        r.defs.add(scan.propEvidence(key));
        r.defConfidence = max(r.defConfidence, CONFIG);
    }

    private static void fromEnv(CommScan scan, Map<String, Res> res, String kind, String name, String var) {
        Res r = named(scan, res, kind, name);
        if (r == null) {
            return;
        }
        r.envVars.add(var);
        r.idents.add(var);
        setWeak(r, var.replaceAll("(?i)(_TABLE_NAME|_TABLE|_BUCKET_NAME|_BUCKET|_QUEUE_URL|_QUEUE_NAME|_QUEUE"
                + "|DYNAMODB_TABLE_|DYNAMODB_)", ""));
        r.defs.add(scan.envEvidence(var));
        r.defConfidence = max(r.defConfidence, CONFIG);
    }

    private static void awsFromCode(CommScan scan, Map<String, Res> res) {
        for (Src f : scan.src.prod()) {
            for (CodeName cn : CODE_NAMES) {
                Matcher m = cn.pattern().matcher(f.text);
                while (m.find()) {
                    Res r = named(scan, res, cn.kind(), m.group(1));
                    if (r != null) {
                        r.defs.add(f.evidence(m.start(), "code"));
                        r.defConfidence = max(r.defConfidence, CODE);
                    }
                }
            }
            for (CodeName cn : ENV_NAMES) {
                Matcher m = cn.pattern().matcher(f.text);
                while (m.find()) {
                    String var = m.group(1);
                    String value = scan.cfg.env().get(var);
                    if (value != null) {
                        continue; // resolved through config already
                    }
                    String id = scan.storeId(cn.kind(), null, var);
                    Res r = res.computeIfAbsent(id, k -> new Res(cn.kind(), id,
                            CommScan.storeLabel(cn.kind()) + " (name from env " + var + ")", true));
                    r.idents.add(var);
                    r.envVars.add(var);
                    r.defs.add(f.evidence(m.start(), "code (name only known at deploy time)"));
                    r.defConfidence = UNRESOLVED;
                }
            }
        }
    }

    /** Get or create the resource for a concrete name; null when the name is not a real resource. */
    static Res named(CommScan scan, Map<String, Res> res, String kind, String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim();
        if (n.length() < 3 || n.matches("\\d+") || n.equalsIgnoreCase("true")
                || n.equalsIgnoreCase("false") || n.contains("${") || n.contains(" ")) {
            return null;
        }
        String id = scan.storeId(kind, n, null);
        Res r = res.computeIfAbsent(id, k -> new Res(kind, id, n, false));
        r.literals.add("\"" + n + "\"");
        r.literals.add("'" + n + "'");
        scan.cfg.constants().forEach((c, v) -> {
            if (n.equals(v)) {
                r.idents.add(c);
            }
        });
        return r;
    }

    /** Placeholder for a family whose code is present but whose name never resolved. */
    static Res placeholder(CommScan scan, Map<String, Res> res, String kind, String key, String label,
                           Evidence ev) {
        String id = scan.storeId(kind, null, key);
        Res r = res.computeIfAbsent(id, k -> new Res(kind, id, label, true));
        if (ev != null && r.defs.size() < MAX_EVIDENCE) {
            r.defs.add(ev);
        }
        r.defConfidence = UNRESOLVED;
        return r;
    }

    private static void setWeak(Res r, String raw) {
        String w = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        if (w.length() >= 5 && r.weak == null) {
            r.weak = w;
        }
    }

    // --- AWS call attribution ---------------------------------------------------

    private static void attributeAwsOps(CommScan scan, Map<String, Res> res) {
        Map<String, List<Evidence>> orphans = new HashMap<>();
        Map<String, int[]> kindTotals = new HashMap<>(); // kind -> [reads, writes]
        for (Src f : scan.src.prod()) {
            List<Op> ops = awsOps(f);
            if (ops.isEmpty()) {
                continue;
            }
            Map<String, String> binds = KafkaScanner.nodeEnvBindings(f.text);
            Map<Res, List<Integer>> occ = new HashMap<>();
            for (Res r : res.values()) {
                if (SQL_KINDS.contains(r.kind) || !isAws(r.kind)) {
                    continue;
                }
                List<Integer> lines = occurrences(f, r, binds);
                if (!lines.isEmpty()) {
                    occ.put(r, lines);
                }
            }
            for (Op op : ops) {
                kindTotals.computeIfAbsent(op.kind(), k -> new int[2])[op.write() ? 1 : 0]++;
                Evidence ev = f.evidence(op.offset(), "code");
                // Follow the call's own table/bucket/queue argument first.
                List<Res> flowed = TableFlow.resolve(scan.src, f, op.offset(), op.kind(),
                        (g, e) -> direct(scan, res, g, e, op.kind()));
                if (!flowed.isEmpty()) {
                    for (Res r : flowed) {
                        addCapped(op.write() ? r.writes : r.reads, ev);
                    }
                    continue;
                }
                Res target = attribute(f, op, res, occ);
                if (target == null) {
                    addCapped(orphans.computeIfAbsent(op.kind() + (op.write() ? ":w" : ":r"),
                            k -> new ArrayList<>()), ev);
                    continue;
                }
                addCapped(op.write() ? target.writes : target.reads, ev);
            }
        }
        // Calls that could not be tied to one resource.
        for (String kind : List.of("dynamodb", "s3", "sqs")) {
            List<Evidence> orphanReads = orphans.getOrDefault(kind + ":r", List.of());
            List<Evidence> orphanWrites = orphans.getOrDefault(kind + ":w", List.of());
            if (orphanReads.isEmpty() && orphanWrites.isEmpty()) {
                continue;
            }
            List<Res> ofKind = res.values().stream().filter(r -> r.kind.equals(kind)).toList();
            if (ofKind.isEmpty()) {
                // Calls with no resolvable resource name at all: draw a placeholder.
                Res p = placeholder(scan, res, kind, "unresolved",
                        CommScan.storeLabel(kind) + " (name not resolved)", null);
                p.readConfidence = UNRESOLVED;
                p.writeConfidence = UNRESOLVED;
                addAllCapped(p.reads, relabel(orphanReads, "code (resource name not resolved)"));
                addAllCapped(p.writes, relabel(orphanWrites, "code (resource name not resolved)"));
                continue;
            }
            // When every call of this kind in the service reads (or every call writes),
            // that direction holds for each resource of the kind, even unattributed ones.
            int[] t = kindTotals.getOrDefault(kind, new int[2]);
            String label = CommScan.storeLabel(kind);
            for (Res r : ofKind) {
                if (!r.reads.isEmpty() || !r.writes.isEmpty()) {
                    continue;
                }
                if (t[1] == 0 && t[0] > 0) {
                    addAllCapped(r.reads, relabel(orphanReads,
                            "inferred: every " + label + " call in this service is a read"));
                    r.readConfidence = INFERRED;
                } else if (t[0] == 0 && t[1] > 0) {
                    addAllCapped(r.writes, relabel(orphanWrites,
                            "inferred: every " + label + " call in this service is a write"));
                    r.writeConfidence = INFERRED;
                }
            }
        }
    }

    private static final Pattern ENV_EXPR = Pattern.compile(
            "process\\.env(?:\\.(\\w+)|\\[\\s*['\"](\\w+)['\"]\\s*])");

    /** Resources of {@code kind} that an expression in file {@code g} names directly. */
    private static List<Res> direct(CommScan scan, Map<String, Res> res, Src g, String expr, String kind) {
        String e = expr.trim();
        String var = null;
        String lit = null;
        String ident = null;
        Matcher env = ENV_EXPR.matcher(e);
        if (env.matches()) {
            var = env.group(1) != null ? env.group(1) : env.group(2);
        } else if (e.length() >= 2 && (e.startsWith("\"") || e.startsWith("'") || e.startsWith("`"))
                && e.charAt(e.length() - 1) == e.charAt(0)) {
            lit = e.substring(1, e.length() - 1);
        } else if (e.matches("[A-Za-z_$][\\w$.]*")) {
            String s = e.replaceFirst("^(this|self)\\.", "");
            ident = s.substring(s.lastIndexOf('.') + 1);
        } else {
            return List.of();
        }
        String boundVar = ident == null ? null : KafkaScanner.nodeEnvBindings(g.text).get(ident);
        String constant = ident == null ? null : scan.cfg.constants().get(ident);
        Set<String> local = new LinkedHashSet<>();
        List<Res> out = new ArrayList<>();
        for (Res r : res.values()) {
            if (!r.kind.equals(kind)) {
                continue;
            }
            local.clear();
            local.addAll(r.fileIdents.getOrDefault(g.path.normalize(), Set.of()));
            boolean hit = (var != null && r.envVars.contains(var))
                    || (lit != null && (r.label.equals(lit) || lit.endsWith("/" + r.label)))
                    || (ident != null && (r.idents.contains(ident) || local.contains(ident)
                    || (boundVar != null && r.envVars.contains(boundVar))
                    || (constant != null && r.label.equals(constant))));
            if (hit) {
                out.add(r);
            }
        }
        return out;
    }

    private static List<Evidence> relabel(List<Evidence> evs, String via) {
        return evs.stream().map(e -> new Evidence(e.file(), e.line(), e.snippet(), via)).toList();
    }

    private static boolean isAws(String kind) {
        return kind.equals("dynamodb") || kind.equals("s3") || kind.equals("sqs");
    }

    private static List<Op> awsOps(Src f) {
        List<Op> ops = new ArrayList<>();
        String t = f.text;
        if (DDB_MARKER.matcher(t).find()) {
            collect(ops, "dynamodb", false, DDB_READ, f);
            collect(ops, "dynamodb", true, DDB_WRITE, f);
            // SDK v2's DocumentClient uses generic method names (.get/.put/.delete).
            // SDK v3's DynamoDBDocumentClient sends commands instead, so in a v3 file
            // those names belong to Maps, caches and Sets, not to DynamoDB.
            if (DOC_CLIENT_V2.matcher(t).find()) {
                collect(ops, "dynamodb", false, DOC_CLIENT_READ, f);
                collect(ops, "dynamodb", true, DOC_CLIENT_WRITE, f);
            }
            if (t.contains("DynamoDBMapper")) {
                collect(ops, "dynamodb", false, MAPPER_READ, f);
                collect(ops, "dynamodb", true, MAPPER_WRITE, f);
            }
        }
        if (S3_MARKER.matcher(t).find()) {
            collect(ops, "s3", false, S3_READ, f);
            collect(ops, "s3", true, S3_WRITE, f);
        }
        if (SQS_MARKER.matcher(t).find()) {
            collect(ops, "sqs", false, SQS_READ, f);
            collect(ops, "sqs", true, SQS_WRITE, f);
        }
        return ops;
    }

    private static final Pattern OWN_METHOD = Pattern.compile("(?<![\\w$])(?:this|self)\\s*$");

    private static void collect(List<Op> ops, String kind, boolean write, Pattern p, Src f) {
        Matcher m = p.matcher(f.text);
        while (m.find()) {
            if (f.inImport(m.start())) {
                continue;
            }
            // this.upload(...) is the class's own method, not the SDK's (its body,
            // which calls the client, is classified on its own).
            if (f.text.charAt(m.start()) == '.'
                    && OWN_METHOD.matcher(f.text.substring(Math.max(0, m.start() - 8), m.start())).find()) {
                continue;
            }
            ops.add(new Op(kind, write, m.start()));
        }
    }

    /** Lines (1-based) in {@code f} where {@code r} is referenced. */
    private static List<Integer> occurrences(Src f, Res r, Map<String, String> binds) {
        List<Integer> lines = new ArrayList<>();
        String t = f.text;
        for (String k : r.keys) {
            addIndexOf(lines, f, t, k);
        }
        for (String lit : r.literals) {
            addIndexOf(lines, f, t, lit);
        }
        Set<String> idents = new LinkedHashSet<>(r.idents);
        idents.addAll(r.fileIdents.getOrDefault(f.path.normalize(), Set.of()));
        binds.forEach((ident, var) -> {
            if (r.envVars.contains(var)) {
                idents.add(ident);
            }
        });
        for (String id : idents) {
            // A bare or this./self.-qualified name; never a member of another object
            // (`.tableName(`) or a call.
            Matcher m = Pattern.compile("(?<![\\w$])(?:(?<=this\\.)|(?<=self\\.)|(?<!\\.))" + Pattern.quote(id)
                    + "(?![\\w$])(?!\\s*\\()").matcher(t);
            while (m.find()) {
                lines.add(f.line(m.start()));
            }
        }
        return lines;
    }

    private static void addIndexOf(List<Integer> lines, Src f, String text, String needle) {
        if (needle == null || needle.isEmpty()) {
            return;
        }
        int i = text.indexOf(needle);
        while (i >= 0) {
            lines.add(f.line(i));
            i = text.indexOf(needle, i + needle.length());
        }
    }

    /** The resource a call refers to: nearest reference, else the only one in the file or service. */
    private static Res attribute(Src f, Op op, Map<String, Res> res, Map<Res, List<Integer>> occ) {
        int line = f.line(op.offset());
        Res best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Map.Entry<Res, List<Integer>> e : occ.entrySet()) {
            if (!e.getKey().kind.equals(op.kind())) {
                continue;
            }
            for (int l : e.getValue()) {
                if (l < line - WINDOW_BEFORE || l > line + WINDOW_AFTER) {
                    continue;
                }
                // Prefer references before the call (the table is named, then used).
                int dist = l <= line ? line - l : (l - line) * 2;
                if (dist < bestDist) {
                    bestDist = dist;
                    best = e.getKey();
                }
            }
        }
        if (best != null) {
            return best;
        }
        List<Res> inFile = occ.keySet().stream().filter(r -> r.kind.equals(op.kind())).toList();
        if (inFile.size() == 1) {
            return inFile.get(0);
        }
        List<Res> inService = res.values().stream().filter(r -> r.kind.equals(op.kind())).toList();
        if (inService.size() == 1) {
            return inService.get(0);
        }
        // Weak match: a resource whose name fragment appears near the call.
        int from = f.lineStart(Math.max(1, line - WINDOW_BEFORE));
        int to = f.lineStart(Math.min(f.lineCount(), line + WINDOW_AFTER) + 1);
        String window = f.text.substring(from, Math.max(from, Math.min(to, f.text.length())))
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        Res weak = null;
        for (Res r : inService) {
            if (r.weak != null && window.contains(r.weak)) {
                if (weak != null) {
                    return null; // ambiguous
                }
                weak = r;
            }
        }
        return weak;
    }

    // --- relational / NoSQL direction --------------------------------------------

    // Repository interfaces and data-access templates, by family. Calls on variables
    // of these types are classified wherever they occur (service classes rarely
    // mention JpaRepository themselves).
    private static final Pattern REPO_DECL = Pattern.compile(
            "interface\\s+(\\w+)\\s*(?:<[^>{]*>)?\\s+extends\\s+([\\w<>,.\\s]+?)\\s*\\{");
    private static final Map<String, String> TEMPLATE_FAMILY = Map.ofEntries(
            Map.entry("JdbcTemplate", "sql"), Map.entry("NamedParameterJdbcTemplate", "sql"),
            Map.entry("JdbcClient", "sql"), Map.entry("EntityManager", "sql"),
            Map.entry("R2dbcEntityTemplate", "sql"), Map.entry("MongoTemplate", "mongodb"),
            Map.entry("MongoOperations", "mongodb"), Map.entry("CassandraTemplate", "cassandra"),
            Map.entry("CassandraOperations", "cassandra"), Map.entry("CqlSession", "cassandra"),
            Map.entry("RedisTemplate", "redis"), Map.entry("StringRedisTemplate", "redis"),
            Map.entry("ElasticsearchOperations", "elasticsearch"), Map.entry("ElasticsearchTemplate", "elasticsearch"));
    private static final Pattern RECEIVER_WRITE = Pattern.compile(
            "\\s*\\.\\s*(?:save\\w*|delete\\w*|insert\\w*|update\\w*|persist|merge|remove|batchUpdate|executeUpdate"
                    + "|upsert\\w*|index|bulk|opsFor\\w+\\(\\)\\s*\\.\\s*(?:set|put)\\w*)\\s*\\(");
    private static final Pattern RECEIVER_READ = Pattern.compile(
            "\\s*\\.\\s*(?:find\\w*|get\\w*|exists\\w*|count\\w*|query\\w*|search\\w*|select\\w*|load\\w*"
                    + "|opsFor\\w+\\(\\)\\s*\\.\\s*get\\w*)\\s*\\(");

    /** Family of a repository base type, or null when it is not a data repository. */
    private static String repoFamily(String bases) {
        if (bases.matches("(?s).*\\b(?:Mongo|ReactiveMongo)Repository\\b.*")) {
            return "mongodb";
        }
        if (bases.matches("(?s).*\\b(?:Cassandra|ReactiveCassandra)Repository\\b.*")) {
            return "cassandra";
        }
        if (bases.matches("(?s).*\\bElasticsearchRepository\\b.*")) {
            return "elasticsearch";
        }
        if (bases.matches("(?s).*\\b(?:Jpa|Crud|PagingAndSorting|ListCrud|ReactiveCrud|R2dbc|Jdbc)Repository\\b.*")) {
            return "sql";
        }
        return null;
    }

    /** family -> variable names typed as one of its repositories/templates. */
    private static Map<String, Set<String>> receivers(CommScan scan) {
        Map<String, String> typeFamily = new HashMap<>(TEMPLATE_FAMILY);
        for (Src f : scan.src.prod()) {
            if (!f.isJava()) {
                continue;
            }
            Matcher m = REPO_DECL.matcher(f.text);
            while (m.find()) {
                String fam = repoFamily(m.group(2));
                if (fam != null) {
                    typeFamily.put(m.group(1), fam);
                }
            }
        }
        Map<String, Set<String>> out = new HashMap<>();
        for (Src f : scan.src.prod()) {
            if (!f.isJava()) {
                continue;
            }
            for (Map.Entry<String, String> e : typeFamily.entrySet()) {
                if (!f.text.contains(e.getKey())) {
                    continue;
                }
                Matcher v = Pattern.compile("\\b" + Pattern.quote(e.getKey()) + "(?:<[^>;]*>)?\\s+(\\w+)\\s*[;=,)]")
                        .matcher(f.text);
                while (v.find()) {
                    out.computeIfAbsent(e.getValue(), k -> new LinkedHashSet<>()).add(v.group(1));
                }
            }
        }
        return out;
    }

    private static void familyOps(CommScan scan, Map<String, Res> res) {
        Map<String, Set<String>> receivers = receivers(scan);
        for (Family fam : FAMILIES) {
            List<Evidence> reads = new ArrayList<>();
            List<Evidence> writes = new ArrayList<>();
            Evidence marker = null;
            boolean sqlText = fam.name().equals("sql") || fam.name().equals("cassandra");
            for (Src f : scan.src.prod()) {
                Matcher mk = fam.marker().matcher(f.text);
                if (mk.find() && marker == null) {
                    marker = f.evidence(mk.start(), "code");
                }
            }
            if (marker == null) {
                continue;
            }
            Set<String> vars = receivers.getOrDefault(fam.name(), Set.of());
            for (Src f : scan.src.prod()) {
                if (fam.marker().matcher(f.text).find()) {
                    firstMatch(f, fam.read(), reads);
                    firstMatch(f, fam.write(), writes);
                }
                for (String var : vars) {
                    if (!f.text.contains(var)) {
                        continue;
                    }
                    firstMatch(f, Pattern.compile("\\b" + Pattern.quote(var) + RECEIVER_WRITE.pattern()), writes);
                    firstMatch(f, Pattern.compile("\\b" + Pattern.quote(var) + RECEIVER_READ.pattern()), reads);
                }
                if (sqlText) {
                    firstMatch(f, SQL_TEXT_READ, reads);
                    firstMatch(f, SQL_TEXT_WRITE, writes);
                }
            }
            List<Res> targets = res.values().stream().filter(r -> family(r.kind).equals(fam.name())).toList();
            if (targets.isEmpty()) {
                targets = List.of(familyPlaceholder(scan, res, fam.name(), marker));
            }
            for (Res r : targets) {
                addAllCapped(r.reads, reads);
                addAllCapped(r.writes, writes);
                if (r.placeholder) {
                    r.readConfidence = UNRESOLVED;
                    r.writeConfidence = UNRESOLVED;
                }
            }
        }
    }

    private static Res familyPlaceholder(CommScan scan, Map<String, Res> res, String family, Evidence marker) {
        if (family.equals("sql")) {
            // A database host read from the environment at deploy time names the
            // engine even though the database itself is unknown.
            for (Src f : scan.src.prod()) {
                Matcher m = DB_ENV_REF.matcher(f.text);
                if (m.find()) {
                    String engine = RelationalConfig.engineFromCode(scan);
                    return placeholder(scan, res, engine, "env:" + m.group(1),
                            CommScan.storeLabel(engine) + " (host from env " + m.group(1) + ")",
                            f.evidence(m.start(), "code (database only known at deploy time)"));
                }
            }
            return placeholder(scan, res, "sql", "datasource",
                    "Relational database (datasource not in repo config)", marker);
        }
        return placeholder(scan, res, family, "config",
                CommScan.storeLabel(family) + " (connection not in repo config)", marker);
    }

    static String family(String kind) {
        return SQL_KINDS.contains(kind) ? "sql" : kind;
    }

    private static void firstMatch(Src f, Pattern p, List<Evidence> into) {
        Matcher m = p.matcher(f.text);
        while (m.find()) {
            if (!f.inImport(m.start())) {
                addCapped(into, f.evidence(m.start(), "code"));
                return;
            }
        }
    }

    // --- entity metadata -----------------------------------------------------------

    private static void entities(CommScan scan, Map<String, Res> res) {
        List<Res> sql = res.values().stream().filter(r -> SQL_KINDS.contains(r.kind)).toList();
        if (sql.size() != 1) {
            return;
        }
        Set<String> tables = new LinkedHashSet<>();
        for (Src f : scan.src.prod()) {
            if (!f.isJava() || !f.text.contains("@Entity")) {
                continue;
            }
            Matcher m = ENTITY_TABLE.matcher(f.text);
            if (m.find()) {
                tables.add(m.group(1));
            } else {
                tables.add(f.name.replace(".java", ""));
            }
            if (tables.size() >= 50) {
                break;
            }
        }
        if (!tables.isEmpty()) {
            sql.get(0).meta.put("tables", new ArrayList<>(tables));
        }
    }

    // --- output ----------------------------------------------------------------------

    private static void emit(CommScan scan, Map<String, Res> res) {
        for (Res r : res.values()) {
            if (scan.partOfSplit && r.reads.isEmpty() && r.writes.isEmpty() && !referencedIn(scan, r)) {
                continue; // configured for the package, but this handler's code never touches it
            }
            scan.storeNode(r.id, r.kind, r.label, r.placeholder, r.meta);
            boolean any = false;
            if (!r.reads.isEmpty()) {
                any = true;
                String conf = r.placeholder ? UNRESOLVED : r.readConfidence;
                for (Evidence ev : r.reads) {
                    scan.link("reads", r.id, "", conf, ev, true);
                }
                for (Evidence ev : r.defs) {
                    scan.link("reads", r.id, "", conf, ev, true);
                }
            }
            if (!r.writes.isEmpty()) {
                any = true;
                String conf = r.placeholder ? UNRESOLVED : r.writeConfidence;
                for (Evidence ev : r.writes) {
                    scan.link("writes", r.id, "", conf, ev, false);
                }
                for (Evidence ev : r.defs) {
                    scan.link("writes", r.id, "", conf, ev, false);
                }
            }
            if (!any) {
                String conf = r.placeholder ? UNRESOLVED : (r.defConfidence == null ? CONFIG : r.defConfidence);
                if (r.defs.isEmpty()) {
                    scan.link("uses", r.id, "", conf, null, false);
                }
                for (Evidence ev : r.defs) {
                    scan.link("uses", r.id, "", conf, ev, false);
                }
            }
        }
    }

    private static boolean referencedIn(CommScan scan, Res r) {
        for (Src f : scan.src.prod()) {
            if (!occurrences(f, r, KafkaScanner.nodeEnvBindings(f.text)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    // --- small helpers ------------------------------------------------------------------

    static void addCapped(List<Evidence> list, Evidence ev) {
        if (ev != null && list.size() < MAX_EVIDENCE) {
            list.add(ev);
        }
    }

    private static void addAllCapped(List<Evidence> list, List<Evidence> evs) {
        for (Evidence ev : evs) {
            addCapped(list, ev);
        }
    }

    static String max(String a, String b) {
        return CommScan.rank(a) >= CommScan.rank(b) ? a : b;
    }

    static String queueName(String urlOrName) {
        if (urlOrName == null) {
            return null;
        }
        int slash = urlOrName.lastIndexOf('/');
        return slash >= 0 ? urlOrName.substring(slash + 1) : urlOrName;
    }
}

package com.flowscope.service;

import static com.flowscope.service.CommScan.CONFIG;
import static com.flowscope.service.CommScan.UNRESOLVED;

import com.flowscope.dto.ServiceMap.Evidence;
import com.flowscope.service.SourceIndex.Src;
import com.flowscope.service.WorkspaceScanner.Service;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serverless structure: one node per exported Lambda handler (so a package with
 * a consumer, a worker and a collector shows as three boxes, each with its own
 * connections), and the triggers that invoke each handler, read from IaC in the
 * scanned tree (Terraform, SAM, Serverless Framework). A handler typed for an
 * event (SQSEvent, KafkaEvent, …) whose trigger isn't in the tree gets a
 * placeholder trigger rather than looking unconnected.
 */
final class ServerlessScanner {

    /** A handler (or the whole package when {@code handler} is null) and the files it reaches. */
    record Unit(String handler, SourceIndex src) {
    }

    /** A trigger declared in IaC. {@code handlerExport} is the export name after the last '.'. */
    record TriggerDecl(String function, String handlerExport, String kind, List<String> names, Evidence evidence,
                       Path file) {
    }

    private static final List<String> ENTRY_FILES = List.of(
            "index.ts", "index.js", "src/index.ts", "src/index.js", "handler.ts", "handler.js",
            "src/handler.ts", "src/handler.js", "src/handlers/index.ts", "src/handlers/index.js");
    private static final Pattern EXPORT_CONST = Pattern.compile(
            "(?m)^export\\s+(?:const|let|var)\\s+(\\w+)\\s*(?::[^=\\n]+)?=\\s*(\\S)");
    private static final Pattern EXPORT_FN = Pattern.compile("(?m)^export\\s+(?:async\\s+)?function\\s+(\\w+)");
    private static final Pattern EXPORT_FROM = Pattern.compile(
            "(?m)^export\\s*\\{([^}]*)}\\s*from\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern EXPORT_LIST = Pattern.compile("(?m)^export\\s*\\{([^}]*)}\\s*;?\\s*$");
    private static final Pattern CJS_EXPORT = Pattern.compile("(?m)^(?:module\\.)?exports\\.(\\w+)\\s*=");
    private static final Pattern IMPORT = Pattern.compile(
            "import\\s+(?:type\\s+)?(?:(\\w+)\\s*,?\\s*)?(?:\\*\\s+as\\s+(\\w+)|\\{([^}]*)})?\\s*from\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern REQUIRE = Pattern.compile(
            "(?:const|let|var)\\s+(?:(\\w+)|\\{([^}]*)})\\s*=\\s*require\\(\\s*['\"]([^'\"]+)['\"]\\s*\\)");
    private static final Pattern REEXPORT = Pattern.compile("export\\s*(?:\\*|\\{[^}]*})\\s*from\\s*['\"]([^'\"]+)['\"]");

    private static final Pattern EVENT_TYPE = Pattern.compile(
            "\\b(SelfManagedKafkaEvent|MSKEvent|KafkaEvent|SQSEvent|SQSHandler|SNSEvent|SNSHandler|ScheduledEvent"
                    + "|ScheduledHandler|EventBridgeEvent|EventBridgeHandler|DynamoDBStreamEvent|DynamoDBStreamHandler"
                    + "|DynamodbEvent|S3Event|S3Handler)\\b");

    private ServerlessScanner() {
    }

    // --- handler split ----------------------------------------------------------------

    static List<Unit> split(Service svc, SourceIndex src) {
        if (!"node-lambda".equals(svc.subtype)) {
            return List.of(new Unit(null, src));
        }
        Map<Path, Src> byPath = new HashMap<>();
        for (Src f : src.prod()) {
            byPath.put(f.path.normalize(), f);
        }
        Src entry = null;
        for (String e : ENTRY_FILES) {
            entry = byPath.get(svc.dir.resolve(e).normalize());
            if (entry != null) {
                break;
            }
        }
        if (entry == null) {
            return List.of(new Unit(null, src));
        }
        Map<String, int[]> inline = new LinkedHashMap<>();   // handler -> [start, end) in entry
        Map<String, Path> reexported = new LinkedHashMap<>(); // handler -> module file
        List<Integer> exportStarts = new ArrayList<>();
        Map<String, Path> importIdents = imports(svc, entry, byPath);
        collectExports(entry, inline, reexported, exportStarts, importIdents, svc, byPath);
        Set<String> handlers = new LinkedHashSet<>();
        handlers.addAll(inline.keySet());
        handlers.addAll(reexported.keySet());
        if (handlers.size() < 2) {
            return List.of(new Unit(null, src));
        }
        List<Unit> units = new ArrayList<>();
        Set<Path> claimed = new LinkedHashSet<>();
        claimed.add(entry.path.normalize());
        for (String h : handlers) {
            Set<Path> seeds = new LinkedHashSet<>();
            List<int[]> keep = new ArrayList<>();
            if (reexported.containsKey(h)) {
                seeds.add(reexported.get(h));
            } else {
                int[] range = inline.get(h);
                keep.add(range);
                String segment = entry.text.substring(range[0], range[1]);
                importIdents.forEach((ident, file) -> {
                    if (Pattern.compile("(?<![\\w$])" + Pattern.quote(ident) + "(?![\\w$])").matcher(segment).find()) {
                        seeds.add(file);
                    }
                });
            }
            Set<Path> closure = closure(svc, seeds, byPath);
            claimed.addAll(closure);
            List<Src> files = new ArrayList<>();
            if (!keep.isEmpty()) {
                files.add(blanked(entry, keep));
            }
            for (Path p : closure) {
                Src f = byPath.get(p);
                if (f != null && !f.path.normalize().equals(entry.path.normalize())) {
                    files.add(f);
                }
            }
            units.add(new Unit(h, SourceIndex.of(files)));
        }
        // Code no handler reaches still belongs to the package: keep it on the package node.
        List<Src> shared = new ArrayList<>();
        for (Src f : src.prod()) {
            if (!claimed.contains(f.path.normalize())) {
                shared.add(f);
            }
        }
        if (!shared.isEmpty()) {
            units.add(new Unit(null, SourceIndex.of(shared)));
        }
        return units;
    }

    private static void collectExports(Src entry, Map<String, int[]> inline, Map<String, Path> reexported,
                                       List<Integer> starts, Map<String, Path> importIdents, Service svc,
                                       Map<Path, Src> byPath) {
        String t = entry.text;
        Map<String, Integer> inlineStart = new LinkedHashMap<>();
        Matcher c = EXPORT_CONST.matcher(t);
        while (c.find()) {
            starts.add(c.start());
            String first = c.group(2);
            if ("'\"`[{0123456789".contains(first) || t.startsWith("new", c.start(2))) {
                continue; // a constant, not a function
            }
            inlineStart.put(c.group(1), c.start());
        }
        Matcher f = EXPORT_FN.matcher(t);
        while (f.find()) {
            starts.add(f.start());
            inlineStart.put(f.group(1), f.start());
        }
        Matcher cjs = CJS_EXPORT.matcher(t);
        while (cjs.find()) {
            starts.add(cjs.start());
            inlineStart.put(cjs.group(1), cjs.start());
        }
        Matcher ef = EXPORT_FROM.matcher(t);
        while (ef.find()) {
            starts.add(ef.start());
            Path mod = resolveModule(svc, entry.path, ef.group(2), byPath);
            for (String part : ef.group(1).split(",")) {
                String name = exportedName(part);
                if (!name.isEmpty() && mod != null) {
                    reexported.put(name, mod);
                }
            }
        }
        Matcher el = EXPORT_LIST.matcher(t);
        while (el.find()) {
            starts.add(el.start());
            for (String part : el.group(1).split(",")) {
                String local = part.trim().split("\\s+as\\s+")[0].trim();
                String name = exportedName(part);
                Path mod = importIdents.get(local);
                if (!name.isEmpty() && mod != null) {
                    reexported.put(name, mod);
                }
            }
        }
        starts.sort(Integer::compareTo);
        for (Map.Entry<String, Integer> e : inlineStart.entrySet()) {
            int s = e.getValue();
            int end = t.length();
            for (int st : starts) {
                if (st > s) {
                    end = st;
                    break;
                }
            }
            inline.put(e.getKey(), new int[]{s, end});
        }
    }

    private static String exportedName(String part) {
        String[] asSplit = part.trim().split("\\s+as\\s+");
        return asSplit[asSplit.length - 1].trim();
    }

    /** Imported identifier -> resolved local module file (packages are skipped). */
    private static Map<String, Path> imports(Service svc, Src f, Map<Path, Src> byPath) {
        Map<String, Path> out = new LinkedHashMap<>();
        Matcher m = IMPORT.matcher(f.text);
        while (m.find()) {
            Path mod = resolveModule(svc, f.path, m.group(4), byPath);
            if (mod == null) {
                continue;
            }
            if (m.group(1) != null) {
                out.put(m.group(1), mod);
            }
            if (m.group(2) != null) {
                out.put(m.group(2), mod);
            }
            if (m.group(3) != null) {
                for (String part : m.group(3).split(",")) {
                    String local = exportedName(part);
                    if (!local.isEmpty()) {
                        out.put(local, mod);
                    }
                }
            }
        }
        Matcher r = REQUIRE.matcher(f.text);
        while (r.find()) {
            Path mod = resolveModule(svc, f.path, r.group(3), byPath);
            if (mod == null) {
                continue;
            }
            if (r.group(1) != null) {
                out.put(r.group(1), mod);
            } else {
                for (String part : r.group(2).split(",")) {
                    String local = part.trim().split("\\s*:\\s*")[0].trim();
                    if (!local.isEmpty()) {
                        out.put(local, mod);
                    }
                }
            }
        }
        return out;
    }

    /** Files transitively imported from {@code seeds} (relative and src/ alias imports). */
    private static Set<Path> closure(Service svc, Set<Path> seeds, Map<Path, Src> byPath) {
        Set<Path> seen = new LinkedHashSet<>();
        Deque<Path> todo = new ArrayDeque<>(seeds);
        while (!todo.isEmpty()) {
            Path p = todo.pop();
            if (!seen.add(p)) {
                continue;
            }
            Src f = byPath.get(p);
            if (f == null) {
                continue;
            }
            for (Path dep : imports(svc, f, byPath).values()) {
                if (!seen.contains(dep)) {
                    todo.push(dep);
                }
            }
            Matcher re = REEXPORT.matcher(f.text);
            while (re.find()) {
                Path dep = resolveModule(svc, f.path, re.group(1), byPath);
                if (dep != null && !seen.contains(dep)) {
                    todo.push(dep);
                }
            }
        }
        return seen;
    }

    private static Path resolveModule(Service svc, Path from, String spec, Map<Path, Src> byPath) {
        Path base;
        if (spec.startsWith(".")) {
            base = from.getParent().resolve(spec).normalize();
        } else if (spec.startsWith("@/")) {
            base = svc.dir.resolve("src").resolve(spec.substring(2)).normalize();
        } else if (spec.startsWith("src/")) {
            base = svc.dir.resolve(spec).normalize();
        } else {
            return null; // a package
        }
        String b = base.toString();
        for (String ext : List.of("", ".ts", ".tsx", ".js", ".mjs", ".cjs", "/index.ts", "/index.js")) {
            Path p = Path.of(b + ext).normalize();
            if (byPath.containsKey(p)) {
                return p;
            }
        }
        return null;
    }

    /** A copy of {@code f} keeping only {@code ranges} plus its import lines, offsets preserved. */
    private static Src blanked(Src f, List<int[]> ranges) {
        char[] out = f.text.toCharArray();
        for (int i = 0; i < out.length; i++) {
            if (out[i] == '\n') {
                continue;
            }
            boolean keep = f.inImport(i);
            for (int[] r : ranges) {
                if (i >= r[0] && i < r[1]) {
                    keep = true;
                    break;
                }
            }
            if (!keep) {
                out[i] = ' ';
            }
        }
        return new Src(f.path, f.rel, new String(out), false);
    }

    // --- triggers ----------------------------------------------------------------------

    /** Event types the handler's code is written for (kafka, sqs, sns, schedule, …). */
    static Map<String, Evidence> eventTypes(SourceIndex src) {
        Map<String, Evidence> out = new LinkedHashMap<>();
        for (Src f : src.prod()) {
            if (!f.isNode() && !f.isJava()) {
                continue;
            }
            Matcher m = EVENT_TYPE.matcher(f.text);
            while (m.find()) {
                if (f.inImport(m.start())) {
                    continue;
                }
                out.putIfAbsent(kindOf(m.group(1)), f.evidence(m.start(), "code (handler event type)"));
            }
        }
        return out;
    }

    private static String kindOf(String type) {
        if (type.contains("Kafka") || type.equals("MSKEvent")) {
            return "kafka";
        }
        if (type.startsWith("SQS")) {
            return "sqs";
        }
        if (type.startsWith("SNS")) {
            return "sns";
        }
        if (type.startsWith("Dynamo")) {
            return "dynamodb";
        }
        if (type.startsWith("S3")) {
            return "s3";
        }
        return "schedule";
    }

    /** Apply IaC triggers (or code-only placeholders) to one handler's scan. */
    static void applyTriggers(CommScan scan, List<TriggerDecl> decls, Map<String, Evidence> codeEvents) {
        Set<String> covered = new LinkedHashSet<>();
        for (TriggerDecl d : decls) {
            covered.add(d.kind());
            apply(scan, d.kind(), d.names(), d.evidence(), CONFIG);
        }
        for (Map.Entry<String, Evidence> e : codeEvents.entrySet()) {
            if (!covered.contains(e.getKey())) {
                apply(scan, e.getKey(), List.of(), e.getValue(), UNRESOLVED);
            }
        }
        if (!decls.isEmpty() && decls.get(0).function() != null) {
            scan.meta.put("functionName", decls.get(0).function());
        }
    }

    static void apply(CommScan scan, String kind, List<String> names, Evidence ev, String confidence) {
        List<String> real = names.stream().filter(n -> n != null && !n.isBlank() && !n.contains("${")).toList();
        String self = scan.selfId().replace("svc:", "");
        switch (kind) {
            case "kafka" -> {
                if (real.isEmpty()) {
                    String id = "ext:self-managed-kafka";
                    scan.node(id, "Kafka (topic set by event-source mapping)", "external", "kafka",
                            Map.of("kind", "Kafka event source not in repo", "unresolved", true));
                    scan.link("consumes", id, "event-source mapping", UNRESOLVED, ev, true);
                }
                for (String t : real) {
                    scan.consume(t, confidence, ev);
                }
            }
            case "sqs", "dynamodb", "s3" -> {
                if (real.isEmpty()) {
                    String id = scan.storeId(kind, null, "trigger");
                    scan.storeNode(id, kind, CommScan.storeLabel(kind) + " (trigger not in repo)", true, Map.of());
                    scan.link("reads", id, "trigger", UNRESOLVED, ev, true);
                }
                for (String n : real) {
                    String id = scan.storeId(kind, n, null);
                    scan.storeNode(id, kind, n, false, Map.of());
                    scan.link("reads", id, "trigger", confidence, ev, true);
                }
            }
            case "sns" -> {
                List<String> topics = real.isEmpty() ? List.of("?" + self) : real;
                for (String n : topics) {
                    String id = "topic:sns:" + n;
                    boolean unresolved = n.startsWith("?");
                    scan.node(id, unresolved ? "SNS topic (subscription not in repo)" : n, "topic", "sns",
                            unresolved ? Map.of("kind", "SNS topic", "unresolved", true) : Map.of("kind", "SNS topic"));
                    scan.link("consumes", id, "SNS", unresolved ? UNRESOLVED : confidence, ev, true);
                }
            }
            default -> { // schedule / eventbridge
                String label = real.isEmpty() ? "Schedule / EventBridge rule (not in repo)" : "Trigger " + real.get(0);
                String id = "trigger:" + self + ":" + (real.isEmpty() ? "?" : CommScan.slug(real.get(0)));
                scan.node(id, label, "trigger", "schedule",
                        real.isEmpty() ? Map.of("kind", "Trigger", "unresolved", true) : Map.of("kind", "Trigger"));
                scan.link("triggers", id, real.isEmpty() ? "" : real.get(0),
                        real.isEmpty() ? UNRESOLVED : confidence, ev, true);
            }
        }
    }

    // --- IaC parsing ----------------------------------------------------------------------

    private static final Pattern TF_RESOURCE = Pattern.compile("resource\\s+\"(\\w+)\"\\s+\"([\\w-]+)\"\\s*\\{");
    private static final Pattern TF_ATTR = Pattern.compile("(?m)^\\s*(\\w+)\\s*=\\s*(\"[^\"\\n]*\"|\\[[^\\]]*]|[^\\n#]+)");

    private record TfBlock(String type, String name, Map<String, String> attrs, int line) {
    }

    /** IaC trigger declarations found under {@code dir}. */
    static List<TriggerDecl> iac(Path dir, Path relBase, int maxDepth) {
        List<TriggerDecl> out = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        WorkspaceScanner.walkFiles(dir, maxDepth, name -> name.endsWith(".tf") || name.equals("serverless.yml")
                || name.equals("serverless.yaml") || name.equals("template.yaml") || name.equals("template.yml"),
                files::add);
        List<TfBlock> tf = new ArrayList<>();
        Map<Path, List<TfBlock>> tfByFile = new LinkedHashMap<>();
        for (Path p : files) {
            String name = p.getFileName().toString();
            String text = WorkspaceScanner.read(p);
            if (name.endsWith(".tf")) {
                List<TfBlock> blocks = terraformBlocks(text);
                tf.addAll(blocks);
                tfByFile.put(p, blocks);
            } else {
                out.addAll(IacYaml.parse(p, text, relBase));
            }
        }
        if (!tf.isEmpty()) {
            out.addAll(terraform(tf, tfByFile, relBase));
        }
        return out;
    }

    private static List<TfBlock> terraformBlocks(String text) {
        List<TfBlock> out = new ArrayList<>();
        Matcher m = TF_RESOURCE.matcher(text);
        while (m.find()) {
            int open = m.end() - 1;
            int depth = 0;
            int end = text.length();
            for (int i = open; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        end = i;
                        break;
                    }
                }
            }
            String body = text.substring(open + 1, end);
            Map<String, String> attrs = new LinkedHashMap<>();
            Matcher a = TF_ATTR.matcher(body);
            while (a.find()) {
                attrs.putIfAbsent(a.group(1), a.group(2).trim());
            }
            int line = 1;
            for (int i = 0; i < m.start(); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                }
            }
            out.add(new TfBlock(m.group(1), m.group(2), attrs, line));
        }
        return out;
    }

    private static List<TriggerDecl> terraform(List<TfBlock> all, Map<Path, List<TfBlock>> byFile, Path relBase) {
        Map<String, TfBlock> lambdas = new HashMap<>();
        Map<String, String> named = new HashMap<>(); // "type.name" -> resource name attr
        Map<String, TfBlock> rules = new HashMap<>();
        for (TfBlock b : all) {
            if (b.type().equals("aws_lambda_function")) {
                lambdas.put(b.name(), b);
            } else if (b.type().equals("aws_cloudwatch_event_rule")) {
                rules.put(b.name(), b);
            }
            String n = unquote(b.attrs().get("name"));
            if (n != null) {
                named.put(b.type() + "." + b.name(), n);
            }
        }
        List<TriggerDecl> out = new ArrayList<>();
        for (Map.Entry<Path, List<TfBlock>> e : byFile.entrySet()) {
            String rel = relBase.relativize(e.getKey()).toString().replace('\\', '/');
            for (TfBlock b : e.getValue()) {
                Evidence ev = new Evidence(rel, b.line(), "resource \"" + b.type() + "\" \"" + b.name() + "\"",
                        "config (" + b.type() + ")");
                switch (b.type()) {
                    case "aws_lambda_event_source_mapping" -> {
                        String[] fn = function(b.attrs().get("function_name"), lambdas);
                        String arn = b.attrs().getOrDefault("event_source_arn", "");
                        List<String> topics = list(b.attrs().get("topics"));
                        if (!topics.isEmpty() || arn.contains("msk") || arn.contains("kafka")
                                || b.attrs().containsKey("self_managed_event_source")) {
                            out.add(new TriggerDecl(fn[0], fn[1], "kafka", topics, ev, e.getKey()));
                        } else if (arn.contains("aws_sqs_queue.") || arn.contains(":sqs:")) {
                            out.add(new TriggerDecl(fn[0], fn[1], "sqs", List.of(refName(arn, "aws_sqs_queue", named)),
                                    ev, e.getKey()));
                        } else if (arn.contains("aws_dynamodb_table.") || arn.contains(":dynamodb:")) {
                            out.add(new TriggerDecl(fn[0], fn[1], "dynamodb",
                                    List.of(refName(arn, "aws_dynamodb_table", named)), ev, e.getKey()));
                        }
                    }
                    case "aws_cloudwatch_event_target" -> {
                        String[] fn = function(b.attrs().get("arn"), lambdas);
                        Matcher r = Pattern.compile("aws_cloudwatch_event_rule\\.([\\w-]+)")
                                .matcher(b.attrs().getOrDefault("rule", ""));
                        TfBlock rule = r.find() ? rules.get(r.group(1)) : null;
                        String expr = rule == null ? null : unquote(rule.attrs().get("schedule_expression"));
                        String label = expr != null ? expr : rule != null ? rule.name() : null;
                        if (fn[0] != null || fn[1] != null) {
                            out.add(new TriggerDecl(fn[0], fn[1], "schedule",
                                    label == null ? List.of() : List.of(label), ev, e.getKey()));
                        }
                    }
                    case "aws_sns_topic_subscription" -> {
                        if (!"\"lambda\"".equals(b.attrs().get("protocol"))) {
                            continue;
                        }
                        String[] fn = function(b.attrs().get("endpoint"), lambdas);
                        out.add(new TriggerDecl(fn[0], fn[1], "sns",
                                List.of(refName(b.attrs().getOrDefault("topic_arn", ""), "aws_sns_topic", named)),
                                ev, e.getKey()));
                    }
                    default -> {
                    }
                }
            }
        }
        return out;
    }

    /** [functionName, handlerExport] for a function_name/arn attribute value. */
    private static String[] function(String value, Map<String, TfBlock> lambdas) {
        if (value == null) {
            return new String[]{null, null};
        }
        Matcher m = Pattern.compile("aws_lambda_function\\.([\\w-]+)").matcher(value);
        if (m.find()) {
            TfBlock fn = lambdas.get(m.group(1));
            if (fn != null) {
                return new String[]{unquote(fn.attrs().get("function_name")), export(unquote(fn.attrs().get("handler")))};
            }
            return new String[]{m.group(1), null};
        }
        return new String[]{unquote(value), null};
    }

    static String export(String handler) {
        if (handler == null) {
            return null;
        }
        int dot = handler.lastIndexOf('.');
        return dot >= 0 ? handler.substring(dot + 1) : handler;
    }

    private static String refName(String arn, String type, Map<String, String> named) {
        Matcher m = Pattern.compile(type + "\\.([\\w-]+)").matcher(arn);
        if (m.find()) {
            return named.getOrDefault(type + "." + m.group(1), m.group(1));
        }
        String a = unquote(arn);
        if (a != null && a.startsWith("arn:")) {
            String last = a.substring(a.lastIndexOf(':') + 1);
            return last.contains("/") ? last.substring(last.lastIndexOf('/') + 1) : last;
        }
        return null;
    }

    private static List<String> list(String value) {
        List<String> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        Matcher m = Pattern.compile("\"([^\"]+)\"").matcher(value);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    static String unquote(String v) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
            return t.substring(1, t.length() - 1);
        }
        return t.isEmpty() ? null : t;
    }
}

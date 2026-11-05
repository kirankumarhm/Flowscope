package com.flowscope.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.flowscope.dto.ServiceMap.Evidence;
import static com.flowscope.service.CommScan.CODE;
import static com.flowscope.service.CommScan.UNRESOLVED;

/**
 * Container jobs that do their work in shell: {@code psql} / {@code mysql},
 * {@code aws s3}, {@code aws sqs} and {@code curl} in a Dockerfile or the scripts
 * it runs. Only the Dockerfile's own scripts are read (or every script of a
 * Dockerfile-only job), so deploy/CI helper scripts never add arrows.
 */
final class ScriptScanner {

    private static final Pattern SCRIPT_REF = Pattern.compile("([\\w./-]+\\.(?:sh|bash))");
    private static final Pattern PSQL = Pattern.compile("\\bpsql\\b[^\\n]*");
    private static final Pattern MYSQL = Pattern.compile("\\bmysql\\s+-[^\\n]*");
    private static final Pattern PSQL_DB = Pattern.compile(
            "(?:-d\\s+|--dbname[= ])['\"]?([\\w$-]+)|postgres(?:ql)?://[^/\\s]+/([\\w$-]+)");
    private static final Pattern MYSQL_DB = Pattern.compile("(?:-D\\s*|--database[= ])['\"]?([\\w$-]+)");
    private static final Pattern SQL_WRITE = Pattern.compile(
            "(?i)\\b(?:INSERT\\s+INTO|UPDATE\\s+\\w+\\s+SET|DELETE\\s+FROM|COPY\\s+\\w+\\s+FROM|\\\\copy\\s+\\w+\\s+FROM)\\b");
    private static final Pattern SQL_READ = Pattern.compile("(?i)\\bSELECT\\b|\\bCOPY\\s*\\(|\\\\copy\\s*\\(|\\bTO\\s+STDOUT\\b");
    private static final Pattern S3_CMD = Pattern.compile("\\baws\\s+s3\\s+(cp|sync|mv|rm|ls)\\b([^\\n]*)");
    private static final Pattern S3_URI = Pattern.compile("s3://([\\w.${}-]+)");
    private static final Pattern SQS_CMD = Pattern.compile(
            "\\baws\\s+sqs\\s+(send-message(?:-batch)?|receive-message|delete-message)\\b([^\\n]*)");
    private static final Pattern QUEUE_URL = Pattern.compile("--queue-url\\s+['\"]?(\\S+?)['\"]?(?:\\s|$)");
    private static final Pattern CURL = Pattern.compile("\\bcurl\\b[^\\n]*?(https?://[^\\s'\"]+)");
    private static final Pattern VAR = Pattern.compile("\\$\\{?(\\w+)}?");

    private ScriptScanner() {
    }

    /** Code that runs a shell script: spawn('./x.sh'), execSync("x.sh"), ProcessBuilder("x.sh"), subprocess.run([...]). */
    private static final Pattern SPAWNED = Pattern.compile(
            "(?:spawn|spawnSync|exec|execSync|execFile|execFileSync|ProcessBuilder|subprocess\\.\\w+|os\\.system"
                    + "|exec\\.Command)\\s*\\(\\s*\\[?\\s*['\"`]([^'\"`]*?([\\w.-]+\\.(?:sh|bash)))");

    /** True when the service has a Dockerfile, or its code or package scripts run shell scripts. */
    static boolean applies(CommScan scan) {
        return dockerfile(scan.svc.dir) != null || !referencedScripts(scan).isEmpty();
    }

    /** The service's Dockerfile, whatever its case ({@code Dockerfile}, {@code dockerfile}, {@code Containerfile}). */
    static Path dockerfile(Path dir) {
        for (String n : List.of("Dockerfile", "dockerfile", "Containerfile")) {
            Path p = dir.resolve(n);
            if (Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /** Script file names run by the scan's code or by package.json scripts. */
    private static Set<String> referencedScripts(CommScan scan) {
        Set<String> out = new LinkedHashSet<>();
        for (SourceIndex.Src f : scan.src.prod()) {
            Matcher m = SPAWNED.matcher(f.text);
            while (m.find()) {
                out.add(m.group(2));
            }
        }
        Path pkg = scan.svc.dir.resolve("package.json");
        if (Files.isRegularFile(pkg)) {
            Matcher m = SCRIPT_REF.matcher(WorkspaceScanner.read(pkg));
            while (m.find()) {
                out.add(Path.of(m.group(1)).getFileName().toString());
            }
        }
        return out;
    }

    static void scan(CommScan scan, boolean containerJob, Externals externals) {
        Path dir = scan.svc.dir;
        List<Path> files = new ArrayList<>();
        Path dockerfile = dockerfile(dir);
        Set<String> referenced = new LinkedHashSet<>(referencedScripts(scan));
        if (dockerfile != null) {
            files.add(dockerfile);
            Matcher m = SCRIPT_REF.matcher(WorkspaceScanner.read(dockerfile));
            while (m.find()) {
                referenced.add(Path.of(m.group(1)).getFileName().toString());
            }
        }
        WorkspaceScanner.walkFiles(dir, 4, name -> name.endsWith(".sh") || name.endsWith(".bash"), p -> {
            String rel = "/" + dir.relativize(p).toString().replace('\\', '/');
            if (SourceIndex.isTest(rel, p.getFileName().toString())) {
                return;
            }
            if (containerJob || referenced.contains(p.getFileName().toString())) {
                files.add(p);
            }
        });
        for (Path p : files) {
            scanFile(scan, p, WorkspaceScanner.read(p), externals);
        }
    }

    private static void scanFile(CommScan scan, Path p, String text, Externals externals) {
        String rel = scan.relBase.relativize(p).toString().replace('\\', '/');
        boolean writes = SQL_WRITE.matcher(text).find();
        boolean reads = SQL_READ.matcher(text).find();
        sql(scan, rel, text, PSQL, PSQL_DB, "postgresql", writes, reads);
        sql(scan, rel, text, MYSQL, MYSQL_DB, "mysql", writes, reads);

        Matcher s3 = S3_CMD.matcher(text);
        while (s3.find()) {
            String args = s3.group(2);
            Matcher uri = S3_URI.matcher(args);
            if (!uri.find()) {
                continue;
            }
            String op = s3.group(1);
            boolean write = op.equals("rm") || (!op.equals("ls") && isDestination(args, uri.start()));
            store(scan, "s3", uri.group(1), ev(rel, text, s3.start(), "code (shell)"), write);
        }
        Matcher sqs = SQS_CMD.matcher(text);
        while (sqs.find()) {
            Matcher q = QUEUE_URL.matcher(sqs.group(2));
            String queue = q.find() ? DatastoreScanner.queueName(q.group(1)) : null;
            store(scan, "sqs", queue, ev(rel, text, sqs.start(), "code (shell)"), sqs.group(1).startsWith("send"));
        }
        Matcher curl = CURL.matcher(text);
        while (curl.find()) {
            String[] hp = HttpScanner.hostPath(curl.group(1));
            String name = externals.classify(hp[0], hp[1]);
            if (name != null) {
                scan.external(name, "http", "rest", hp[0], CODE, ev(rel, text, curl.start(), "code (shell)"));
            }
        }
    }

    /** True when a positional (source) argument precedes the s3:// URI, making it the destination. */
    private static boolean isDestination(String args, int uriStart) {
        String before = args.substring(0, uriStart).replaceAll("--\\S+", " ").trim();
        return !before.isEmpty();
    }

    private static void sql(CommScan scan, String rel, String text, Pattern cmd, Pattern dbRe, String engine,
                            boolean writes, boolean reads) {
        Matcher m = cmd.matcher(text);
        if (!m.find()) {
            return;
        }
        Matcher db = dbRe.matcher(m.group());
        String name = null;
        if (db.find()) {
            name = db.group(1) != null ? db.group(1) : db.groupCount() > 1 ? db.group(2) : null;
        }
        Evidence ev = ev(rel, text, m.start(), "code (shell)");
        if (name != null && name.contains("$")) {
            Matcher v = VAR.matcher(name);
            String var = v.find() ? v.group(1) : name;
            String resolved = scan.cfg.env().get(var);
            name = resolved;
            if (resolved == null) {
                String id = scan.storeId(engine, null, "env:" + var);
                scan.storeNode(id, engine, CommScan.storeLabel(engine) + " (name from env " + var + ")", true, Map.of());
                link(scan, id, ev, writes, reads, UNRESOLVED);
                return;
            }
        }
        if (name == null) {
            String id = scan.storeId(engine, null, "script");
            scan.storeNode(id, engine, CommScan.storeLabel(engine) + " (" + cmdName(engine) + " in script)", true,
                    Map.of());
            link(scan, id, ev, writes, reads, UNRESOLVED);
            return;
        }
        String id = scan.storeId(engine, name, null);
        scan.storeNode(id, engine, name, false, Map.of());
        link(scan, id, ev, writes, reads, CODE);
    }

    private static String cmdName(String engine) {
        return engine.equals("mysql") ? "mysql" : "psql";
    }

    private static void store(CommScan scan, String kind, String name, Evidence ev, boolean write) {
        String id;
        if (name == null || name.contains("$")) {
            String var = null;
            if (name != null) {
                Matcher v = VAR.matcher(name);
                var = v.find() ? v.group(1) : null;
            }
            String resolved = var == null ? null : scan.cfg.env().get(var);
            if (resolved != null) {
                name = resolved;
            } else {
                id = scan.storeId(kind, null, var == null ? "script" : "env:" + var);
                scan.storeNode(id, kind, CommScan.storeLabel(kind)
                        + (var == null ? " (name not resolved)" : " (name from env " + var + ")"), true, Map.of());
                scan.link(write ? "writes" : "reads", id, "", UNRESOLVED, ev, !write);
                return;
            }
        }
        id = scan.storeId(kind, name, null);
        scan.storeNode(id, kind, name, false, Map.of());
        scan.link(write ? "writes" : "reads", id, "", CODE, ev, !write);
    }

    private static void link(CommScan scan, String id, Evidence ev, boolean writes, boolean reads, String conf) {
        if (writes) {
            scan.link("writes", id, "", conf, ev, false);
        }
        if (reads) {
            scan.link("reads", id, "", conf, ev, true);
        }
        if (!writes && !reads) {
            scan.link("uses", id, "", conf, ev, false);
        }
    }

    private static Evidence ev(String rel, String text, int offset, String via) {
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        int end = text.indexOf('\n', lineStart);
        String snippet = text.substring(lineStart, end < 0 ? text.length() : end).trim();
        return new Evidence(rel, line, SourceIndex.clip(snippet), via);
    }
}

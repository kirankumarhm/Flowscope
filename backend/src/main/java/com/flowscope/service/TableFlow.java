package com.flowscope.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.flowscope.service.SourceIndex.Src;

/**
 * Finds which resource a datastore call operates on by following the expression
 * passed as its table / bucket / queue argument:
 *
 * <ol>
 *   <li>a direct reference ({@code process.env.X}, a literal, a bound field);</li>
 *   <li>a local variable assigned earlier in the same method;</li>
 *   <li>a parameter of the enclosing method, through its call sites
 *       ({@code getByKey(this.eidTable, key)});</li>
 *   <li>a field set from a constructor parameter, through {@code super(...)} and
 *       {@code new X(...)} calls ({@code super(client, process.env.ORDERS_TABLE)});</li>
 *   <li>a property of a config object, through object literals that set it
 *       ({@code super({ stateTableName: process.env.CART_STATE })}).</li>
 * </ol>
 *
 * Text-based, so it handles the shapes Java and TypeScript share.
 */
final class TableFlow {

    private static final int BEFORE = 12;
    private static final int AFTER = 8;
    private static final String EXPR = "(process\\.env\\[\\s*['\"]\\w+['\"]\\s*]|['\"][^'\"\\n]+['\"]|[A-Za-z_$][\\w$.]*)";

    private static final Pattern DDB_ARG = Pattern.compile(
            "(?:\\.tableName\\(\\s*|\\bTableName\\s*[:=]\\s*|\\.[Tt]able\\(\\s*|RequestItems\\s*:\\s*\\{\\s*\\[\\s*)" + EXPR);
    // SDK v3 / v2 named arguments, and SDK v1 calls whose first argument is the bucket
    // (amazonS3.deleteObject(bucket, key), new GetObjectRequest(bucket, key)).
    private static final Pattern S3_ARG = Pattern.compile(
            "(?:\\.bucket\\(\\s*|\\bBucket\\s*[:=]\\s*|\\.withBucketName\\(\\s*"
                    + "|\\bnew\\s+(?:GetObject|PutObject|DeleteObject|CopyObject|ListObjects(?:V2)?|InitiateMultipartUpload)"
                    + "Request\\s*\\(\\s*"
                    + "|\\.(?:getObject|putObject|deleteObject|doesObjectExist|doesBucketExistV2|listObjects(?:V2)?"
                    + "|getObjectMetadata|copyObject|generatePresignedUrl)\\s*\\(\\s*)" + EXPR);
    private static final Pattern SQS_ARG = Pattern.compile("(?:\\.queueUrl\\(\\s*|\\bQueueUrl\\s*[:=]\\s*)" + EXPR);
    private static final Pattern CLASS = Pattern.compile("\\bclass\\s+(\\w+)");

    private TableFlow() {
    }

    /**
     * Resources named by the table/bucket/queue argument nearest to the call at
     * {@code opOffset}; empty when there is no such argument or it doesn't resolve.
     *
     * @param direct maps an expression in a file to the resources it names directly
     */
    static <R> List<R> resolve(SourceIndex src, Src f, int opOffset, String kind,
                               BiFunction<Src, String, List<R>> direct) {
        Pattern argRe = switch (kind) {
            case "dynamodb" -> DDB_ARG;
            case "s3" -> S3_ARG;
            case "sqs" -> SQS_ARG;
            default -> null;
        };
        if (argRe == null) {
            return List.of();
        }
        int line = f.line(opOffset);
        Matcher m = argRe.matcher(f.text);
        List<int[]> candidates = new ArrayList<>(); // [score, exprStart, exprEnd]
        while (m.find()) {
            int d = f.line(m.start()) - line;
            if (d < -BEFORE || d > AFTER) {
                continue;
            }
            int score = d >= 0 ? d * 2 : -d * 2 + 1; // prefer the argument inside/after the call
            candidates.add(new int[]{score, m.start(1), m.end(1)});
        }
        // Nearest first; the next one is tried when the nearest is a request object
        // (getObject(request)) rather than the name itself.
        candidates.sort((a, b) -> Integer.compare(a[0], b[0]));
        for (int[] c : candidates.subList(0, Math.min(3, candidates.size()))) {
            List<R> r = expression(src, f, c[1], f.text.substring(c[1], c[2]), direct, 0);
            if (!r.isEmpty()) {
                return r;
            }
        }
        return List.of();
    }

    private static <R> List<R> expression(SourceIndex src, Src f, int at, String expr,
                                          BiFunction<Src, String, List<R>> direct, int depth) {
        List<R> out = direct.apply(f, expr);
        if (!out.isEmpty() || depth > 1) {
            return out;
        }
        String e = expr.trim();
        boolean thisRef = e.startsWith("this.") || e.startsWith("self.");
        String rest = thisRef ? e.substring(5) : e;
        if (!rest.contains(".")) {
            if (!thisRef) {
                List<R> local = localBinding(src, f, at, rest, direct, depth);
                if (!local.isEmpty()) {
                    return local;
                }
                List<R> viaParam = ParamFlow.resolve(src, f, at, rest, direct, 0);
                if (!viaParam.isEmpty()) {
                    return viaParam;
                }
            }
            return field(src, f, at, rest, direct, depth);
        }
        // config.stateTableName / this.config.stateTableName → whoever sets stateTableName.
        return property(src, rest.substring(rest.lastIndexOf('.') + 1), direct);
    }

    /** {@code name = <expr>} earlier in the same method. */
    private static <R> List<R> localBinding(SourceIndex src, Src f, int at, String name,
                                            BiFunction<Src, String, List<R>> direct, int depth) {
        ParamFlow.Decl decl = ParamFlow.enclosing(f.text, at);
        int from = decl == null ? 0 : decl.nameStart();
        Matcher m = Pattern.compile("(?<![\\w$.])" + Pattern.quote(name) + "\\s*(?::\\s*[\\w<>\\[\\]| ]+)?=(?![=>])\\s*"
                + EXPR).matcher(f.text);
        m.region(from, Math.max(from, at));
        String rhs = null;
        int rhsAt = -1;
        while (m.find()) {
            rhs = m.group(1);
            rhsAt = m.start(1);
        }
        return rhs == null || rhs.equals(name) ? List.of() : expression(src, f, rhsAt, rhs, direct, depth + 1);
    }

    /** {@code this.name = <expr>} in the class; a constructor parameter is followed to its callers. */
    private static <R> List<R> field(SourceIndex src, Src f, int at, String name,
                                     BiFunction<Src, String, List<R>> direct, int depth) {
        Matcher assign = Pattern.compile("this\\." + Pattern.quote(name) + "\\s*=\\s*" + EXPR).matcher(f.text);
        while (assign.find()) {
            String rhs = assign.group(1);
            List<R> r = direct.apply(f, rhs);
            if (!r.isEmpty()) {
                return r;
            }
            List<R> viaCtor = constructorArg(src, f, assign.start(), rhs, direct);
            if (!viaCtor.isEmpty()) {
                return viaCtor;
            }
        }
        // TypeScript parameter property: constructor(private readonly tableName: string)
        return constructorArg(src, f, at, name, direct);
    }

    /** Values passed for constructor parameter {@code param} via {@code new C(...)} or a subclass's {@code super(...)}. */
    private static <R> List<R> constructorArg(SourceIndex src, Src f, int at, String param,
                                              BiFunction<Src, String, List<R>> direct) {
        String cls = null;
        Matcher c = CLASS.matcher(f.text);
        while (c.find() && c.start() < at) {
            cls = c.group(1);
        }
        if (cls == null) {
            return List.of();
        }
        // TypeScript constructor(...) or a Java constructor declaration (modifier or line start, not `new C(`).
        Matcher ctor = Pattern.compile("\\bconstructor\\s*\\(|(?m)(?:\\b(?:public|protected|private)\\s+|^[ \\t]*)"
                + Pattern.quote(cls) + "\\s*\\(").matcher(f.text);
        int idx = -1;
        while (ctor.find()) {
            List<String> params = ParamFlow.args(f.text, ctor.end() - 1);
            if (params != null) {
                idx = ParamFlow.paramIndex(String.join(",", params), param);
                if (idx >= 0) {
                    break;
                }
            }
        }
        if (idx < 0) {
            return List.of();
        }
        Set<R> out = new LinkedHashSet<>();
        Pattern newCall = Pattern.compile("\\bnew\\s+" + Pattern.quote(cls) + "\\s*(?:<[^>]*>)?\\s*\\(");
        Pattern sub = Pattern.compile("\\bextends\\s+" + Pattern.quote(cls) + "\\b");
        Pattern superCall = Pattern.compile("\\bsuper\\s*\\(");
        for (Src g : src.prod()) {
            if (!g.text.contains(cls)) {
                continue;
            }
            List<Pattern> calls = new ArrayList<>(List.of(newCall));
            if (sub.matcher(g.text).find()) {
                calls.add(superCall);
            }
            for (Pattern p : calls) {
                Matcher m = p.matcher(g.text);
                while (m.find()) {
                    List<String> args = ParamFlow.args(g.text, m.end() - 1);
                    if (args != null && idx < args.size()) {
                        out.addAll(direct.apply(g, args.get(idx)));
                    }
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** Object literals ({@code name: <expr>}) and assignments ({@code .name = <expr>}) anywhere in the scan. */
    private static <R> List<R> property(SourceIndex src, String name, BiFunction<Src, String, List<R>> direct) {
        Pattern p = Pattern.compile("(?:(?<![\\w$.])|\\.)" + Pattern.quote(name) + "\\s*[:=](?![=>])\\s*" + EXPR);
        Set<R> out = new LinkedHashSet<>();
        for (Src g : src.prod()) {
            if (!g.text.contains(name)) {
                continue;
            }
            Matcher m = p.matcher(g.text);
            while (m.find()) {
                out.addAll(direct.apply(g, m.group(1)));
            }
        }
        return new ArrayList<>(out);
    }
}

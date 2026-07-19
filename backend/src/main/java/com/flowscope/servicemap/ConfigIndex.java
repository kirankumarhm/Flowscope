package com.flowscope.servicemap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Per-service name resolution. Comm primitives (Kafka topics, base URLs, table
 * names) are almost never written as literals at the call site — they are
 * indirected through config. This class builds one lookup table per service from:
 *
 * <ul>
 *   <li>Spring YAML/properties ({@code kafka.topics.*}, {@code listener.*.topics},
 *       {@code dynamodb.table.*}, {@code *.base-url}, …), preferring prod values;</li>
 *   <li>Node {@code .env.*} variables (prod preferred);</li>
 *   <li>Java {@code public static final String X = "literal"} constants;</li>
 *   <li>Java {@code @Value("${prop:default}") String field} declarations.</li>
 * </ul>
 *
 * {@link #resolve(String)} then turns a reference token (a property placeholder,
 * env var, constant, field name, or string literal) into the concrete value(s).
 */
public final class ConfigIndex {

    /** dotted YAML/properties key -> value (prod-preferred). */
    private final Map<String, String> props = new LinkedHashMap<>();
    /** .env variable -> value (prod-preferred). */
    private final Map<String, String> env = new LinkedHashMap<>();
    /** Java constant simple name -> literal value. */
    private final Map<String, String> constants = new HashMap<>();
    /** Java @Value field name -> resolved literal. */
    private final Map<String, String> valueFields = new HashMap<>();

    private static final Pattern CONST_RE = Pattern.compile(
            "static\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern VALUE_FIELD_RE = Pattern.compile(
            "@Value\\(\\s*\"\\$\\{([^}\"]*)}\"\\s*\\)\\s*(?:private\\s+|public\\s+|protected\\s+|final\\s+|static\\s+)*"
                    + "(?:final\\s+)?String\\s+(\\w+)");
    private static final Pattern PLACEHOLDER_RE = Pattern.compile("\\$\\{([^}]*)}");

    public static ConfigIndex forService(Path dir) {
        ConfigIndex idx = new ConfigIndex();
        idx.loadYaml(dir);
        idx.loadEnv(dir);
        idx.loadJava(dir);
        return idx;
    }

    public Map<String, String> props() {
        return props;
    }

    public Map<String, String> env() {
        return env;
    }

    /**
     * Resolve a reference token to its concrete value(s). Handles Spring SpEL
     * ({@code #{'${a.b:x,y}'.split(',')}}), placeholders ({@code ${a.b:def}}),
     * string literals, {@code process.env.VAR}, {@code ClassName.CONST}, bare
     * constants/env vars, and {@code @Value} field names. Comma-separated values
     * are split. Returns an empty list when nothing resolves.
     */
    public List<String> resolve(String rawToken) {
        List<String> out = new ArrayList<>();
        if (rawToken == null) {
            return out;
        }
        String token = rawToken.trim();
        if (token.isEmpty()) {
            return out;
        }

        // Placeholder(s) anywhere in the token: ${prop:default}
        Matcher ph = PLACEHOLDER_RE.matcher(token);
        if (ph.find()) {
            ph.reset();
            while (ph.find()) {
                String inner = ph.group(1);
                int colon = inner.indexOf(':');
                String key = colon >= 0 ? inner.substring(0, colon) : inner;
                String def = colon >= 0 ? inner.substring(colon + 1) : null;
                String val = props.getOrDefault(key.trim(), def);
                addSplit(out, val);
            }
            return out;
        }

        // Quoted string literal: "topic" or 'topic'
        if ((token.startsWith("\"") && token.endsWith("\"") && token.length() >= 2)
                || (token.startsWith("'") && token.endsWith("'") && token.length() >= 2)) {
            addSplit(out, token.substring(1, token.length() - 1));
            return out;
        }

        // process.env.VAR
        if (token.startsWith("process.env.")) {
            addSplit(out, env.get(token.substring("process.env.".length()).trim()));
            return out;
        }

        // Dotted reference: ClassName.CONSTANT -> use the last segment.
        String simple = token;
        int dot = simple.lastIndexOf('.');
        if (dot >= 0) {
            simple = simple.substring(dot + 1);
        }

        String v = firstNonNull(
                constants.get(simple),
                valueFields.get(simple),
                env.get(simple),
                env.get(token),
                props.get(token));
        addSplit(out, v);
        return out;
    }

    /** Property/env entries whose key matches {@code keyRegex}, as [key,value] pairs. */
    public List<String[]> matching(Map<String, String> map, String keyRegex) {
        Pattern p = Pattern.compile(keyRegex, Pattern.CASE_INSENSITIVE);
        List<String[]> hits = new ArrayList<>();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (p.matcher(e.getKey()).find()) {
                hits.add(new String[]{e.getKey(), e.getValue()});
            }
        }
        return hits;
    }

    // --- loaders -----------------------------------------------------------

    private void loadYaml(Path dir) {
        Path resources = dir.resolve("src").resolve("main").resolve("resources");
        if (!Files.isDirectory(resources)) {
            return;
        }
        // Only the base + prod profiles: merging dev/qa/stage/stage2 pollutes names
        // with env-suffixed values (e.g. spectrum-provision-event-stage2). Base loads
        // first, prod last so prod wins; keys defined by neither fall back to the
        // @Value/inline default, which is the canonical name.
        List<Path> base = new ArrayList<>();
        List<Path> prod = new ArrayList<>();
        try (Stream<Path> s = Files.list(resources)) {
            s.filter(Files::isRegularFile).sorted().forEach(p -> {
                String profile = configProfile(p.getFileName().toString());
                if ("base".equals(profile)) {
                    base.add(p);
                } else if ("prod".equals(profile)) {
                    prod.add(p);
                }
            });
        } catch (IOException e) {
            return;
        }
        base.addAll(prod);
        for (Path p : base) {
            String name = p.getFileName().toString();
            if (name.endsWith(".properties")) {
                loadProperties(WorkspaceScanner.read(p));
            } else {
                flattenYaml(WorkspaceScanner.read(p));
            }
        }
    }

    /** "base", "prod", or null for a Spring config filename (profiles filtered out). */
    private static String configProfile(String name) {
        if (!name.matches("(application|bootstrap).*\\.(yml|yaml|properties)")) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        String stem = name.substring(0, dot); // e.g. application-prod, application
        int dash = stem.indexOf('-');
        if (dash < 0) {
            return "base";
        }
        String profile = stem.substring(dash + 1);
        return profile.equals("prod") || profile.equals("production") ? "prod" : null;
    }

    private void loadProperties(String text) {
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) {
                continue;
            }
            int eq = t.indexOf('=');
            if (eq > 0) {
                props.put(t.substring(0, eq).trim(), stripInlineComment(t.substring(eq + 1).trim()));
            }
        }
    }

    /** Minimal indentation-based YAML flattener for scalar leaves. */
    private void flattenYaml(String yaml) {
        List<String> keyStack = new ArrayList<>();
        List<Integer> indentStack = new ArrayList<>();
        for (String raw : yaml.split("\n")) {
            String line = raw.replace("\t", "  ");
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            int colon = colonIndex(trimmed);
            if (colon < 0) {
                continue;
            }
            int indent = indentOf(line);
            while (!indentStack.isEmpty() && indentStack.get(indentStack.size() - 1) >= indent) {
                indentStack.remove(indentStack.size() - 1);
                keyStack.remove(keyStack.size() - 1);
            }
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();
            if (value.isEmpty()) {
                keyStack.add(key);
                indentStack.add(indent);
            } else {
                String dotted = keyStack.isEmpty() ? key : String.join(".", keyStack) + "." + key;
                // Last write wins, so the prod file (loaded last) overrides the base.
                props.put(dotted, unquote(stripInlineComment(value)));
            }
        }
    }

    private void loadEnv(Path dir) {
        List<Path> ordered = new ArrayList<>();
        Path prod = null;
        for (String sub : List.of("resources", "", "config", "env")) {
            Path base = sub.isEmpty() ? dir : dir.resolve(sub);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> s = Files.list(base)) {
                for (Path p : s.filter(Files::isRegularFile).sorted().toList()) {
                    String n = p.getFileName().toString();
                    // Only base (.env) and prod (.env.prod/.env.production); skip
                    // dev/qa/stage envs so resource names don't get env-suffixed.
                    if (n.equals(".env")) {
                        ordered.add(p);
                    } else if (n.equals(".env.prod") || n.equals(".env.production")) {
                        prod = p;
                    }
                }
            } catch (IOException e) {
                // ignore
            }
        }
        if (prod != null) {
            ordered.add(prod); // prod last so it wins
        }
        for (Path p : ordered) {
            for (String line : WorkspaceScanner.read(p).split("\n")) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                int eq = t.indexOf('=');
                if (eq > 0) {
                    env.put(t.substring(0, eq).trim(), unquote(t.substring(eq + 1).trim()));
                }
            }
        }
    }

    private void loadJava(Path dir) {
        Path main = dir.resolve("src").resolve("main").resolve("java");
        if (!Files.isDirectory(main)) {
            return;
        }
        try (Stream<Path> s = Files.walk(main, 15)) {
            s.filter(p -> p.toString().endsWith(".java")).forEach(this::indexJavaFile);
        } catch (IOException e) {
            // ignore
        }
    }

    private void indexJavaFile(Path p) {
        String text = WorkspaceScanner.read(p);
        Matcher c = CONST_RE.matcher(text);
        while (c.find()) {
            constants.putIfAbsent(c.group(1), c.group(2));
        }
        Matcher v = VALUE_FIELD_RE.matcher(text);
        while (v.find()) {
            String placeholder = v.group(1); // prop or prop:default
            String field = v.group(2);
            int colon = placeholder.indexOf(':');
            String key = colon >= 0 ? placeholder.substring(0, colon) : placeholder;
            String def = colon >= 0 ? placeholder.substring(colon + 1) : null;
            String val = props.getOrDefault(key.trim(), def);
            if (val != null && !val.isBlank()) {
                valueFields.putIfAbsent(field, val);
            }
        }
    }

    // --- helpers -----------------------------------------------------------

    private static void addSplit(List<String> out, String value) {
        if (value == null) {
            return;
        }
        for (String part : value.split(",")) {
            String t = unquote(part.trim());
            if (!t.isEmpty() && !t.startsWith("${")) {
                out.add(t);
            }
        }
    }

    /** Index of the key/value colon, ignoring '::' and inline values with URLs. */
    private static int colonIndex(String trimmed) {
        int colon = trimmed.indexOf(':');
        // "http://..." as a value on a keyless line shouldn't happen (we split on first colon
        // which is the key/value separator in YAML mappings).
        return colon;
    }

    private static String stripInlineComment(String v) {
        int hash = v.indexOf(" #");
        return hash >= 0 ? v.substring(0, hash).trim() : v;
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\""))
                || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) {
                return v;
            }
        }
        return null;
    }
}

package com.flowscope.service;

import com.flowscope.dto.ServiceMap.Evidence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Optional {@code flowscope.yml} (or {@code .yaml}) at the workspace root, for
 * facts that live outside the code: API gateway routing, Lambda triggers defined
 * in another repository, names of external systems, and paths to skip.
 *
 * <pre>
 * hosts:            # host or host+path → service or external system
 *   api.example.com/admin/*: reports-dashboard
 * triggers:         # service[:handler] → topics / queues / schedule
 *   orders-lambda:commandConsumer: { consumes: [orders-commands] }
 * externals:
 *   partner.example.com: Partner Billing API
 * ignore: [legacy/, sandbox/]
 * </pre>
 *
 * Edges that come from this file get confidence {@code declared} and point at it.
 */
final class OverrideFile {

    record HostRule(String host, String pathPrefix, String target, int line) {
    }

    record Trigger(String service, String handler, List<String> consumes, List<String> queues,
                   String schedule, int line) {
    }

    private static final OverrideFile NONE = new OverrideFile(null, "", List.of(), List.of(), List.of(),
            List.of(), List.of());

    final Path file;
    final String rel;
    final List<HostRule> hosts;
    final List<Externals.Rule> externals;
    final List<Trigger> triggers;
    final List<String> ignore;
    final List<String> errors;
    private List<String> lines = List.of();

    private OverrideFile(Path file, String rel, List<HostRule> hosts, List<Externals.Rule> externals,
                         List<Trigger> triggers, List<String> ignore, List<String> errors) {
        this.file = file;
        this.rel = rel;
        this.hosts = hosts;
        this.externals = externals;
        this.triggers = triggers;
        this.ignore = ignore;
        this.errors = errors;
    }

    static OverrideFile none() {
        return NONE;
    }

    static OverrideFile load(Path workspace) {
        Path file = null;
        for (String n : List.of("flowscope.yml", "flowscope.yaml")) {
            if (Files.isRegularFile(workspace.resolve(n))) {
                file = workspace.resolve(n);
                break;
            }
        }
        if (file == null) {
            return NONE;
        }
        String text = WorkspaceScanner.read(file);
        List<String> errors = new ArrayList<>();
        List<HostRule> hosts = new ArrayList<>();
        List<Externals.Rule> externals = new ArrayList<>();
        List<Trigger> triggers = new ArrayList<>();
        List<String> ignore = new ArrayList<>();
        List<String> lines = List.of(text.split("\n", -1));
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(text);
        } catch (RuntimeException e) {
            errors.add(file.getFileName() + " could not be parsed: " + e.getMessage().split("\n")[0]);
            root = null;
        }
        if (root instanceof Map<?, ?> map) {
            if (map.get("hosts") instanceof Map<?, ?> h) {
                h.forEach((k, v) -> {
                    String[] hp = splitHostPath(String.valueOf(k));
                    hosts.add(new HostRule(hp[0], hp[1], String.valueOf(v), lineOf(lines, String.valueOf(k))));
                });
            }
            if (map.get("externals") instanceof Map<?, ?> x) {
                x.forEach((k, v) -> {
                    String[] hp = splitHostPath(String.valueOf(k));
                    externals.add(new Externals.Rule(hp[0], hp[1], String.valueOf(v), true));
                });
            }
            if (map.get("triggers") instanceof Map<?, ?> t) {
                t.forEach((k, v) -> {
                    String key = String.valueOf(k);
                    int colon = key.indexOf(':');
                    String svc = colon >= 0 ? key.substring(0, colon) : key;
                    String handler = colon >= 0 ? key.substring(colon + 1) : null;
                    Map<?, ?> body = v instanceof Map<?, ?> b ? b : Map.of();
                    Object schedule = body.get("schedule");
                    triggers.add(new Trigger(svc.trim(), handler == null ? null : handler.trim(),
                            strings(body.get("consumes")), strings(body.get("queues")),
                            schedule == null ? null : String.valueOf(schedule), lineOf(lines, key)));
                });
            }
            ignore.addAll(strings(map.get("ignore")));
        }
        OverrideFile o = new OverrideFile(file, workspace.relativize(file).toString().replace('\\', '/'),
                hosts, externals, triggers, ignore, errors);
        o.lines = lines;
        return o;
    }

    /** The most specific host rule for a host (+ path), or null. */
    HostRule host(String host, String path) {
        if (host == null) {
            return null;
        }
        String h = host.toLowerCase(Locale.ROOT);
        String p = path == null ? "" : path;
        HostRule best = null;
        for (HostRule r : hosts) {
            boolean hostOk = r.host().startsWith("*.")
                    ? h.endsWith(r.host().substring(1)) || h.equals(r.host().substring(2))
                    : h.equals(r.host());
            if (hostOk && p.startsWith(r.pathPrefix())
                    && (best == null || r.pathPrefix().length() > best.pathPrefix().length())) {
                best = r;
            }
        }
        return best;
    }

    /** True when a workspace-relative path falls under an {@code ignore:} entry. */
    boolean ignored(String relPath) {
        String p = relPath.replace('\\', '/');
        for (String i : ignore) {
            String prefix = i.replaceAll("^\\./", "").replaceAll("/+$", "");
            if (!prefix.isEmpty() && (p.equals(prefix) || p.startsWith(prefix + "/"))) {
                return true;
            }
        }
        return false;
    }

    Evidence evidence(int line) {
        String text = line >= 1 && line <= lines.size() ? SourceIndex.clip(lines.get(line - 1).trim()) : "";
        return new Evidence(rel, line, text, "declared in " + rel);
    }

    private static String[] splitHostPath(String key) {
        String k = key.trim().toLowerCase(Locale.ROOT).replaceFirst("^https?://", "");
        int slash = k.indexOf('/');
        String host = slash >= 0 ? k.substring(0, slash) : k;
        String path = slash >= 0 ? k.substring(slash) : "";
        path = path.replaceAll("\\*+$", "").replaceAll("/+$", "");
        return new String[]{host, path};
    }

    private static List<String> strings(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> list) {
            list.forEach(x -> out.add(String.valueOf(x).trim()));
        } else if (v != null) {
            out.add(String.valueOf(v).trim());
        }
        return out;
    }

    private static int lineOf(List<String> lines, String key) {
        for (int i = 0; i < lines.size(); i++) {
            String t = lines.get(i).trim();
            if (t.startsWith(key + ":") || t.startsWith("\"" + key + "\"") || t.startsWith("'" + key + "'")) {
                return i + 1;
            }
        }
        return 0;
    }
}

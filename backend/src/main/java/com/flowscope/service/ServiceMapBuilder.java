package com.flowscope.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

import com.flowscope.dto.ServiceMap;
import com.flowscope.dto.ServiceMap.Edge;
import com.flowscope.dto.ServiceMap.Evidence;
import com.flowscope.dto.ServiceMap.Node;
import com.flowscope.dto.ServiceMap.Stats;
import static com.flowscope.service.CommScan.DECLARED;
import com.flowscope.service.CommScan.HostRef;
import static com.flowscope.service.CommScan.INFERRED;
import com.flowscope.service.CommScan.Link;
import com.flowscope.service.CommScan.NodeDef;
import com.flowscope.service.CommScan.PathRef;
import static com.flowscope.service.CommScan.UNRESOLVED;
import com.flowscope.service.ServerlessScanner.TriggerDecl;
import com.flowscope.service.ServerlessScanner.Unit;
import com.flowscope.service.WorkspaceScanner.Service;

/**
 * Builds the whole-workspace {@link ServiceMap}: every service is scanned for
 * Kafka, datastores, HTTP/SOAP calls, SDK dependencies, gRPC and serverless
 * triggers (see the {@code *Scanner} classes), then outbound URLs and UI paths are
 * resolved against the other services, the override file is applied, and the
 * results are assembled into nodes and edges.
 *
 * <p>Every edge carries a confidence ({@code declared}, {@code code}, {@code config},
 * {@code inferred}, {@code unresolved}) and file:line evidence, so a reader can
 * check why it is drawn. Detection is pattern-based (not full ASTs), so it works
 * uniformly across Java, Node/TypeScript, Go and Python.
 */
public final class ServiceMapBuilder {

    private static final int MAX_EVIDENCE = 5;
    private static final int MAX_NOTES = 80;
    private static final String NON_ALNUM = "[^a-z0-9]+";

    /** Config-key and service-name words too generic to identify a system. */
    private static final Set<String> GENERIC_KEYS = Set.of(
            "management", "spring", "server", "logging", "cloud", "eureka", "feign",
            "ribbon", "endpoints", "endpoint", "base", "url", "api", "app", "http",
            "client", "service", "config", "aws", "kafka", "datasource", "db", "host",
            "gateway", "internal", "external", "https", "uri", "baseurl", "environment");

    private ServiceMapBuilder() {
    }

    // --- workspace scan -------------------------------------------------------------------

    public static ServiceMap build(Path workspace, Set<String> excludeDirs) {
        OverrideFile overrides = OverrideFile.load(workspace);
        Externals externals = new Externals(overrides.externals);
        Predicate<String> ignored = overrides::ignored;
        List<Service> services = WorkspaceScanner.scan(workspace, excludeDirs, ignored);
        List<String> notes = new ArrayList<>(overrides.errors);

        Map<String, Service> byId = new LinkedHashMap<>();
        Map<String, ConfigIndex> cfgs = new HashMap<>();
        Map<String, SourceIndex> srcs = new HashMap<>();
        Map<String, References> refs = new HashMap<>();
        for (Service s : services) {
            byId.put(s.id, s);
            ConfigIndex cfg = ConfigIndex.forService(s.dir);
            SourceIndex src = SourceIndex.load(s.dir, workspace, ignored);
            cfgs.put(s.id, cfg);
            srcs.put(s.id, src);
            refs.put(s.id, References.of(src, cfg));
        }
        // Keys a shared library reads count as read by the services built on it.
        for (Service s : services) {
            for (String dep : s.dependsOn) {
                Service lib = byId.get(dep);
                if (lib != null && lib.library) {
                    refs.get(s.id).addAll(refs.get(dep));
                }
            }
            if (s.library && s.libraryReason != null && s.language.equals("java")) {
                notes.add(s.displayName + " is treated as a library: " + s.libraryReason);
            }
        }

        // Routes served by each back end (for URL-path and UI-path matching).
        RouteIndex routes = new RouteIndex();
        for (Service s : services) {
            if (!s.library && !HttpScanner.isUi(s.subtype)) {
                routes.add(svcId(s), RouteIndex.routesOf(srcs.get(s.id), cfgs.get(s.id)));
            }
        }

        // IaC outside any service directory (a shared terraform/ folder, …).
        List<TriggerDecl> workspaceIac = new ArrayList<>();
        for (TriggerDecl d : ServerlessScanner.iac(workspace, workspace, 6)) {
            boolean inService = services.stream().anyMatch(s -> d.file().startsWith(s.dir));
            if (!inService && !overrides.ignored(workspace.relativize(d.file()).toString())) {
                workspaceIac.add(d);
            }
        }

        // 1. Scan every service (one scan per exported Lambda handler).
        List<CommScan> scans = new ArrayList<>();
        Map<String, List<CommScan>> scansBySvc = new LinkedHashMap<>();
        for (Service s : services) {
            List<Unit> units = ServerlessScanner.split(s, srcs.get(s.id));
            List<TriggerDecl> own = s.subtype.equals("node-lambda") || s.language.equals("java")
                    ? ServerlessScanner.iac(s.dir, workspace, 6) : List.of();
            boolean split = units.size() > 1;
            Set<String> handlerNames = new HashSet<>();
            units.forEach(u -> {
                if (u.handler() != null) {
                    handlerNames.add(u.handler());
                }
            });
            boolean scriptsDone = false;
            boolean unusedReported = false;
            for (Unit u : units) {
                CommScan sc = new CommScan(s, cfgs.get(s.id), u.src(), workspace, u.handler());
                sc.partOfSplit = split;
                boolean scripts = !scriptsDone && (u.handler() == null || !split);
                scriptsDone |= scripts;
                // A handler only gets the config URLs its own code reads; unused-key
                // notes are reported once per package.
                References unitRefs = refs.get(s.id);
                if (split) {
                    unitRefs = References.of(u.src(), cfgs.get(s.id));
                    for (String dep : s.dependsOn) {
                        if (byId.containsKey(dep) && byId.get(dep).library) {
                            unitRefs.addAll(refs.get(dep));
                        }
                    }
                }
                References unusedRefs = unusedReported ? null : refs.get(s.id);
                unusedReported = true;
                scanUnit(sc, unitRefs, unusedRefs, externals, overrides, scripts);
                List<TriggerDecl> decls = new ArrayList<>();
                for (TriggerDecl d : own) {
                    boolean mine = !split
                            || (u.handler() != null && u.handler().equals(d.handlerExport()))
                            || (u.handler() == null && !handlerNames.contains(String.valueOf(d.handlerExport())));
                    if (mine) {
                        decls.add(d);
                    }
                }
                for (TriggerDecl d : workspaceIac) {
                    if (matchesWorkspaceTrigger(d, s, u.handler(), services)) {
                        decls.add(d);
                    }
                }
                ServerlessScanner.applyTriggers(sc, decls, ServerlessScanner.eventTypes(u.src()));
                scans.add(sc);
                scansBySvc.computeIfAbsent(s.id, k -> new ArrayList<>()).add(sc);
            }
        }

        // 2. Declared triggers from the override file.
        for (OverrideFile.Trigger t : overrides.triggers) {
            List<CommScan> targets = triggerTargets(t, scansBySvc, byId);
            if (targets.isEmpty()) {
                notes.add(overrides.rel + ": trigger " + t.service()
                        + (t.handler() == null ? "" : ":" + t.handler()) + " names no scanned service or handler");
            }
            Evidence ev = overrides.evidence(t.line());
            for (CommScan sc : targets) {
                // A declared trigger replaces the placeholder drawn from the handler's event type.
                sc.links.removeIf(l -> UNRESOLVED.equals(l.confidence())
                        && ((!t.consumes().isEmpty() && l.targetId().equals("ext:self-managed-kafka"))
                        || (!t.queues().isEmpty() && l.targetId().startsWith("ds:sqs:?"))
                        || (t.schedule() != null && l.targetId().startsWith("trigger:"))));
                for (String topic : t.consumes()) {
                    sc.consume(topic, DECLARED, ev);
                }
                if (!t.queues().isEmpty()) {
                    ServerlessScanner.apply(sc, "sqs", t.queues(), ev, DECLARED);
                }
                if (t.schedule() != null) {
                    ServerlessScanner.apply(sc, "schedule", List.of(t.schedule()), ev, DECLARED);
                }
            }
        }

        // 3. Resolve outbound URLs and UI paths to services or external systems.
        Map<String, Set<String>> tokenIndex = new HashMap<>();
        Map<String, Set<String>> svcTokens = new HashMap<>();
        Set<String> uiIds = new HashSet<>();
        for (Service s : services) {
            if (HttpScanner.isUi(s.subtype)) {
                uiIds.add(svcId(s));
            }
            if (s.library || HttpScanner.isUi(s.subtype) || s.subtype.equals("container")) {
                continue;
            }
            Set<String> toks = tokensOf(s.dirName);
            svcTokens.put(svcId(s), toks);
            for (String tok : toks) {
                tokenIndex.computeIfAbsent(tok, k -> new LinkedHashSet<>()).add(svcId(s));
            }
        }
        Resolver resolver = new Resolver(byId, routes, tokenIndex, svcTokens, uiIds, externals, overrides, notes);
        for (CommScan sc : scans) {
            resolver.resolve(sc);
        }

        // 4. Assemble nodes and edges.
        ServiceMap map = assemble(workspace, scans, byId, notes);
        // HTTP routes served, for the Architecture view's APIs column.
        for (Node n : map.nodes()) {
            if ((n.type().equals("service") || n.type().equals("ui")) && n.meta() != null) {
                int count = routes.count(ArchitectureBuilder.pkg(n.id()));
                if (count > 0) {
                    n.meta().put("httpRoutes", count);
                }
            }
        }
        return map;
    }

    /** Run every per-unit scanner. */
    private static void scanUnit(CommScan sc, References refs, References unusedRefs, Externals externals,
                                 OverrideFile overrides, boolean scripts) {
        KafkaScanner.scan(sc);
        DatastoreScanner.scan(sc);
        HttpScanner.scan(sc, refs, unusedRefs, externals, overrides);
        GrpcScanner.scan(sc);
        if (scripts && (sc.svc.subtype.equals("container") || ScriptScanner.applies(sc))) {
            ScriptScanner.scan(sc, sc.svc.subtype.equals("container"), externals);
        }
    }

    private static boolean matchesWorkspaceTrigger(TriggerDecl d, Service s, String handler, List<Service> all) {
        String fn = d.function() == null ? "" : d.function().toLowerCase(Locale.ROOT);
        if (handler != null && handler.equals(d.handlerExport()) && !handler.equals("handler")) {
            return true;
        }
        if (handler == null && !fn.isEmpty() && fn.contains(s.dirName.toLowerCase(Locale.ROOT))) {
            // Only when no other service name is also contained (longest name wins).
            return all.stream().noneMatch(o -> o != s && o.dirName.length() > s.dirName.length()
                    && fn.contains(o.dirName.toLowerCase(Locale.ROOT)));
        }
        return false;
    }

    private static List<CommScan> triggerTargets(OverrideFile.Trigger t, Map<String, List<CommScan>> bySvc,
                                                 Map<String, Service> byId) {
        List<CommScan> out = new ArrayList<>();
        for (Service s : byId.values()) {
            if (!(s.dirName.equalsIgnoreCase(t.service()) || s.id.equalsIgnoreCase(t.service())
                    || s.appName.equalsIgnoreCase(t.service()))) {
                continue;
            }
            List<CommScan> units = bySvc.getOrDefault(s.id, List.of());
            for (CommScan sc : units) {
                if (t.handler() == null ? sc.handler == null : t.handler().equals(sc.handler)) {
                    out.add(sc);
                }
            }
            if (out.isEmpty() && t.handler() == null) {
                out.addAll(units);
            }
        }
        return out;
    }

    // --- URL / path resolution ----------------------------------------------------------------

    private static final class Resolver {
        private final Map<String, Service> byId;
        private final RouteIndex routes;
        private final Map<String, Set<String>> tokenIndex;
        private final Map<String, Set<String>> svcTokens;
        private final Set<String> uiIds;
        private final Externals externals;
        private final OverrideFile overrides;
        private final List<String> notes;

        Resolver(Map<String, Service> byId, RouteIndex routes, Map<String, Set<String>> tokenIndex,
                 Map<String, Set<String>> svcTokens, Set<String> uiIds, Externals externals,
                 OverrideFile overrides, List<String> notes) {
            this.byId = byId;
            this.routes = routes;
            this.tokenIndex = tokenIndex;
            this.svcTokens = svcTokens;
            this.uiIds = uiIds;
            this.externals = externals;
            this.overrides = overrides;
            this.notes = notes;
        }

        void resolve(CommScan sc) {
            String self = svcId(sc.svc);
            Set<String> exclude = new HashSet<>(uiIds);
            exclude.add(self);
            boolean routeMatched = false;
            for (PathRef p : sc.apiPaths) {
                RouteIndex.Match m = routes.best(p.path(), null, exclude, 2);
                if (m == null) {
                    continue;
                }
                routeMatched = true;
                sc.link("rest", m.svcId(), pathLabel(p.path()), INFERRED,
                        withVia(p.evidence(), "route match " + m.route()), false);
            }
            boolean ui = HttpScanner.isUi(sc.svc.subtype);
            for (HostRef h : sc.hosts) {
                resolveHost(sc, h, exclude, ui && routeMatched);
            }
        }

        private void resolveHost(CommScan sc, HostRef h, Set<String> exclude, boolean uiPathsDecide) {
            String kind = edgeKind(h);
            OverrideFile.HostRule rule = overrides.host(h.host(), h.path());
            if (rule != null) {
                Evidence oev = overrides.evidence(rule.line());
                String target = serviceFor(rule.target());
                if (target != null) {
                    sc.link(kind, target, h.host(), DECLARED, oev, false);
                    sc.link(kind, target, h.host(), DECLARED, h.evidence(), false);
                } else {
                    sc.external(rule.target(), "http", kind, h.host(), DECLARED, oev);
                    sc.external(rule.target(), "http", kind, h.host(), DECLARED, h.evidence());
                }
                return;
            }
            if (h.literal()) {
                sc.external(externals.classify(h.host(), h.path()), "http", kind, h.host(), h.confidence(),
                        h.evidence());
                return;
            }
            Set<String> cands = new LinkedHashSet<>();
            if (h.host() != null) {
                for (String label : h.host().split(NON_ALNUM)) {
                    cands.addAll(tokenIndex.getOrDefault(label, Set.of()));
                }
            } else if (h.key() != null) {
                for (String tok : tokensOf(h.key())) {
                    cands.addAll(tokenIndex.getOrDefault(tok, Set.of()));
                }
            }
            cands.removeAll(exclude);
            String target = null;
            String via = null;
            if (h.path() != null) {
                RouteIndex.Match m = routes.best(h.path(), cands.isEmpty() ? null : cands, exclude,
                        cands.isEmpty() ? 2 : 1);
                if (m != null) {
                    target = m.svcId();
                    via = "route match " + m.route();
                }
            }
            boolean sharedToken = cands.size() > 1;
            if (target == null && h.path() != null && !cands.isEmpty()) {
                // A service matched by name whose routes clearly don't serve this path
                // (same first segment, different rest) is not the target.
                Set<String> conflicting = new LinkedHashSet<>();
                for (String c : cands) {
                    if (routes.conflicts(c, h.path())) {
                        conflicting.add(c);
                    }
                }
                if (!conflicting.isEmpty()) {
                    cands.removeAll(conflicting);
                    List<String> names = conflicting.stream().map(c -> byId.get(c.substring(4)))
                            .filter(s -> s != null).map(s -> s.displayName).toList();
                    notes.add(sc.selfLabel() + ": " + h.host() + h.path() + " matches " + String.join(", ", names)
                            + " by name, but no route there serves " + h.path()
                            + "; add a hosts: entry to flowscope.yml if another service handles it");
                }
            }
            if (target == null && !cands.isEmpty()) {
                if (uiPathsDecide) {
                    return; // the UI's relative API paths already named the back end
                }
                if (cands.size() == 1 && !sharedToken) {
                    target = cands.iterator().next();
                    via = "host/key token match";
                } else {
                    Set<String> words = new HashSet<>(List.of((h.host() != null ? h.host() : h.key())
                            .toLowerCase(Locale.ROOT).split(NON_ALNUM)));
                    List<String> full = cands.stream()
                            .filter(c -> words.containsAll(svcTokens.getOrDefault(c, Set.of()))).toList();
                    if (full.size() == 1) {
                        target = full.get(0);
                        via = "host/key token match (all name tokens)";
                    } else if (cands.size() > 1) {
                        ambiguous(sc, h, kind, cands);
                        return;
                    }
                    // else: the one service left shares only a word that several services
                    // use; that is not enough to name it, so treat the host as external.
                }
            }
            if (target != null) {
                sc.link(kind, target, h.host() == null ? "" : h.host(), INFERRED, withVia(h.evidence(),
                        h.evidence().via() + "; service matched by " + via), false);
                return;
            }
            String ext = externalName(externals, h);
            if (ext != null) {
                sc.external(ext, "http", kind, h.host() == null ? "" : h.host(), h.confidence(), h.evidence());
            }
        }

        private void ambiguous(CommScan sc, HostRef h, String kind, Set<String> cands) {
            List<String> names = cands.stream().map(c -> byId.get(c.substring(4)))
                    .filter(s -> s != null).map(s -> s.displayName).toList();
            String what = h.host() != null ? h.host() : h.key();
            String id = "ext:ambiguous:" + CommScan.slug(what);
            sc.node(id, what + " (ambiguous: " + String.join(" | ", names) + ")", "external", "http",
                    Map.of("kind", "Ambiguous host", "unresolved", true, "candidates", names));
            sc.link(kind, id, what, UNRESOLVED, h.evidence(), false);
            notes.add(sc.selfLabel() + ": " + what + " matches several services (" + String.join(", ", names)
                    + "); add a hosts: entry to flowscope.yml to pick one");
        }

        private String serviceFor(String name) {
            for (Service s : byId.values()) {
                if (s.dirName.equalsIgnoreCase(name) || s.id.equalsIgnoreCase(name)
                        || s.appName.equalsIgnoreCase(name)) {
                    return svcId(s);
                }
            }
            return null;
        }
    }

    private static String edgeKind(HostRef h) {
        return switch (h.usage() == null ? "call" : h.usage()) {
            case "reference" -> "reference";
            case "soap" -> "soap";
            default -> "rest";
        };
    }

    private static String pathLabel(String path) {
        List<String> segs = RouteIndex.segments(path);
        return "/" + String.join("/", segs.subList(0, Math.min(3, segs.size())));
    }

    private static Evidence withVia(Evidence ev, String via) {
        return ev == null ? null : new Evidence(ev.file(), ev.line(), ev.snippet(), via);
    }

    /** External system for a config URL: dictionary by host, then by key, then the domain or key name. */
    private static String externalName(Externals externals, HostRef h) {
        String known = externals.classify(h.host(), h.path());
        if (known != null) {
            return known;
        }
        String byKey = externals.classifyKey(h.key());
        if (byKey != null) {
            return byKey;
        }
        boolean ip = h.host() != null && h.host().matches("\\d{1,3}(\\.\\d{1,3}){3}");
        if (h.host() != null && !ip && h.host().contains(".") && !HttpScanner.isInfraHost(h.host())) {
            String[] parts = h.host().split("\\.");
            String pd = parts.length >= 2 ? parts[parts.length - 2] : h.host();
            if (!GENERIC_KEYS.contains(pd) && !Set.of("net", "com", "www", "prod").contains(pd)) {
                return pd;
            }
        }
        if (h.key() != null) {
            for (String seg : h.key().split(NON_ALNUM)) {
                if (seg.length() >= 3 && !GENERIC_KEYS.contains(seg)) {
                    return seg.toUpperCase(Locale.ROOT);
                }
            }
        }
        return null;
    }

    private static Set<String> tokensOf(String name) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : name.toLowerCase(Locale.ROOT).split(NON_ALNUM)) {
            if (part.length() >= 4 && !GENERIC_KEYS.contains(part)) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    // --- assembly ----------------------------------------------------------------------------

    private static final class EdgeAcc {
        final String source;
        final String target;
        final String kind;
        String label;
        String confidence;
        final List<Evidence> evidence = new ArrayList<>();

        EdgeAcc(String source, String target, String kind) {
            this.source = source;
            this.target = target;
            this.kind = kind;
        }
    }

    private static ServiceMap assemble(Path workspace, List<CommScan> scans, Map<String, Service> byId,
                                       List<String> notes) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        Map<String, EdgeAcc> edges = new LinkedHashMap<>();

        for (CommScan sc : scans) {
            Service s = sc.svc;
            boolean isolated = sc.links.isEmpty();
            boolean split = sc.handler != null;
            if (isolated && (s.library || s.subtype.equals("container"))) {
                continue;
            }
            if (isolated && !split && scans.stream().anyMatch(o -> o != sc && o.svc == s)) {
                continue; // the shared-code node of a split package with nothing of its own
            }
            nodes.put(sc.selfId(), serviceNode(sc));
        }
        for (CommScan sc : scans) {
            if (!nodes.containsKey(sc.selfId())) {
                continue;
            }
            for (Link l : sc.links) {
                NodeDef def = sc.nodes.get(l.targetId());
                if (def != null) {
                    Node existing = nodes.get(def.id());
                    if (existing == null) {
                        nodes.put(def.id(), new Node(def.id(), def.label(), def.type(), def.subtype(), null, null,
                                new TreeMap<>(def.meta())));
                    } else if (existing.meta() != null && !existing.type().equals("service")) {
                        CommScan.mergeMeta(existing.meta(), def.meta());
                    }
                } else if (l.targetId().startsWith("svc:") && !nodes.containsKey(l.targetId())) {
                    Service t = byId.get(l.targetId().substring(4));
                    if (t == null) {
                        continue;
                    }
                    nodes.put(l.targetId(), serviceNode(new CommScan(t, null, null, workspace, null)));
                }
                String from = l.inbound() ? l.targetId() : sc.selfId();
                String to = l.inbound() ? sc.selfId() : l.targetId();
                if (from.equals(to)) {
                    continue;
                }
                EdgeAcc acc = edges.computeIfAbsent(from + "|" + to + "|" + l.kind(),
                        k -> new EdgeAcc(from, to, l.kind()));
                if ((acc.label == null || acc.label.isEmpty()) && l.label() != null) {
                    acc.label = l.label();
                }
                acc.confidence = acc.confidence == null ? l.confidence() : stronger(acc.confidence, l.confidence());
                addEvidence(acc.evidence, l.evidence());
            }
        }
        // Topic counts on service nodes.
        Map<String, int[]> counts = new HashMap<>();
        for (EdgeAcc e : edges.values()) {
            if (e.kind.equals("produces")) {
                counts.computeIfAbsent(e.source, k -> new int[2])[0]++;
            } else if (e.kind.equals("consumes") && e.source.startsWith("topic:")) {
                counts.computeIfAbsent(e.target, k -> new int[2])[1]++;
            }
        }
        counts.forEach((id, c) -> {
            Node n = nodes.get(id);
            if (n != null && n.meta() != null) {
                n.meta().put("produces", c[0]);
                n.meta().put("consumes", c[1]);
            }
        });

        // A neutral "uses" edge adds nothing once a reads/writes edge links the same pair.
        Set<String> directed = new HashSet<>();
        for (EdgeAcc e : edges.values()) {
            if (e.kind.equals("reads") || e.kind.equals("writes")) {
                directed.add(e.source + "|" + e.target);
                directed.add(e.target + "|" + e.source);
            }
        }
        edges.values().removeIf(e -> e.kind.equals("uses") && directed.contains(e.source + "|" + e.target));

        List<Edge> out = new ArrayList<>();
        int unresolved = 0;
        for (EdgeAcc e : edges.values()) {
            if (UNRESOLVED.equals(e.confidence)) {
                unresolved++;
            }
            out.add(new Edge(e.source + "->" + e.target + ":" + e.kind, e.source, e.target, e.kind,
                    e.label == null ? "" : e.label, e.confidence, List.copyOf(e.evidence)));
        }

        int svcCount = count(nodes, "service") + count(nodes, "ui");
        int topicCount = count(nodes, "topic");
        int dsCount = count(nodes, "datastore");
        int extCount = count(nodes, "external");
        int grpcCount = count(nodes, "grpc");
        List<String> allNotes = new ArrayList<>();
        allNotes.add(svcCount + " services, " + topicCount + " topics, " + dsCount + " datastores, "
                + extCount + " external systems" + (grpcCount > 0 ? ", " + grpcCount + " gRPC services" : "")
                + " detected");
        if (unresolved > 0) {
            allNotes.add(unresolved + " connections are placeholders for names FlowScope could not resolve"
                    + " (confidence: unresolved)");
        }
        for (CommScan sc : scans) {
            notes.addAll(sc.notes);
        }
        for (String n : new LinkedHashSet<>(notes)) {
            if (allNotes.size() >= MAX_NOTES) {
                allNotes.add("… " + (notes.size() - MAX_NOTES) + " more notes omitted");
                break;
            }
            allNotes.add(n);
        }
        Stats stats = new Stats(svcCount, topicCount, dsCount, extCount, out.size(), allNotes);
        return new ServiceMap(workspace.toString(), new ArrayList<>(nodes.values()), out, stats);
    }

    private static Node serviceNode(CommScan sc) {
        Service s = sc.svc;
        Map<String, Object> meta = new TreeMap<>();
        meta.put("appName", s.appName);
        meta.put("runtime", s.subtype);
        meta.put("produces", 0);
        meta.put("consumes", 0);
        if (sc.handler != null) {
            meta.put("handler", sc.handler);
        }
        meta.putAll(sc.meta);
        if (s.libraryReason != null) {
            meta.put("libraryReason", s.libraryReason);
        }
        boolean ui = HttpScanner.isUi(s.subtype);
        return new Node(sc.selfId(), sc.selfLabel(), ui ? "ui" : "service", s.subtype, s.language, s.id, meta);
    }

    private static int count(Map<String, Node> nodes, String type) {
        return (int) nodes.values().stream().filter(n -> n.type().equals(type)).count();
    }

    static void addEvidence(List<Evidence> list, Evidence ev) {
        if (ev == null || list.size() >= MAX_EVIDENCE) {
            return;
        }
        for (Evidence e : list) {
            if (java.util.Objects.equals(e.file(), ev.file()) && e.line() == ev.line()
                    && java.util.Objects.equals(e.via(), ev.via())) {
                return;
            }
        }
        list.add(ev);
    }

    static String stronger(String a, String b) {
        return CommScan.rank(a) >= CommScan.rank(b) ? a : b;
    }

    private static String svcId(Service s) {
        return "svc:" + s.id;
    }
}

package com.flowscope.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.flowscope.service.SourceIndex.Src;

/**
 * The HTTP routes each service serves, so an outbound URL path (or a UI's
 * relative API path) can be matched to the service that actually handles it.
 * Covers Spring MVC/WebFlux annotations (plus {@code server.servlet.context-path}),
 * NestJS controllers, Express-style routers, Flask/FastAPI decorators and Go muxes.
 */
final class RouteIndex {

    /** A served route: service id + normalized path segments ({@code *} for variables). */
    record Route(String svcId, List<String> segs, String raw) {
    }

    /** The outcome of matching a path. */
    record Match(String svcId, String route, int score) {
    }

    private static final Pattern CLASS_DECL = Pattern.compile("\\b(?:class|interface)\\s+\\w+");
    private static final Pattern REQUEST_MAPPING = Pattern.compile("@RequestMapping\\s*(?:\\(([^)]*)\\))?");
    private static final Pattern METHOD_MAPPING = Pattern.compile(
            "@(?:Get|Post|Put|Delete|Patch|Request)Mapping\\b\\s*(?:\\(([^)]*)\\))?");
    private static final Pattern LEADING_PATHS = Pattern.compile("^\\s*(\\{[^}]*}|\"[^\"]*\")");
    private static final Pattern NAMED_PATHS = Pattern.compile("(?:value|path)\\s*=\\s*(\\{[^}]*}|\"[^\"]*\")");
    private static final Pattern STRING = Pattern.compile("\"([^\"]*)\"");

    private static final Pattern NEST_CONTROLLER = Pattern.compile("@Controller\\(\\s*['\"]([^'\"]*)['\"]");
    private static final Pattern NEST_ROUTE = Pattern.compile(
            "@(?:Get|Post|Put|Delete|Patch|All)\\(\\s*(?:['\"]([^'\"]*)['\"])?\\s*\\)");
    private static final Pattern NEST_PREFIX = Pattern.compile("setGlobalPrefix\\(\\s*['\"]([^'\"]*)['\"]");
    private static final Pattern EXPRESS_ROUTE = Pattern.compile(
            "\\b(?:app|router|server|api|route|fastify)\\.(?:get|post|put|delete|patch|all|route)\\(\\s*['\"`](/[^'\"`]*)['\"`]");
    private static final Pattern PY_ROUTE = Pattern.compile(
            "@\\w+\\.(?:route|get|post|put|delete|patch)\\(\\s*['\"](/[^'\"]*)['\"]");
    private static final Pattern GO_ROUTE = Pattern.compile(
            "(?:HandleFunc|Handle|\\.GET|\\.POST|\\.PUT|\\.DELETE|\\.PATCH|\\.Get|\\.Post|\\.Put|\\.Delete)"
                    + "\\(\\s*\"(/[^\"]*)\"");

    private final Map<String, List<Route>> bySvc = new LinkedHashMap<>();

    void add(String svcId, Collection<String> paths) {
        List<Route> list = bySvc.computeIfAbsent(svcId, k -> new ArrayList<>());
        for (String p : paths) {
            list.add(new Route(svcId, segments(p), p));
        }
    }

    int count(String svcId) {
        List<Route> r = bySvc.get(svcId);
        return r == null ? 0 : r.size();
    }

    boolean hasRoutes(String svcId) {
        List<Route> r = bySvc.get(svcId);
        return r != null && !r.isEmpty();
    }

    /**
     * The service whose routes best match {@code path}: the most leading literal
     * segments in common. Null when no service reaches {@code minScore} or the best
     * score is shared (ambiguous).
     *
     * @param candidates restrict to these service ids, or null for every service
     * @param exclude    service ids never returned (the caller itself)
     */
    Match best(String path, Collection<String> candidates, Set<String> exclude, int minScore) {
        List<String> url = segments(path);
        if (url.isEmpty()) {
            return null;
        }
        Match best = null;
        boolean tie = false;
        for (Map.Entry<String, List<Route>> e : bySvc.entrySet()) {
            String svc = e.getKey();
            if (exclude.contains(svc) || (candidates != null && !candidates.contains(svc))) {
                continue;
            }
            Match svcBest = null;
            for (Route r : e.getValue()) {
                int s = score(url, r.segs());
                if (s > 0 && (svcBest == null || s > svcBest.score())) {
                    svcBest = new Match(svc, r.raw(), s);
                }
            }
            if (svcBest == null) {
                continue;
            }
            if (best == null || svcBest.score() > best.score()) {
                best = svcBest;
                tie = false;
            } else if (svcBest.score() == best.score()) {
                tie = true;
            }
        }
        if (best == null || tie || best.score() < Math.min(minScore, url.size())) {
            return null;
        }
        return best;
    }

    /**
     * Literal segments matched when one path is a prefix of the other (a base URL
     * {@code /admin/api} fits route {@code /admin/api/summary}, and a call to
     * {@code /orders/1/items} fits route {@code /orders/{id}}). Paths that diverge
     * ({@code /auth/gql} vs {@code /auth/nonce}) score 0. Variable segments match
     * anything but don't count.
     */
    private static int score(List<String> url, List<String> route) {
        int literal = 0;
        int n = Math.min(url.size(), route.size());
        for (int i = 0; i < n; i++) {
            String r = route.get(i);
            String u = url.get(i);
            if (r.equals("*") || u.equals("*")) {
                continue;
            }
            if (!r.equalsIgnoreCase(u)) {
                return 0;
            }
            literal++;
        }
        return literal;
    }

    /**
     * True when {@code svcId} serves routes under the same first segment as
     * {@code path} but none of them fits it: the path is then served by something
     * else on the same host (an API gateway routing {@code /auth/*} elsewhere).
     */
    boolean conflicts(String svcId, String path) {
        List<String> url = segments(path);
        List<Route> rs = bySvc.get(svcId);
        if (url.isEmpty() || rs == null || rs.isEmpty()) {
            return false;
        }
        boolean sameRoot = false;
        for (Route r : rs) {
            if (r.segs().isEmpty()) {
                continue;
            }
            if (score(url, r.segs()) > 0) {
                return false;
            }
            sameRoot |= r.segs().get(0).equalsIgnoreCase(url.get(0));
        }
        return sameRoot;
    }

    static List<String> segments(String path) {
        List<String> out = new ArrayList<>();
        if (path == null) {
            return out;
        }
        String p = path;
        int q = p.indexOf('?');
        if (q >= 0) {
            p = p.substring(0, q);
        }
        for (String s : p.split("/")) {
            String t = s.trim();
            if (t.isEmpty()) {
                continue;
            }
            boolean variable = t.startsWith("{") || t.startsWith(":") || t.startsWith("<")
                    || t.equals("*") || t.equals("**") || t.contains("${");
            out.add(variable ? "*" : t.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    // --- extraction --------------------------------------------------------------

    /** Every route a service serves, with its context path / global prefix applied. */
    static List<String> routesOf(SourceIndex src, ConfigIndex cfg) {
        List<String> routes = new ArrayList<>();
        String context = firstNonBlank(cfg.props().get("server.servlet.context-path"),
                cfg.props().get("server.context-path"), cfg.props().get("spring.webflux.base-path"),
                cfg.props().get("spring.mvc.servlet.path"));
        String nestPrefix = "";
        for (Src f : src.prod()) {
            Matcher np = NEST_PREFIX.matcher(f.text);
            if (np.find()) {
                nestPrefix = np.group(1);
            }
        }
        for (Src f : src.prod()) {
            String t = f.text;
            if (f.isJava()) {
                if (t.contains("@FeignClient") || !(t.contains("@RestController") || t.contains("@Controller")
                        || t.contains("@RequestMapping"))) {
                    continue;
                }
                for (String r : spring(t, cfg.constants())) {
                    routes.add(join(cfg.expand(context == null ? "" : context), cfg.expand(r)));
                }
            } else if (f.isNode()) {
                Matcher nc = NEST_CONTROLLER.matcher(t);
                if (nc.find()) {
                    String base = nc.group(1);
                    Matcher nr = NEST_ROUTE.matcher(t);
                    boolean any = false;
                    while (nr.find()) {
                        any = true;
                        routes.add(join(nestPrefix, join(base, nr.group(1) == null ? "" : nr.group(1))));
                    }
                    if (!any) {
                        routes.add(join(nestPrefix, base));
                    }
                }
                collect(EXPRESS_ROUTE, t, routes);
            } else if (f.isPython()) {
                collect(PY_ROUTE, t, routes);
            } else if (f.isGo()) {
                collect(GO_ROUTE, t, routes);
            }
        }
        return routes;
    }

    private static List<String> spring(String text, Map<String, String> constants) {
        List<String> out = new ArrayList<>();
        Matcher cls = CLASS_DECL.matcher(text);
        int classAt = cls.find() ? cls.start() : 0;
        List<String> bases = new ArrayList<>(List.of(""));
        Matcher rm = REQUEST_MAPPING.matcher(text);
        while (rm.find() && rm.start() < classAt) {
            bases = paths(rm.group(1), constants);
        }
        Matcher mm = METHOD_MAPPING.matcher(text);
        boolean any = false;
        while (mm.find()) {
            if (mm.start() < classAt) {
                continue;
            }
            any = true;
            for (String b : bases) {
                for (String p : paths(mm.group(1), constants)) {
                    out.add(join(b, p));
                }
            }
        }
        if (!any) {
            out.addAll(bases);
        }
        return out;
    }

    private static final Pattern NAMED_EXPR = Pattern.compile("(?:value|path)\\s*=\\s*(\\{[^}]*}|[^,)]+)");

    /**
     * Paths built from constants: {@code @RequestMapping(ADMIN_API)},
     * {@code (value = Paths.BASE + "/x")}, {@code ({A, B})}. Elements with an
     * identifier that isn't a known string constant are skipped.
     */
    private static List<String> constantPaths(String args, Map<String, String> constants) {
        String expr = args.trim();
        Matcher named = NAMED_EXPR.matcher(expr);
        if (named.find()) {
            expr = named.group(1).trim();
        } else {
            List<String> top = ParamFlow.splitTopLevel(expr);
            expr = top.isEmpty() ? "" : top.get(0);
        }
        if (expr.startsWith("{") && expr.endsWith("}")) {
            expr = expr.substring(1, expr.length() - 1);
        }
        List<String> out = new ArrayList<>();
        for (String element : ParamFlow.splitTopLevel(expr)) {
            StringBuilder sb = new StringBuilder();
            boolean ok = !element.isBlank();
            for (String piece : element.split("\\+")) {
                String p = piece.trim();
                if (p.length() >= 2 && p.startsWith("\"") && p.endsWith("\"")) {
                    sb.append(p, 1, p.length() - 1);
                } else if (p.matches("[A-Za-z_][\\w.]*")) {
                    String v = constants.get(p.substring(p.lastIndexOf('.') + 1));
                    if (v == null) {
                        ok = false;
                        break;
                    }
                    sb.append(v);
                } else {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                out.add(sb.toString());
            }
        }
        return out;
    }

    /** Path strings of a mapping annotation's arguments ({@code ""} when none). */
    private static List<String> paths(String args, Map<String, String> constants) {
        List<String> out = new ArrayList<>();
        if (args != null) {
            Matcher lead = LEADING_PATHS.matcher(args);
            String part = lead.find() ? lead.group(1) : null;
            if (part == null) {
                Matcher named = NAMED_PATHS.matcher(args);
                part = named.find() ? named.group(1) : null;
            }
            if (part != null && !args.contains("+")) {
                Matcher s = STRING.matcher(part);
                while (s.find()) {
                    out.add(s.group(1));
                }
            }
            if (out.isEmpty() && constants != null) {
                out.addAll(constantPaths(args, constants));
            }
        }
        if (out.isEmpty()) {
            out.add("");
        }
        return out;
    }

    private static void collect(Pattern p, String text, List<String> into) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            into.add(m.group(1));
        }
    }

    private static String join(String a, String b) {
        String x = a == null ? "" : a.replaceAll("/+$", "");
        String y = b == null ? "" : b.replaceAll("^/+", "");
        if (x.isEmpty()) {
            return "/" + y;
        }
        if (!x.startsWith("/")) {
            x = "/" + x;
        }
        return y.isEmpty() ? x : x + "/" + y;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}

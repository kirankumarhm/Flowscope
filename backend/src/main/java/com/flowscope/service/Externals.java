package com.flowscope.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The dictionary of known third-party systems. Each entry names exactly one
 * system. Entries from the override file ({@code flowscope.yml} {@code externals:})
 * are checked first and may key on host <em>and</em> path prefix, so one shared
 * API gateway can resolve to several back-end systems.
 */
final class Externals {

    /** A dictionary entry. {@code host} is a host fragment (built-ins) or a host pattern (overrides). */
    record Rule(String host, String pathPrefix, String name, boolean exactHost) {
    }

    /** Built-in fragments, matched on DNS-label boundaries; most specific first. */
    private static final List<Rule> BUILT_IN = new ArrayList<>();

    static {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        m.put("appattest", "Apple DeviceCheck");
        m.put("devicecheck", "Apple DeviceCheck");
        m.put("apple", "Apple");
        m.put("playintegrity", "Google Play Integrity");
        m.put("fcm.googleapis", "Firebase FCM");
        m.put("fcm", "Firebase FCM");
        m.put("firebaseio", "Firebase Realtime Database");
        m.put("firebase", "Firebase");
        m.put("googleapis", "Google APIs");
        m.put("smartsheet", "Smartsheet");
        m.put("datadoghq", "Datadog");
        m.put("cognito", "AWS Cognito");
        m.forEach((k, v) -> BUILT_IN.add(new Rule(k, "", v, false)));
        // Longer fragments are more specific (fcm.googleapis before googleapis).
        BUILT_IN.sort(Comparator.comparingInt((Rule r) -> r.host().length()).reversed());
    }

    private final List<Rule> overrides;

    Externals(List<Rule> overrides) {
        this.overrides = overrides;
    }

    static Externals builtIn() {
        return new Externals(List.of());
    }

    /** The system a host (and optional path) belongs to, or null if unknown. */
    String classify(String host, String path) {
        if (host == null || host.isBlank()) {
            return null;
        }
        String h = host.toLowerCase(Locale.ROOT);
        String p = path == null ? "" : path;
        Rule best = null;
        for (Rule r : overrides) {
            if (hostMatches(r, h) && p.startsWith(r.pathPrefix())
                    && (best == null || r.pathPrefix().length() > best.pathPrefix().length())) {
                best = r;
            }
        }
        if (best != null) {
            return best.name();
        }
        for (Rule r : BUILT_IN) {
            if (fragmentMatches(h, r.host())) {
                return r.name();
            }
        }
        return null;
    }

    /** Dictionary lookup on a config key's segments (e.g. {@code smartsheet.api.url}). */
    String classifyKey(String key) {
        if (key == null) {
            return null;
        }
        String k = key.toLowerCase(Locale.ROOT);
        for (Rule r : BUILT_IN) {
            if (fragmentMatches(k, r.host())) {
                return r.name();
            }
        }
        return null;
    }

    private static boolean hostMatches(Rule r, String host) {
        String pattern = r.host();
        if (pattern.startsWith("*.")) {
            return host.endsWith(pattern.substring(1)) || host.equals(pattern.substring(2));
        }
        return host.equals(pattern);
    }

    /**
     * A fragment containing a dot matches as a substring; otherwise it must equal
     * a label (split on {@code . - _}) or, when 5+ characters long, sit inside one.
     * So "fcm" must be a whole label ("fcm.googleapis.com"), but "cognito" also matches "amazoncognito".
     */
    static boolean fragmentMatches(String text, String fragment) {
        if (fragment.contains(".")) {
            return text.contains(fragment);
        }
        for (String label : text.split("[.\\-_/:]+")) {
            if (label.equals(fragment) || (fragment.length() >= 5 && label.contains(fragment))) {
                return true;
            }
        }
        return false;
    }
}

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
 * Follows a value that reaches a call as a <em>method parameter</em> back to the
 * arguments passed at each call site. Used when a producer is wrapped in a helper:
 *
 * <pre>
 *   private void ack(String id, String topic, Ack a) { kafkaTemplate.send(topic, json(a)); }
 *   ack(id, this.successTopic, a);          // → successTopic → @Value → real topic
 * </pre>
 *
 * Text-based (Java and TypeScript share the shape), limited to two levels.
 */
final class ParamFlow {

    // name(params) [throws X] [: ReturnType] {   — Java methods and TS methods/functions
    private static final Pattern METHOD_DECL = Pattern.compile(
            "(\\w+)\\s*\\(([^()]*)\\)\\s*(?:throws\\s+[\\w.,\\s]+)?(?::\\s*[\\w<>\\[\\]|.,\\s]+)?\\{");
    // name = [async] (params) [: T] =>        — TS/JS arrow functions
    private static final Pattern ARROW_DECL = Pattern.compile(
            "(\\w+)\\s*=\\s*(?:async\\s*)?\\(([^()]*)\\)\\s*(?::\\s*[^=;{]+)?=>");
    private static final Set<String> KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized", "return", "function",
            "else", "try", "do", "new", "constructor");
    private static final Pattern IDENT = Pattern.compile("(?:this\\.|self\\.)?[A-Za-z_$][\\w$]*");

    record Decl(String name, String params, int nameStart) {
    }

    /**
     * A value found at a call site.
     *
     * @param file       the file holding the call that passes the value
     * @param callOffset offset of that call
     * @param caller     the method/function containing that call, or null
     */
    record Site<T>(T value, Src file, int callOffset, String caller) {
    }

    private ParamFlow() {
    }

    /**
     * Resolve {@code token} (a bare parameter name used at {@code offset} in {@code f})
     * through the call sites of its enclosing method, anywhere in the service.
     *
     * @param resolver turns an argument expression at a call site into concrete values
     */
    static <T> List<T> resolve(SourceIndex src, Src f, int offset, String token,
                               BiFunction<Src, String, List<T>> resolver, int depth) {
        Set<T> seen = new LinkedHashSet<>();
        for (Site<T> s : resolveSites(src, f, offset, token, resolver, depth)) {
            seen.add(s.value());
        }
        return new ArrayList<>(seen);
    }

    /** As {@link #resolve}, keeping where each value was passed. */
    static <T> List<Site<T>> resolveSites(SourceIndex src, Src f, int offset, String token,
                                          BiFunction<Src, String, List<T>> resolver, int depth) {
        List<Site<T>> out = new ArrayList<>();
        if (token == null || !IDENT.matcher(token.trim()).matches()) {
            return out;
        }
        String name = token.trim().replaceFirst("^(this|self)\\.", "");
        Decl decl = enclosing(f.text, offset);
        if (decl == null) {
            return out;
        }
        int idx = paramIndex(decl.params(), name);
        if (idx < 0) {
            return out;
        }
        Pattern call = Pattern.compile("(?<![\\w$])" + Pattern.quote(decl.name()) + "\\s*\\(");
        for (Src g : src.prod()) {
            if (!g.text.contains(decl.name())) {
                continue;
            }
            Matcher c = call.matcher(g.text);
            while (c.find()) {
                if (g == f && c.start() == decl.nameStart()) {
                    continue; // the declaration itself
                }
                List<String> args = args(g.text, c.end() - 1);
                if (args == null || idx >= args.size()) {
                    continue;
                }
                String arg = args.get(idx).trim();
                List<T> r = resolver.apply(g, arg);
                if (r.isEmpty() && depth < 1) {
                    out.addAll(resolveSites(src, g, c.start(), arg, resolver, depth + 1));
                    continue;
                }
                Decl caller = enclosing(g.text, c.start());
                for (T v : r) {
                    out.add(new Site<>(v, g, c.start(), caller == null ? null : caller.name()));
                }
            }
        }
        return out;
    }

    /**
     * True when some file of {@code src} calls method/function {@code name}: a
     * receiver call ({@code x.name(}) or a bare call that isn't its own declaration.
     */
    static boolean isCalled(SourceIndex src, String name) {
        Pattern call = Pattern.compile("(?<![\\w$])" + Pattern.quote(name) + "\\s*\\(");
        for (Src g : src.prod()) {
            if (!g.text.contains(name)) {
                continue;
            }
            Matcher c = call.matcher(g.text);
            while (c.find()) {
                int before = c.start() - 1;
                while (before >= 0 && Character.isWhitespace(g.text.charAt(before))) {
                    before--;
                }
                if (before >= 0 && g.text.charAt(before) == '.') {
                    return true;
                }
                List<String> a = args(g.text, c.end() - 1);
                int close = closingParen(g.text, c.end() - 1);
                if (a == null || close < 0) {
                    continue;
                }
                int next = close + 1;
                while (next < g.text.length() && Character.isWhitespace(g.text.charAt(next))) {
                    next++;
                }
                char n = next < g.text.length() ? g.text.charAt(next) : ';';
                if (n != '{' && n != ':' && !g.text.startsWith("throws", next) && !g.text.startsWith("=>", next)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Offset of the ')' closing the '(' at {@code open}, or -1. */
    static int closingParen(String text, int open) {
        int depth = 0;
        char quote = 0;
        int limit = Math.min(text.length(), open + 4000);
        for (int i = open; i < limit; i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** The method/function declaration that most closely precedes {@code offset}. */
    static Decl enclosing(String text, int offset) {
        Decl best = null;
        for (Pattern p : List.of(METHOD_DECL, ARROW_DECL)) {
            Matcher m = p.matcher(text);
            while (m.find() && m.start() < offset) {
                if (KEYWORDS.contains(m.group(1))) {
                    continue;
                }
                if (best == null || m.start(1) > best.nameStart()) {
                    best = new Decl(m.group(1), m.group(2), m.start(1));
                }
            }
        }
        return best;
    }

    /** Index of parameter {@code name} in a Java or TypeScript parameter list. */
    static int paramIndex(String params, String name) {
        List<String> parts = splitTopLevel(params);
        for (int i = 0; i < parts.size(); i++) {
            if (name.equals(paramName(parts.get(i)))) {
                return i;
            }
        }
        return -1;
    }

    private static String paramName(String raw) {
        String p = raw.replaceAll("@\\w+(\\([^)]*\\))?", " ").trim();
        int eq = p.indexOf('=');
        if (eq >= 0) {
            p = p.substring(0, eq).trim();
        }
        int colon = p.indexOf(':');
        if (colon >= 0) { // TypeScript: name[?]: Type, with optional modifiers
            String n = p.substring(0, colon).replace("?", "").trim();
            String[] words = n.split("\\s+");
            return words[words.length - 1];
        }
        String[] words = p.split("[\\s.]+"); // Java: [final] Type name
        return words.length == 0 ? "" : words[words.length - 1].replace("...", "");
    }

    /** Arguments of the call whose '(' is at {@code open}, or null if unbalanced. */
    static List<String> args(String text, int open) {
        if (open < 0 || open >= text.length() || text.charAt(open) != '(') {
            return null;
        }
        int depth = 0;
        char quote = 0;
        int limit = Math.min(text.length(), open + 4000);
        for (int i = open; i < limit; i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                quote = c;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    return splitTopLevel(text.substring(open + 1, i));
                }
            }
        }
        return null;
    }

    /** Split on commas that are not nested in (), [], {}, <> or quotes. */
    static List<String> splitTopLevel(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) {
            return out;
        }
        int depth = 0;
        char quote = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'' || c == '`') {
                quote = c;
            } else if ("([{<".indexOf(c) >= 0) {
                depth++;
            } else if (c == '>' && i > 0 && (s.charAt(i - 1) == '=' || s.charAt(i - 1) == '-')) {
                // arrow (=>, ->), not a closing generic bracket
            } else if (")]}>".indexOf(c) >= 0) {
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(s.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(s.substring(start).trim());
        return out;
    }
}

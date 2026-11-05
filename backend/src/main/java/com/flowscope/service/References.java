package com.flowscope.service;

import com.flowscope.service.SourceIndex.Src;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The config keys and environment variables a service's code actually reads.
 * A URL in config only becomes an arrow when its key is in here, because
 * legacy config files keep settings for integrations removed long ago.
 *
 * <p>Keys compare in Spring's relaxed form (lower case, no {@code -} or {@code _}),
 * so {@code partner.api.base-url} matches {@code @ConfigurationProperties("partner.api")}
 * with a {@code baseUrl} field.
 */
final class References {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{\\s*([\\w.\\-\\[\\]]+)");
    private static final Pattern CONFIG_PROPS = Pattern.compile(
            "@ConfigurationProperties\\(\\s*(?:(?:prefix|value)\\s*=\\s*)?\"([\\w.\\-]+)\"");
    private static final Pattern GET_PROPERTY = Pattern.compile(
            "get(?:Required)?Property\\(\\s*\"([\\w.\\-]+)\"");
    private static final Pattern JAVA_ENV = Pattern.compile("System\\.getenv\\(\\s*\"(\\w+)\"");
    private static final Pattern NODE_ENV = Pattern.compile(
            "process\\.env\\.(\\w+)|process\\.env\\[\\s*['\"](\\w+)['\"]\\s*]|import\\.meta\\.env\\.(\\w+)");
    private static final Pattern NODE_ENV_DESTRUCTURE = Pattern.compile(
            "(?:const|let|var)\\s*\\{([^}]*)}\\s*=\\s*process\\.env");
    private static final Pattern GO_ENV = Pattern.compile(
            "os\\.(?:Getenv|LookupEnv)\\(\\s*\"(\\w+)\"|viper\\.\\w+\\(\\s*\"([\\w.\\-]+)\"");
    private static final Pattern PY_ENV = Pattern.compile(
            "os\\.(?:getenv|environ\\.get)\\(\\s*['\"](\\w+)['\"]|os\\.environ\\[\\s*['\"](\\w+)['\"]"
                    + "|(?:config|settings|conf)\\.get\\(\\s*['\"]([\\w.\\-]+)['\"]");
    private static final Pattern ANGULAR_ENV = Pattern.compile("\\benvironment\\.([\\w.]+)");

    private final Set<String> keys = new HashSet<>();
    private final Set<String> prefixes = new HashSet<>();
    private final Set<String> env = new HashSet<>();

    static References of(SourceIndex src, ConfigIndex cfg) {
        References r = new References();
        for (Src f : src.prod()) {
            String t = f.text;
            each(PLACEHOLDER, t, m -> r.keys.add(canonical(m.group(1))));
            each(CONFIG_PROPS, t, m -> r.prefixes.add(canonical(m.group(1))));
            each(GET_PROPERTY, t, m -> r.keys.add(canonical(m.group(1))));
            each(JAVA_ENV, t, m -> r.env.add(m.group(1)));
            each(NODE_ENV, t, m -> r.env.add(first(m)));
            each(NODE_ENV_DESTRUCTURE, t, m -> {
                for (String part : m.group(1).split(",")) {
                    String name = part.trim().split("[:=\\s]")[0].trim();
                    if (!name.isEmpty()) {
                        r.env.add(name);
                    }
                }
            });
            each(GO_ENV, t, m -> {
                if (m.group(1) != null) {
                    r.env.add(m.group(1));
                } else {
                    r.keys.add(canonical(m.group(2)));
                }
            });
            each(PY_ENV, t, m -> {
                String v = first(m);
                r.env.add(v);
                r.keys.add(canonical(v));
            });
            if (f.isNode()) {
                each(ANGULAR_ENV, t, m -> r.keys.add(canonical("environment." + m.group(1))));
            }
        }
        // A config value that interpolates another key (url: ${partner.base-url}) reads it.
        for (Map.Entry<String, String> e : cfg.props().entrySet()) {
            if (e.getValue() != null) {
                each(PLACEHOLDER, e.getValue(), m -> {
                    r.keys.add(canonical(m.group(1)));
                    r.env.add(m.group(1));
                });
            }
        }
        return r;
    }

    void addAll(References other) {
        keys.addAll(other.keys);
        prefixes.addAll(other.prefixes);
        env.addAll(other.env);
    }

    /** True when code reads the props key (directly, by prefix binding, or via the framework). */
    boolean prop(String key) {
        String k = canonical(key);
        if (k.startsWith("spring.security.oauth2.")
                || k.matches("(spring\\.cloud\\.openfeign|feign)\\.client\\.config\\.[^.]+\\.url")) {
            return true; // consumed by the framework itself
        }
        if (keys.contains(k)) {
            return true;
        }
        for (String p : prefixes) {
            if (k.startsWith(p + ".")) {
                return true;
            }
        }
        return false;
    }

    boolean env(String var) {
        return env.contains(var);
    }

    static String canonical(String key) {
        return key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    private static String first(Matcher m) {
        for (int i = 1; i <= m.groupCount(); i++) {
            if (m.group(i) != null) {
                return m.group(i);
            }
        }
        return "";
    }

    private static void each(Pattern p, String text, java.util.function.Consumer<Matcher> action) {
        Matcher m = p.matcher(text);
        while (m.find()) {
            action.accept(m);
        }
    }
}

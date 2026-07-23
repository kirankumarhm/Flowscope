package com.flowscope.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Discovers the individual applications ("services") in a workspace directory,
 * across languages. A service is a directory holding a recognised build manifest:
 * {@code pom.xml} (Java/Maven), {@code package.json} (Node/TypeScript — plain
 * Node, NestJS, or Next.js), {@code go.mod} (Go), or a Python manifest
 * ({@code requirements.txt}, {@code pyproject.toml}, {@code setup.py},
 * {@code Pipfile}, {@code manage.py}).
 *
 * <p>Multi-module Maven aggregators (e.g. {@code cm-platform}) are expanded into
 * their leaf modules; scaffolding ({@code service-template}), test-only modules,
 * and build output are skipped.
 */
public final class WorkspaceScanner {

    /** Directory names never treated as (or descended into as) services. */
    private static final Set<String> SKIP_DIRS = Set.of(
            "node_modules", "target", "dist", "build", "out", "coverage",
            ".git", ".idea", ".vscode", "service-template", "tests", "test",
            "__tests__", "__mocks__", "src", "resources",
            "vendor", "venv", ".venv", "__pycache__", ".next", ".gradle");

    /** How deep to descend looking for services in a monorepo (services/, apps/, …). */
    private static final int MAX_DISCOVERY_DEPTH = 3;

    private WorkspaceScanner() {
    }

    /** A discovered application in the workspace. */
    public static final class Service {
        public final String id;          // workspace-relative path, stable node id source
        public final String dirName;     // leaf directory name
        public final String displayName; // shown on the node (the directory name)
        public final String appName;     // spring.application.name / package name (metadata)
        public final Path dir;           // absolute directory
        public final String language;    // java | typescript | javascript | go | python
        public final String subtype;     // spring-boot | node-lambda | node-service | nestjs | nextjs | react | go-service | python-service | library
        public final boolean library;

        Service(String id, String dirName, String displayName, String appName, Path dir,
                String language, String subtype, boolean library) {
            this.id = id;
            this.dirName = dirName;
            this.displayName = displayName;
            this.appName = appName;
            this.dir = dir;
            this.language = language;
            this.subtype = subtype;
            this.library = library;
        }
    }

    /**
     * Discover every service under {@code workspace}, skipping any top-level
     * directory whose name is in {@code excludeDirs}.
     */
    public static List<Service> scan(Path workspace, Set<String> excludeDirs) {
        List<Service> services = new ArrayList<>();
        // Descend into the workspace looking for services. The root itself is never
        // treated as a single service here (a monorepo root often carries its own
        // package.json — e.g. CORTEX — which must not shadow the services nested
        // under services/, apps/, …); the single-app fallback below handles the
        // "user pointed straight at one app" case instead.
        discover(workspace, workspace, services, excludeDirs, 0);
        // Fallback: the given path may itself be a single application directory
        // rather than a workspace of many (e.g. the user pointed at cm-admin-api
        // instead of its parent). Discovering nothing from the children while the
        // directory itself carries a build manifest means exactly that — treat it
        // as a one-service workspace so its Kafka/REST/datastore usage still shows.
        if (services.isEmpty() && isServiceDir(workspace)) {
            Path base = workspace.getParent() != null ? workspace.getParent() : workspace;
            collect(base, workspace, services);
        }
        return services;
    }

    /**
     * Recursively find services under {@code dir}. A directory that is itself a
     * buildable service is collected and NOT descended into (so we never walk a
     * service's own {@code internal/}, {@code cmd/}, sub-packages as separate
     * services). Container directories (a monorepo's {@code services/}, {@code apps/})
     * are descended up to {@link #MAX_DISCOVERY_DEPTH}. The workspace root itself
     * ({@code depth == 0}) is always descended, never collected as one service.
     */
    private static void discover(Path workspace, Path dir, List<Service> out,
                                 Set<String> excludeDirs, int depth) {
        if (depth > 0 && isServiceDir(dir)) {
            collect(workspace, dir, out);
            return;
        }
        if (depth >= MAX_DISCOVERY_DEPTH) {
            return;
        }
        List<Path> children;
        try (Stream<Path> s = Files.list(dir)) {
            children = s.filter(Files::isDirectory).sorted().toList();
        } catch (IOException e) {
            return;
        }
        for (Path child : children) {
            String name = fileName(child);
            if (SKIP_DIRS.contains(name) || name.startsWith(".")
                    || (depth == 0 && excludeDirs.contains(name))) {
                continue;
            }
            discover(workspace, child, out, excludeDirs, depth + 1);
        }
    }

    /** True when {@code dir} itself is a buildable service (Maven/Node/Go/Python). */
    private static boolean isServiceDir(Path dir) {
        return Files.isRegularFile(dir.resolve("pom.xml"))
                || Files.isRegularFile(dir.resolve("package.json"))
                || Files.isRegularFile(dir.resolve("go.mod"))
                || isPythonManifest(dir);
    }

    /** True when {@code dir} carries a recognised Python build/run manifest. */
    private static boolean isPythonManifest(Path dir) {
        return Files.isRegularFile(dir.resolve("requirements.txt"))
                || Files.isRegularFile(dir.resolve("pyproject.toml"))
                || Files.isRegularFile(dir.resolve("setup.py"))
                || Files.isRegularFile(dir.resolve("Pipfile"))
                || Files.isRegularFile(dir.resolve("manage.py"));
    }

    /** Classify {@code dir}: a leaf service, or a Maven aggregator to expand. */
    private static void collect(Path workspace, Path dir, List<Service> out) {
        Path pom = dir.resolve("pom.xml");
        Path pkg = dir.resolve("package.json");

        if (Files.isRegularFile(pkg)) {
            out.add(nodeService(workspace, dir, pkg));
            return;
        }
        if (Files.isRegularFile(pom)) {
            List<Path> modules = mavenModules(dir);
            if (!modules.isEmpty() && !hasJavaSources(dir)) {
                // Pure aggregator: expand into leaf modules.
                for (Path m : modules) {
                    collect(workspace, m, out);
                }
            } else {
                out.add(javaService(workspace, dir));
            }
            return;
        }
        if (Files.isRegularFile(dir.resolve("go.mod"))) {
            out.add(goService(workspace, dir));
            return;
        }
        if (isPythonManifest(dir)) {
            out.add(pythonService(workspace, dir));
        }
    }

    private static Service javaService(Path workspace, Path dir) {
        String dirName = fileName(dir);
        // A deployable service has a @SpringBootApplication entry point; a module
        // that merely depends on Spring Boot (e.g. cm-commons) is a library.
        boolean isApp = hasSpringBootApp(dir);
        boolean library = !isApp;
        String subtype = isApp ? "spring-boot" : "library";
        // The directory name is the canonical service identity across this workspace
        // (matches the architecture docs); spring.application.name is kept as metadata.
        String appName = firstNonBlank(springApplicationName(dir), dirName);
        return new Service(rel(workspace, dir), dirName, dirName, appName, dir, "java", subtype, library);
    }

    private static Service nodeService(Path workspace, Path dir, Path pkg) {
        String dirName = fileName(dir);
        String json = read(pkg);
        boolean typescript = Files.isRegularFile(dir.resolve("tsconfig.json"))
                || json.contains("typescript");
        // Next.js pulls in React, so it must be checked before plain React.
        boolean next = json.contains("\"next\"") || hasNextConfig(dir);
        boolean nest = json.contains("@nestjs/") || Files.isRegularFile(dir.resolve("nest-cli.json"));
        boolean react = json.contains("\"react\"");
        boolean lambda = json.toLowerCase(Locale.ROOT).contains("lambda")
                || json.contains("aws-lambda")
                || Files.isRegularFile(dir.resolve("template.yaml"))
                || Files.isRegularFile(dir.resolve("serverless.yml"));
        boolean library = dirName.contains("common") || dirName.contains("utils");
        String subtype = next ? "nextjs"
                : nest ? "nestjs"
                : react ? "react"
                : library ? "library"
                : lambda ? "node-lambda"
                : "node-service";
        String appName = firstNonBlank(packageName(json), dirName);
        String language = typescript ? "typescript" : "javascript";
        return new Service(rel(workspace, dir), dirName, dirName, appName, dir, language, subtype, library);
    }

    private static boolean hasNextConfig(Path dir) {
        return Files.isRegularFile(dir.resolve("next.config.js"))
                || Files.isRegularFile(dir.resolve("next.config.mjs"))
                || Files.isRegularFile(dir.resolve("next.config.ts"));
    }

    private static Service goService(Path workspace, Path dir) {
        String dirName = fileName(dir);
        String appName = firstNonBlank(goModuleName(read(dir.resolve("go.mod"))), dirName);
        // Go modules are kept as services (not libraries); comm-less ones are
        // filtered out downstream by the isolated-node check anyway.
        return new Service(rel(workspace, dir), dirName, dirName, appName, dir, "go", "go-service", false);
    }

    private static Service pythonService(Path workspace, Path dir) {
        String dirName = fileName(dir);
        return new Service(rel(workspace, dir), dirName, dirName, dirName, dir, "python", "python-service", false);
    }

    /** Last path segment of the {@code module <path>} line in a go.mod, or null. */
    private static String goModuleName(String goMod) {
        for (String raw : goMod.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("module ")) {
                String mod = line.substring("module ".length()).trim();
                int slash = mod.lastIndexOf('/');
                return slash >= 0 ? mod.substring(slash + 1) : mod;
            }
        }
        return null;
    }

    // --- Maven helpers -----------------------------------------------------

    /** Child directories declared as {@code <module>} entries with their own pom. */
    private static List<Path> mavenModules(Path dir) {
        String pom = read(dir.resolve("pom.xml"));
        List<Path> modules = new ArrayList<>();
        int i = 0;
        while ((i = pom.indexOf("<module>", i)) >= 0) {
            int end = pom.indexOf("</module>", i);
            if (end < 0) {
                break;
            }
            String mod = pom.substring(i + "<module>".length(), end).trim();
            Path child = dir.resolve(mod).normalize();
            if (Files.isRegularFile(child.resolve("pom.xml")) && !SKIP_DIRS.contains(fileName(child))) {
                modules.add(child);
            }
            i = end + 1;
        }
        return modules;
    }

    private static boolean hasJavaSources(Path dir) {
        Path main = dir.resolve("src").resolve("main").resolve("java");
        if (!Files.isDirectory(main)) {
            return false;
        }
        try (Stream<Path> s = Files.walk(main, 12)) {
            return s.anyMatch(p -> p.toString().endsWith(".java"));
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasSpringBootApp(Path dir) {
        Path main = dir.resolve("src").resolve("main").resolve("java");
        if (!Files.isDirectory(main)) {
            return false;
        }
        try (Stream<Path> s = Files.walk(main, 12)) {
            return s.filter(p -> p.toString().endsWith(".java"))
                    .anyMatch(p -> read(p).contains("@SpringBootApplication"));
        } catch (IOException e) {
            return false;
        }
    }

    /** spring.application.name from application.yml / bootstrap.yml (any profile). */
    private static String springApplicationName(Path dir) {
        Path resources = dir.resolve("src").resolve("main").resolve("resources");
        if (!Files.isDirectory(resources)) {
            return null;
        }
        for (String f : List.of("bootstrap.yml", "application.yml", "bootstrap.yaml", "application.yaml")) {
            Path p = resources.resolve(f);
            if (Files.isRegularFile(p)) {
                String name = yamlApplicationName(read(p));
                if (name != null && !name.startsWith("${")) {
                    return name;
                }
            }
        }
        return null;
    }

    /** Extract spring.application.name from a YAML body (indentation-based). */
    private static String yamlApplicationName(String yaml) {
        boolean inSpring = false;
        boolean inApplication = false;
        for (String raw : yaml.split("\n")) {
            String line = raw.replace("\t", "  ");
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int indent = indentOf(line);
            if (indent == 0) {
                inSpring = trimmed.startsWith("spring:");
                inApplication = false;
            } else if (inSpring && indent == 2) {
                inApplication = trimmed.startsWith("application:");
            } else if (inSpring && inApplication && indent >= 4 && trimmed.startsWith("name:")) {
                return valueOf(trimmed);
            }
        }
        return null;
    }

    private static String packageName(String json) {
        int i = json.indexOf("\"name\"");
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i);
        int q1 = json.indexOf('"', colon + 1);
        int q2 = json.indexOf('"', q1 + 1);
        if (q1 < 0 || q2 < 0) {
            return null;
        }
        String name = json.substring(q1 + 1, q2).trim();
        // Strip npm scope (@scope/name → name) for display.
        int slash = name.lastIndexOf('/');
        return slash >= 0 ? name.substring(slash + 1) : name;
    }

    // --- small utilities ---------------------------------------------------

    private static int indentOf(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    private static String valueOf(String keyLine) {
        int colon = keyLine.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String v = keyLine.substring(colon + 1).trim();
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
            v = v.substring(1, v.length() - 1);
        }
        return v.isEmpty() ? null : v;
    }

    /**
     * Walk source files under {@code root}, pruning heavy/irrelevant directories
     * (node_modules, target, dist, …) so we never descend into dependency trees.
     * Only regular files whose name passes {@code accept} are handed to {@code action}.
     */
    public static void walkSourceFiles(Path root, java.util.function.Predicate<String> accept,
                                       java.util.function.Consumer<Path> action) {
        if (!Files.isDirectory(root)) {
            return;
        }
        try {
            Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(
                        Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    String n = fileName(dir);
                    if (!dir.equals(root) && (PRUNE_DIRS.contains(n) || n.startsWith("."))) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(
                        Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && accept.test(fileName(file))) {
                        action.accept(file);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // best-effort
        }
    }

    /** Directory names pruned from any source walk (dependency/build output). */
    private static final Set<String> PRUNE_DIRS = Set.of(
            "node_modules", "target", "dist", "build", "out", "coverage",
            ".git", ".idea", ".vscode", ".gradle", ".mvn", "bin", "obj");

    static String read(Path p) {
        try {
            if (Files.isRegularFile(p) && Files.size(p) < 2_000_000) {
                return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            // ignore
        }
        return "";
    }

    private static String fileName(Path p) {
        Path n = p.getFileName();
        return n == null ? "" : n.toString();
    }

    private static String rel(Path workspace, Path dir) {
        return workspace.relativize(dir).toString().replace('\\', '/');
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    /** Sort helper: services first, libraries last, then alphabetical. */
    public static Comparator<Service> displayOrder() {
        return Comparator.comparing((Service s) -> s.library).thenComparing(s -> s.displayName);
    }
}

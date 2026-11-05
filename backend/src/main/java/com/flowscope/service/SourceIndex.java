package com.flowscope.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.flowscope.dto.ServiceMap.Evidence;

/**
 * The source files of one service, read once and shared by every scanner. Each
 * file knows its path relative to the evidence base (the workspace, or the app
 * for the Architecture view) so detections can point at {@code file:line}.
 *
 * <p>Test, mock and non-production environment files are flagged here so every
 * scanner applies the same rule. The checks use the path <em>relative to the
 * service</em>: a workspace that itself lives under a {@code test/} directory
 * must not make every file look like a test.
 */
final class SourceIndex {

    /** Source extensions scanned for communication primitives. */
    private static final List<String> EXTENSIONS = List.of(
            ".java", ".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs", ".go", ".py");

    /** Directory segments that hold tests, mocks or fixtures rather than production code. */
    private static final List<String> TEST_DIRS = List.of(
            "/test/", "/tests/", "/__tests__/", "/testing/", "/mocks/", "/mock/",
            "/__mocks__/", "/fixtures/", "/__fixtures__/", "/e2e/", "/cypress/",
            "/test-helpers/", "/test-utils/", "/testutils/", "/testhelpers/", "/test-support/",
            "/test-data/", "/testdata/");

    /** Environment/config files for a non-production stage (Angular, plain JS config). */
    private static final Pattern NON_PROD_ENV = Pattern.compile(
            "(environment|config|settings|env)[.-](dev|development|local|qa|test|stage|staging|stage\\d+|"
                    + "ci|uat|sit|int|sandbox|perf|demo)\\.(ts|js|json)");
    private static final Pattern ANGULAR_ENV = Pattern.compile("environment\\.(ts|js)");

    /** Import statements (TS/JS, Java, Python, Go): symbols named there are not calls. */
    private static final List<Pattern> IMPORT_PATTERNS = List.of(
            Pattern.compile("(?m)^\\s*import\\s+[^;]*?from\\s*['\"][^'\"]+['\"]"),
            Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?[\\w.*]+\\s*;"),
            Pattern.compile("(?m)^\\s*(?:const|let|var)\\s+[^=;]+=\\s*require\\(\\s*['\"][^'\"]+['\"]\\s*\\)"),
            Pattern.compile("(?m)^\\s*(?:from\\s+[\\w.]+\\s+)?import\\s+[\\w., ()*]+$"),
            Pattern.compile("(?m)^import\\s*\\([^)]*\\)"));

    /** Root-level build/tool config that never carries runtime communication. */
    private static final Pattern TOOL_CONFIG = Pattern.compile(
            ".*\\.config\\.(js|ts|mjs|cjs)|\\..*rc\\.(js|cjs)|gulpfile\\.js|Gruntfile\\.js");

    /** One source file. */
    static final class Src {
        final Path path;
        final String rel;
        final String name;
        final String text;
        final boolean test;
        private int[] lineStarts;

        Src(Path path, String rel, String text, boolean test) {
            this.path = path;
            this.rel = rel;
            this.name = path.getFileName().toString();
            this.text = text;
            this.test = test;
        }

        boolean isJava() {
            return name.endsWith(".java");
        }

        boolean isNode() {
            return name.endsWith(".ts") || name.endsWith(".tsx") || name.endsWith(".js")
                    || name.endsWith(".jsx") || name.endsWith(".mjs") || name.endsWith(".cjs");
        }

        boolean isGo() {
            return name.endsWith(".go");
        }

        boolean isPython() {
            return name.endsWith(".py");
        }

        /** 1-based line number of a character offset. */
        int line(int offset) {
            int[] starts = starts();
            int idx = Arrays.binarySearch(starts, Math.max(0, offset));
            return idx >= 0 ? idx + 1 : -idx - 1;
        }

        /** Offset of the first character of a 1-based line. */
        int lineStart(int line) {
            int[] starts = starts();
            if (line <= 1) {
                return 0;
            }
            return line - 1 < starts.length ? starts[line - 1] : text.length();
        }

        int lineCount() {
            return starts().length;
        }

        /** The trimmed text of a 1-based line, capped for display. */
        String lineText(int line) {
            int[] starts = starts();
            if (line < 1 || line > starts.length) {
                return "";
            }
            int from = starts[line - 1];
            int to = line < starts.length ? starts[line] - 1 : text.length();
            return clip(text.substring(from, Math.max(from, to)).trim());
        }

        Evidence evidence(int offset, String via) {
            int line = line(offset);
            return new Evidence(rel, line, lineText(line), via);
        }

        /** True when {@code offset} falls inside an import/require statement. */
        boolean inImport(int offset) {
            if (importRanges == null) {
                List<int[]> ranges = new ArrayList<>();
                for (Pattern p : IMPORT_PATTERNS) {
                    java.util.regex.Matcher m = p.matcher(text);
                    while (m.find()) {
                        ranges.add(new int[]{m.start(), m.end()});
                    }
                }
                importRanges = ranges;
            }
            for (int[] r : importRanges) {
                if (offset >= r[0] && offset < r[1]) {
                    return true;
                }
            }
            return false;
        }

        private List<int[]> importRanges;

        private int[] starts() {
            if (lineStarts == null) {
                List<Integer> s = new ArrayList<>();
                s.add(0);
                for (int i = 0; i < text.length(); i++) {
                    if (text.charAt(i) == '\n') {
                        s.add(i + 1);
                    }
                }
                lineStarts = s.stream().mapToInt(Integer::intValue).toArray();
            }
            return lineStarts;
        }
    }

    private final List<Src> files;

    private SourceIndex(List<Src> files) {
        this.files = files;
    }

    static SourceIndex of(Collection<Src> files) {
        return new SourceIndex(new ArrayList<>(files));
    }

    /**
     * Load a service's sources: everything under {@code src/} when it exists (the
     * historical scan root), plus source files directly in the service directory
     * (Lambda {@code index.ts}, {@code handler.js}, …) minus tool config.
     *
     * @param relBase  base directory evidence paths are made relative to
     * @param ignored  workspace-relative paths to skip (override-file {@code ignore})
     */
    static SourceIndex load(Path serviceDir, Path relBase, Predicate<String> ignored) {
        List<Src> out = new ArrayList<>();
        Path src = serviceDir.resolve("src");
        Path walkRoot = Files.isDirectory(src) ? src : serviceDir;
        WorkspaceScanner.walkSourceFiles(walkRoot, SourceIndex::isSourceName,
                p -> add(out, serviceDir, relBase, p, ignored));
        if (!walkRoot.equals(serviceDir)) {
            try (Stream<Path> s = Files.list(serviceDir)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> isSourceName(p.getFileName().toString()))
                        .filter(p -> !TOOL_CONFIG.matcher(p.getFileName().toString()).matches())
                        .sorted()
                        .forEach(p -> add(out, serviceDir, relBase, p, ignored));
            } catch (Exception e) {
                // best-effort
            }
        }
        return new SourceIndex(out);
    }

    private static void add(List<Src> out, Path serviceDir, Path relBase, Path p, Predicate<String> ignored) {
        String rel = relBase.relativize(p).toString().replace('\\', '/');
        if (ignored.test(rel)) {
            return;
        }
        String inService = "/" + serviceDir.relativize(p).toString().replace('\\', '/');
        String name = p.getFileName().toString();
        if (isNonProdEnv(p, name)) {
            return;
        }
        out.add(new Src(p, rel, WorkspaceScanner.read(p), isTest(inService, name)));
    }

    static boolean isSourceName(String name) {
        if (name.endsWith(".d.ts")) {
            return false;
        }
        for (String ext : EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** Test/mock/fixture detection on a service-relative path ("/src/app/x.ts"). */
    static boolean isTest(String inServicePath, String name) {
        String p = inServicePath.toLowerCase(Locale.ROOT);
        for (String d : TEST_DIRS) {
            if (p.contains(d)) {
                return true;
            }
        }
        return name.contains(".test.") || name.contains(".spec.") || name.contains(".mock.")
                || name.contains(".mocks.") || name.contains(".stories.")
                || name.endsWith("Test.java") || name.endsWith("Tests.java") || name.endsWith("IT.java")
                || name.endsWith("_test.go")
                || name.startsWith("test_") || name.endsWith("_test.py") || name.equals("conftest.py");
    }

    /**
     * Non-production environment files: {@code environment.dev.ts}, {@code config.stage.js}, …
     * The base {@code environment.ts} is also skipped when a prod sibling exists,
     * because Angular replaces it with {@code environment.prod.ts} in prod builds.
     */
    static boolean isNonProdEnv(Path p, String name) {
        if (NON_PROD_ENV.matcher(name).matches()) {
            return true;
        }
        if (ANGULAR_ENV.matcher(name).matches()) {
            Path dir = p.getParent();
            for (String prod : Set.of("environment.prod.ts", "environment.production.ts",
                    "environment.prod.js", "environment.production.js")) {
                if (dir != null && Files.isRegularFile(dir.resolve(prod))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Production (non-test) files. */
    List<Src> prod() {
        return files.stream().filter(f -> !f.test).toList();
    }

    List<Src> all() {
        return files;
    }

    static String clip(String s) {
        return s.length() > 160 ? s.substring(0, 157) + "..." : s;
    }
}

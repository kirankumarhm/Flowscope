package com.flowscope.service;

import com.flowscope.dto.Architecture;
import com.flowscope.service.ArchitectureBuilder;
import com.flowscope.dto.ComponentMap;
import com.flowscope.service.ComponentMapBuilder;
import com.flowscope.service.JavaFlowBuilder;
import com.flowscope.entity.JavaProgramModel;
import com.flowscope.entity.JavaProgramModel.MethodInfo;
import com.flowscope.service.JavaSequenceBuilder;
import com.flowscope.dto.SequenceDiagram;
import com.flowscope.service.DefaultFileWalker;
import com.flowscope.entity.ProjectRoots;
import com.flowscope.entity.SourceFile;
import com.flowscope.entity.WalkResult;
import com.flowscope.entity.CFG;
import com.flowscope.service.LanguageRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.flowscope.exception.ApiExceptions;

/**
 * Builds endpoint-rooted, inter-procedural flow charts on demand. The parsed
 * whole-program model is expensive to build, so the most-recent roots are cached.
 */
@Service
public class FlowService {

    private static final int MAX_CACHE = 2;
    private static final int MAX_DEPTH_CAP = 8;

    private final LanguageRegistry registry;

    /** Access-ordered LRU of program models keyed by absolute root path. */
    private final Map<String, JavaProgramModel> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, JavaProgramModel> eldest) {
                    return size() > MAX_CACHE;
                }
            });

    /** LRU of Go per-function CFGs keyed by root path (Go bypasses JavaProgramModel). */
    private final Map<String, Map<String, CFG>> goCache = Collections.synchronizedMap(
            new LinkedHashMap<>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Map<String, CFG>> eldest) {
                    return size() > MAX_CACHE;
                }
            });

    public FlowService(LanguageRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param pathParam  the analyzed source root (same value passed to /api/analyze)
     * @param functionId the entry function/endpoint node ID
     * @param maxDepth   how many call levels to inline (clamped to 1..8, default 4)
     */
    public CFG flow(String pathParam, String functionId, Integer maxDepth) {
        Extractor ts = treeSitterExtractor(functionId);
        if (ts != null) {
            // Go/Python flow is intra-procedural for now (no callee inlining), so
            // maxDepth is not yet used; the per-function CFG is returned directly.
            return tsCfgs(pathParam, functionId, ts).getOrThrow(functionId);
        }
        Resolved r = resolve(pathParam, functionId);
        int depth = maxDepth == null ? 4 : Math.max(1, Math.min(MAX_DEPTH_CAP, maxDepth));
        return JavaFlowBuilder.build(r.model(), r.entry(), depth);
    }

    /** Generate a Mermaid sequence diagram rooted at {@code functionId}. */
    public SequenceDiagram sequence(String pathParam, String functionId, Integer maxDepth) {
        Extractor ts = treeSitterExtractor(functionId);
        if (ts != null) {
            return CfgSequenceBuilder.build(functionId, tsCfgs(pathParam, functionId, ts).getOrThrow(functionId));
        }
        Resolved r = resolve(pathParam, functionId);
        int depth = maxDepth == null ? 10 : Math.max(1, Math.min(20, maxDepth));
        return JavaSequenceBuilder.build(r.model(), r.entry(), depth);
    }

    /** The tree-sitter extractor for a function id's source file, or null for Java. */
    private static Extractor treeSitterExtractor(String functionId) {
        if (functionId == null) {
            return null;
        }
        int hash = functionId.indexOf('#');
        int start = functionId.startsWith("func:") ? 5 : 0;
        String rel = hash > start ? functionId.substring(start, hash) : functionId;
        if (rel.endsWith(".go")) {
            return new GoExtractor();
        }
        if (rel.endsWith(".py")) {
            return new PythonExtractor();
        }
        return null;
    }

    /** Small holder so tree-sitter lookups throw a 404 consistently with Java. */
    private record TsCfgs(Map<String, CFG> byId) {
        CFG getOrThrow(String functionId) {
            CFG cfg = byId.get(functionId);
            if (cfg == null) {
                throw new ApiExceptions.NotFoundException("function not found: " + functionId);
            }
            return cfg;
        }
    }

    private TsCfgs tsCfgs(String pathParam, String functionId, Extractor extractor) {
        if (pathParam == null || pathParam.isBlank()) {
            throw new ApiExceptions.MissingPathException("the 'path' query parameter is required");
        }
        if (functionId == null || functionId.isBlank()) {
            throw new ApiExceptions.MissingPathException("the 'functionId' query parameter is required");
        }
        Path root = Paths.get(pathParam).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new ApiExceptions.InvalidPathException("not a directory: " + root);
        }
        String key = root + "#" + extractor.languageId();
        return new TsCfgs(goCache.computeIfAbsent(key, k -> buildTsCfgs(root, extractor)));
    }

    /** Extract every function's CFG under {@code root} for one tree-sitter language,
     *  using the same file walk (and thus the same relative-path/function-id
     *  contract) as /api/analyze. */
    private Map<String, CFG> buildTsCfgs(Path root, Extractor extractor) {
        WalkResult walk = new DefaultFileWalker(registry).walkAll(ProjectRoots.resolve(root));
        Map<String, CFG> all = new LinkedHashMap<>();
        String lang = extractor.languageId();
        for (SourceFile f : walk.files()) {
            if (!lang.equals(f.language())) {
                continue;
            }
            try {
                byte[] src = Files.readAllBytes(f.absolutePath());
                all.putAll(extractor.extract(f.relativePath(), src).cfgs());
            } catch (IOException | RuntimeException e) {
                // best-effort: skip a file that can't be read/parsed
            }
        }
        return all;
    }

    /** Build the intra-app component map for the analyzed root (reuses the model cache). */
    public ComponentMap componentMap(String pathParam) {
        Path root = validatedDir(pathParam);
        String project = projectName(root);
        WalkResult walk = new DefaultFileWalker(registry).walkAll(ProjectRoots.resolve(root));
        String ts = treeSitterLang(walk);
        if (ts != null) {
            return TreeSitterComponentBuilder.build(project, walk.files(), ts);
        }
        JavaProgramModel model = cache.computeIfAbsent(root.toString(), key -> buildModel(root));
        return ComponentMapBuilder.build(project, model);
    }

    /** Build the high-level layered architecture view for the analyzed app. */
    public Architecture architecture(String pathParam) {
        Path root = validatedDir(pathParam);
        String project = projectName(root);
        WalkResult walk = new DefaultFileWalker(registry).walkAll(ProjectRoots.resolve(root));
        String ts = treeSitterLang(walk);
        if (ts != null) {
            ComponentMap cm = TreeSitterComponentBuilder.build(project, walk.files(), ts);
            return ArchitectureBuilder.build(project, cm, root);
        }
        JavaProgramModel model = cache.computeIfAbsent(root.toString(), key -> buildModel(root));
        return ArchitectureBuilder.build(project, model, root);
    }

    private Path validatedDir(String pathParam) {
        if (pathParam == null || pathParam.isBlank()) {
            throw new ApiExceptions.MissingPathException("the 'path' query parameter is required");
        }
        Path root = Paths.get(pathParam).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new ApiExceptions.InvalidPathException("not a directory: " + root);
        }
        return root;
    }

    private static String projectName(Path root) {
        return root.getFileName() != null ? root.getFileName().toString() : root.toString();
    }

    /** The tree-sitter language to use for Component/Architecture, or null for the
     *  Java path. Java takes precedence when present (its analysis is richer); else
     *  the dominant of Go/Python wins. */
    private static String treeSitterLang(WalkResult walk) {
        int java = 0;
        int go = 0;
        int py = 0;
        for (SourceFile f : walk.files()) {
            switch (f.language()) {
                case "java" -> java++;
                case "go" -> go++;
                case "python" -> py++;
                default -> { }
            }
        }
        if (java > 0) {
            return null;
        }
        if (go == 0 && py == 0) {
            return null;
        }
        return go >= py ? "go" : "python";
    }

    private record Resolved(JavaProgramModel model, MethodInfo entry) {
    }

    private Resolved resolve(String pathParam, String functionId) {
        if (pathParam == null || pathParam.isBlank()) {
            throw new ApiExceptions.MissingPathException("the 'path' query parameter is required");
        }
        if (functionId == null || functionId.isBlank()) {
            throw new ApiExceptions.MissingPathException("the 'functionId' query parameter is required");
        }
        Path root = Paths.get(pathParam).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new ApiExceptions.InvalidPathException("not a directory: " + root);
        }
        JavaProgramModel model = cache.computeIfAbsent(root.toString(), key -> buildModel(root));
        MethodInfo entry = model.method(functionId);
        if (entry == null) {
            throw new ApiExceptions.NotFoundException("function not found: " + functionId);
        }
        return new Resolved(model, entry);
    }

    private JavaProgramModel buildModel(Path root) {
        // Same root resolution as analysis, so method IDs match and cross-module
        // (base-class) calls inline in the flow.
        WalkResult walk = new DefaultFileWalker(registry).walkAll(ProjectRoots.resolve(root));
        return JavaProgramModel.build(walk.files());
    }
}

package com.flowscope.api;

import com.flowscope.architecture.Architecture;
import com.flowscope.architecture.ArchitectureBuilder;
import com.flowscope.component.ComponentMap;
import com.flowscope.component.ComponentMapBuilder;
import com.flowscope.extract.JavaFlowBuilder;
import com.flowscope.extract.JavaProgramModel;
import com.flowscope.extract.JavaProgramModel.MethodInfo;
import com.flowscope.extract.JavaSequenceBuilder;
import com.flowscope.ir.SequenceDiagram;
import com.flowscope.ingest.DefaultFileWalker;
import com.flowscope.ingest.ProjectRoots;
import com.flowscope.ingest.WalkResult;
import com.flowscope.ir.CFG;
import com.flowscope.lang.LanguageRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

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

    public FlowService(LanguageRegistry registry) {
        this.registry = registry;
    }

    /**
     * @param pathParam  the analyzed source root (same value passed to /api/analyze)
     * @param functionId the entry function/endpoint node ID
     * @param maxDepth   how many call levels to inline (clamped to 1..8, default 4)
     */
    public CFG flow(String pathParam, String functionId, Integer maxDepth) {
        Resolved r = resolve(pathParam, functionId);
        int depth = maxDepth == null ? 4 : Math.max(1, Math.min(MAX_DEPTH_CAP, maxDepth));
        return JavaFlowBuilder.build(r.model(), r.entry(), depth);
    }

    /** Generate a Mermaid sequence diagram rooted at {@code functionId}. */
    public SequenceDiagram sequence(String pathParam, String functionId, Integer maxDepth) {
        Resolved r = resolve(pathParam, functionId);
        int depth = maxDepth == null ? 10 : Math.max(1, Math.min(20, maxDepth));
        return JavaSequenceBuilder.build(r.model(), r.entry(), depth);
    }

    /** Build the intra-app component map for the analyzed root (reuses the model cache). */
    public ComponentMap componentMap(String pathParam) {
        if (pathParam == null || pathParam.isBlank()) {
            throw new ApiExceptions.MissingPathException("the 'path' query parameter is required");
        }
        Path root = Paths.get(pathParam).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new ApiExceptions.InvalidPathException("not a directory: " + root);
        }
        JavaProgramModel model = cache.computeIfAbsent(root.toString(), key -> buildModel(root));
        String project = root.getFileName() != null ? root.getFileName().toString() : root.toString();
        return ComponentMapBuilder.build(project, model);
    }

    /** Build the high-level layered architecture view for the analyzed app. */
    public Architecture architecture(String pathParam) {
        if (pathParam == null || pathParam.isBlank()) {
            throw new ApiExceptions.MissingPathException("the 'path' query parameter is required");
        }
        Path root = Paths.get(pathParam).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new ApiExceptions.InvalidPathException("not a directory: " + root);
        }
        JavaProgramModel model = cache.computeIfAbsent(root.toString(), key -> buildModel(root));
        String project = root.getFileName() != null ? root.getFileName().toString() : root.toString();
        return ArchitectureBuilder.build(project, model, root);
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

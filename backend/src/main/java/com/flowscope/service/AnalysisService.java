package com.flowscope.service;

import com.flowscope.service.DefaultParserEngine;
import com.flowscope.service.ParserEngine;
import com.flowscope.service.DefaultFileWalker;
import com.flowscope.service.FileWalker;
import com.flowscope.entity.ProjectRoots;
import com.flowscope.entity.WalkResult;
import com.flowscope.entity.Graph;
import com.flowscope.service.IRBuilder;
import com.flowscope.entity.ExtractionResult;
import com.flowscope.service.LanguageRegistry;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;
import com.flowscope.exception.ApiExceptions;

/**
 * Orchestrates the analysis pipeline: File Walker → Parser Engine → IR Builder.
 * Enforces the memory and timeout guards from Requirement 10.
 */
@Service
public class AnalysisService {

    private static final long MAX_HEAP_BYTES = 2L * 1024 * 1024 * 1024; // 2 GB
    private static final long TIMEOUT_SECONDS = 120;

    private final LanguageRegistry registry;
    private final ParserEngine parserEngine = new DefaultParserEngine();
    private final IRBuilder irBuilder = new IRBuilder();

    public AnalysisService(LanguageRegistry registry) {
        this.registry = registry;
    }

    /**
     * Validate the path and run the pipeline within the time/memory budget.
     *
     * @throws ApiExceptions.MissingPathException  path param missing/empty
     * @throws ApiExceptions.InvalidPathException  path not found or not a directory
     * @throws ApiExceptions.AnalysisTimeoutException pipeline exceeded 120s
     * @throws ApiExceptions.MemoryExceededException  heap exceeded 2 GB
     */
    public Graph analyze(String pathParam) {
        if (pathParam == null || pathParam.isBlank()) {
            throw new ApiExceptions.MissingPathException("the 'path' query parameter is required");
        }

        // Requirement 6.7: resolve relative paths to absolute before validating.
        Path root = Paths.get(pathParam).toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            throw new ApiExceptions.InvalidPathException("path not found: " + root);
        }
        if (!Files.isDirectory(root)) {
            throw new ApiExceptions.InvalidPathException("not a directory: " + root);
        }

        CompletableFuture<Graph> future = CompletableFuture.supplyAsync(() -> runPipeline(root));
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ApiExceptions.AnalysisTimeoutException(
                    "analysis timed out after " + TIMEOUT_SECONDS + " seconds");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause == null ? e.getMessage() : cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("analysis interrupted");
        }
    }

    private Graph runPipeline(Path root) {
        checkHeap();
        // Auto-detect sibling modules this project depends on (e.g. shared libraries)
        // so base-class and cross-module calls resolve.
        List<ProjectRoots.Root> roots = ProjectRoots.resolve(root);
        List<String> modules = roots.stream().map(ProjectRoots.Root::module).toList();

        FileWalker walker = new DefaultFileWalker(registry);
        WalkResult walk = walker.walkAll(roots);

        ExtractionResult parsed = parserEngine.extract(walk.files(), registry);
        checkHeap();

        String project = root.getFileName() != null ? root.getFileName().toString() : root.toString();
        return irBuilder.build(project, modules, parsed, walk.files());
    }

    private void checkHeap() {
        long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        if (used > MAX_HEAP_BYTES) {
            throw new ApiExceptions.MemoryExceededException(
                    "codebase exceeds analysis capacity (heap > 2GB)");
        }
    }
}

package com.flowscope.api;

import com.flowscope.servicemap.ServiceMap;
import com.flowscope.servicemap.ServiceMapBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Service;

/**
 * Builds the whole-workspace {@link ServiceMap} on demand. Scanning a multi-app
 * workspace is expensive, so results are cached by (root, excludes).
 */
@Service
public class ServiceMapService {

    private static final long TIMEOUT_SECONDS = 180;

    /** Directories that are part of the workspace but not deployable CMS apps. */
    private static final Set<String> DEFAULT_EXCLUDES = Set.of(
            "modernization-guide-java", "mds-dba-research", "eSIM_PII_CPNI_Data_Governance");

    private final Map<String, ServiceMap> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(4, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, ServiceMap> eldest) {
                    return size() > 3;
                }
            });

    public ServiceMap serviceMap(String pathParam, String excludeParam) {
        if (pathParam == null || pathParam.isBlank()) {
            throw new ApiExceptions.MissingPathException("the 'path' query parameter is required");
        }
        Path root = Paths.get(pathParam).toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            throw new ApiExceptions.InvalidPathException("path not found: " + root);
        }
        if (!Files.isDirectory(root)) {
            throw new ApiExceptions.InvalidPathException("not a directory: " + root);
        }

        Set<String> excludes = new LinkedHashSet<>(DEFAULT_EXCLUDES);
        if (excludeParam != null && !excludeParam.isBlank()) {
            Arrays.stream(excludeParam.split(",")).map(String::trim)
                    .filter(s -> !s.isEmpty()).forEach(excludes::add);
        }

        String cacheKey = root + "::" + new java.util.TreeSet<>(excludes);
        ServiceMap cached = cache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        CompletableFuture<ServiceMap> future =
                CompletableFuture.supplyAsync(() -> ServiceMapBuilder.build(root, excludes));
        try {
            ServiceMap result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            cache.put(cacheKey, result);
            return result;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ApiExceptions.AnalysisTimeoutException(
                    "service-map scan timed out after " + TIMEOUT_SECONDS + " seconds");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(cause == null ? e.getMessage() : cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("service-map scan interrupted");
        }
    }
}

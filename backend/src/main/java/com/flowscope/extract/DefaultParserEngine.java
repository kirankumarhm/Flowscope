package com.flowscope.extract;

import com.flowscope.ingest.SourceFile;
import com.flowscope.ir.CFG;
import com.flowscope.ir.IREdge;
import com.flowscope.ir.IRNode;
import com.flowscope.lang.ExtractionResult;
import com.flowscope.lang.Extractor;
import com.flowscope.lang.LanguageRegistry;
import com.flowscope.lang.LanguageSpec;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Parses files concurrently on a bounded thread pool (one file per task), then
 * merges per-file results in deterministic file order. Read/parse failures are
 * captured as non-fatal errors so analysis continues (Requirements 2.5, 2.6, 10.3).
 */
public class DefaultParserEngine implements ParserEngine {

    @Override
    public ExtractionResult extract(List<SourceFile> files, LanguageRegistry registry) {
        int threads = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<CompletableFuture<ExtractionResult>> futures = new ArrayList<>(files.size());
            for (SourceFile file : files) {
                futures.add(CompletableFuture.supplyAsync(() -> parseOne(file, registry), pool));
            }

            List<IRNode> nodes = new ArrayList<>();
            List<IREdge> edges = new ArrayList<>();
            Map<String, CFG> cfgs = new LinkedHashMap<>();
            List<String> errors = new ArrayList<>();

            for (CompletableFuture<ExtractionResult> f : futures) {
                ExtractionResult r = f.join();
                nodes.addAll(r.nodes());
                edges.addAll(r.edges());
                cfgs.putAll(r.cfgs());
                errors.addAll(r.errors());
            }
            return new ExtractionResult(nodes, edges, cfgs, errors);
        } finally {
            pool.shutdown();
        }
    }

    private ExtractionResult parseOne(SourceFile file, LanguageRegistry registry) {
        Optional<LanguageSpec> spec = registry.forLanguage(file.language());
        if (spec.isEmpty()) {
            return ExtractionResult.empty();
        }
        Extractor extractor = spec.get().extractor();
        try {
            byte[] source = Files.readAllBytes(file.absolutePath());
            return extractor.extract(file.relativePath(), source);
        } catch (Exception e) {
            return new ExtractionResult(List.of(), List.of(), Map.of(),
                    List.of(file.relativePath() + ": " + e.getMessage()));
        }
    }
}

package com.flowscope.service;

import com.flowscope.service.JavaCallResolver;
import com.flowscope.entity.SourceFile;
import com.flowscope.entity.ExtractionResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import com.flowscope.entity.EdgeKind;
import com.flowscope.entity.Graph;
import com.flowscope.entity.IREdge;
import com.flowscope.entity.IRNode;

/**
 * Merges per-file extraction output into a single {@link Graph}: deduplicates
 * nodes by ID, runs per-language call resolution, deduplicates and self-filters
 * {@code calls} edges, and attaches graph-level metadata (Requirements 3.7, 3.9,
 * 9.5, 9.6).
 */
public class IRBuilder {

    public Graph build(String project, List<String> modules, ExtractionResult parsed,
                       List<SourceFile> files) {
        // Nodes: keep first occurrence of each ID (Requirement 3.7 — unique IDs).
        Map<String, IRNode> nodesById = new LinkedHashMap<>();
        for (IRNode n : parsed.nodes()) {
            nodesById.putIfAbsent(n.id(), n);
        }
        Set<String> nodeIds = nodesById.keySet();

        // Structural/import edges from extraction (non-calls kept as-is).
        List<IREdge> edges = new ArrayList<>();
        Set<String> callSeen = new LinkedHashSet<>();
        for (IREdge e : parsed.edges()) {
            if (e.kind() == EdgeKind.CALLS) {
                addCallEdge(edges, callSeen, nodeIds, e);
            } else {
                edges.add(e);
            }
        }

        // Call resolution post-pass (needs the complete symbol table).
        for (IREdge e : new JavaCallResolver().resolve(files)) {
            addCallEdge(edges, callSeen, nodeIds, e);
        }

        List<String> languages = detectLanguages(files);
        return new Graph(project, languages, modules,
                new ArrayList<>(nodesById.values()), edges, parsed.cfgs());
    }

    /** Add a CALLS edge if it is not self-recursive, not a duplicate, and both ends exist. */
    private void addCallEdge(List<IREdge> edges, Set<String> seen, Set<String> nodeIds, IREdge e) {
        if (e.from().equals(e.to())) {
            return; // Requirement 9.8: exclude self-recursive edges
        }
        if (!nodeIds.contains(e.from()) || !nodeIds.contains(e.to())) {
            return; // dangling endpoint
        }
        String key = e.from() + "|" + e.to();
        if (seen.add(key)) { // Requirement 9.7: dedup by (caller, callee)
            edges.add(e);
        }
    }

    private List<String> detectLanguages(List<SourceFile> files) {
        Set<String> langs = new TreeSet<>();
        for (SourceFile f : files) {
            langs.add(f.language());
        }
        return new ArrayList<>(langs);
    }
}

package com.flowscope.entity;

import com.flowscope.entity.CFG;
import com.flowscope.entity.IREdge;
import com.flowscope.entity.IRNode;
import java.util.List;
import java.util.Map;

/**
 * The per-file output of an {@link Extractor}: IR entities, structural/import
 * edges, per-function CFGs, and any non-fatal errors encountered.
 *
 * <p>Note: cross-file {@code calls} edges are produced later by the IR builder's
 * resolution pass, which needs the complete symbol table.
 */
public record ExtractionResult(
        List<IRNode> nodes,
        List<IREdge> edges,
        Map<String, CFG> cfgs,
        List<String> errors) {

    public static ExtractionResult empty() {
        return new ExtractionResult(List.of(), List.of(), Map.of(), List.of());
    }
}

package com.flowscope.ir;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * The control-flow graph of a single function (drill-down flowchart).
 *
 * @param functionId ID of the associated function node
 * @param nodes      CFG nodes (contains exactly one entry and one exit)
 * @param edges      CFG edges; every from/to references a node id in {@code nodes}
 * @param groups     collapsible inlined-method groups (null for plain per-function CFGs)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CFG(
        String functionId,
        List<CFGNode> nodes,
        List<CFGEdge> edges,
        List<CFGGroup> groups) {
}

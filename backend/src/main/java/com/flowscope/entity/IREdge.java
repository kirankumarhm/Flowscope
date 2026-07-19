package com.flowscope.entity;

/**
 * A directed relationship between two IR nodes.
 *
 * @param from       source node ID
 * @param to         target node ID
 * @param kind       relationship kind
 * @param confidence resolution confidence in [0.0, 1.0]; 1.0 = fully resolved,
 *                   lower values indicate heuristic resolution
 * @param order      call order within a function body (used for sequence diagrams)
 */
public record IREdge(
        String from,
        String to,
        EdgeKind kind,
        double confidence,
        int order) {
}

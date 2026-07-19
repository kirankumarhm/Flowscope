package com.flowscope.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * The whole analyzed project: the language-neutral map (nodes + edges) plus
 * per-function CFGs keyed by function node ID. This is the single source of
 * truth from which every diagram view is projected.
 *
 * <p>Diagram-specific fields (serviceMap, sequences, components, architecture)
 * are added in later phases; they are nullable and omitted from JSON when absent.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Graph(
        String project,
        List<String> languages,
        List<String> modules,
        List<IRNode> nodes,
        List<IREdge> edges,
        Map<String, CFG> cfgs) {
}

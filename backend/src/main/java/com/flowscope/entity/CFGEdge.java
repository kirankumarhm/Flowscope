package com.flowscope.entity;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A directed edge in a function's control-flow graph.
 *
 * @param from  source CFG node id
 * @param to    target CFG node id
 * @param label branch condition ("yes", "no", "body", "default", a case value) or null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CFGEdge(
        String from,
        String to,
        String label) {
}

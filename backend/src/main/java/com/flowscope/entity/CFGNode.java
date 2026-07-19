package com.flowscope.entity;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One node in a function's control-flow graph.
 *
 * @param id    unique within the CFG
 * @param kind  the control-flow construct this node represents
 * @param label single-line, whitespace-normalized source summary (max 52 chars)
 * @param line  1-based source line number (nullable)
 * @param title full untruncated text for hover/tooltip; null when {@code label}
 *              already holds the complete text
 * @param group id of the inlined-method group this node belongs to (for
 *              collapse/expand), or null for the top-level entry method
 * @param file  source file (module-relative) this node's code lives in; enables
 *              the detail panel's file:line and open-in-editor
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CFGNode(
        String id,
        CFGNodeKind kind,
        String label,
        Integer line,
        String title,
        String group,
        String file) {
}

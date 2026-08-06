package com.flowscope.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A human-readable walkthrough of a flow chart's control-flow graph.
 *
 * @param functionId the entry function the description was generated for
 * @param headline   one-line orientation, e.g. "OrderController.place walks
 *                   through 3 methods, 12 calls and 4 decisions."
 * @param stats      counts behind the headline
 * @param steps      the walkthrough in execution order
 * @param text       the same walkthrough as plain text, for copy/download
 * @param truncated  true when the flow exceeded {@code STEP_BUDGET} and
 *                   {@code steps} covers only the first part of it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlowDescription(
        String functionId,
        String headline,
        Stats stats,
        List<Step> steps,
        String text,
        boolean truncated) {

    /**
     * Counts summarizing the flow's shape.
     *
     * @param nodes     total CFG nodes
     * @param calls     call sites
     * @param decisions branch (if/switch) heads
     * @param loops     loop heads
     * @param thrown    throw / fatal-exit sites
     * @param returns   return statements
     * @param methods   inlined method groups (1 when nothing was inlined)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Stats(
            int nodes,
            int calls,
            int decisions,
            int loops,
            int thrown,
            int returns,
            int methods) {
    }

    /**
     * One line of the walkthrough.
     *
     * @param index    1-based step number; 0 for structural notes that are not
     *                 numbered steps (the opening line, group headers)
     * @param kind     the source node's {@link com.flowscope.entity.CFGNodeKind}
     *                 as JSON text, or "note" for structural lines
     * @param text     the sentence to render
     * @param line     1-based source line, when known
     * @param file     module-relative source file, when known
     * @param method   label of the inlined method this step belongs to, or null
     *                 at the top level
     * @param depth    nesting level, for indentation (0 at the top level)
     * @param branches outgoing branch labels for decisions/loops, e.g.
     *                 ["yes → validate(order)", "no → return 400"]
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Step(
            int index,
            String kind,
            String text,
            Integer line,
            String file,
            String method,
            int depth,
            List<String> branches) {
    }
}

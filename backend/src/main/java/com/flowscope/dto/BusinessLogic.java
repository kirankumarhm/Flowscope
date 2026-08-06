package com.flowscope.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * What a flow is trying to accomplish, expressed in business terms rather than
 * control-flow terms.
 *
 * <p>Two origins produce this shape, and {@link #origin} always says which:
 * {@code "llm"} when a configured provider wrote the narrative, {@code
 * "heuristic"} when it was inferred offline from naming and structure. The
 * distinction matters — heuristics categorize what the code touches, an LLM can
 * explain intent — so the UI shows it rather than presenting both as equivalent.
 *
 * @param functionId    entry function the analysis is for
 * @param origin        "llm" or "heuristic"
 * @param provider      endpoint host + model when origin is "llm", else null
 * @param llmConfigured whether a provider is configured at all, so the UI can
 *                      offer to enable it
 * @param summary       one-paragraph account of what the flow does
 * @param phases        ordered stages of the flow
 * @param rules         business rules / invariants the code enforces
 * @param caveats       honest limits of this particular analysis
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BusinessLogic(
        String functionId,
        String origin,
        String provider,
        boolean llmConfigured,
        String summary,
        List<Phase> phases,
        List<String> rules,
        List<String> caveats) {

    /**
     * One stage of the flow.
     *
     * @param index    1-based ordering
     * @param name     short label, e.g. "Validate request"
     * @param kind     machine-readable category (see {@code BusinessLogicHeuristic})
     * @param purpose  what this stage accomplishes
     * @param evidence the concrete code steps this was inferred from
     * @param line     first source line of the stage, when known
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Phase(
            int index,
            String name,
            String kind,
            String purpose,
            List<String> evidence,
            Integer line) {
    }
}

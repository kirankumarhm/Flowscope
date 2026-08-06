package com.flowscope.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowscope.config.LlmProperties;
import com.flowscope.dto.BusinessLogic;
import com.flowscope.dto.FlowDescription;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Produces the business-logic view of a flow.
 *
 * <p>When a provider is configured the flow's structure is sent to it for an
 * interpretation of intent; otherwise (or if the call fails) the offline
 * {@link BusinessLogicHeuristic} answers instead. Either way the response says
 * which produced it, and a provider failure degrades rather than erroring — a
 * misconfigured endpoint shouldn't take the feature down.
 */
@Service
public class BusinessLogicService {

    private static final String SYSTEM_PROMPT = """
            You explain what code does in business terms for engineers reading an \
            unfamiliar codebase. You receive a control-flow walkthrough of one \
            function, already ordered by execution.

            Explain the business purpose: what real-world operation this performs, \
            what rules it enforces, and what it talks to. Describe intent, not \
            syntax — do not restate the steps.

            Reply with only a JSON object, no prose or code fences:
            {
              "summary": "one paragraph on what this accomplishes and why",
              "phases": [
                {"name": "short label", "purpose": "what this stage accomplishes"}
              ],
              "rules": ["business rules or invariants the code enforces"],
              "caveats": ["anything you could not determine from the walkthrough"]
            }

            Ground every claim in the walkthrough. If intent is genuinely unclear, \
            say so in caveats rather than inventing a rationale.""";

    private final FlowService flowService;
    private final FlowDescriptionService descriptionService;
    private final BusinessLogicHeuristic heuristic;
    private final LlmClient llm;
    private final LlmProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public BusinessLogicService(FlowService flowService,
                               FlowDescriptionService descriptionService,
                               BusinessLogicHeuristic heuristic,
                               LlmClient llm,
                               LlmProperties props) {
        this.flowService = flowService;
        this.descriptionService = descriptionService;
        this.heuristic = heuristic;
        this.llm = llm;
        this.props = props;
    }

    public BusinessLogic analyze(String path, String functionId, Integer maxDepth) {
        FlowDescription description =
                descriptionService.generate(flowService.flow(path, functionId, maxDepth));

        if (!llm.available()) {
            return heuristic.infer(description, false);
        }
        try {
            return fromLlm(description);
        } catch (LlmClient.LlmUnavailableException e) {
            // Degrade rather than fail: heuristics still answer, and the caveat
            // tells the user why the provider didn't.
            BusinessLogic fallback = heuristic.infer(description, true);
            List<String> caveats = new ArrayList<>(
                    fallback.caveats() == null ? List.of() : fallback.caveats());
            caveats.add("LLM provider was configured but did not answer (" + e.getMessage()
                    + "); this is the offline analysis.");
            return new BusinessLogic(fallback.functionId(), fallback.origin(), null, true,
                    fallback.summary(), fallback.phases(), fallback.rules(), caveats);
        }
    }

    /** True when an LLM provider is configured and usable. */
    public boolean llmAvailable() {
        return llm.available();
    }

    /** Endpoint host + model, or "not configured". Never includes the key. */
    public String providerDescription() {
        return props.describe();
    }

    // --- LLM path -----------------------------------------------------------

    private BusinessLogic fromLlm(FlowDescription description) {
        String answer = llm.complete(SYSTEM_PROMPT, prompt(description));
        JsonNode root = parseJson(answer);

        List<BusinessLogic.Phase> phases = new ArrayList<>();
        for (JsonNode node : root.path("phases")) {
            String name = node.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            phases.add(new BusinessLogic.Phase(phases.size() + 1, name, "llm",
                    node.path("purpose").asText(null), null, null));
        }

        List<String> rules = strings(root.path("rules"));
        List<String> caveats = strings(root.path("caveats"));
        if (caveats == null) {
            caveats = new ArrayList<>();
        }
        caveats.add("Written by " + props.describe() + " from the flow's structure — "
                + "verify against the source before relying on it.");

        String summary = root.path("summary").asText("");
        return new BusinessLogic(
                description.functionId(),
                "llm",
                props.describe(),
                true,
                summary.isBlank() ? "The provider returned no summary." : summary,
                phases.isEmpty() ? null : phases,
                rules,
                caveats);
    }

    /**
     * Reads the model's JSON, tolerating the code fences and leading prose that
     * models commonly add despite instructions.
     */
    private JsonNode parseJson(String answer) {
        String text = answer.strip();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                text = text.substring(firstNewline + 1, lastFence).strip();
            }
        }
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        if (open >= 0 && close > open) {
            text = text.substring(open, close + 1);
        }
        try {
            return mapper.readTree(text);
        } catch (IOException e) {
            throw new LlmClient.LlmUnavailableException(
                    "provider did not return usable JSON");
        }
    }

    private List<String> strings(JsonNode array) {
        if (!array.isArray() || array.isEmpty()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode node : array) {
            String value = node.asText("");
            if (!value.isBlank()) {
                out.add(value);
            }
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * Renders the walkthrough as the user prompt. Sends the structured steps
     * rather than raw source: it is far more compact, already in execution
     * order, and carries the file/line anchors the model needs to stay grounded.
     */
    private String prompt(FlowDescription description) {
        StringBuilder sb = new StringBuilder();
        sb.append("Function: ").append(description.functionId()).append('\n');
        sb.append(description.headline()).append("\n\n");
        sb.append("Execution walkthrough:\n");
        sb.append(description.text());
        if (description.truncated()) {
            sb.append("\n(The walkthrough was truncated; describe only what is shown.)\n");
        }
        return sb.toString();
    }
}

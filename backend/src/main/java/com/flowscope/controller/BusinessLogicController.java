package com.flowscope.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.flowscope.dto.BusinessLogic;
import com.flowscope.service.BusinessLogicService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.Map;

/**
 * {@code GET /api/business-logic} explains a flow's business purpose;
 * {@code GET /api/llm-status} reports whether an LLM provider is configured so
 * the UI can set expectations before the user clicks.
 */
@RestController
@Tag(name = "Business Logic", description = "What a flow is trying to accomplish, in business terms.")
public class BusinessLogicController {

    private final BusinessLogicService service;

    public BusinessLogicController(BusinessLogicService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/business-logic", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Explain a flow's business purpose",
            description = "Uses the configured LLM provider when one is available, otherwise "
                    + "offline heuristics. The 'origin' field always says which produced the result.")
    public BusinessLogic businessLogic(
            @Parameter(description = "Analyzed source root", example = "/path/to/my-app")
            @RequestParam(name = "path", required = false) String path,
            @Parameter(description = "Entry function/endpoint node id")
            @RequestParam(name = "functionId", required = false) String functionId,
            @Parameter(description = "Call levels to inline (1–8, default 4)", example = "4")
            @RequestParam(name = "maxDepth", required = false) Integer maxDepth) {
        return service.analyze(path, functionId, maxDepth);
    }

    @GetMapping(value = "/api/llm-status", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Report LLM provider availability",
            description = "Returns whether a provider is configured and a non-secret label for it. "
                    + "Never returns the API key.")
    public Map<String, Object> llmStatus() {
        return Map.of(
                "available", service.llmAvailable(),
                "provider", service.providerDescription());
    }
}

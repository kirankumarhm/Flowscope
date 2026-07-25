package com.flowscope.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.flowscope.dto.FlowDescription;
import com.flowscope.entity.CFG;
import com.flowscope.service.FlowDescriptionService;
import com.flowscope.service.FlowService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * {@code GET /api/flow?path=<dir>&functionId=<id>&maxDepth=<n>} returns the
 * endpoint-rooted, inter-procedural flow chart for a function as a CFG.
 */
@RestController
@Tag(name = "Flow Chart", description = "Endpoint-rooted, inter-procedural control-flow graph for a function.")
public class FlowController {

    private final FlowService service;
    private final FlowDescriptionService descriptionService;

    public FlowController(FlowService service, FlowDescriptionService descriptionService) {
        this.service = service;
        this.descriptionService = descriptionService;
    }

    @GetMapping(value = "/api/flow", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Build an inter-procedural flow chart",
            description = "Returns a CFG whose call sites are expanded inline into the callee methods' flow.")
    public CFG flow(
            @Parameter(description = "Analyzed source root", example = "/path/to/my-app")
            @RequestParam(name = "path", required = false) String path,
            @Parameter(description = "Entry function/endpoint node id")
            @RequestParam(name = "functionId", required = false) String functionId,
            @Parameter(description = "Call levels to inline (1–8, default 4)", example = "4")
            @RequestParam(name = "maxDepth", required = false) Integer maxDepth) {
        return service.flow(path, functionId, maxDepth);
    }

    @GetMapping(value = "/api/flow-description", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Generate a textual description of the flow",
            description = "Returns a human-readable narrative of the inter-procedural flow for a function.")
    public FlowDescription description(
            @Parameter(description = "Analyzed source root", example = "/path/to/my-app")
            @RequestParam(name = "path", required = false) String path,
            @Parameter(description = "Entry function/endpoint node id")
            @RequestParam(name = "functionId", required = false) String functionId,
            @Parameter(description = "Call levels to inline (1–8, default 4)", example = "4")
            @RequestParam(name = "maxDepth", required = false) Integer maxDepth) {
        System.out.println("[FlowController] Received request for flow-description: path=" + path + ", functionId=" + functionId);
        CFG cfg = service.flow(path, functionId, maxDepth);
        return new FlowDescription(descriptionService.generateDescription(cfg));
    }
}

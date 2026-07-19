package com.flowscope.controller;

import com.flowscope.entity.CFG;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.flowscope.service.FlowService;

/**
 * {@code GET /api/flow?path=<dir>&functionId=<id>&maxDepth=<n>} returns the
 * endpoint-rooted, inter-procedural flow chart for a function as a CFG.
 */
@RestController
@Tag(name = "Flow Chart", description = "Endpoint-rooted, inter-procedural control-flow graph for a function.")
public class FlowController {

    private final FlowService service;

    public FlowController(FlowService service) {
        this.service = service;
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
}

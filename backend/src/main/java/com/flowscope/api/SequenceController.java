package com.flowscope.api;

import com.flowscope.ir.SequenceDiagram;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/sequence?path=<dir>&functionId=<id>&maxDepth=<n>} returns a
 * Mermaid sequence diagram tracing the request from the given function.
 */
@RestController
@Tag(name = "Sequence", description = "Mermaid sequence diagram tracing a request from an entry function.")
public class SequenceController {

    private final FlowService service;

    public SequenceController(FlowService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/sequence", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Build a sequence diagram",
            description = "Returns Mermaid text plus the ordered participant list for the traced request.")
    public SequenceDiagram sequence(
            @Parameter(description = "Analyzed source root", example = "/path/to/my-app")
            @RequestParam(name = "path", required = false) String path,
            @Parameter(description = "Entry function/endpoint node id")
            @RequestParam(name = "functionId", required = false) String functionId,
            @Parameter(description = "Call levels to trace (1–20, default 10)", example = "10")
            @RequestParam(name = "maxDepth", required = false) Integer maxDepth) {
        return service.sequence(path, functionId, maxDepth);
    }
}

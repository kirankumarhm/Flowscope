package com.flowscope.api;

import com.flowscope.architecture.Architecture;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/architecture?path=<app-dir>} returns the high-level layered
 * architecture view (layers + aggregate dependencies + external resources).
 */
@RestController
@Tag(name = "Architecture", description = "A single app's layered architecture plus the external resources it uses.")
public class ArchitectureController {

    private final FlowService service;

    public ArchitectureController(FlowService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/architecture", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Build the architecture view",
            description = "Aggregates components into layers and attaches datastores, Kafka topics, and external systems.")
    public Architecture architecture(
            @Parameter(description = "Absolute path to a single application directory", example = "/path/to/my-app")
            @RequestParam(name = "path", required = false) String path) {
        return service.architecture(path);
    }
}

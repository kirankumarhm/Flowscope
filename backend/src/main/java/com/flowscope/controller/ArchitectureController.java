package com.flowscope.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.flowscope.dto.Architecture;
import com.flowscope.service.ArchitectureService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * {@code GET /api/architecture?path=<dir>} returns the whole-project architecture:
 * services, queues, data stores and external systems in columns. A path to one
 * service inside a project returns that project with the service highlighted.
 */
@RestController
@Tag(name = "Architecture", description = "Whole-project architecture: services, messaging, data stores and external systems.")
public class ArchitectureController {

    private final ArchitectureService service;

    public ArchitectureController(ArchitectureService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/architecture", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Build the whole-project architecture view",
            description = "Lays out every deployable service with its queues, data stores and external systems. "
                    + "When the path is one service, its project is shown and the service is highlighted (focus).")
    public Architecture architecture(
            @Parameter(description = "Absolute path to the project, or to one service inside it",
                    example = "/path/to/workspace/orders-service")
            @RequestParam(name = "path", required = false) String path,
            @Parameter(description = "Optional comma-separated directory names to exclude from the project scan")
            @RequestParam(name = "exclude", required = false) String exclude) {
        return service.architecture(path, exclude);
    }
}

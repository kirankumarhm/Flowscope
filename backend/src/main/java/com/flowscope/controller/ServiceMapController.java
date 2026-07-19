package com.flowscope.controller;

import com.flowscope.dto.ServiceMap;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.flowscope.service.ServiceMapService;

/**
 * {@code GET /api/servicemap?path=<workspace-dir>&exclude=<dir,dir>} returns the
 * whole-workspace service-to-service topology (Kafka, REST, datastores, externals).
 */
@RestController
@Tag(name = "Service Map", description = "Cross-language service-to-service topology of a whole workspace.")
public class ServiceMapController {

    private final ServiceMapService service;

    public ServiceMapController(ServiceMapService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/servicemap", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Build the service map",
            description = "Discovers services across languages and links them via Kafka topics, REST, datastores, and externals.")
    public ServiceMap serviceMap(
            @Parameter(description = "Workspace directory that CONTAINS the services", example = "/path/to/workspace")
            @RequestParam(name = "path", required = false) String path,
            @Parameter(description = "Comma-separated top-level directories to exclude")
            @RequestParam(name = "exclude", required = false) String exclude) {
        return service.serviceMap(path, exclude);
    }
}

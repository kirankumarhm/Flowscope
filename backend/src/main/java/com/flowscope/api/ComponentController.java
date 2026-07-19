package com.flowscope.api;

import com.flowscope.component.ComponentMap;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/component?path=<app-dir>} returns the intra-app component
 * diagram (Spring beans + their injection/call dependencies) for one service.
 */
@RestController
@Tag(name = "Component", description = "A single app's Spring beans and the dependencies between them.")
public class ComponentController {

    private final FlowService service;

    public ComponentController(FlowService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/component", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Build the component diagram",
            description = "Classes classified by layer (controller/service/repository/…) with injection & call edges.")
    public ComponentMap component(
            @Parameter(description = "Absolute path to a single application directory", example = "/path/to/my-app")
            @RequestParam(name = "path", required = false) String path) {
        return service.componentMap(path);
    }
}

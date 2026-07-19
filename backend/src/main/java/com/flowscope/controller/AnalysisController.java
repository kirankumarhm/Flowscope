package com.flowscope.controller;

import com.flowscope.entity.Graph;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.flowscope.service.AnalysisService;

/**
 * REST entry point. {@code GET /api/analyze?path=<dir>} runs the analysis
 * pipeline and returns the IR {@link Graph} (nodes, edges, per-function CFGs).
 * Errors are mapped to JSON bodies by {@link GlobalExceptionHandler}.
 */
@RestController
@Tag(name = "Analysis", description = "Parse a source directory into the intermediate representation (IR).")
public class AnalysisController {

    private final AnalysisService service;

    public AnalysisController(AnalysisService service) {
        this.service = service;
    }

    @GetMapping(value = "/api/analyze", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Analyze a source directory",
            description = "Runs File Walker → Parser → IR Builder and returns nodes, edges, and per-function CFGs.")
    public Graph analyze(
            @Parameter(description = "Absolute path to the source directory to analyze", example = "/path/to/my-app")
            @RequestParam(name = "path", required = false) String path) {
        return service.analyze(path);
    }
}

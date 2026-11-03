package com.flowscope.mcp.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowscope.mcp.model.McpModels.JsonRpcRequest;
import com.flowscope.mcp.service.McpServerDispatcher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-Sent Events (SSE) and HTTP transport controller for Model Context Protocol (MCP).
 */
@RestController
@RequestMapping("/mcp")
@Tag(name = "MCP Server", description = "Model Context Protocol (MCP) SSE and HTTP endpoints for AI coding agents.")
public class McpSseController {

    private static final Logger log = LoggerFactory.getLogger(McpSseController.class);
    private static final long SSE_TIMEOUT_MS = 30 * 60 * 1000L; // 30 minutes

    private final McpServerDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final Map<String, SseEmitter> sessions = new ConcurrentHashMap<>();

    public McpSseController(McpServerDispatcher dispatcher, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    /**
     * Establishes a persistent SSE stream for an MCP client.
     */
    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Establish MCP SSE stream",
            description = "Initiates a persistent Server-Sent Events stream, emitting the message endpoint URI.")
    public SseEmitter connectSse() {
        String sessionId = UUID.randomUUID().toString();
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        sessions.put(sessionId, emitter);
        log.info("New MCP SSE client connected, sessionId={}", sessionId);

        emitter.onCompletion(() -> {
            log.info("MCP SSE connection completed, sessionId={}", sessionId);
            sessions.remove(sessionId);
        });
        emitter.onTimeout(() -> {
            log.info("MCP SSE connection timed out, sessionId={}", sessionId);
            sessions.remove(sessionId);
            emitter.complete();
        });
        emitter.onError(e -> {
            log.warn("MCP SSE connection error, sessionId={}: {}", sessionId, e.getMessage());
            sessions.remove(sessionId);
        });

        // Send initial 'endpoint' event per MCP SSE transport specification
        try {
            emitter.send(SseEmitter.event()
                    .name("endpoint")
                    .data("/mcp/message?sessionId=" + sessionId));
        } catch (IOException e) {
            log.error("Failed to send initial endpoint event, sessionId={}", sessionId, e);
            sessions.remove(sessionId);
        }

        return emitter;
    }

    /**
     * Receives JSON-RPC messages from MCP clients.
     */
    @PostMapping(value = "/message", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Post JSON-RPC message",
            description = "Receives an MCP JSON-RPC 2.0 request. If sessionId is provided, sends response via SSE; otherwise returns response directly.")
    public ResponseEntity<?> handleMessage(
            @Parameter(description = "Active SSE session identifier", required = false)
            @RequestParam(name = "sessionId", required = false) String sessionId,
            @RequestBody JsonRpcRequest request) {

        Object response = dispatcher.dispatch(request);

        // If request is a notification (returns null), return 202 Accepted
        if (response == null) {
            return ResponseEntity.accepted().build();
        }

        // If an active SSE session was specified, send the response over SSE
        if (sessionId != null && sessions.containsKey(sessionId)) {
            SseEmitter emitter = sessions.get(sessionId);
            try {
                String json = objectMapper.writeValueAsString(response);
                emitter.send(SseEmitter.event().name("message").data(json));
                return ResponseEntity.accepted().build();
            } catch (Exception e) {
                log.error("Failed to dispatch MCP response over SSE, sessionId={}", sessionId, e);
                sessions.remove(sessionId);
            }
        }

        // Default / direct HTTP fallback: return response in HTTP response body
        return ResponseEntity.ok(response);
    }
}


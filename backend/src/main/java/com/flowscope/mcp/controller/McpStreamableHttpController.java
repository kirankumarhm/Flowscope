package com.flowscope.mcp.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowscope.mcp.model.McpModels.JsonRpcErrorResponse;
import com.flowscope.mcp.model.McpModels.JsonRpcRequest;
import com.flowscope.mcp.service.McpServerDispatcher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MCP Streamable HTTP transport (protocol 2025-03-26 and later): a single
 * {@code POST /mcp} endpoint that takes a JSON-RPC message (or, for 2025-03-26
 * clients, a batch) and answers with {@code application/json}. The server never
 * initiates messages, so {@code GET /mcp} (a server-to-client stream) returns 405.
 *
 * <p>Browsers' {@code Origin} header is validated to block DNS-rebinding attacks:
 * only loopback origins and {@code flowscope.cors.allowed-origins} are accepted.
 * Requests without an Origin (non-browser MCP clients) are allowed.
 */
@RestController
@RequestMapping("/mcp")
@Tag(name = "MCP Server", description = "Model Context Protocol (MCP) endpoints for AI coding agents.")
public class McpStreamableHttpController {

    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]", "::1");

    private final McpServerDispatcher dispatcher;
    private final ObjectMapper objectMapper;
    private final List<String> allowedOrigins;

    public McpStreamableHttpController(McpServerDispatcher dispatcher, ObjectMapper objectMapper,
                                       @Value("${flowscope.cors.allowed-origins:http://localhost:5173}") String origins) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
        this.allowedOrigins = Arrays.stream(origins.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "MCP Streamable HTTP message",
            description = "Receives an MCP JSON-RPC 2.0 message and returns the response as JSON.")
    public ResponseEntity<?> post(@RequestHeader(value = "Origin", required = false) String origin,
                                  @RequestBody JsonNode body) {
        if (!originAllowed(origin, allowedOrigins)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(JsonRpcErrorResponse.of(null, -32600, "Origin not allowed: " + origin));
        }
        if (body == null || body.isNull()) {
            return ResponseEntity.badRequest().body(JsonRpcErrorResponse.of(null, -32700, "Parse error"));
        }
        if (body.isArray()) {
            List<Object> responses = new ArrayList<>();
            for (JsonNode item : body) {
                Object r = dispatch(item);
                if (r != null) {
                    responses.add(r);
                }
            }
            return responses.isEmpty() ? ResponseEntity.accepted().build() : ResponseEntity.ok(responses);
        }
        Object response = dispatch(body);
        if (response == null) {
            return ResponseEntity.accepted().build(); // notification or client response
        }
        ResponseEntity.BodyBuilder ok = ResponseEntity.ok();
        if (body.hasNonNull("method") && "initialize".equals(body.get("method").asText())) {
            ok.header("Mcp-Session-Id", UUID.randomUUID().toString());
        }
        return ok.body(response);
    }

    @GetMapping
    @Operation(summary = "Server-to-client stream (not offered)")
    public ResponseEntity<Void> stream() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    @DeleteMapping
    @Operation(summary = "Session termination (not required: the server keeps no session state)")
    public ResponseEntity<Void> terminate() {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).build();
    }

    private Object dispatch(JsonNode message) {
        JsonRpcRequest request;
        try {
            request = objectMapper.treeToValue(message, JsonRpcRequest.class);
        } catch (Exception e) {
            return JsonRpcErrorResponse.of(null, -32600, "Invalid Request");
        }
        if (request.method() == null) {
            return null; // a client response to a server request; this server sends none
        }
        return dispatcher.dispatch(request);
    }

    /** No Origin (non-browser client), a loopback origin, or a configured CORS origin. */
    public static boolean originAllowed(String origin, List<String> allowed) {
        if (origin == null || origin.isBlank()) {
            return true;
        }
        if (allowed.contains(origin)) {
            return true;
        }
        try {
            String host = URI.create(origin).getHost();
            return host != null && LOOPBACK.contains(host);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

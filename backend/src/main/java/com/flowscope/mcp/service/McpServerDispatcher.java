package com.flowscope.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowscope.mcp.model.McpModels;
import com.flowscope.mcp.model.McpModels.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Core JSON-RPC 2.0 message dispatcher implementing the Model Context Protocol (v2024-11-05).
 */
@Service
public class McpServerDispatcher {

    private static final Logger log = LoggerFactory.getLogger(McpServerDispatcher.class);

    private final FlowScopeMcpToolHandler toolHandler;
    private final ObjectMapper objectMapper;

    public McpServerDispatcher(FlowScopeMcpToolHandler toolHandler, ObjectMapper objectMapper) {
        this.toolHandler = toolHandler;
        this.objectMapper = objectMapper;
    }

    /**
     * Dispatches an inbound JSON-RPC 2.0 request and produces an outbound response.
     * Returns null for notifications where no response is expected.
     */
    public Object dispatch(JsonRpcRequest request) {
        if (request == null) {
            return JsonRpcErrorResponse.of(null, -32600, "Invalid Request");
        }

        String method = request.method();
        Object id = request.id();
        JsonNode params = request.params();

        if (method == null) {
            return JsonRpcErrorResponse.of(id, -32600, "Missing method name");
        }

        // Notification handler (no response sent)
        if (method.equals("notifications/initialized") || method.startsWith("notifications/")) {
            log.info("Received MCP notification: {}", method);
            return null;
        }

        try {
            return switch (method) {
                case "initialize" -> handleInitialize(id, params);
                case "ping" -> JsonRpcResponse.success(id, Map.of());
                case "tools/list" -> handleToolsList(id);
                case "tools/call" -> handleToolsCall(id, params);
                case "resources/list" -> handleResourcesList(id);
                case "resources/read" -> handleResourcesRead(id, params);
                case "prompts/list" -> handlePromptsList(id);
                case "prompts/get" -> handlePromptsGet(id, params);
                default -> JsonRpcErrorResponse.of(id, -32601, "Method not found: " + method);
            };
        } catch (Exception e) {
            log.error("Error executing MCP method {}", method, e);
            return JsonRpcErrorResponse.of(id, -32603, "Internal error: " + e.getMessage());
        }
    }

    private JsonRpcResponse handleInitialize(Object id, JsonNode params) {
        ServerCapabilities capabilities = new ServerCapabilities(
                new ServerCapabilities.ToolsCapability(false),
                new ServerCapabilities.ResourcesCapability(false, false),
                new ServerCapabilities.PromptsCapability(false));

        Implementation serverInfo = new Implementation(McpModels.SERVER_NAME, McpModels.SERVER_VERSION);
        InitializeResult result = new InitializeResult(McpModels.PROTOCOL_VERSION, capabilities, serverInfo);
        return JsonRpcResponse.success(id, result);
    }

    private JsonRpcResponse handleToolsList(Object id) {
        List<ToolDefinition> tools = toolHandler.listTools();
        return JsonRpcResponse.success(id, Map.of("tools", tools));
    }

    private JsonRpcResponse handleToolsCall(Object id, JsonNode params) {
        if (params == null || !params.has("name")) {
            return JsonRpcResponse.success(id, CallToolResult.error("Missing required parameter 'name'"));
        }
        String name = params.get("name").asText();
        JsonNode args = params.get("arguments");
        CallToolResult result = toolHandler.callTool(name, args);
        return JsonRpcResponse.success(id, result);
    }

    private JsonRpcResponse handleResourcesList(Object id) {
        List<ResourceDefinition> resources = List.of(
                new ResourceDefinition(
                        "flowscope://architecture",
                        "App Architecture View",
                        "Layered architecture and external dependencies for a single application",
                        "application/json"),
                new ResourceDefinition(
                        "flowscope://servicemap",
                        "Workspace Service Map",
                        "Cross-language service-to-service communication topology (REST, Kafka, gRPC)",
                        "application/json"));
        return JsonRpcResponse.success(id, Map.of("resources", resources));
    }

    private JsonRpcResponse handleResourcesRead(Object id, JsonNode params) {
        if (params == null || !params.has("uri")) {
            return JsonRpcResponse.success(id, CallToolResult.error("Missing required parameter 'uri'"));
        }
        String uri = params.get("uri").asText();
        return JsonRpcResponse.success(id, new ReadResourceResult(List.of(
                new ReadResourceResult.ResourceContent(uri, "text/plain", "Use the flowscope_architecture or flowscope_servicemap tools with a 'path' parameter."))));
    }

    private JsonRpcResponse handlePromptsList(Object id) {
        List<PromptDefinition> prompts = List.of(
                new PromptDefinition(
                        "audit-endpoint",
                        "Comprehensive analysis of an API endpoint: inter-procedural flow, sequence diagram, and business logic intent",
                        List.of(
                                new PromptDefinition.PromptArgument("path", "Absolute path to repository root", true),
                                new PromptDefinition.PromptArgument("functionId", "Target function/endpoint ID", true))),
                new PromptDefinition(
                        "review-architecture",
                        "Review application architecture layers, external dependencies, and component fan-in/fan-out metrics",
                        List.of(
                                new PromptDefinition.PromptArgument("path", "Absolute path to application root", true))));
        return JsonRpcResponse.success(id, Map.of("prompts", prompts));
    }

    private Object handlePromptsGet(Object id, JsonNode params) {
        if (params == null || !params.has("name")) {
            return JsonRpcResponse.success(id, CallToolResult.error("Missing required parameter 'name'"));
        }
        String name = params.get("name").asText();
        JsonNode args = params.get("arguments");

        if ("audit-endpoint".equals(name)) {
            String path = args != null && args.has("path") ? args.get("path").asText() : "";
            String functionId = args != null && args.has("functionId") ? args.get("functionId").asText() : "";

            String promptText = """
                    Please perform a comprehensive architectural and flow audit for the following function/endpoint:
                    - Repository Path: %s
                    - Target Function: %s

                    Steps to execute:
                    1. Call `flowscope_flow_description` to examine the step-by-step execution path.
                    2. Call `flowscope_sequence` to retrieve the Mermaid sequence diagram showing component interactions.
                    3. Call `flowscope_business_logic` to determine business rules and validation constraints.
                    4. Summarize your findings, identifying potential error branches, database transactions, or bottlenecks.
                    """.formatted(path, functionId);

            GetPromptResult result = new GetPromptResult(
                    "Endpoint Audit Workflow",
                    List.of(new GetPromptResult.PromptMessage("user", new TextContent("text", promptText))));
            return JsonRpcResponse.success(id, result);
        }

        if ("review-architecture".equals(name)) {
            String path = args != null && args.has("path") ? args.get("path").asText() : "";
            String promptText = """
                    Please review the architecture of the application at: %s

                    Steps to execute:
                    1. Call `flowscope_architecture` to retrieve layers, datastores, Kafka topics, and external dependencies.
                    2. Call `flowscope_component` to inspect Spring beans, layer classifications, and injection/call graphs.
                    3. Assess architectural layering integrity, identifying high fan-in/fan-out classes or anomalous dependencies.
                    """.formatted(path);

            GetPromptResult result = new GetPromptResult(
                    "Architecture Review Workflow",
                    List.of(new GetPromptResult.PromptMessage("user", new TextContent("text", promptText))));
            return JsonRpcResponse.success(id, result);
        }

        return JsonRpcErrorResponse.of(id, -32602, "Unknown prompt: " + name);
    }
}


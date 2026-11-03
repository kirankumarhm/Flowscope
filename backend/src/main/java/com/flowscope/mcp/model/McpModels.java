package com.flowscope.mcp.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;

/**
 * Model Context Protocol (MCP) version 2024-11-05 / JSON-RPC 2.0 data models.
 */
public final class McpModels {

    private McpModels() {
    }

    public static final String JSONRPC_VERSION = "2.0";
    public static final String PROTOCOL_VERSION = "2024-11-05";
    public static final String SERVER_NAME = "flowscope";
    public static final String SERVER_VERSION = "0.1.0";

    /**
     * Inbound JSON-RPC 2.0 request or notification.
     *
     * @param jsonrpc "2.0"
     * @param id      identifier for requests (null for notifications)
     * @param method  the RPC method name (e.g. "initialize", "tools/list", "tools/call")
     * @param params  optional method parameters
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JsonRpcRequest(
            String jsonrpc,
            Object id,
            String method,
            JsonNode params) {
    }

    /**
     * Outbound JSON-RPC 2.0 success response.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JsonRpcResponse(
            String jsonrpc,
            Object id,
            Object result) {

        public static JsonRpcResponse success(Object id, Object result) {
            return new JsonRpcResponse(JSONRPC_VERSION, id, result);
        }
    }

    /**
     * Outbound JSON-RPC 2.0 error response.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JsonRpcErrorResponse(
            String jsonrpc,
            Object id,
            ErrorDetail error) {

        public record ErrorDetail(int code, String message, Object data) {
        }

        public static JsonRpcErrorResponse of(Object id, int code, String message) {
            return new JsonRpcErrorResponse(JSONRPC_VERSION, id, new ErrorDetail(code, message, null));
        }

        public static JsonRpcErrorResponse of(Object id, int code, String message, Object data) {
            return new JsonRpcErrorResponse(JSONRPC_VERSION, id, new ErrorDetail(code, message, data));
        }
    }

    /**
     * Outbound JSON-RPC 2.0 notification.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record JsonRpcNotification(
            String jsonrpc,
            String method,
            Object params) {

        public static JsonRpcNotification of(String method, Object params) {
            return new JsonRpcNotification(JSONRPC_VERSION, method, params);
        }
    }

    /**
     * Server capabilities descriptor returned during initialization.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ServerCapabilities(
            ToolsCapability tools,
            ResourcesCapability resources,
            PromptsCapability prompts) {

        public record ToolsCapability(boolean listChanged) {
        }

        public record ResourcesCapability(boolean subscribe, boolean listChanged) {
        }

        public record PromptsCapability(boolean listChanged) {
        }
    }

    public record Implementation(String name, String version) {
    }

    /**
     * Result of the "initialize" handshake method.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InitializeResult(
            String protocolVersion,
            ServerCapabilities capabilities,
            Implementation serverInfo) {
    }

    /**
     * Tool definition exposed in tools/list.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolDefinition(
            String name,
            String description,
            ToolInputSchema inputSchema) {
    }

    /**
     * JSON Schema description for a tool's parameters.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ToolInputSchema(
            String type,
            Map<String, ParameterProperty> properties,
            List<String> required) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ParameterProperty(
            String type,
            String description,
            Object defaultValue,
            List<String> enums) {

        public static ParameterProperty of(String type, String description) {
            return new ParameterProperty(type, description, null, null);
        }

        public static ParameterProperty of(String type, String description, Object defaultValue) {
            return new ParameterProperty(type, description, defaultValue, null);
        }
    }

    /**
     * Result returned by tools/call.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CallToolResult(
            List<ContentItem> content,
            boolean isError) {

        public static CallToolResult text(String text) {
            return new CallToolResult(List.of(new TextContent("text", text)), false);
        }

        public static CallToolResult error(String errorMessage) {
            return new CallToolResult(List.of(new TextContent("text", errorMessage)), true);
        }
    }

    public interface ContentItem {
        String type();
    }

    public record TextContent(String type, String text) implements ContentItem {
    }

    /**
     * Resource definition exposed in resources/list.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResourceDefinition(
            String uri,
            String name,
            String description,
            String mimeType) {
    }

    /**
     * Result returned by resources/read.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReadResourceResult(List<ResourceContent> contents) {
        public record ResourceContent(String uri, String mimeType, String text) {
        }
    }

    /**
     * Prompt definition exposed in prompts/list.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PromptDefinition(
            String name,
            String description,
            List<PromptArgument> arguments) {

        public record PromptArgument(String name, String description, boolean required) {
        }
    }

    /**
     * Result returned by prompts/get.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GetPromptResult(
            String description,
            List<PromptMessage> messages) {

        public record PromptMessage(String role, TextContent content) {
        }
    }
}


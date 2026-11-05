package com.flowscope.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowscope.dto.Architecture;
import com.flowscope.dto.BusinessLogic;
import com.flowscope.dto.ComponentMap;
import com.flowscope.dto.FlowDescription;
import com.flowscope.dto.SequenceDiagram;
import com.flowscope.dto.ServiceMap;
import com.flowscope.entity.CFG;
import com.flowscope.entity.Graph;
import com.flowscope.mcp.model.McpModels.CallToolResult;
import com.flowscope.mcp.model.McpModels.ParameterProperty;
import com.flowscope.mcp.model.McpModels.ToolDefinition;
import com.flowscope.mcp.model.McpModels.ToolInputSchema;
import com.flowscope.service.AnalysisService;
import com.flowscope.service.ArchitectureService;
import com.flowscope.service.BusinessLogicService;
import com.flowscope.service.FlowDescriptionService;
import com.flowscope.service.FlowService;
import com.flowscope.service.ServiceMapService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dispatches MCP tool invocations to FlowScope core services.
 */
@Service
public class FlowScopeMcpToolHandler {

    private final AnalysisService analysisService;
    private final FlowService flowService;
    private final FlowDescriptionService descriptionService;
    private final ServiceMapService serviceMapService;
    private final BusinessLogicService businessLogicService;
    private final ArchitectureService architectureService;
    private final ObjectMapper objectMapper;

    public FlowScopeMcpToolHandler(
            AnalysisService analysisService,
            FlowService flowService,
            FlowDescriptionService descriptionService,
            ServiceMapService serviceMapService,
            BusinessLogicService businessLogicService,
            ArchitectureService architectureService,
            ObjectMapper objectMapper) {
        this.analysisService = analysisService;
        this.flowService = flowService;
        this.descriptionService = descriptionService;
        this.serviceMapService = serviceMapService;
        this.businessLogicService = businessLogicService;
        this.architectureService = architectureService;
        this.objectMapper = objectMapper;
    }

    /**
     * Lists all available FlowScope MCP tools.
     */
    public List<ToolDefinition> listTools() {
        List<ToolDefinition> tools = new ArrayList<>();

        // 1. flowscope_analyze
        tools.add(new ToolDefinition(
                "flowscope_analyze",
                "Analyzes and indexes a source codebase directory into a language-neutral Intermediate Representation (IR). The default summary lists modules, languages, counts and entry points (with their functionIds); format=json returns the whole IR.",
                new ToolInputSchema(
                        "object",
                        Map.of("path", ParameterProperty.of("string", "Absolute path to the source repository directory"),
                                "format", ParameterProperty.ofEnum("Output format (default summary)", "summary",
                                        List.of("summary", "json"))),
                        List.of("path"))));

        // 1b. flowscope_find_entry_points
        tools.add(new ToolDefinition(
                "flowscope_find_entry_points",
                "Finds functions and HTTP endpoints whose path, name or file matches a query, returning the functionId to pass to flowscope_flow, flowscope_sequence or flowscope_business_logic. With no query, lists every endpoint and main().",
                new ToolInputSchema(
                        "object",
                        Map.of("path", ParameterProperty.of("string", "Absolute path to the analyzed application root"),
                                "query", ParameterProperty.of("string", "Case-insensitive text to match, e.g. /orders or placeOrder")),
                        List.of("path"))));

        // 2. flowscope_flow
        tools.add(new ToolDefinition(
                "flowscope_flow",
                "Generates an endpoint-rooted, inter-procedural control-flow graph (CFG) for a function, inlining downstream callee methods up to the specified depth.",
                new ToolInputSchema(
                        "object",
                        Map.of(
                                "path", ParameterProperty.of("string", "Absolute path to the analyzed application root"),
                                "functionId", ParameterProperty.of("string", "Function/endpoint ID (e.g., func:com.example.OrderController#placeOrder(OrderRequest))"),
                                "maxDepth", ParameterProperty.of("integer", "Call levels to inline (1 to 8, default 4)", 4)),
                        List.of("path", "functionId"))));

        // 3. flowscope_flow_description
        tools.add(new ToolDefinition(
                "flowscope_flow_description",
                "Provides a step-by-step human-readable walkthrough of the inter-procedural flow for a function in exact execution order, describing decisions, branches, and inlined method calls.",
                new ToolInputSchema(
                        "object",
                        Map.of(
                                "path", ParameterProperty.of("string", "Absolute path to the analyzed application root"),
                                "functionId", ParameterProperty.of("string", "Function/endpoint node ID"),
                                "maxDepth", ParameterProperty.of("integer", "Call levels to inline (1 to 8, default 4)", 4)),
                        List.of("path", "functionId"))));

        // 4. flowscope_sequence
        tools.add(new ToolDefinition(
                "flowscope_sequence",
                "Generates a complete Mermaid sequence diagram tracing component-to-component interactions for a given request or function entry point.",
                new ToolInputSchema(
                        "object",
                        Map.of(
                                "path", ParameterProperty.of("string", "Absolute path to the analyzed application root"),
                                "functionId", ParameterProperty.of("string", "Function/endpoint node ID"),
                                "maxDepth", ParameterProperty.of("integer", "Call levels to trace (1 to 20, default 10)", 10)),
                        List.of("path", "functionId"))));

        // 5. flowscope_component
        tools.add(new ToolDefinition(
                "flowscope_component",
                "Extracts intra-application component architecture: Spring beans (controllers, services, repositories, clients, configs) and their injection/call dependencies.",
                new ToolInputSchema(
                        "object",
                        Map.of("path", ParameterProperty.of("string", "Absolute path to the single application directory")),
                        List.of("path"))));

        // 6. flowscope_architecture
        tools.add(new ToolDefinition(
                "flowscope_architecture",
                "Generates an architecture view. A folder with several services gives the whole project: every deployable service grouped into front ends, APIs and processing, with their queues, data stores (one per table/bucket/database), external systems and cloud services, and the connections between them. A folder with one service gives that application: its components by layer, one Kafka box with its topics, and the services, queues, data stores, external systems and cloud services it connects to, each connection starting at the layer whose code makes it. Default output is a compact summary.",
                new ToolInputSchema(
                        "object",
                        Map.of("path", ParameterProperty.of("string", "Absolute path to a project folder (several services) or to one service's folder"),
                                "exclude", ParameterProperty.of("string", "Optional comma-separated directory names to exclude"),
                                "format", ParameterProperty.ofEnum("Output format (default summary)", "summary",
                                        List.of("summary", "json"))),
                        List.of("path"))));

        // 7. flowscope_servicemap
        tools.add(new ToolDefinition(
                "flowscope_servicemap",
                "Discovers multi-service workspace topology across languages (Java, TypeScript, Python, Go) and maps inter-service communication (Kafka topics, REST/SOAP calls, gRPC, shared datastores, cloud SDKs, Lambda triggers). Every edge has a confidence (declared, code, config, inferred, unresolved) and file:line evidence. Default output is a compact summary, one line per edge.",
                new ToolInputSchema(
                        "object",
                        Map.of(
                                "path", ParameterProperty.of("string", "Workspace directory that contains multiple services"),
                                "exclude", ParameterProperty.of("string", "Optional comma-separated directory names to exclude"),
                                "format", ParameterProperty.ofEnum("Output format (default summary)", "summary",
                                        List.of("summary", "mermaid", "json")),
                                "service", ParameterProperty.of("string", "Only edges touching these services (comma-separated names)"),
                                "kind", ParameterProperty.of("string", "Only these edge kinds: kafka, rest, soap, datastore, grpc, cloud, trigger, reference (comma-separated)"),
                                "confidence", ParameterProperty.of("string", "Only these confidence levels: declared, code, config, inferred, unresolved (comma-separated)")),
                        List.of("path"))));

        // 8. flowscope_business_logic
        tools.add(new ToolDefinition(
                "flowscope_business_logic",
                "Explains the business purpose and intent of a function flow, including ordered execution phases, enforced business rules/invariants, and analysis caveats.",
                new ToolInputSchema(
                        "object",
                        Map.of(
                                "path", ParameterProperty.of("string", "Absolute path to the analyzed application root"),
                                "functionId", ParameterProperty.of("string", "Function/endpoint node ID"),
                                "maxDepth", ParameterProperty.of("integer", "Call levels to inline (1 to 8, default 4)", 4)),
                        List.of("path", "functionId"))));

        // 9. flowscope_llm_status
        tools.add(new ToolDefinition(
                "flowscope_llm_status",
                "Reports whether an external LLM provider is configured in FlowScope for intent explanations, and returns its non-secret provider details.",
                new ToolInputSchema("object", Map.of(), List.of())));

        return tools;
    }

    /**
     * Executes a tool by name with the given arguments.
     */
    public CallToolResult callTool(String name, JsonNode args) {
        try {
            return switch (name) {
                case "flowscope_analyze" -> callAnalyze(args);
                case "flowscope_find_entry_points" -> callFindEntryPoints(args);
                case "flowscope_flow" -> callFlow(args);
                case "flowscope_flow_description" -> callFlowDescription(args);
                case "flowscope_sequence" -> callSequence(args);
                case "flowscope_component" -> callComponent(args);
                case "flowscope_architecture" -> callArchitecture(args);
                case "flowscope_servicemap" -> callServiceMap(args);
                case "flowscope_business_logic" -> callBusinessLogic(args);
                case "flowscope_llm_status" -> callLlmStatus();
                default -> CallToolResult.error("Unknown tool: " + name);
            };
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return CallToolResult.error("FlowScope execution error: " + msg);
        }
    }

    private CallToolResult callAnalyze(JsonNode args) throws Exception {
        String path = getString(args, "path");
        Graph graph = analysisService.analyze(path);
        if ("json".equalsIgnoreCase(getString(args, "format"))) {
            return CallToolResult.text(json(graph));
        }
        return CallToolResult.text(McpFormats.analyzeSummary(graph));
    }

    private CallToolResult callFindEntryPoints(JsonNode args) throws Exception {
        Graph graph = analysisService.analyze(getString(args, "path"));
        return CallToolResult.text(McpFormats.findEntryPoints(graph, getString(args, "query")));
    }

    /** Compact JSON: pretty-printing roughly doubles the size agents have to read. */
    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private CallToolResult callFlow(JsonNode args) throws Exception {
        String path = getString(args, "path");
        String functionId = getString(args, "functionId");
        Integer maxDepth = getInteger(args, "maxDepth");
        CFG cfg = flowService.flow(path, functionId, maxDepth);
        return CallToolResult.text(json(cfg));
    }

    private CallToolResult callFlowDescription(JsonNode args) throws Exception {
        String path = getString(args, "path");
        String functionId = getString(args, "functionId");
        Integer maxDepth = getInteger(args, "maxDepth");
        CFG cfg = flowService.flow(path, functionId, maxDepth);
        FlowDescription desc = descriptionService.generate(cfg);
        
        // Format with plain text walkthrough for immediate agent comprehension
        StringBuilder sb = new StringBuilder();
        sb.append("### Flow Walkthrough: ").append(desc.functionId()).append("\n\n");
        sb.append("**Headline**: ").append(desc.headline()).append("\n\n");
        sb.append("**Summary Stats**: ")
          .append(desc.stats().nodes()).append(" nodes, ")
          .append(desc.stats().calls()).append(" calls, ")
          .append(desc.stats().decisions()).append(" decisions, ")
          .append(desc.stats().methods()).append(" methods inlined.\n\n");
        sb.append("```text\n").append(desc.text()).append("\n```\n\n");
        sb.append("Full JSON Details:\n");
        sb.append(json(desc));
        return CallToolResult.text(sb.toString());
    }

    private CallToolResult callSequence(JsonNode args) throws Exception {
        String path = getString(args, "path");
        String functionId = getString(args, "functionId");
        Integer maxDepth = getInteger(args, "maxDepth");
        SequenceDiagram seq = flowService.sequence(path, functionId, maxDepth);
        
        StringBuilder sb = new StringBuilder();
        sb.append("### Sequence Diagram for `").append(seq.entryFunctionId()).append("`\n\n");
        sb.append("Participants: ").append(String.join(", ", seq.participants())).append("\n\n");
        sb.append("```mermaid\n").append(seq.mermaidText()).append("\n```\n");
        return CallToolResult.text(sb.toString());
    }

    private CallToolResult callComponent(JsonNode args) throws Exception {
        String path = getString(args, "path");
        ComponentMap componentMap = flowService.componentMap(path);
        return CallToolResult.text(json(componentMap));
    }

    private CallToolResult callArchitecture(JsonNode args) throws Exception {
        Architecture architecture = architectureService.architecture(getString(args, "path"), getString(args, "exclude"));
        if ("json".equalsIgnoreCase(getString(args, "format"))) {
            return CallToolResult.text(json(architecture));
        }
        return CallToolResult.text(McpFormats.architectureSummary(architecture));
    }

    private CallToolResult callServiceMap(JsonNode args) throws Exception {
        String path = getString(args, "path");
        String exclude = getString(args, "exclude");
        ServiceMap serviceMap = McpFormats.filter(serviceMapService.serviceMap(path, exclude),
                getString(args, "service"), getString(args, "kind"), getString(args, "confidence"));
        String format = getString(args, "format");
        if (format == null || format.isBlank() || format.equalsIgnoreCase("summary")) {
            return CallToolResult.text(McpFormats.summary(serviceMap));
        }
        if (format.equalsIgnoreCase("mermaid")) {
            return CallToolResult.text(McpFormats.mermaid(serviceMap));
        }
        if (format.equalsIgnoreCase("json")) {
            return CallToolResult.text(json(serviceMap));
        }
        return CallToolResult.error("Unknown format '" + format + "'; use summary, mermaid or json");
    }

    private CallToolResult callBusinessLogic(JsonNode args) throws Exception {
        String path = getString(args, "path");
        String functionId = getString(args, "functionId");
        Integer maxDepth = getInteger(args, "maxDepth");
        BusinessLogic logic = businessLogicService.analyze(path, functionId, maxDepth);
        return CallToolResult.text(json(logic));
    }

    private CallToolResult callLlmStatus() throws Exception {
        Map<String, Object> status = Map.of(
                "available", businessLogicService.llmAvailable(),
                "provider", businessLogicService.providerDescription());
        return CallToolResult.text(json(status));
    }

    private static String getString(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asText();
    }

    private static Integer getInteger(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return null;
        }
        return node.get(field).asInt();
    }
}


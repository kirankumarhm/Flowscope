package com.flowscope.mcp;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowscope.mcp.model.McpModels;
import com.flowscope.mcp.model.McpModels.CallToolResult;
import com.flowscope.mcp.model.McpModels.InitializeResult;
import com.flowscope.mcp.model.McpModels.JsonRpcRequest;
import com.flowscope.mcp.model.McpModels.JsonRpcResponse;
import com.flowscope.mcp.service.FlowScopeMcpToolHandler;
import com.flowscope.mcp.service.McpServerDispatcher;
import com.flowscope.service.AnalysisService;
import com.flowscope.service.BusinessLogicService;
import com.flowscope.service.FlowDescriptionService;
import com.flowscope.service.FlowService;
import com.flowscope.service.ServiceMapService;

class McpServerDispatcherTest {

    private AnalysisService analysisService;
    private FlowService flowService;
    private FlowDescriptionService descriptionService;
    private ServiceMapService serviceMapService;
    private BusinessLogicService businessLogicService;
    private ObjectMapper objectMapper;
    private McpServerDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        analysisService = Mockito.mock(AnalysisService.class);
        flowService = Mockito.mock(FlowService.class);
        descriptionService = Mockito.mock(FlowDescriptionService.class);
        serviceMapService = Mockito.mock(ServiceMapService.class);
        businessLogicService = Mockito.mock(BusinessLogicService.class);
        objectMapper = new ObjectMapper();

        FlowScopeMcpToolHandler toolHandler = new FlowScopeMcpToolHandler(
                analysisService,
                flowService,
                descriptionService,
                serviceMapService,
                businessLogicService,
                Mockito.mock(com.flowscope.service.ArchitectureService.class),
                objectMapper);

        dispatcher = new McpServerDispatcher(toolHandler, objectMapper);
    }

    @Test
    @DisplayName("initialize returns protocol 2024-11-05 and server capabilities")
    void testInitialize() {
        JsonRpcRequest request = new JsonRpcRequest("2.0", 1, "initialize", null);
        Object response = dispatcher.dispatch(request);

        assertThat(response).isInstanceOf(JsonRpcResponse.class);
        JsonRpcResponse success = (JsonRpcResponse) response;
        assertThat(success.id()).isEqualTo(1);
        assertThat(success.result()).isInstanceOf(InitializeResult.class);

        InitializeResult init = (InitializeResult) success.result();
        assertThat(init.protocolVersion()).isEqualTo("2024-11-05");
        assertThat(init.serverInfo().name()).isEqualTo("flowscope");
    }

    @Test
    @DisplayName("initialize answers a supported requested version and falls back to the newest otherwise")
    void testVersionNegotiation() {
        JsonNode newer = objectMapper.createObjectNode().put("protocolVersion", "2025-06-18");
        InitializeResult a = (InitializeResult) ((JsonRpcResponse) dispatcher.dispatch(
                new JsonRpcRequest("2.0", 1, "initialize", newer))).result();
        assertThat(a.protocolVersion()).isEqualTo("2025-06-18");

        JsonNode unknown = objectMapper.createObjectNode().put("protocolVersion", "2099-01-01");
        InitializeResult b = (InitializeResult) ((JsonRpcResponse) dispatcher.dispatch(
                new JsonRpcRequest("2.0", 2, "initialize", unknown))).result();
        assertThat(b.protocolVersion()).isEqualTo(McpModels.SUPPORTED_PROTOCOL_VERSIONS.get(0));
    }

    @Test
    @DisplayName("tool schemas use the JSON Schema keywords 'default' and 'enum'")
    void testSchemaKeywords() throws Exception {
        String json = objectMapper.writeValueAsString(dispatcher.dispatch(
                new JsonRpcRequest("2.0", 9, "tools/list", null)));
        assertThat(json).contains("\"enum\":[").contains("\"default\":").doesNotContain("\"enums\"")
                .doesNotContain("\"defaultValue\"");
    }

    @Test
    @DisplayName("tools/list returns all 10 FlowScope analysis tools")
    void testToolsList() {
        JsonRpcRequest request = new JsonRpcRequest("2.0", 2, "tools/list", null);
        Object response = dispatcher.dispatch(request);

        assertThat(response).isInstanceOf(JsonRpcResponse.class);
        JsonRpcResponse success = (JsonRpcResponse) response;
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) success.result();
        assertThat(result).containsKey("tools");

        @SuppressWarnings("unchecked")
        List<?> tools = (List<?>) result.get("tools");
        assertThat(tools).hasSize(10);
    }

    @Test
    @DisplayName("tools/call executes flowscope_llm_status successfully")
    void testToolsCallLlmStatus() {
        when(businessLogicService.llmAvailable()).thenReturn(true);
        when(businessLogicService.providerDescription()).thenReturn("ollama:llama3");

        JsonNode params = objectMapper.createObjectNode()
                .put("name", "flowscope_llm_status");

        JsonRpcRequest request = new JsonRpcRequest("2.0", 3, "tools/call", params);
        Object response = dispatcher.dispatch(request);

        assertThat(response).isInstanceOf(JsonRpcResponse.class);
        JsonRpcResponse success = (JsonRpcResponse) response;
        assertThat(success.result()).isInstanceOf(CallToolResult.class);

        CallToolResult toolResult = (CallToolResult) success.result();
        assertThat(toolResult.isError()).isFalse();
        assertThat(((McpModels.TextContent) toolResult.content().get(0)).text()).contains("ollama:llama3");
    }

    @Test
    @DisplayName("prompts/list and prompts/get return workflow prompts")
    void testPrompts() {
        JsonRpcRequest listReq = new JsonRpcRequest("2.0", 4, "prompts/list", null);
        Object listResp = dispatcher.dispatch(listReq);
        assertThat(listResp).isInstanceOf(JsonRpcResponse.class);

        JsonNode getParams = objectMapper.createObjectNode()
                .put("name", "audit-endpoint")
                .set("arguments", objectMapper.createObjectNode()
                        .put("path", "/test/repo")
                        .put("functionId", "func:OrderController#checkout"));

        JsonRpcRequest getReq = new JsonRpcRequest("2.0", 5, "prompts/get", getParams);
        Object getResp = dispatcher.dispatch(getReq);
        assertThat(getResp).isInstanceOf(JsonRpcResponse.class);
    }
}


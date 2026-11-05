package com.flowscope.mcp;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowscope.mcp.controller.McpStreamableHttpController;
import com.flowscope.mcp.service.FlowScopeMcpToolHandler;
import com.flowscope.mcp.service.McpServerDispatcher;
import com.flowscope.service.AnalysisService;
import com.flowscope.service.BusinessLogicService;
import com.flowscope.service.FlowDescriptionService;
import com.flowscope.service.FlowService;
import com.flowscope.service.ServiceMapService;

class McpStreamableHttpControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private McpStreamableHttpController controller;

    @BeforeEach
    void setUp() {
        FlowScopeMcpToolHandler tools = new FlowScopeMcpToolHandler(
                Mockito.mock(AnalysisService.class), Mockito.mock(FlowService.class),
                Mockito.mock(FlowDescriptionService.class), Mockito.mock(ServiceMapService.class),
                Mockito.mock(BusinessLogicService.class),
                Mockito.mock(com.flowscope.service.ArchitectureService.class), mapper);
        controller = new McpStreamableHttpController(new McpServerDispatcher(tools, mapper), mapper,
                "http://localhost:5173");
    }

    private JsonNode json(String s) throws Exception {
        return mapper.readTree(s);
    }

    @Test
    void initializeReturnsJsonAndASessionId() throws Exception {
        ResponseEntity<?> r = controller.post(null, json(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(r.getHeaders().getFirst("Mcp-Session-Id")).isNotBlank();
        assertThat(mapper.writeValueAsString(r.getBody())).contains("\"protocolVersion\":\"2025-03-26\"");
    }

    @Test
    void notificationsAreAccepted() throws Exception {
        ResponseEntity<?> r = controller.post(null, json(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    void batchesReturnAnArrayOfResponses() throws Exception {
        ResponseEntity<?> r = controller.post(null, json(
                "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},"
                        + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]"));
        assertThat(r.getBody()).isInstanceOf(List.class);
        assertThat((List<?>) r.getBody()).hasSize(1);
    }

    @Test
    void foreignBrowserOriginsAreRejected() throws Exception {
        ResponseEntity<?> r = controller.post("https://evil.example", json(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(McpStreamableHttpController.originAllowed("http://127.0.0.1:3000", List.of())).isTrue();
        assertThat(McpStreamableHttpController.originAllowed("http://localhost:5173",
                List.of("http://localhost:5173"))).isTrue();
    }

    @Test
    void getStreamIsNotOffered() {
        assertThat(controller.stream().getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }
}

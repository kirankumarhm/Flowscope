package com.flowscope.mcp.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowscope.mcp.model.McpModels.JsonRpcRequest;
import com.flowscope.mcp.service.McpServerDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Standard I/O (STDIO) transport runner for Model Context Protocol.
 * <p>
 * Activated when the application is launched with {@code --mcp}, {@code -Dmcp=true},
 * or environment variable {@code FLOWSCOPE_MCP=true}. Reads JSON-RPC requests from
 * {@code System.in} and writes JSON-RPC responses to {@code System.out}.
 */
@Component
public class McpStdioRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(McpStdioRunner.class);

    private final McpServerDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public McpStdioRunner(McpServerDispatcher dispatcher, ObjectMapper objectMapper) {
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) {
        boolean mcpEnabled = Arrays.asList(args).contains("--mcp")
                || Boolean.getBoolean("mcp")
                || "true".equalsIgnoreCase(System.getenv("FLOWSCOPE_MCP"));

        if (!mcpEnabled) {
            log.info("MCP STDIO runner inactive (start with --mcp or FLOWSCOPE_MCP=true to enable STDIO transport)");
            return;
        }

        // Start STDIO listener loop on a separate daemon thread so it doesn't block Spring context lifecycle
        Thread stdioThread = new Thread(this::listenStdio, "flowscope-mcp-stdio");
        stdioThread.setDaemon(false);
        stdioThread.start();
    }

    private void listenStdio() {
        System.err.println("[FlowScope MCP] Server initialized over STDIO. Awaiting JSON-RPC requests...");
        PrintStream out = System.out;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }

                try {
                    JsonRpcRequest request = objectMapper.readValue(line, JsonRpcRequest.class);
                    Object response = dispatcher.dispatch(request);

                    if (response != null) {
                        String jsonResponse = objectMapper.writeValueAsString(response);
                        synchronized (out) {
                            out.println(jsonResponse);
                            out.flush();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[FlowScope MCP] Failed to process line: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[FlowScope MCP] STDIO reader terminated: " + e.getMessage());
        }
    }
}


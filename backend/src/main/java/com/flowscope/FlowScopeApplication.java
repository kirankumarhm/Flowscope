package com.flowscope;

import java.util.Arrays;
import java.util.List;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** FlowScope Web and MCP server application entry point. */
@SpringBootApplication
public class FlowScopeApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(FlowScopeApplication.class);
        List<String> argList = Arrays.asList(args);

        boolean isMcp = isMcpMode(args);
        // STDIO MCP clients only need the JSON-RPC loop. The web server (REST API +
        // HTTP MCP transports) stays off unless explicitly requested, so launching
        // FlowScope as an agent subprocess never opens a network port.
        boolean mcpHttp = argList.contains("--mcp-http")
                || "true".equalsIgnoreCase(System.getenv("FLOWSCOPE_MCP_HTTP"));

        if (isMcp) {
            app.setBannerMode(Banner.Mode.OFF);
            if (!mcpHttp) {
                app.setWebApplicationType(WebApplicationType.NONE);
            }
        }

        app.run(args);
    }

    /** True when {@code args} asks for the STDIO MCP transport (shared with the runner). */
    public static boolean isMcpMode(String... args) {
        return Arrays.asList(args).contains("--mcp")
                || Boolean.getBoolean("mcp")
                || "true".equalsIgnoreCase(System.getenv("FLOWSCOPE_MCP"));
    }
}

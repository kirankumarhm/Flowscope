package com.flowscope;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

/** FlowScope Web and MCP server application entry point. */
@SpringBootApplication
public class FlowScopeApplication {

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(FlowScopeApplication.class);

        boolean isMcp = Arrays.asList(args).contains("--mcp")
                || Boolean.getBoolean("mcp")
                || "true".equalsIgnoreCase(System.getenv("FLOWSCOPE_MCP"));

        if (isMcp) {
            app.setBannerMode(Banner.Mode.OFF);
        }

        app.run(args);
    }
}

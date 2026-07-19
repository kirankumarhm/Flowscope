package com.flowscope.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 metadata for the FlowScope REST API. The generated spec is served at
 * {@code /v3/api-docs} and the interactive Swagger UI at {@code /swagger-ui.html}.
 */
@Configuration
public class OpenApiConfig {

    @Value("${flowscope.version:0.1.0}")
    private String version;

    @Bean
    public OpenAPI flowScopeOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("FlowScope API")
                        .version(version)
                        .description("""
                                FlowScope is a reverse-engineering diagram tool: point it at source code and it
                                generates interactive diagrams. The API exposes five analyses:

                                - **Flow Chart** (`/api/flow`) — endpoint-rooted, inter-procedural control flow.
                                - **Sequence** (`/api/sequence`) — a Mermaid sequence diagram for a request.
                                - **Component** (`/api/component`) — a single app's Spring beans and their dependencies.
                                - **Architecture** (`/api/architecture`) — a single app's layered architecture + external resources.
                                - **Service Map** (`/api/servicemap`) — the service-to-service topology of a whole workspace.

                                `/api/analyze` returns the raw IR (nodes, edges, per-function CFGs) that backs the Flow view.
                                """)
                        .contact(new Contact().name("FlowScope").email("kirankumar.hm@cognizant.com"))
                        .license(new License().name("Proprietary")))
                .servers(List.of(new Server().url("/").description("This server")));
    }
}

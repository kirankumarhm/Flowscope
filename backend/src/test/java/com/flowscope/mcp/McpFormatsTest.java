package com.flowscope.mcp;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import com.flowscope.dto.ServiceMap;
import com.flowscope.dto.ServiceMap.Edge;
import com.flowscope.dto.ServiceMap.Evidence;
import com.flowscope.dto.ServiceMap.Node;
import com.flowscope.dto.ServiceMap.Stats;
import com.flowscope.mcp.service.McpFormats;

class McpFormatsTest {

    private static ServiceMap sample() {
        List<Node> nodes = List.of(
                new Node("svc:a", "orders", "service", "spring-boot", "java", "orders", Map.of()),
                new Node("svc:b", "billing", "service", "spring-boot", "java", "billing", Map.of()),
                new Node("topic:t", "order-events", "topic", "kafka", null, null, Map.of()),
                new Node("ds:postgresql:db", "ordersdb", "datastore", "postgresql", null, null, Map.of()));
        List<Edge> edges = List.of(
                new Edge("e1", "svc:a", "topic:t", "produces", "order-events", "code",
                        List.of(new Evidence("orders/src/Pub.java", 12, "kafkaTemplate.send(...)", "code"))),
                new Edge("e2", "topic:t", "svc:b", "consumes", "order-events", "code", List.of()),
                new Edge("e3", "svc:a", "ds:postgresql:db", "writes", "", "code", List.of()),
                new Edge("e4", "svc:b", "svc:a", "rest", "orders.example.com", "inferred", List.of()));
        return new ServiceMap("/ws", nodes, edges,
                new Stats(2, 1, 1, 0, 4, List.of("2 services detected", "billing: config key x is unused")));
    }

    @Test
    void summaryIsOneLinePerEdgeWithConfidenceAndEvidence() {
        String s = McpFormats.summary(sample());
        assertThat(s).contains("orders -produces-> order-events [code] orders/src/Pub.java:12")
                .contains("billing -rest-> orders orders.example.com [inferred]")
                .contains("- billing: config key x is unused");
    }

    @Test
    void filtersByServiceKindAndConfidence() {
        ServiceMap byService = McpFormats.filter(sample(), "billing", null, null);
        assertThat(byService.edges()).extracting(Edge::id).containsExactlyInAnyOrder("e2", "e4");

        ServiceMap byKind = McpFormats.filter(sample(), null, "datastore", null);
        assertThat(byKind.edges()).extracting(Edge::id).containsExactly("e3");
        assertThat(byKind.nodes()).extracting(Node::id).containsExactlyInAnyOrder("svc:a", "ds:postgresql:db");

        ServiceMap byConfidence = McpFormats.filter(sample(), null, null, "inferred");
        assertThat(byConfidence.edges()).extracting(Edge::id).containsExactly("e4");

        ServiceMap kafka = McpFormats.filter(sample(), "orders", "kafka", "code");
        assertThat(kafka.edges()).extracting(Edge::id).containsExactly("e1");
    }

    @Test
    void summaryMergesIdenticalEdgesOfLambdaHandlersAndShortensJavaPaths() {
        List<Node> nodes = List.of(
                new Node("svc:p#a", "pkg:a", "service", "node-lambda", "typescript", "pkg", Map.of("handler", "a")),
                new Node("svc:p#b", "pkg:b", "service", "node-lambda", "typescript", "pkg", Map.of("handler", "b")),
                new Node("svc:j", "java-svc", "service", "spring-boot", "java", "java-svc", Map.of()),
                new Node("topic:t", "events", "topic", "kafka", null, null, Map.of()));
        List<Edge> edges = List.of(
                new Edge("e1", "svc:p#a", "topic:t", "produces", "events", "code", List.of()),
                new Edge("e2", "svc:p#b", "topic:t", "produces", "events", "code", List.of()),
                new Edge("e3", "svc:j", "topic:t", "produces", "events", "code", List.of(new Evidence(
                        "java-svc/src/main/java/com/acme/app/kafka/Pub.java", 9, "send", "code"))));
        String s = McpFormats.summary(new ServiceMap("/ws", nodes, edges, new Stats(3, 1, 0, 0, 3, List.of("x"))));
        assertThat(s).contains("pkg:{a,b} -produces-> events [code]")
                .doesNotContain("pkg:a -produces->")
                .contains("java-svc -produces-> events [code] java-svc/…/kafka/Pub.java:9");
    }

    @Test
    void mermaidDrawsInferredEdgesDotted() {
        String m = McpFormats.mermaid(sample());
        assertThat(m).startsWith("flowchart LR").contains("[(\"ordersdb\")]").contains("-.->|\"rest\"|");
    }
}

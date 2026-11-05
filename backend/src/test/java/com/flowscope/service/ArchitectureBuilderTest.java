package com.flowscope.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowscope.dto.Architecture;
import com.flowscope.dto.Architecture.Edge;
import com.flowscope.dto.Architecture.Node;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The whole-project architecture view, built from the service-map fixtures. */
class ArchitectureBuilderTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures").toAbsolutePath();

    private static Architecture arch(String fixture) {
        Path root = FIXTURES.resolve(fixture);
        return ArchitectureBuilder.build(ServiceMapBuilder.build(root, Set.of()), null, root);
    }

    private static Optional<Node> node(Architecture a, String label) {
        return a.nodes().stream().filter(n -> n.label().equals(label)).findFirst();
    }

    private static Optional<Edge> edge(Architecture a, String from, String kind, String to) {
        String s = node(a, from).map(Node::id).orElse("?");
        String t = node(a, to).map(Node::id).orElse("?");
        return a.edges().stream()
                .filter(e -> e.source().equals(s) && e.target().equals(t) && e.kind().equals(kind)).findFirst();
    }

    @Test
    @DisplayName("Services sharing a database point at one database box, with read/write direction")
    void sharedDatabase() {
        Architecture a = arch("b1-shared-db");
        assertThat(node(a, "orders").orElseThrow().column()).isEqualTo("data");
        assertThat(edge(a, "orders-svc", "writes", "orders")).isPresent();
        assertThat(edge(a, "invoice-svc", "reads", "orders")).isPresent();
        assertThat(a.edges()).allSatisfy(e -> assertThat(e.evidence()).isNotEmpty());
    }

    @Test
    @DisplayName("Lambda handlers fold into one box; topics with no consumer in the project go to the Kafka box")
    void handlersFoldAndOutsideTopicsUseKafkaBox() {
        Architecture a = arch("b3b-shared-producer");
        Node lambda = node(a, "events-lambda").orElseThrow();
        assertThat(lambda.detail()).isEqualTo("AWS Lambda · 2 handlers");
        assertThat(lambda.column()).isEqualTo("processing");
        assertThat(a.nodes()).noneMatch(n -> n.label().startsWith("events-lambda:"));
        Edge toKafka = edge(a, "events-lambda", "async", "Apache Kafka").orElseThrow();
        assertThat(toKafka.items()).containsExactly("orders-command", "orders-event");
        assertThat(toKafka.label()).isEqualTo("2 topics");
    }

    @Test
    @DisplayName("Kafka producer → consumer inside the project is one direct connection labelled with the topic")
    void kafkaBetweenServices() {
        Architecture a = arch("b3-serverless");
        // orders-events comes from outside; work-queue is a queue box between the two handlers' package.
        assertThat(edge(a, "Apache Kafka", "async", "pipeline-lambda")).isPresent();
        assertThat(node(a, "work-queue").orElseThrow().column()).isEqualTo("messaging");
        assertThat(edge(a, "pipeline-lambda", "sends", "work-queue")).isPresent();
        assertThat(edge(a, "work-queue", "consumes", "pipeline-lambda")).isPresent();
        assertThat(node(a, "results").orElseThrow().column()).isEqualTo("data");
    }

    @Test
    @DisplayName("One box per AWS table; SOAP and cloud SDKs get their own columns")
    void perTableAndCloudColumns() {
        Architecture tables = arch("a3b-java-sdk-v2");
        assertThat(tables.nodes()).filteredOn(n -> n.column().equals("data")).extracting(Node::label)
                .containsExactlyInAnyOrder("prod-OrderIndex", "prod-SkuIndex", "prod-CartState");
        assertThat(edge(tables, "device-svc", "reads", "prod-OrderIndex")).isPresent();
        assertThat(edge(tables, "device-svc", "writes", "prod-CartState")).isPresent();

        Architecture soap = arch("b5-soap-sdk");
        assertThat(node(soap, "AWS Secrets Manager").orElseThrow().column()).isEqualTo("cloud");
        assertThat(edge(soap, "provisioner", "uses", "AWS Secrets Manager")).isPresent();
        assertThat(edge(soap, "provisioner", "soap", "acmepartner")).isPresent();
    }

    @Test
    @DisplayName("Placeholders for unresolved names are left out and counted in the notes")
    void placeholdersLeftOut() {
        Architecture a = arch("b6-unresolved");
        assertThat(a.nodes()).noneMatch(n -> n.label().contains("not resolved") || n.label().contains("from env"));
        assertThat(a.stats().notes()).anyMatch(n -> n.contains("could not resolve are left out"));
        assertThat(node(a, "exports-bucket")).isPresent();
    }

    @Test
    @DisplayName("A folder holding several services shows the whole project")
    void projectFolderShowsWholeProject() {
        Architecture whole = new ArchitectureService(new ServiceMapService(), dir -> null)
                .architecture(FIXTURES.resolve("b4b-ui-config").toString(), null);
        assertThat(whole.scope()).isEqualTo(Architecture.PROJECT);
        assertThat(whole.focus()).isNull();
        assertThat(whole.app()).isNull();
        assertThat(whole.nodes()).extracting(Node::id).containsAll(List.of("svc:admin-api", "svc:ops-ui"));
        assertThat(node(whole, "admin-api").orElseThrow().column()).isEqualTo("apis");
        assertThat(node(whole, "ops-ui").orElseThrow().column()).isEqualTo("frontends");
        assertThat(edge(whole, "ops-ui", "rest", "admin-api")).isPresent();
    }

    @Test
    @DisplayName("A service folder shows that application only, with the services connected to it")
    void serviceFolderShowsThatApplication() {
        Architecture a = new ArchitectureService(new ServiceMapService(), dir -> null)
                .architecture(FIXTURES.resolve("b4b-ui-config/admin-api").toString(), null);
        assertThat(a.scope()).isEqualTo(Architecture.APPLICATION);
        assertThat(a.project()).isEqualTo("admin-api");
        assertThat(a.focus()).isEqualTo("svc:admin-api");
        assertThat(a.workspace()).isEqualTo(FIXTURES.resolve("b4b-ui-config").toString());
        assertThat(node(a, "admin-api").orElseThrow().column()).isEqualTo("app");
        assertThat(node(a, "ops-ui").orElseThrow().column()).isEqualTo("connected");
        assertThat(edge(a, "ops-ui", "rest", "admin-api")).isPresent();
        assertThat(a.stats().notes()).anyMatch(n -> n.contains("every connection attaches to the application"));
    }

    @Test
    @DisplayName("Inside one application: components by layer, one Kafka box with its topics, connections from layers")
    void applicationViewGroupsComponentsByLayer() {
        FlowService flows = new FlowService(new com.flowscope.config.LanguageConfig().languageRegistry());
        Architecture a = new ArchitectureService(new ServiceMapService(), flows)
                .architecture(FIXTURES.resolve("b7-app-view/billing-svc").toString(), null);
        assertThat(a.scope()).isEqualTo(Architecture.APPLICATION);
        assertThat(a.app()).isNotNull();
        assertThat(a.app().lanes()).extracting(Architecture.Lane::id).containsExactly("entry", "logic", "access");
        assertThat(a.nodes()).extracting(Node::id)
                .contains("cmp:BillingController", "cmp:OrderEventListener", "cmp:BillingService",
                        "cmp:InvoiceRepository", "grp:controller", "grp:component", "grp:service", "grp:repository")
                .doesNotContain("cmp:KafkaConfig");
        assertThat(a.stats().notes()).anyMatch(n -> n.contains("1 configuration class not shown"));

        Node kafka = a.nodes().stream().filter(n -> n.id().equals(AppArchitectureBuilder.KAFKA)).findFirst().orElseThrow();
        assertThat(kafka.column()).isEqualTo("messaging");
        assertThat(kafka.meta().get("topics").toString()).contains("orders-created", "billing-done");
        assertThat(edgeIds(a, "svc:orders-svc", "async", AppArchitectureBuilder.KAFKA)).isTrue();
        assertThat(edgeIds(a, AppArchitectureBuilder.KAFKA, "async", "grp:component")).isTrue();
        assertThat(edgeIds(a, "grp:service", "async", AppArchitectureBuilder.KAFKA)).isTrue();
        assertThat(edgeIds(a, AppArchitectureBuilder.KAFKA, "async", "svc:orders-svc")).isTrue();
        assertThat(node(a, "orders-svc").orElseThrow().column()).isEqualTo("connected");

        Node db = a.nodes().stream().filter(n -> "postgresql".equals(n.subtype())).findFirst().orElseThrow();
        assertThat(a.edges()).anyMatch(e -> e.target().equals(db.id())
                && (e.source().equals("grp:repository") || e.source().equals("svc:billing-svc")));
        assertThat(edgeIds(a, "cmp:BillingController", "inject", "cmp:BillingService")
                || edgeIds(a, "cmp:BillingController", "call", "cmp:BillingService")).isTrue();
    }

    private static boolean edgeIds(Architecture a, String source, String kind, String target) {
        return a.edges().stream().anyMatch(e -> e.source().equals(source) && e.target().equals(target)
                && e.kind().equals(kind));
    }
}

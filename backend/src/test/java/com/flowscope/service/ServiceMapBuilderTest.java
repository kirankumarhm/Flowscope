package com.flowscope.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.flowscope.dto.ServiceMap;
import com.flowscope.dto.ServiceMap.Edge;
import com.flowscope.dto.ServiceMap.Node;

/**
 * One synthetic workspace per case in docs/IMPROVEMENTS.md. Most problems were
 * false positives, so each test checks both that the right arrows are present
 * and that the wrong ones are absent.
 */
class ServiceMapBuilderTest {

    private static final Path FIXTURES = Path.of("src/test/resources/fixtures").toAbsolutePath();

    private static ServiceMap map(String fixture) {
        return ServiceMapBuilder.build(FIXTURES.resolve(fixture), Set.of());
    }

    private static String label(ServiceMap m, String id) {
        return m.nodes().stream().filter(n -> n.id().equals(id)).map(Node::label).findFirst().orElse(id);
    }

    private static Optional<Edge> edge(ServiceMap m, String from, String kind, String to) {
        return m.edges().stream()
                .filter(e -> e.kind().equals(kind) && label(m, e.source()).equals(from)
                        && label(m, e.target()).equals(to))
                .findFirst();
    }

    private static List<Edge> edgesTouching(ServiceMap m, String labelPart) {
        return m.edges().stream()
                .filter(e -> label(m, e.source()).contains(labelPart) || label(m, e.target()).contains(labelPart))
                .toList();
    }

    private static Optional<Node> node(ServiceMap m, String label) {
        return m.nodes().stream().filter(n -> n.label().equals(label)).findFirst();
    }

    @Test
    @DisplayName("A1: only config keys that code reads become arrows; the rest are reported unused")
    void configKeysMustBeRead() {
        ServiceMap m = map("a1-config-unused");
        Edge e = edge(m, "billing-service", "rest", "partnerbilling").orElseThrow();
        assertThat(e.confidence()).isEqualTo("config");
        assertThat(e.evidence()).anySatisfy(ev -> {
            assertThat(ev.file()).isEqualTo("billing-service/src/main/resources/application.yml");
            assertThat(ev.line()).isEqualTo(3);
            assertThat(ev.via()).contains("partner.api.base-url");
        });
        assertThat(edgesTouching(m, "legacy")).isEmpty();
        assertThat(m.stats().notes())
                .filteredOn(n -> n.contains("legacy.api.base-url") && n.contains("never read by code"))
                .hasSize(1);
    }

    @Test
    @DisplayName("A2: database-url is not a base URL; Firebase comes from code, not config")
    void databaseUrlIsNotABaseUrl() {
        ServiceMap m = map("a2-database-url");
        assertThat(m.nodes()).noneMatch(n -> n.label().toLowerCase().contains("firebase"));
        assertThat(edge(m, "notify-service", "rest", "notifyhub")).isPresent();
        assertThat(m.stats().notes()).noneMatch(n -> n.contains("database-url"));
    }

    @Test
    @DisplayName("A3: direction is per table and per call; GetCommand reads, PutCommand writes")
    void directionPerTable() {
        ServiceMap m = map("a3-direction");
        Edge reads = edge(m, "orders", "reads", "orders-svc").orElseThrow();
        assertThat(reads.confidence()).isEqualTo("code");
        assertThat(reads.evidence()).anySatisfy(ev -> {
            assertThat(ev.file()).isEqualTo("orders-svc/src/repo.ts");
            assertThat(ev.line()).isEqualTo(7);
        });
        assertThat(edge(m, "orders-svc", "writes", "orders")).isEmpty();
        assertThat(edge(m, "orders-svc", "writes", "audit")).isPresent();
        assertThat(edge(m, "audit", "reads", "orders-svc")).isPresent();
    }

    @Test
    @DisplayName("A4: URL path picks between services sharing a name token; link-only URLs are references")
    void urlMatching() {
        ServiceMap m = map("a4-matching");
        Edge dash = edge(m, "billing", "rest", "reports-dashboard").orElseThrow();
        assertThat(dash.confidence()).isEqualTo("inferred");
        assertThat(dash.evidence()).anySatisfy(ev -> assertThat(ev.via()).contains("route match /admin/api"));
        assertThat(edge(m, "billing", "rest", "reports-api")).isEmpty();
        assertThat(edge(m, "billing", "reference", "orders-service")).isPresent();
        assertThat(edge(m, "billing", "rest", "orders-service")).isEmpty();
    }

    @Test
    @DisplayName("A5: mocks, testing/ helpers and non-prod environment files don't contribute")
    void mocksAndNonProdEnvironmentsIgnored() {
        ServiceMap m = map("a5-mocks");
        List<Edge> cognito = m.edges().stream()
                .filter(e -> label(m, e.target()).equals("AWS Cognito")).toList();
        assertThat(cognito).hasSize(1);
        assertThat(cognito.get(0).evidence()).isNotEmpty().allSatisfy(ev ->
                assertThat(ev.file()).endsWith("src/environments/environment.prod.ts"));
    }

    @Test
    @DisplayName("A6: a module other modules depend on is a library, even with @SpringBootApplication")
    void dependedOnModuleIsLibrary() {
        ServiceMap m = map("a6-library");
        assertThat(node(m, "shared-lib").orElseThrow().subtype()).isEqualTo("library");
        assertThat(node(m, "app-service").orElseThrow().subtype()).isEqualTo("spring-boot");
        assertThat(m.stats().notes()).anyMatch(n -> n.startsWith("shared-lib is treated as a library"));
    }

    @Test
    @DisplayName("A7: one gateway host splits into several systems by path; one system per dictionary entry")
    void hostAndPathExternals() {
        ServiceMap m = map("a7-externals");
        assertThat(edge(m, "portal", "rest", "Billing System")).isPresent();
        assertThat(edge(m, "portal", "rest", "Identity Service")).isPresent();
        assertThat(edge(m, "portal", "rest", "Smartsheet")).isPresent();
        assertThat(m.nodes()).filteredOn(n -> n.label().contains("Smartsheet")).hasSize(1);
    }

    @Test
    @DisplayName("B1: services sharing a JDBC database share one node; the Architecture view links Repositories to it")
    void sharedRelationalDatabase() {
        ServiceMap m = map("b1-shared-db");
        List<Node> dbs = m.nodes().stream().filter(n -> n.id().equals("ds:postgresql:orders")).toList();
        assertThat(dbs).hasSize(1);
        assertThat(edge(m, "orders-svc", "writes", "orders")).isPresent();
        assertThat(edge(m, "orders", "reads", "invoice-svc")).isPresent();
        assertThat(edge(m, "invoice-svc", "writes", "orders")).isEmpty();
    }

    @Test
    @DisplayName("B2: topics passed through helper-method parameters and the TS { topic } shorthand resolve")
    void topicsThroughHelpers() {
        ServiceMap m = map("b2-helpers");
        Edge java = edge(m, "ack-service", "produces", "orders-success").orElseThrow();
        assertThat(java.evidence()).anySatisfy(ev -> assertThat(ev.via()).contains("call-site"));
        assertThat(edge(m, "cmd-producer", "produces", "orders-commands")).isPresent();
        assertThat(m.nodes()).noneMatch(n -> n.label().contains("unresolved"));
    }

    @Test
    @DisplayName("B3: one node per exported handler, with triggers read from Terraform")
    void serverlessHandlersAndTriggers() {
        ServiceMap m = map("b3-serverless");
        assertThat(node(m, "pipeline-lambda:consumer")).isPresent();
        assertThat(node(m, "pipeline-lambda:worker")).isPresent();
        Edge trigger = edge(m, "orders-events", "consumes", "pipeline-lambda:consumer").orElseThrow();
        assertThat(trigger.confidence()).isEqualTo("config");
        assertThat(trigger.evidence()).anySatisfy(ev -> assertThat(ev.file()).endsWith("terraform/main.tf"));
        assertThat(edge(m, "pipeline-lambda:consumer", "writes", "work-queue")).isPresent();
        assertThat(edge(m, "work-queue", "reads", "pipeline-lambda:worker")).isPresent();
        assertThat(edge(m, "pipeline-lambda:worker", "writes", "results")).isPresent();
        assertThat(edge(m, "pipeline-lambda:consumer", "writes", "results")).isEmpty();
        assertThat(m.nodes()).noneMatch(n -> n.id().equals("ext:self-managed-kafka"));
    }

    @Test
    @DisplayName("B4: an Angular UI links to the back end serving its relative API paths")
    void uiToBackEnd() {
        ServiceMap m = map("b4-ui");
        Edge e = edge(m, "admin-ui", "rest", "admin-api").orElseThrow();
        assertThat(e.confidence()).isEqualTo("inferred");
        assertThat(e.evidence()).anySatisfy(ev -> {
            assertThat(ev.file()).isEqualTo("admin-ui/src/app/devices.service.ts");
            assertThat(ev.via()).contains("route match /api/3.0.0/devices");
        });
        assertThat(edge(m, "admin-ui", "rest", "device-api")).isEmpty();
    }

    @Test
    @DisplayName("B5: SOAP clients and cloud SDK clients are detected")
    void soapAndSdk() {
        ServiceMap m = map("b5-soap-sdk");
        assertThat(edge(m, "provisioner", "soap", "acmepartner")).isPresent();
        Edge sdk = edge(m, "provisioner", "uses", "AWS Secrets Manager").orElseThrow();
        assertThat(sdk.confidence()).isEqualTo("code");
    }

    @Test
    @DisplayName("B6: unresolved names become placeholders; shell container jobs are scanned")
    void unresolvedPlaceholders() {
        ServiceMap m = map("b6-unresolved");
        Edge q = edge(m, "replay-svc", "writes", "SQS queue (name from env SQS_OUTPUT_QUEUE_NAME)").orElseThrow();
        assertThat(q.confidence()).isEqualTo("unresolved");
        assertThat(node(m, "export-job").orElseThrow().subtype()).isEqualTo("container");
        assertThat(edge(m, "reporting", "reads", "export-job")).isPresent();
        assertThat(edge(m, "export-job", "writes", "exports-bucket")).isPresent();
        assertThat(m.stats().notes()).anyMatch(n -> n.contains("placeholders"));
    }

    @Test
    @DisplayName("C3: flowscope.yml declares hosts, triggers, externals and ignored paths")
    void overrideFile() {
        ServiceMap m = map("c3-overrides");
        Edge declared = edge(m, "caller", "rest", "reports-dashboard").orElseThrow();
        assertThat(declared.confidence()).isEqualTo("declared");
        assertThat(declared.evidence()).anySatisfy(ev -> assertThat(ev.file()).isEqualTo("flowscope.yml"));
        assertThat(edge(m, "caller", "rest", "Partner Billing API")).isPresent();
        Edge trigger = edge(m, "orders-commands", "consumes", "orders-lambda:commandConsumer").orElseThrow();
        assertThat(trigger.confidence()).isEqualTo("declared");
        assertThat(m.edges()).noneMatch(e -> e.source().equals("ext:self-managed-kafka"));
        assertThat(m.nodes()).noneMatch(n -> n.label().contains("old-service") || n.label().contains("old-topic"));
    }

    @Test
    @DisplayName("A3: Java SDK v2 tables reach the call through a helper parameter; field names are per file")
    void javaSdkV2TablesThroughHelperParameter() {
        ServiceMap m = map("a3b-java-sdk-v2");
        Edge order = edge(m, "prod-OrderIndex", "reads", "device-svc").orElseThrow();
        assertThat(order.confidence()).isEqualTo("code");
        assertThat(order.evidence()).anySatisfy(ev -> assertThat(ev.file()).endsWith("IndexRepository.java"));
        assertThat(edge(m, "prod-SkuIndex", "reads", "device-svc")).isPresent();
        assertThat(edge(m, "device-svc", "writes", "prod-CartState")).isPresent();
        // StateWriter's field is called "tableName"; IndexRepository's .tableName(tableName) is not about it.
        assertThat(edge(m, "prod-CartState", "reads", "device-svc")).isEmpty();
        assertThat(m.edges()).noneMatch(e -> e.kind().equals("uses"));
    }

    @Test
    @DisplayName("A3: S3 SDK v1 buckets reach a shared helper as a parameter; a class's own upload() is not S3")
    void s3SdkV1BucketsThroughHelper() {
        ServiceMap m = map("a3d-s3-sdk-v1");
        assertThat(edge(m, "admin-svc", "writes", "soft-bucket")).isPresent();
        assertThat(edge(m, "soft-bucket", "reads", "admin-svc")).isPresent();
        assertThat(edge(m, "export-bucket", "reads", "admin-svc")).isPresent();
        assertThat(edge(m, "admin-svc", "writes", "export-bucket")).isEmpty();
    }

    @Test
    @DisplayName("A3: TS tables reach a base class through super(...) and a config object")
    void tsTablesThroughBaseClassAndConfig() {
        ServiceMap m = map("a3c-ts-base-class");
        assertThat(edge(m, "state", "reads", "resolver-svc").orElseThrow().confidence()).isEqualTo("code");
        assertThat(edge(m, "history", "reads", "resolver-svc")).isPresent();
        assertThat(edge(m, "resolver-svc", "writes", "profile")).isPresent();
        assertThat(edge(m, "resolver-svc", "writes", "state")).isEmpty();
        assertThat(edge(m, "profile", "reads", "resolver-svc")).isEmpty();
    }

    @Test
    @DisplayName("B3: a handler only produces the topics of the producer methods its own code calls")
    void splitHandlersOnlyGetTheirOwnTopics() {
        ServiceMap m = map("b3b-shared-producer");
        Edge cmd = edge(m, "events-lambda:commander", "produces", "orders-command").orElseThrow();
        assertThat(cmd.evidence()).anySatisfy(ev -> {
            assertThat(ev.file()).isEqualTo("events-lambda/src/shared/producer.ts");
            assertThat(ev.snippet()).contains("this.commandTopic");
        });
        assertThat(edge(m, "events-lambda:commander", "produces", "orders-event")).isEmpty();
        assertThat(edge(m, "events-lambda:notifier", "produces", "orders-event")).isPresent();
        assertThat(edge(m, "events-lambda:notifier", "produces", "orders-command")).isEmpty();
        // "[topic:${topic}]" in a log string and the test-helpers/ event factory add nothing.
        assertThat(m.nodes()).noneMatch(n -> n.label().contains("unresolved") || n.id().equals("ext:self-managed-kafka"));
    }

    @Test
    @DisplayName("B4: UI paths kept in a configuration class match a route declared with a constant")
    void uiPathsFromConfigurationClass() {
        ServiceMap m = map("b4b-ui-config");
        Edge e = edge(m, "ops-ui", "rest", "admin-api").orElseThrow();
        assertThat(e.evidence()).anySatisfy(ev -> {
            assertThat(ev.file()).isEqualTo("ops-ui/src/app/shared/configuration.ts");
            assertThat(ev.via()).contains("route match /api/3.0.0/devices");
        });
        assertThat(edgesTouching(m, "ops-ui")).noneMatch(x -> label(m, x.target()).equals("device-api"));
    }

    @Test
    @DisplayName("A4: a name match whose routes don't serve the URL path is rejected and reported")
    void nameMatchWithConflictingRoutes() {
        ServiceMap m = map("b4b-ui-config");
        assertThat(edge(m, "admin-api", "rest", "device-api")).isEmpty();
        // The other service sharing the word "device" isn't picked just because it is left over.
        assertThat(edge(m, "admin-api", "rest", "device-authorizer")).isEmpty();
        assertThat(m.stats().notes()).anyMatch(n -> n.startsWith("admin-api:") && n.contains("/auth/gql")
                && n.contains("device-api"));
    }

    @Test
    @DisplayName("B5/B6: SOAP set up in a config bean; a Lambda's shell scripts are scanned, deploy scripts are not")
    void soapConfigAndSpawnedScripts() {
        ServiceMap m = map("b5b-soap-config");
        assertThat(edge(m, "provision-svc", "soap", "INVENTORYSOAP")).isPresent();
        assertThat(edge(m, "export-lambda", "writes", "exports-bucket")).isPresent();
        assertThat(edge(m, "export-lambda", "uses", "exports-bucket")).isEmpty();
        assertThat(edge(m, "PostgreSQL database (psql in script)", "reads", "export-lambda")).isPresent();
        assertThat(edge(m, "export-lambda", "writes", "PostgreSQL database (psql in script)")).isPresent();
        assertThat(m.nodes()).noneMatch(n -> n.label().equals("deploy-artifacts"));
    }

    @Test
    @DisplayName("C1/C2: every edge has a confidence, and every non-placeholder edge has evidence")
    void everyEdgeIsExplained() {
        for (String fx : List.of("a1-config-unused", "a3-direction", "a4-matching", "b1-shared-db", "b3-serverless",
                "b4-ui", "b5-soap-sdk", "b6-unresolved", "c3-overrides", "a3b-java-sdk-v2", "a3c-ts-base-class",
                "b3b-shared-producer", "b4b-ui-config", "b5b-soap-config", "a3d-s3-sdk-v1")) {
            ServiceMap m = map(fx);
            assertThat(m.edges()).as(fx).isNotEmpty().allSatisfy(e -> {
                assertThat(e.confidence()).as(fx + " " + e.id())
                        .isIn("declared", "code", "config", "inferred", "unresolved");
                assertThat(e.evidence()).as(fx + " " + e.id()).isNotEmpty();
            });
        }
    }
}

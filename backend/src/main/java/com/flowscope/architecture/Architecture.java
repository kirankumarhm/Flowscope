package com.flowscope.architecture;

import java.util.List;

/**
 * A high-level, layered architecture view of a single application: its
 * architectural layers (controllers/services/repositories/clients/…) as
 * aggregate boxes, the dependencies between those layers, and the external
 * resources the app talks to (datastores, Kafka topics, external systems).
 *
 * <p>Deliberately coarse — a handful of nodes — so it reads as an architecture
 * diagram rather than the class-level detail of the Component view.
 */
public record Architecture(
        String project,
        List<Node> nodes,
        List<Edge> edges,
        Stats stats) {

    /**
     * @param type  {@code layer} | {@code datastore} | {@code topic} | {@code external}
     * @param subtype layer name (controller/service/…) or store kind (dynamodb/s3/sqs/kafka)
     * @param count number of classes in a layer (0 for non-layer nodes)
     */
    public record Node(
            String id,
            String label,
            String type,
            String subtype,
            int count) {
    }

    /**
     * @param kind {@code depends} (layer→layer) | {@code uses} (layer→datastore) |
     *             {@code produces} (layer→topic) | {@code consumes} (topic→layer) |
     *             {@code calls} (layer→external)
     */
    public record Edge(
            String id,
            String source,
            String target,
            String kind,
            int weight) {
    }

    public record Stats(
            int layers,
            int datastores,
            int topics,
            int externals,
            List<String> notes) {
    }
}

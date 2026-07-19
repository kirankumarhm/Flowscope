package com.flowscope.servicemap;

import java.util.List;
import java.util.Map;

/**
 * The service-to-service topology of a whole workspace of applications. Unlike
 * the Flow Chart / Sequence diagrams (which trace one function inside one app),
 * the Service Map is a cross-language, cross-application graph of how the
 * services communicate: Kafka topics, REST calls, shared datastores, and
 * external systems.
 *
 * <p>It is a plain node/edge graph so the frontend can render it directly. Node
 * {@code type} drives shape/colour; {@link Edge#kind()} drives edge style.
 */
public record ServiceMap(
        String workspace,
        List<Node> nodes,
        List<Edge> edges,
        Stats stats) {

    /**
     * A vertex in the topology.
     *
     * @param id      stable unique id ({@code svc:<dir>}, {@code topic:<name>},
     *                {@code ds:<kind>:<name>}, {@code ext:<name>})
     * @param label   human-readable name shown on the node
     * @param type    {@code service} | {@code topic} | {@code datastore} |
     *                {@code external} | {@code ui}
     * @param subtype finer classification, e.g. {@code spring-boot},
     *                {@code node-lambda}, {@code react}, {@code dynamodb},
     *                {@code s3}, {@code sqs}, {@code kafka}
     * @param language {@code java} | {@code typescript} | {@code javascript}, or null
     * @param path    workspace-relative directory of the service, or null
     * @param meta    extra display detail (produces/consumes counts, app name, …)
     */
    public record Node(
            String id,
            String label,
            String type,
            String subtype,
            String language,
            String path,
            Map<String, Object> meta) {
    }

    /**
     * A directed communication edge, oriented along the data-flow direction so
     * arrows read naturally (producer → topic → consumer, service → datastore).
     *
     * @param kind  {@code produces} | {@code consumes} | {@code rest} |
     *              {@code reads} | {@code writes}
     * @param label edge annotation (topic name, HTTP verb/path, table name)
     */
    public record Edge(
            String id,
            String source,
            String target,
            String kind,
            String label) {
    }

    /** Summary counts plus human-readable notes about what could not be resolved. */
    public record Stats(
            int services,
            int topics,
            int datastores,
            int externals,
            int connections,
            List<String> notes) {
    }
}

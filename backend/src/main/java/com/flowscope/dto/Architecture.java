package com.flowscope.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The architecture of a whole project or of one application.
 *
 * <p><b>Project scope</b> (a folder holding several services): every deployable service, arranged in
 * columns (front ends, APIs, processing, messaging, data stores, external
 * systems, cloud services), with the connections between them. Built from the
 * {@link ServiceMap}, but simplified for reading: Lambda handlers fold into their
 * package, Kafka topics become producer → consumer connections, and placeholders
 * for names that could not be resolved are left out.
 *
 * <p><b>Application scope</b> (a folder holding one service): that application in the middle,
 * with its components grouped by layer ({@link #app()}), the services and messaging it connects
 * to on the left, and its data stores, external systems and cloud services on the right. Each
 * outside connection starts at the layer group whose code makes it.
 *
 * @param project   display name of the project or application (its directory name)
 * @param workspace absolute path of the project root that was scanned; evidence files are relative to it
 * @param focus     application scope: id of the application's node; project scope: null
 * @param columns   the columns, left to right, each listing its node ids in display order
 * @param scope     {@code project} or {@code application}
 * @param app       application scope: the application's layers and their component groups
 */
public record Architecture(
        String project,
        String workspace,
        String focus,
        List<Column> columns,
        List<Node> nodes,
        List<Edge> edges,
        Stats stats,
        String scope,
        @JsonInclude(JsonInclude.Include.NON_NULL) App app) {

    public static final String PROJECT = "project";
    public static final String APPLICATION = "application";

    /**
     * Inside one application, left to right.
     *
     * @param id    id of the application's node
     * @param lanes {@code entry} (controllers, listeners), {@code logic} (services),
     *              {@code access} (repositories, clients); empty lanes are left out
     */
    public record App(String id, List<Lane> lanes) {
    }

    public record Lane(String id, String title, List<Group> groups) {
    }

    /**
     * Components of one layer. The group is also a node (type {@code group}), so
     * connections can start at it.
     *
     * @param nodes component node ids, top to bottom
     */
    public record Group(String id, String title, List<String> nodes) {
    }

    /**
     * @param id    {@code frontends} | {@code apis} | {@code processing} | {@code messaging} |
     *              {@code data} | {@code external} | {@code cloud}; application scope also
     *              {@code connected} (other services) and {@code app} (the application)
     * @param nodes node ids, top to bottom
     */
    public record Column(String id, String title, List<String> nodes) {
    }

    /**
     * @param type    {@code ui} | {@code service} | {@code kafka} | {@code queue} | {@code trigger} |
     *                {@code grpc} | {@code datastore} | {@code external} | {@code cloud}; application
     *                scope also {@code group} (a layer) and {@code component} (a class in it)
     * @param subtype runtime or technology ({@code spring-boot}, {@code node-lambda},
     *                {@code postgresql}, {@code dynamodb}, …)
     * @param column  id of the column holding the node
     * @param detail  second line shown under the label ("Spring Boot", "AWS Lambda · 10 handlers")
     * @param meta    extra detail for the side panel (handlers, path, routes, …)
     */
    public record Node(
            String id,
            String label,
            String type,
            String subtype,
            String column,
            String detail,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, Object> meta) {
    }

    /**
     * @param kind       {@code rest} | {@code soap} | {@code grpc} | {@code async} (Kafka) |
     *                   {@code sends} / {@code consumes} (queues) | {@code triggers} |
     *                   {@code reads} | {@code writes} | {@code readwrite} | {@code uses};
     *                   inside an application {@code inject} | {@code call} between components
     * @param label      short annotation (topic name, "3 topics", "read/write", "REST")
     * @param confidence strongest confidence of the merged service-map edges
     * @param items      the topics (or other names) a merged connection stands for
     * @param evidence   file:line evidence, as on the service map
     */
    public record Edge(
            String id,
            String source,
            String target,
            String kind,
            String label,
            String confidence,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> items,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ServiceMap.Evidence> evidence) {
    }

    public record Stats(
            int services,
            int datastores,
            int messaging,
            int externals,
            int connections,
            List<String> notes) {
    }
}

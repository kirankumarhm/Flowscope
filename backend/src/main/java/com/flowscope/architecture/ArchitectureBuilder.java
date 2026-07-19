package com.flowscope.architecture;

import com.flowscope.architecture.Architecture.Edge;
import com.flowscope.architecture.Architecture.Node;
import com.flowscope.architecture.Architecture.Stats;
import com.flowscope.component.ComponentMap;
import com.flowscope.component.ComponentMapBuilder;
import com.flowscope.extract.JavaProgramModel;
import com.flowscope.servicemap.ServiceMapBuilder;
import com.flowscope.servicemap.ServiceMapBuilder.ServiceComms;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the {@link Architecture} view by aggregating the class-level
 * {@link ComponentMap} up to its layers and attaching the external resources the
 * app uses (from the Service Map's single-app comm scan).
 */
public final class ArchitectureBuilder {

    /** Layers in left-to-right architectural flow order. */
    private static final List<String> LAYER_ORDER =
            List.of("controller", "service", "repository", "client", "component", "config");
    private static final Map<String, String> LAYER_LABELS = Map.of(
            "controller", "Controllers",
            "service", "Services",
            "repository", "Repositories",
            "client", "Clients",
            "component", "Components",
            "config", "Config");

    private ArchitectureBuilder() {
    }

    public static Architecture build(String project, JavaProgramModel model, Path appDir) {
        ComponentMap cm = ComponentMapBuilder.build(project, model);

        // Layer -> class count.
        Map<String, Integer> layerCount = new LinkedHashMap<>();
        Map<String, String> classLayer = new LinkedHashMap<>();
        for (ComponentMap.Component c : cm.components()) {
            layerCount.merge(c.layer(), 1, Integer::sum);
            classLayer.put(c.id(), c.layer());
        }

        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();

        // Layer nodes (only those actually present), in flow order.
        for (String layer : LAYER_ORDER) {
            Integer count = layerCount.get(layer);
            if (count != null) {
                nodes.add(new Node("layer:" + layer, LAYER_LABELS.getOrDefault(layer, layer),
                        "layer", layer, count));
            }
        }

        // Aggregate class-level dependencies up to layer -> layer edges.
        Map<String, Integer> layerEdgeWeight = new LinkedHashMap<>();
        for (ComponentMap.Dependency d : cm.dependencies()) {
            String from = classLayer.get(d.source());
            String to = classLayer.get(d.target());
            if (from != null && to != null && !from.equals(to)) {
                layerEdgeWeight.merge(from + ">" + to, 1, Integer::sum);
            }
        }
        for (Map.Entry<String, Integer> e : layerEdgeWeight.entrySet()) {
            String[] ft = e.getKey().split(">", 2);
            edges.add(new Edge("layer:" + ft[0] + "->layer:" + ft[1], "layer:" + ft[0],
                    "layer:" + ft[1], "depends", e.getValue()));
        }

        // External resources this app talks to (best-effort attachment to a layer).
        ServiceComms comms = ServiceMapBuilder.comms(appDir);
        String dataLayer = firstPresent(layerCount, "repository", "service", "component", "controller");
        String msgLayer = firstPresent(layerCount, "service", "component", "controller");
        String extLayer = firstPresent(layerCount, "client", "service", "component", "controller");

        int datastores = 0;
        for (Map.Entry<String, java.util.Set<String>> e : comms.stores().entrySet()) {
            for (String name : e.getValue()) {
                String id = "ds:" + e.getKey() + ":" + name;
                nodes.add(new Node(id, name, "datastore", e.getKey(), 0));
                datastores++;
                if (dataLayer != null) {
                    edges.add(new Edge("layer:" + dataLayer + "->" + id, "layer:" + dataLayer, id, "uses", 1));
                }
            }
        }

        int topics = 0;
        for (String topic : comms.produces()) {
            String id = "topic:" + topic;
            if (addNodeOnce(nodes, id, topic, "topic", "kafka")) {
                topics++;
            }
            if (msgLayer != null) {
                edges.add(new Edge("layer:" + msgLayer + "->" + id, "layer:" + msgLayer, id, "produces", 1));
            }
        }
        for (String topic : comms.consumes()) {
            String id = "topic:" + topic;
            if (addNodeOnce(nodes, id, topic, "topic", "kafka")) {
                topics++;
            }
            if (msgLayer != null) {
                edges.add(new Edge(id + "->layer:" + msgLayer, id, "layer:" + msgLayer, "consumes", 1));
            }
        }

        int externals = 0;
        for (String ext : comms.externals()) {
            String id = "ext:" + slug(ext);
            if (addNodeOnce(nodes, id, ext, "external", "http")) {
                externals++;
            }
            if (extLayer != null) {
                edges.add(new Edge("layer:" + extLayer + "->" + id, "layer:" + extLayer, id, "calls", 1));
            }
        }

        List<String> notes = List.of(
                layerCount.size() + " layers, " + datastores + " datastores, "
                        + topics + " topics, " + externals + " external systems");
        return new Architecture(project, nodes, edges,
                new Stats(layerCount.size(), datastores, topics, externals, notes));
    }

    private static boolean addNodeOnce(List<Node> nodes, String id, String label, String type, String subtype) {
        for (Node n : nodes) {
            if (n.id().equals(id)) {
                return false;
            }
        }
        nodes.add(new Node(id, label, type, subtype, 0));
        return true;
    }

    private static String firstPresent(Map<String, Integer> layers, String... candidates) {
        for (String c : candidates) {
            if (layers.containsKey(c)) {
                return c;
            }
        }
        return null;
    }

    private static String slug(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }
}

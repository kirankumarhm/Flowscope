package com.flowscope.service;

import com.flowscope.entity.CFG;
import com.flowscope.entity.CFGEdge;
import com.flowscope.entity.CFGNode;
import com.flowscope.entity.CFGNodeKind;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates a textual narrative from a Control Flow Graph (CFG).
 * It traverses the graph to identify the primary execution path and decision points.
 */
@Service
public class FlowDescriptionService {

    public String generateDescription(CFG cfg) {
        if (cfg == null || cfg.nodes().isEmpty()) {
            return "No flow data available to generate a description.";
        }

        // Map for quick node lookup
        Map<String, CFGNode> nodeMap = cfg.nodes().stream()
                .collect(Collectors.toMap(CFGNode::id, n -> n));
        
        // Adjacency list for edges
        Map<String, List<CFGEdge>> adj = new HashMap<>();
        for (CFGEdge edge : cfg.edges()) {
            adj.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
        }

        StringBuilder narrative = new StringBuilder();
        narrative.append("### Execution Flow Analysis\n\n");

        // Find entry node
        CFGNode entry = cfg.nodes().stream()
                .filter(n -> n.kind() == CFGNodeKind.ENTRY)
                .findFirst()
                .orElse(cfg.nodes().get(0));

        narrative.append("The process begins at **").append(entry.label()).append("**.\n\n");

        // Traverse the graph using a simple DFS/BFS to build the narrative
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(entry.id());
        visited.add(entry.id());

        int step = 1;
        while (!queue.isEmpty()) {
            String currentId = queue.poll();
            CFGNode node = nodeMap.get(currentId);
            List<CFGEdge> outgoing = adj.getOrDefault(currentId, Collections.emptyList());

            if (node.kind() == CFGNodeKind.ENTRY) {
                // Skip entry in the step-by-step list as we already mentioned it
            } else if (node.kind() == CFGNodeKind.EXIT) {
                narrative.append("Finally, the flow terminates at the end of the operation.\n");
            } else {
                String prefix = (node.kind() == CFGNodeKind.CALL) ? "Calls " : "Executes ";
                narrative.append(step++).append(". **").append(prefix).append(node.label()).append("**");
                
                if (node.line() != null) {
                    narrative.append(" (line ").append(node.line()).append(")");
                }
                narrative.append(".\n");
            }

            for (CFGEdge edge : outgoing) {
                if (!visited.contains(edge.to())) {
                    visited.add(edge.to());
                    queue.add(edge.to());
                }
            }
        }

        return narrative.toString();
    }
}

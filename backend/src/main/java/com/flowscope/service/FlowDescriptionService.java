package com.flowscope.service;

import com.flowscope.dto.FlowDescription;
import com.flowscope.entity.CFG;
import com.flowscope.entity.CFGEdge;
import com.flowscope.entity.CFGGroup;
import com.flowscope.entity.CFGNode;
import com.flowscope.entity.CFGNodeKind;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Turns a control-flow graph into a readable walkthrough.
 *
 * <p>The walk is depth-first along the graph's own edge order, which is the
 * order the CFG builders emit successors in, so steps come out in execution
 * order rather than in breadth-first discovery order. Branch and loop heads
 * report their outgoing edge labels ("yes"/"no"/"body"/case values) instead of
 * being flattened into the step list, so a decision reads as a decision.
 *
 * <p>Inter-procedural flows carry {@link CFGGroup}s (one per inlined call). Steps
 * are tagged with the group they belong to and a group header is emitted on
 * entry, so the reader can tell which method a step is inside.
 */
@Service
public class FlowDescriptionService {

    /** Hard cap on numbered steps; large flows are cut with an honest note. */
    static final int STEP_BUDGET = 400;

    /** Label text longer than this is trimmed in the plain-text rendering. */
    private static final int LABEL_MAX = 120;

    public FlowDescription generate(CFG cfg) {
        if (cfg == null || cfg.nodes() == null || cfg.nodes().isEmpty()) {
            return new FlowDescription(
                    cfg == null ? null : cfg.functionId(),
                    "No flow data available to describe.",
                    new FlowDescription.Stats(0, 0, 0, 0, 0, 0, 0),
                    List.of(),
                    "No flow data available to describe.",
                    false);
        }

        Map<String, CFGNode> byId = indexNodes(cfg.nodes());
        Map<String, List<CFGEdge>> outgoing = indexEdges(cfg.edges());
        Map<String, String> groupLabels = indexGroups(cfg.groups());

        List<FlowDescription.Step> steps = new ArrayList<>();
        boolean truncated = walk(cfg, byId, outgoing, groupLabels, steps);

        FlowDescription.Stats stats = countKinds(cfg, groupLabels);
        String headline = headline(cfg, stats, truncated);
        String text = renderText(headline, steps, truncated);

        return new FlowDescription(cfg.functionId(), headline, stats, steps, text, truncated);
    }

    // --- traversal ----------------------------------------------------------

    /**
     * Depth-first walk from the entry node, appending steps. Returns true if the
     * step budget was hit before the graph was exhausted.
     *
     * <p>Successors are pushed in reverse so the first outgoing edge is visited
     * first; that keeps the main path contiguous instead of interleaving
     * branches the way a queue would.
     */
    private boolean walk(
            CFG cfg,
            Map<String, CFGNode> byId,
            Map<String, List<CFGEdge>> outgoing,
            Map<String, String> groupLabels,
            List<FlowDescription.Step> steps) {

        CFGNode entry = entryNode(cfg);
        Map<String, Integer> groupDepths = groupDepths(cfg.groups());

        steps.add(note(openingLine(entry), entry.line(), entry.file(), null, 0));

        Set<String> visited = new HashSet<>();
        Deque<String> stack = new ArrayDeque<>();
        stack.push(entry.id());
        visited.add(entry.id());

        String entryName = shortFunctionName(cfg.functionId());
        Set<String> seenGroups = new HashSet<>();
        String prevGroup = null;
        boolean first = true;
        int index = 1;
        boolean truncated = false;
        CFGNode exit = null;

        while (!stack.isEmpty()) {
            CFGNode node = byId.get(stack.pop());
            if (node == null) {
                continue; // edge pointed at a node id that isn't in the graph
            }

            List<CFGEdge> edges = outgoing.getOrDefault(node.id(), List.of());
            String group = node.group();
            int depth = group == null ? 0 : depthOf(groupDepths, group);

            // An inlined callee's branches rejoin the caller, so a depth-first walk
            // legitimately moves back and forth between methods. Announce every
            // transition, not just the first, or the reader loses track of where
            // a step lives.
            if (!first && !Objects.equals(group, prevGroup)) {
                if (group == null) {
                    steps.add(note("Back in " + entryName + ":", node.line(), node.file(), null, 0));
                } else {
                    String method = groupLabels.getOrDefault(group, group);
                    String verb = seenGroups.add(group) ? "Steps into " : "Back in ";
                    steps.add(note(verb + method + ":", node.line(), node.file(), method,
                            Math.max(0, depth - 1)));
                }
            } else if (group != null) {
                seenGroups.add(group);
            }
            prevGroup = group;
            first = false;

            if (node.kind() == CFGNodeKind.EXIT) {
                // Every return converges here, so the exit is reached as soon as
                // the first one is. Hold it back and close the walkthrough with it.
                exit = node;
            } else if (node.kind() != CFGNodeKind.ENTRY) {
                if (index > STEP_BUDGET) {
                    truncated = true;
                    break;
                }
                steps.add(step(index++, node, edges, byId, groupLabels, depth));
            }

            for (int i = edges.size() - 1; i >= 0; i--) {
                String to = edges.get(i).to();
                if (to != null && visited.add(to)) {
                    stack.push(to);
                }
            }
        }

        if (exit != null && !truncated) {
            steps.add(step(index, exit, List.of(), byId, groupLabels, 0));
        }
        return truncated;
    }

    private FlowDescription.Step step(
            int index,
            CFGNode node,
            List<CFGEdge> edges,
            Map<String, CFGNode> byId,
            Map<String, String> groupLabels,
            int depth) {

        String label = text(node);
        List<String> branches = branchLabels(edges, byId);
        String method = node.group() == null ? null
                : groupLabels.getOrDefault(node.group(), node.group());

        return new FlowDescription.Step(
                index,
                node.kind() == null ? "statement" : node.kind().json(),
                sentence(node, label),
                node.line(),
                node.file(),
                method,
                depth,
                branches.isEmpty() ? null : branches);
    }

    /** Phrases a node according to what kind of control-flow construct it is. */
    private String sentence(CFGNode node, String label) {
        CFGNodeKind kind = node.kind() == null ? CFGNodeKind.STATEMENT : node.kind();
        return switch (kind) {
            case CALL -> "Calls " + label;
            case BRANCH -> "Decides on " + label;
            case LOOP -> "Loops while " + label;
            case RETURN -> "Returns " + label;
            case THROW -> "Fails with " + label;
            case MERGE -> "Continues after the branch";
            case EXIT -> "Ends the operation";
            case ENTRY -> "Begins at " + label;
            case STATEMENT -> "Executes " + label;
        };
    }

    /**
     * Renders a decision's outgoing edges as "label → next thing" strings.
     * Unlabeled edges are skipped: a single unlabeled successor is just
     * sequential flow, which the next step already shows.
     */
    private List<String> branchLabels(List<CFGEdge> edges, Map<String, CFGNode> byId) {
        List<String> out = new ArrayList<>();
        for (CFGEdge edge : edges) {
            if (edge.label() == null || edge.label().isBlank()) {
                continue;
            }
            CFGNode target = byId.get(edge.to());
            out.add(target == null
                    ? edge.label()
                    : edge.label() + " → " + text(target));
        }
        return out;
    }

    private String openingLine(CFGNode entry) {
        String label = text(entry);
        return label.isBlank() ? "The flow begins." : "The flow begins at " + label + ".";
    }

    // --- indexing -----------------------------------------------------------

    /**
     * Keeps the first node per id. A duplicate id would make
     * {@code Collectors.toMap} throw, and a malformed CFG should degrade rather
     * than fail the request.
     */
    private Map<String, CFGNode> indexNodes(List<CFGNode> nodes) {
        Map<String, CFGNode> byId = new LinkedHashMap<>();
        for (CFGNode node : nodes) {
            if (node != null && node.id() != null) {
                byId.putIfAbsent(node.id(), node);
            }
        }
        return byId;
    }

    private Map<String, List<CFGEdge>> indexEdges(List<CFGEdge> edges) {
        Map<String, List<CFGEdge>> out = new HashMap<>();
        if (edges == null) {
            return out;
        }
        for (CFGEdge edge : edges) {
            if (edge != null && edge.from() != null) {
                out.computeIfAbsent(edge.from(), k -> new ArrayList<>()).add(edge);
            }
        }
        return out;
    }

    private Map<String, String> indexGroups(List<CFGGroup> groups) {
        Map<String, String> out = new LinkedHashMap<>();
        if (groups == null) {
            return out;
        }
        for (CFGGroup group : groups) {
            if (group != null && group.id() != null) {
                out.put(group.id(), group.label() == null ? group.id() : group.label());
            }
        }
        return out;
    }

    /** Nesting level for a group, defaulting to one level in. */
    private int depthOf(Map<String, Integer> groupDepths, String group) {
        Integer depth = groupDepths.get(group);
        return depth == null ? 1 : depth;
    }

    /** Nesting level per group, following {@link CFGGroup#parent} chains. */
    private Map<String, Integer> groupDepths(List<CFGGroup> groups) {
        Map<String, Integer> depths = new HashMap<>();
        if (groups == null) {
            return depths;
        }
        Map<String, String> parents = new HashMap<>();
        for (CFGGroup group : groups) {
            if (group != null && group.id() != null) {
                parents.put(group.id(), group.parent());
            }
        }
        for (String id : parents.keySet()) {
            int depth = 1;
            String cursor = parents.get(id);
            Set<String> seen = new HashSet<>();
            while (cursor != null && seen.add(cursor) && parents.containsKey(cursor)) {
                depth++;
                cursor = parents.get(cursor);
            }
            depths.put(id, depth);
        }
        return depths;
    }

    private CFGNode entryNode(CFG cfg) {
        return cfg.nodes().stream()
                .filter(n -> n != null && n.kind() == CFGNodeKind.ENTRY)
                .findFirst()
                .orElseGet(() -> cfg.nodes().stream()
                        .filter(n -> n != null)
                        .findFirst()
                        .orElseThrow());
    }

    // --- summary ------------------------------------------------------------

    private FlowDescription.Stats countKinds(CFG cfg, Map<String, String> groupLabels) {
        int calls = 0;
        int decisions = 0;
        int loops = 0;
        int thrown = 0;
        int returns = 0;
        for (CFGNode node : cfg.nodes()) {
            if (node == null || node.kind() == null) {
                continue;
            }
            switch (node.kind()) {
                case CALL -> calls++;
                case BRANCH -> decisions++;
                case LOOP -> loops++;
                case THROW -> thrown++;
                case RETURN -> returns++;
                default -> { }
            }
        }
        return new FlowDescription.Stats(
                cfg.nodes().size(), calls, decisions, loops, thrown, returns,
                groupLabels.isEmpty() ? 1 : groupLabels.size() + 1);
    }

    private String headline(CFG cfg, FlowDescription.Stats stats, boolean truncated) {
        String name = shortFunctionName(cfg.functionId());
        StringBuilder sb = new StringBuilder(name);
        sb.append(" runs ").append(count(stats.nodes(), "step"));
        List<String> parts = new ArrayList<>();
        if (stats.calls() > 0) {
            parts.add(count(stats.calls(), "call"));
        }
        if (stats.decisions() > 0) {
            parts.add(count(stats.decisions(), "decision"));
        }
        if (stats.loops() > 0) {
            parts.add(count(stats.loops(), "loop"));
        }
        if (stats.thrown() > 0) {
            parts.add(count(stats.thrown(), "failure path"));
        }
        if (stats.methods() > 1) {
            parts.add("across " + count(stats.methods(), "method"));
        }
        if (!parts.isEmpty()) {
            sb.append(" — ").append(String.join(", ", parts));
        }
        sb.append('.');
        if (truncated) {
            sb.append(" Only the first ").append(STEP_BUDGET)
              .append(" steps are described.");
        }
        return sb.toString();
    }

    private String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }

    /**
     * Extracts a readable name from a function id such as
     * {@code func:src/main/java/A.java#OrderService.place@42}.
     */
    String shortFunctionName(String functionId) {
        if (functionId == null || functionId.isBlank()) {
            return "This flow";
        }
        String name = functionId;
        int hash = name.indexOf('#');
        if (hash >= 0 && hash + 1 < name.length()) {
            name = name.substring(hash + 1);
        }
        int at = name.lastIndexOf('@');
        if (at > 0) {
            name = name.substring(0, at);
        }
        return name.isBlank() ? "This flow" : name;
    }

    // --- plain text ---------------------------------------------------------

    /** Plain-text rendering used for the copy/download action. */
    private String renderText(String headline, List<FlowDescription.Step> steps, boolean truncated) {
        StringBuilder sb = new StringBuilder();
        sb.append(headline).append("\n\n");
        for (FlowDescription.Step step : steps) {
            sb.append("  ".repeat(Math.max(0, step.depth())));
            if (step.index() > 0) {
                sb.append(step.index()).append(". ");
            }
            sb.append(step.text());
            if (step.line() != null) {
                sb.append(" (line ").append(step.line()).append(')');
            }
            sb.append('\n');
            if (step.branches() != null) {
                for (String branch : step.branches()) {
                    sb.append("  ".repeat(Math.max(0, step.depth()) + 1))
                      .append("- ").append(branch).append('\n');
                }
            }
        }
        if (truncated) {
            sb.append("\n… flow continues beyond step ").append(STEP_BUDGET)
              .append("; narrow the call depth to describe less of it.\n");
        }
        return sb.toString();
    }

    private FlowDescription.Step note(String text, Integer line, String file, String method, int depth) {
        return new FlowDescription.Step(0, "note", text, line, file, method, depth, null);
    }

    /** The node's full text when the label was truncated for display. */
    private String text(CFGNode node) {
        String raw = node.title() != null && !node.title().isBlank() ? node.title() : node.label();
        if (raw == null) {
            return "";
        }
        String trimmed = raw.strip();
        return trimmed.length() > LABEL_MAX ? trimmed.substring(0, LABEL_MAX - 1) + "…" : trimmed;
    }
}

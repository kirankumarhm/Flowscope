package com.flowscope.dto;

import java.util.List;
import java.util.Map;

/**
 * The internal component structure of a single application: its Spring beans
 * (controllers, services, repositories, clients, config, components) and the
 * dependencies between them (constructor/field injection and cross-class calls).
 *
 * <p>This is the intra-app counterpart to the workspace-level Service Map.
 */
public record ComponentMap(
        String project,
        List<Component> components,
        List<Dependency> dependencies,
        Stats stats) {

    /**
     * A class-level component.
     *
     * @param id        stable id (the class simple name)
     * @param name      display name (class simple name)
     * @param layer     controller | service | repository | client | config | component
     * @param pkg       dotted package
     * @param file      module-relative source file
     * @param startLine 1-based line of the type declaration
     * @param methods   number of methods/constructors
     * @param endpoints REST endpoint labels handled (for controllers)
     * @param fanIn     number of components that depend on this one
     * @param fanOut    number of components this one depends on
     */
    public record Component(
            String id,
            String name,
            String layer,
            String pkg,
            String file,
            int startLine,
            int methods,
            List<String> endpoints,
            int fanIn,
            int fanOut) {
    }

    /**
     * A directed dependency between components.
     *
     * @param kind   {@code inject} (field/constructor dependency) or {@code call}
     * @param weight number of distinct call sites (>=1)
     */
    public record Dependency(
            String id,
            String source,
            String target,
            String kind,
            int weight) {
    }

    public record Stats(
            int components,
            int dependencies,
            Map<String, Integer> byLayer,
            List<String> notes) {
    }
}

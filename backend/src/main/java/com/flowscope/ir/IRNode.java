package com.flowscope.ir;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * A modeled entity in the IR graph. {@code meta} carries language- or view-specific
 * extras (receiver type, Spring endpoint, layer, ...) without bloating the core type.
 *
 * <p>ID format: {@code {prefix}:{relativePath}#{name}[@{startLine}]}, e.g.
 * {@code func:src/main/java/com/shop/OrderController.java#placeOrder@42}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IRNode(
        String id,
        NodeKind kind,
        String name,
        String file,
        int startLine,
        int endLine,
        String language,
        String signature,
        Map<String, Object> meta) {
}

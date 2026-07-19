package com.flowscope.entity;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A collapsible group of CFG nodes representing one inlined method call, so the
 * frontend can render it as a labeled container and collapse/expand it.
 *
 * @param id     unique group id
 * @param label  display label, e.g. "OrderService.place"
 * @param parent enclosing group id (for nested inlines), or null at the top level
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CFGGroup(
        String id,
        String label,
        String parent) {
}

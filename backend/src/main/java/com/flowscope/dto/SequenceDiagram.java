package com.flowscope.dto;

import java.util.List;

/**
 * A generated Mermaid sequence diagram for one entry function.
 *
 * @param entryFunctionId the function the trace started from
 * @param mermaidText     a complete {@code sequenceDiagram} definition
 * @param participants    ordered participant (class/service) names
 */
public record SequenceDiagram(
        String entryFunctionId,
        String mermaidText,
        List<String> participants) {
}

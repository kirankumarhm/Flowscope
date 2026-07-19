package com.flowscope.ir;

import com.fasterxml.jackson.annotation.JsonValue;

/** The entity kinds FlowScope models in its language-neutral IR. */
public enum NodeKind {
    MODULE("module"),
    PACKAGE("package"),
    FILE("file"),
    CLASS("class"),
    FUNCTION("function"),
    ENDPOINT("endpoint"),
    EXTERNAL("external");

    private final String json;

    NodeKind(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }
}

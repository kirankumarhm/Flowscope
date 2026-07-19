package com.flowscope.ir;

import com.fasterxml.jackson.annotation.JsonValue;

/** The relationship kinds between IR nodes. */
public enum EdgeKind {
    CONTAINS("contains"),
    IMPORTS("imports"),
    CALLS("calls"),
    IMPLEMENTS("implements"),
    EXTENDS("extends"),
    REMOTE("remote");

    private final String json;

    EdgeKind(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }
}

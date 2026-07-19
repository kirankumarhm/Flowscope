package com.flowscope.entity;

import com.fasterxml.jackson.annotation.JsonValue;

/** Classifies control-flow nodes within a single function's CFG. */
public enum CFGNodeKind {
    ENTRY("entry"),
    EXIT("exit"),
    STATEMENT("statement"),
    BRANCH("branch"),   // if / switch head (decision)
    LOOP("loop"),       // for / while / do head
    CALL("call"),       // a call site worth showing
    RETURN("return"),
    THROW("throw"),
    MERGE("merge");     // join point after a branch

    private final String json;

    CFGNodeKind(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }
}

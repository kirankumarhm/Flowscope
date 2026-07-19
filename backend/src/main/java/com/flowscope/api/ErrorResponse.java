package com.flowscope.api;

/** JSON error body: {@code {"error": "..."}}. */
public record ErrorResponse(String error) {
}

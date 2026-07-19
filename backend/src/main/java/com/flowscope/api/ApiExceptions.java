package com.flowscope.api;

/** Typed analysis errors mapped to HTTP status codes by {@link GlobalExceptionHandler}. */
public final class ApiExceptions {

    private ApiExceptions() {
    }

    /** Missing or empty {@code path} query parameter → HTTP 400. */
    public static class MissingPathException extends RuntimeException {
        public MissingPathException(String message) {
            super(message);
        }
    }

    /** Path does not exist or is not a directory → HTTP 400. */
    public static class InvalidPathException extends RuntimeException {
        public InvalidPathException(String message) {
            super(message);
        }
    }

    /** Analysis exceeded the heap budget → HTTP 500. */
    public static class MemoryExceededException extends RuntimeException {
        public MemoryExceededException(String message) {
            super(message);
        }
    }

    /** Analysis exceeded the time budget → HTTP 504. */
    public static class AnalysisTimeoutException extends RuntimeException {
        public AnalysisTimeoutException(String message) {
            super(message);
        }
    }

    /** Requested function/endpoint ID not found in the analyzed project → HTTP 404. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}

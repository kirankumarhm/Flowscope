package com.flowscope.lang;

/**
 * A pluggable, language-specific extraction strategy. Adding a new language means
 * registering a {@link LanguageSpec} that pairs file extensions with an
 * implementation of this interface — the File Walker, IR, and CFG models require
 * no changes (Requirement 8).
 *
 * <p>Implementations must be thread-safe or stateless: the Parser Engine invokes
 * {@link #extract} concurrently across files.
 */
public interface Extractor {

    /** Canonical language identifier this extractor handles (e.g. "java"). */
    String languageId();

    /**
     * Parse one source file and extract its entities and per-function CFGs.
     *
     * @param relativePath path relative to the analyzed source root (used in node IDs)
     * @param source       raw file bytes
     * @return extracted nodes, edges, CFGs, and non-fatal errors (never null)
     */
    ExtractionResult extract(String relativePath, byte[] source);
}

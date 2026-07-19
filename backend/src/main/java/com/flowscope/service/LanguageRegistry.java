package com.flowscope.service;

import java.util.Collection;
import java.util.Optional;
import com.flowscope.entity.LanguageSpec;

/**
 * Central registry mapping language identifiers and file extensions to their
 * {@link LanguageSpec}. The File Walker consults it for extension→language
 * mapping; the Parser Engine consults it for the extractor to run.
 */
public interface LanguageRegistry {

    /**
     * Register a language spec.
     *
     * @throws IllegalArgumentException if the extractor is null (invalid grammar,
     *                                  Requirement 8.6) or any extension is already
     *                                  claimed by another spec (Requirement 8.5).
     *                                  The registry is left unchanged on rejection.
     */
    void register(LanguageSpec spec);

    /** Look up the spec claiming a file extension (including the leading dot). */
    Optional<LanguageSpec> forExtension(String extension);

    /** Look up the spec for a canonical language id. */
    Optional<LanguageSpec> forLanguage(String languageId);

    /** All registered specs. */
    Collection<LanguageSpec> allSpecs();
}

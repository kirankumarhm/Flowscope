package com.flowscope.entity;

import java.util.Set;
import com.flowscope.service.Extractor;

/**
 * Pairs a language identifier with the file extensions it claims and the
 * {@link Extractor} that parses it. This is the unit of language extensibility:
 * registering a spec is all it takes to make a language analyzable.
 *
 * @param languageId     canonical id, e.g. "java", "go", "python"
 * @param fileExtensions extensions this language claims, each including the dot (".java")
 * @param extractor      the extraction strategy (must be non-null — Requirement 8.6)
 */
public record LanguageSpec(
        String languageId,
        Set<String> fileExtensions,
        Extractor extractor) {

    public LanguageSpec {
        if (languageId == null || languageId.isBlank()) {
            throw new IllegalArgumentException("languageId must be non-empty");
        }
        if (fileExtensions == null || fileExtensions.isEmpty()) {
            throw new IllegalArgumentException("fileExtensions must be non-empty");
        }
        fileExtensions = Set.copyOf(fileExtensions);
    }
}

package com.flowscope.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import com.flowscope.entity.LanguageSpec;

/**
 * Thread-safe in-memory {@link LanguageRegistry} with extension-conflict and
 * null-extractor rejection. Registration is atomic: a rejected spec leaves the
 * registry exactly as it was.
 */
public class InMemoryLanguageRegistry implements LanguageRegistry {

    private final Map<String, LanguageSpec> byLanguage = new ConcurrentHashMap<>();
    private final Map<String, LanguageSpec> byExtension = new ConcurrentHashMap<>();

    @Override
    public synchronized void register(LanguageSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        // Requirement 8.6: reject invalid/null grammar (here: the extractor).
        if (spec.extractor() == null) {
            throw new IllegalArgumentException(
                    "language '" + spec.languageId() + "' has no extractor (invalid grammar)");
        }
        // Requirement 8.5: reject extension conflicts, leaving the registry unchanged.
        Map<String, String> normalized = new LinkedHashMap<>();
        for (String ext : spec.fileExtensions()) {
            String norm = ext.toLowerCase(Locale.ROOT);
            LanguageSpec owner = byExtension.get(norm);
            if (owner != null && !owner.languageId().equals(spec.languageId())) {
                throw new IllegalArgumentException(
                        "extension '" + norm + "' already claimed by language '"
                                + owner.languageId() + "'");
            }
            normalized.put(ext, norm);
        }
        byLanguage.put(spec.languageId(), spec);
        for (String norm : normalized.values()) {
            byExtension.put(norm, spec);
        }
    }

    @Override
    public Optional<LanguageSpec> forExtension(String extension) {
        if (extension == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byExtension.get(extension.toLowerCase(Locale.ROOT)));
    }

    @Override
    public Optional<LanguageSpec> forLanguage(String languageId) {
        if (languageId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byLanguage.get(languageId));
    }

    @Override
    public Collection<LanguageSpec> allSpecs() {
        return byLanguage.values();
    }
}

package com.flowscope.lang;

import com.flowscope.extract.JavaExtractor;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the built-in language specs. This vertical slice ships the
 * JavaParser-based Java extractor; additional languages (Go, Python, Rust,
 * JS/TS) register here once their extractors land — no other component changes
 * (Requirement 8.2).
 */
@Configuration
public class LanguageConfig {

    @Bean
    public LanguageRegistry languageRegistry() {
        LanguageRegistry registry = new InMemoryLanguageRegistry();
        registry.register(new LanguageSpec("java", Set.of(".java"), new JavaExtractor()));
        return registry;
    }
}

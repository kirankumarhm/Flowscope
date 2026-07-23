package com.flowscope.config;

import com.flowscope.service.GoExtractor;
import com.flowscope.service.JavaExtractor;
import com.flowscope.service.PythonExtractor;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.flowscope.service.InMemoryLanguageRegistry;
import com.flowscope.service.LanguageRegistry;
import com.flowscope.entity.LanguageSpec;

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
        registry.register(new LanguageSpec("go", Set.of(".go"), new GoExtractor()));
        registry.register(new LanguageSpec("python", Set.of(".py"), new PythonExtractor()));
        return registry;
    }
}

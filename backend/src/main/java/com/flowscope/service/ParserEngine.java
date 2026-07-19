package com.flowscope.service;

import com.flowscope.entity.SourceFile;
import com.flowscope.entity.ExtractionResult;
import com.flowscope.service.LanguageRegistry;
import java.util.List;

/** Parses source files (concurrently) and aggregates their extraction output. */
public interface ParserEngine {
    ExtractionResult extract(List<SourceFile> files, LanguageRegistry registry);
}

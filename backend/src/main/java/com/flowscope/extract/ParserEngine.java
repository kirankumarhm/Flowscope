package com.flowscope.extract;

import com.flowscope.ingest.SourceFile;
import com.flowscope.lang.ExtractionResult;
import com.flowscope.lang.LanguageRegistry;
import java.util.List;

/** Parses source files (concurrently) and aggregates their extraction output. */
public interface ParserEngine {
    ExtractionResult extract(List<SourceFile> files, LanguageRegistry registry);
}

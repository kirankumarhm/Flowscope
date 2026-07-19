package com.flowscope.entity;

import java.nio.file.Path;

/**
 * A discovered source file.
 *
 * @param absolutePath absolute filesystem path
 * @param relativePath path relative to the analyzed source root (forward slashes)
 * @param language     canonical language id ("go", "java", "javascript",
 *                     "typescript", "python", "rust")
 */
public record SourceFile(
        Path absolutePath,
        String relativePath,
        String language) {
}
